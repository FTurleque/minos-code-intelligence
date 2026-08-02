# Runtime Docker autonome MINOS

> État M29 : cette surface est en cours de qualification sur la branche `m29-autonomous-docker-runtime`. Elle ne constitue pas encore une claim de parité native/Docker ni une fonctionnalité publiée de `1.0.1`.

M29 sépare volontairement le runtime Docker en plusieurs plans afin que l'administration et l'indexation puissent écrire l'état MINOS et les artefacts de build sans rendre le serveur MCP des agents mutable.

## Plans d'exécution

| Plan | Service Compose | Durée | État MINOS | Provider tools | Projets | Réseau |
|---|---|---:|---|---|---|---|
| MCP query | `minos-mcp` | persistant | read-only | read-only | read-only | `none` |
| Administration / indexation | `minos-admin` | éphémère | read-write | read-only | read-only | egress dépendances projet |
| Bootstrap mapping | `minos-bootstrap` | éphémère | read-write | non requis | non requis | `none` |
| Bootstrap providers | `minos-tools-bootstrap` | éphémère | non requis | initialise le volume géré | non requis | `none` |
| Probe providers | `minos-provider-probe` | éphémère | non requis | read-only | non requis | `none` |

Tous les plans conservent :

```text
container filesystem read-only
cap_drop: ALL
no-new-privileges: true
bounded tmpfs
MINOS_RUNTIME_LOCATION=docker
```

Le serveur MCP persistant, le bootstrap et le probe provider gardent `network_mode:none`. Le plan admin/indexation est éphémère et peut résoudre les dépendances propres au projet (Maven/NuGet/etc.) ; cette exception réseau ne sert jamais à installer implicitement les providers MINOS, qui restent préparés au BUILD et vérifiés offline.

Le plan admin n'obtient jamais le droit de modifier le code source des projets. Il écrit l'état métier, les caches de build et les workspaces de staging uniquement sous `/var/lib/minos`. Les outils providers préparés au BUILD vivent dans un volume Linux séparé et sont montés read-only dans les plans métier.

## Mapping des projets

Lors de l'installation Docker, MINOS crée une configuration runtime versionnée :

```text
<MINOS_HOME>/runtime/project-paths.properties
```

Exemple :

```text
N:/workspace-dev <-> /workspace/projects
```

Le registre métier persiste ensuite un `rootRelativePath`, pas le chemin physique du poste ou du conteneur. Le bootstrap est idempotent et refuse de remplacer implicitement un mapping existant différent.

Dans les commandes exécutées **dans Docker**, utiliser le chemin visible par le conteneur :

```text
/workspace/projects/my-project
```

et non `N:\workspace-dev\my-project`.

## Provider-complete image M29-S4

L'image M29 prépare pendant BUILD :

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989 / 2026-07-27 / 12c3381
```

Les toolchains nécessaires sont également préparées : JDK 24, **Apache Maven 3.9.16**, Coursier, Node/npm, Python/pip, .NET SDK 10, Go et Rust/cargo/rustc/rust-analyzer.

Les téléchargements des **providers et toolchains MINOS** se produisent au BUILD. Le probe `minos-provider-probe` s'exécute avec `network_mode:none` et prouve que les exécutables annoncés sont présents sans installation réseau en RUN.

L'indexation d'un projet compilé constitue un problème différent : Maven, NuGet ou d'autres build systems peuvent devoir résoudre les dépendances déclarées par le projet. Cette résolution est autorisée uniquement dans le plan admin/indexation éphémère ; elle n'ouvre jamais le réseau au serveur MCP query.

Le bundle provider est initialisé dans le volume Docker nommé :

```text
minos-provider-tools
```

monté sous :

```text
/var/lib/minos/tools
```

Ce volume est distinct du business data bind `%LOCALAPPDATA%\MINOS\docker-data` ou du `DataRoot` choisi. Il évite de transporter des exécutables Linux sur NTFS et permet de garder les provider tools read-only pendant les requêtes et l'indexation.

L'image produit aussi :

```text
provider-inventory.json
provider-binary-sha256.txt
```

Ces preuves sont copiées dans le répertoire runtime de l'installation. Le manifeste binaire inclut également le Maven packagé requis par `scip-java`.

### Provenance scip-java et version du launcher standalone

La version supportée de `scip-java` reste l'artefact Maven exact :

```text
org.scip-code:scip-java:0.13.1
```

Pendant le BUILD, MINOS exécute cette coordonnée via Coursier et exige `scip-java version 0.13.1`. Il construit ensuite `/usr/local/bin/scip-java` avec `cs bootstrap --standalone`. Tous les JAR du provider sont donc embarqués dans le launcher : le RUN n'a besoin d'aucun téléchargement ni d'aucune résolution réseau pour **le provider lui-même**.

Le bootstrap Coursier standalone matérialise néanmoins ses JAR embarqués dans un cache local au démarrage. MINOS fournit explicitement un emplacement writable sans assouplir le filesystem conteneur :

```text
minos-admin          COURSIER_CACHE=/var/lib/minos/cache/coursier
minos-provider-probe COURSIER_CACHE=/tmp/minos-coursier-cache
```

Le premier chemin reste sous l'état writable MINOS ; le second vit dans le tmpfs borné du probe et disparaît avec le conteneur. Le probe reste `network_mode:none` : ce cache sert uniquement à matérialiser les ressources déjà embarquées.

Ce launcher standalone retourne actuellement :

```text
scip-java version 0.0.0-SNAPSHOT
```

Cette chaîne est une métadonnée embarquée du launcher et **n'est pas utilisée comme provenance de l'artefact**. `provider-inventory.json` conserve séparément `version=0.13.1` et `reportedVersion=0.0.0-SNAPSHOT`, tandis que `provider-binary-sha256.txt` contient le hash du launcher réellement exécuté. Le probe offline vérifie le retour réel du launcher sans prétendre qu'il expose `0.13.1`.

`scip-java` embarque également Mordant/JNA. JNA doit pouvoir extraire puis charger une bibliothèque native ; le tmpfs général `/tmp` reste volontairement `noexec`. Les plans provider positionnent donc :

```text
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native
```

Le launcher standalone conserve également son option embarquée `-Djna.tmpdir=/run/minos-native`. Les seuls plans qui exécutent des providers (`minos-admin` et `minos-provider-probe`) montent `/run/minos-native` comme tmpfs éphémère, `nosuid,nodev,exec`, borné à 16 MiB. Le plan MCP query n'expose pas ce tmpfs exécutable.

Le déplacement de `java.io.tmpdir` est nécessaire au vrai `scip-java index` : le provider fabrique un shim `javac` temporaire qu'il doit exécuter. Ce shim ne doit jamais être créé sous le `/tmp` général `noexec`.

### Maven et staging Java

`scip-java index` ne se contente pas d'écrire `index.scip` : son build Maven crée notamment :

```text
target/scip-targetroot
```

Le projet Docker reste strictement read-only. MINOS crée donc, pour chaque run Java Linux/Docker, une copie de travail writable sous :

```text
/var/lib/minos/runs/<run-id>/scip-java/workspace
```

Le provider travaille dans ce staging ; la racine `/workspace/projects/...` n'est jamais rendue writable. Les arbres générés préexistants (`target`, `build`, `out`, `node_modules`, etc.) et les métadonnées VCS/IDE ne sont pas copiés dans le staging.

Le staging Linux exclut également les deux launchers Maven racine :

```text
mvnw
mvnw.cmd
```

La configuration projet `.mvn` reste disponible. Ce choix est volontaire : `scip-java` préfère `./mvnw` lorsqu'il existe, mais le checkout source est matérialisé sur Windows et le wrapper peut donc être host-dépendant. Docker doit utiliser le **Maven qualifié de l'image**, pas un wrapper copié depuis le poste ni une distribution téléchargée par Maven Wrapper.

Apache Maven 3.9.16 est packagé directement dans l'image et vérifié pendant le BUILD puis dans le probe offline. Son repository local et son HOME sont explicitement confinés :

```text
HOME=/var/lib/minos/cache/home
MAVEN_OPTS=-Dmaven.repo.local=/var/lib/minos/cache/maven/repository
```

Les dépendances déjà présentes y sont réutilisées entre les indexations. Les dépendances manquantes du **projet** peuvent être résolues par le plan admin éphémère.

Les outils .NET reçoivent eux aussi des emplacements writable explicites :

```text
minos-admin          DOTNET_CLI_HOME=/var/lib/minos/cache/dotnet-home
                     NUGET_PACKAGES=/var/lib/minos/cache/nuget
minos-provider-probe DOTNET_CLI_HOME=/tmp/minos-dotnet-home
                     NUGET_PACKAGES=/tmp/minos-nuget
```

### Limitation Node

`scip-typescript 0.4.0` est préparé avec Node 20.20.2 pour rester sur la ligne Node documentée par ce provider. Cette contrainte est enregistrée dans l'inventaire ; elle ne doit pas être interprétée comme une recommandation générale de Node 20 pour d'autres usages.

## Installation du runtime Docker de travail

Depuis un checkout M29 qualifié :

```powershell
$Jar = '.\target\minos-code-intelligence-1.0.1-SNAPSHOT-all.jar'

.\docker\scripts\prod-mcp-release.ps1 `
  -Action Install `
  -Jar $Jar `
  -Version '1.0.1-SNAPSHOT' `
  -Commit (git rev-parse HEAD) `
  -ProjectsRoot 'N:\workspace-dev'
```

`Install` :

1. construit l'image depuis le JAR exact ;
2. valide `docker compose config` ;
3. contrôle Java dans l'image ;
4. extrait l'inventaire/checksums providers ;
5. initialise `minos-provider-tools` depuis le bundle image ;
6. exécute le probe provider offline puis `tools list` / `tools verify --all` ;
7. initialise le mapping host/container ;
8. vérifie que le plan admin expose le CLI stable ;
9. persiste les métadonnées de l'installation.

## Exécuter le CLI MINOS dans Docker

Le workflow packagé accepte `-Action Admin` :

```powershell
$Docker = '.\docker\scripts\prod-mcp-release.ps1'

& $Docker -Action Admin -MinosArguments @('doctor', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'list', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'verify', '--all', '--format', 'json')

& $Docker -Action Admin -MinosArguments @(
  'project', 'add', '/workspace/projects/my-project',
  '--name', 'my-project', '--format', 'json'
)

& $Docker -Action Admin -MinosArguments @('project', 'inspect', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index-status', 'my-project', '--format', 'json')
```

`tools verify --all` est le gate capability-honest. Le probe `minos-provider-probe` reste le gate explicite démontrant que le payload provider et Maven sont exécutables sans réseau.

## Projets read-only pendant l'indexation

Les sources restent read-only dans `minos-admin`. Les process plans M29 ne doivent pas déposer `index.scip`, `target/` ou autre artefact de provider dans la racine projet.

Les sorties SCIP Java, TypeScript, C/C++, C#, Go et Rust sont redirigées vers le run directory MINOS sous l'état writable. Python utilisait déjà un output externe. Rust redirige aussi `CARGO_TARGET_DIR` vers son run directory. Java exécute son build complet depuis le staging writable décrit ci-dessus avec le Maven 3.9.16 de l'image.

Tout provider qui exige encore une écriture dans `/workspace/projects` doit échouer et être corrigé ; le mount ne doit pas être rendu writable pour le contourner.

## État sémantique et hybride

M29 ajoute deux diagnostics CLI read-only :

```text
semantic status <project> [--format <text|json>]
hybrid status <project> [--format <text|json>]
```

`semantic status` reflète `DISABLED`, `NO_ACTIVE_SNAPSHOT`, `MISSING`, `STALE` ou `READY`.

`hybrid status` distingue :

```text
NO_ACTIVE_SNAPSHOT
READY_STRUCTURED_FALLBACK
READY_WITH_SEMANTIC
```

Le signal vectoriel reste `HEURISTIC` et ne crée jamais de fait structurel.

## MCP query-only

Démarrer :

```powershell
& $Docker -Action Start
```

Session STDIO :

```powershell
& $Docker -Action Attach
```

Le processus MCP est lancé dans le conteneur query-only. Il voit projets, état métier et provider tools en lecture seule, avec `network_mode:none`.

## Validation et état

```powershell
& $Docker -Action Validate
& $Docker -Action Status
```

Le gate exact-head S4 complet est :

```powershell
.\scripts\m29\run-s4.ps1 -ExpectedHead <sha> -ProjectsRoot N:\workspace-dev
```

Le gate S3 doit ensuite être exécuté sur le **même SHA** :

```powershell
.\scripts\m29\run-s3.ps1 -ExpectedHead <sha> -ProjectsRoot N:\workspace-dev
```

S3 doit atteindre `index → READY`, les statuts semantic/hybrid, le handshake MCP et la preuve recreate/persistance.

## Désinstallation

```powershell
& $Docker -Action Uninstall
```

Le conteneur, l'image, la configuration runtime et le volume `minos-provider-tools` gérés sont retirés. Les données métier persistantes sont conservées par défaut.

La purge explicite des données et le switching transactionnel natif/Docker relèvent de M29-S7.

## État de qualification

S4 a obtenu une nouvelle preuve exact-head complète sur `45536e2fc7d32ed67932e2715e458fa26a8239b1` : Maven 13/13, docs checker, image Docker 31/31, Maven 3.9.16, probe offline, sept providers READY et `doctor.ready=true` ont PASS.

S3 exécuté immédiatement sur ce même SHA a ensuite prouvé que le staging writable corrige l'ancienne écriture `target/scip-targetroot` sur source read-only. Le nouveau défaut exact est que `scip-java` sélectionne `.../workspace/mvnw`, issu du checkout Windows, et échoue au lancement avec `error=2, No such file or directory`. Son stdout montre aussi un `javac` temporaire sous `/tmp/scip-java...`, incompatible avec le contrat `/tmp` noexec.

La remédiation courante exclut `mvnw`/`mvnw.cmd` du staging Linux, conserve `.mvn`, force l'usage du Maven 3.9.16 packagé et route `java.io.tmpdir`/JNA vers `/run/minos-native`. Ces changements modifient le HEAD ; ils doivent donc repasser **S4 puis S3 exact-head** avant toute nouvelle claim PASS sur la branche courante.

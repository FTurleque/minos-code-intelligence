# Runtime Docker autonome MINOS

> État M29 : cette surface est en cours de qualification sur la branche `m29-autonomous-docker-runtime`. Elle ne constitue pas encore une claim de parité native/Docker ni une fonctionnalité publiée de `1.0.1`.

M29 sépare volontairement le runtime Docker en plusieurs plans afin que l'administration et l'indexation puissent écrire l'état MINOS sans rendre le serveur MCP des agents mutable.

## Plans d'exécution

| Plan | Service Compose | Durée | État MINOS | Provider tools | Projets | Réseau |
|---|---|---:|---|---|---|---|
| MCP query | `minos-mcp` | persistant | read-only | read-only | read-only | `none` |
| Administration / indexation | `minos-admin` | éphémère | read-write | read-only | read-only | `none` |
| Bootstrap mapping | `minos-bootstrap` | éphémère | read-write | non requis | non requis | `none` |
| Bootstrap providers | `minos-tools-bootstrap` | éphémère | non requis | initialise le volume géré | non requis | `none` |

Les plans RUN gardent :

```text
container filesystem read-only
cap_drop: ALL
no-new-privileges: true
network_mode: none
bounded tmpfs
MINOS_RUNTIME_LOCATION=docker
```

Le plan admin n'obtient donc pas le droit de modifier le code source des projets. Il écrit l'état métier MINOS sous `/var/lib/minos`; les outils providers préparés au BUILD vivent dans un volume Linux séparé et sont montés read-only dans les plans métier.

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

Les toolchains nécessaires sont également préparées : JDK 24, Coursier, Node/npm, Python/pip, .NET SDK 10, Go et Rust/cargo/rustc/rust-analyzer.

Les téléchargements se produisent au BUILD. En RUN :

```text
network_mode: none
```

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

Ces preuves sont copiées dans le répertoire runtime de l'installation.

### Provenance scip-java et version du launcher standalone

La version supportée de `scip-java` reste l'artefact Maven exact :

```text
org.scip-code:scip-java:0.13.1
```

Pendant le BUILD, MINOS exécute cette coordonnée via Coursier et exige `scip-java version 0.13.1`. Il construit ensuite `/usr/local/bin/scip-java` avec `cs bootstrap --standalone` afin que le RUN n'ait besoin ni du réseau ni d'un cache Coursier writable.

Ce launcher standalone retourne actuellement :

```text
scip-java version 0.0.0-SNAPSHOT
```

Cette chaîne est une métadonnée embarquée du launcher et **n'est pas utilisée comme provenance de l'artefact**. `provider-inventory.json` conserve séparément `version=0.13.1` et `reportedVersion=0.0.0-SNAPSHOT`, tandis que `provider-binary-sha256.txt` contient le hash du launcher réellement exécuté. Le probe offline vérifie le retour réel du launcher sans prétendre qu'il expose `0.13.1`.

`scip-java` embarque également Mordant/JNA. JNA doit pouvoir extraire puis charger une bibliothèque native ; le tmpfs général `/tmp` reste volontairement `noexec`. Le launcher standalone est donc construit avec :

```text
-Djna.tmpdir=/run/minos-native
```

Les seuls plans qui exécutent des providers (`minos-admin` et `minos-provider-probe`) montent `/run/minos-native` comme tmpfs éphémère, `nosuid,nodev,exec`, borné à 16 MiB. Le plan MCP query n'expose pas ce tmpfs exécutable. Cette exception est limitée au chargement natif du provider et ne rend ni le filesystem conteneur, ni les sources projet, ni le volume providers writable.

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
6. exécute `tools list` puis `tools verify` sans réseau ;
7. initialise le mapping host/container ;
8. vérifie que le plan admin expose le CLI stable ;
9. persiste les métadonnées de l'installation.

## Exécuter le CLI MINOS dans Docker

Le workflow packagé accepte `-Action Admin` :

```powershell
$Docker = '.\docker\scripts\prod-mcp-release.ps1'

& $Docker -Action Admin -MinosArguments @('doctor', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'list', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'verify', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'verify', '--all', '--format', 'json')

& $Docker -Action Admin -MinosArguments @(
  'project', 'add', '/workspace/projects/my-project',
  '--name', 'my-project', '--format', 'json'
)

& $Docker -Action Admin -MinosArguments @('project', 'list', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('project', 'inspect', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index-status', 'my-project', '--format', 'json')
```

`tools verify --all` est le gate S4 capability-honest : il échoue si un provider annoncé par MINOS n'est pas `READY`. Le `tools verify` historique conserve son comportement et vérifie seulement les providers requis par défaut.

Un raccourci source reste disponible :

```powershell
.\docker\scripts\minos-docker.ps1 project list --format json
```

## Projets read-only pendant l'indexation

Les sources restent read-only dans `minos-admin`. Les process plans M29 ne doivent pas déposer `index.scip`, `target/` ou autre artefact de provider dans la racine projet.

Les sorties SCIP Java, TypeScript, C/C++, C#, Go et Rust sont redirigées vers le run directory MINOS sous l'état writable. Python utilisait déjà un output externe. Rust redirige aussi `CARGO_TARGET_DIR` vers son run directory.

Tout provider qui exige encore une écriture dans `/workspace/projects` doit échouer et être corrigé ; le mount ne doit pas être rendu writable pour le contourner.

## État sémantique et hybride

M29 ajoute deux diagnostics CLI read-only :

```text
semantic status <project> [--format <text|json>]
hybrid status <project> [--format <text|json>]
```

Exemples :

```powershell
& $Docker -Action Admin -MinosArguments @('semantic', 'status', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('hybrid', 'status', 'my-project', '--format', 'json')
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

Le processus MCP est lancé dans le conteneur query-only. Il voit projets, état métier et provider tools en lecture seule.

Arrêt :

```powershell
& $Docker -Action Stop
```

## Validation et état

```powershell
& $Docker -Action Validate
& $Docker -Action Status
```

`Validate` revalide Compose, Java, bundle provider, `tools list/verify`, mapping et CLI admin.

Le gate exact-head S4 complet est :

```powershell
.\scripts\m29\run-s4.ps1 -ExpectedHead <sha> -ProjectsRoot N:\workspace-dev
```

Après PASS S4, relancer le gate S3 sur le **même SHA** :

```powershell
.\scripts\m29\run-s3.ps1 -ExpectedHead <sha> -ProjectsRoot N:\workspace-dev
```

S3 doit alors atteindre `index → READY`, handshake MCP et recreate/persistance.

## Désinstallation

```powershell
& $Docker -Action Uninstall
```

Le conteneur, l'image, la configuration runtime et le volume `minos-provider-tools` gérés sont retirés. Les données métier persistantes sont conservées par défaut.

La purge explicite des données et le switching transactionnel natif/Docker relèvent de M29-S7.

## État de qualification

Le plan S3 a déjà été exercé sur Docker réel jusqu'au vrai `index`. L'échec observé était l'absence de `cargo`, `rustc` et `rust-analyzer` dans l'ancienne image, ce qui a déclenché S4.

S4 est maintenant implémenté mais **aucun provider ne doit être déclaré supporté dans l'image avant qualification réelle sans réseau en RUN**. Aucune claim de parité native/Docker n'est acquise à ce stade.
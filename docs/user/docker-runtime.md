# Runtime Docker autonome MINOS

> État M29 : S3/S4 sont qualifiés exact-head sur `3df1b40ca0daf50779596f6e955d966ed5eb4973`. S5 est implémenté sur un HEAD plus récent et attend sa qualification `run-s5.ps1`. Cette surface ne constitue pas encore une claim de parité native/Docker ni une fonctionnalité publiée de `1.0.1`.

M29 sépare volontairement le runtime Docker en plusieurs plans afin que l'administration et l'indexation puissent écrire l'état MINOS et les artefacts de build sans rendre le serveur MCP mutable.

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

Le serveur MCP persistant, les bootstraps et le probe provider gardent `network_mode: none`. Le plan admin/indexation est éphémère et peut résoudre les dépendances propres au projet ; cette exception réseau ne sert jamais à installer implicitement les providers MINOS.

Le plan admin n'obtient jamais le droit de modifier le code source. Il écrit état métier, caches et staging uniquement sous `/var/lib/minos`.

## Mapping des projets

Configuration runtime versionnée :

```text
<MINOS_HOME>/runtime/project-paths.properties
N:/workspace-dev <-> /workspace/projects
```

Le registre persiste `rootRelativePath`, pas le chemin physique host/container. Dans Docker, les commandes utilisent `/workspace/projects/...`.

## Provider-complete image M29-S4

L'image prépare au BUILD :

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989 / 2026-07-27 / 12c3381
Apache Maven         3.9.16
```

Les toolchains nécessaires sont JDK 24, **Apache Maven 3.9.16**, Coursier, Node/npm, Python/pip, .NET SDK 10, Go et Rust/cargo/rustc/rust-analyzer.

Le bundle provider est initialisé dans le volume Docker nommé :

```text
minos-provider-tools
```

monté sous `/var/lib/minos/tools`, read-only dans les plans métier. L'image produit aussi `provider-inventory.json` et `provider-binary-sha256.txt`.

Le probe `minos-provider-probe` reste le gate explicite offline. La CLI ajoute le gate capability-honest :

```text
minos tools verify --all
```

### Provenance scip-java

La coordonnée autoritative reste :

```text
org.scip-code:scip-java:0.13.1
```

Le launcher standalone construit via Coursier retourne actuellement `scip-java version 0.0.0-SNAPSHOT`; cette chaîne n'est pas la provenance de l'artefact. L'inventaire conserve séparément la coordonnée/version attendue et le checksum du binaire réellement exécuté.

JNA et le shim `javac` temporaire de scip-java ont besoin d'un emplacement exécutable. Le `/tmp` général reste `noexec`; les plans provider positionnent :

```text
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native
```

`/run/minos-native` est un tmpfs `nosuid,nodev,exec`, borné à 16 MiB, exposé uniquement aux plans provider.

### Maven et staging Java

Le projet reste read-only. scip-java travaille dans :

```text
/var/lib/minos/runs/<run-id>/scip-java/workspace
```

Les arbres générés (`target`, `build`, `out`, `node_modules`, etc.) ne sont pas copiés. Le staging Linux exclut les launchers racine :

```text
mvnw
mvnw.cmd
```

`.mvn` reste disponible. Docker utilise le Maven 3.9.16 qualifié de l'image. Son état writable est confiné :

```text
HOME=/var/lib/minos/cache/home
MAVEN_OPTS=-Dmaven.repo.local=/var/lib/minos/cache/maven/repository
```

Le checkpoint historique `45536e2fc7d32ed67932e2715e458fa26a8239b1` avait précisément exposé `workspace/mvnw` / `error=2, No such file or directory`; ce défaut est corrigé.

## Routage provider → module/build root M29-S5

Un projet enregistré peut être un monorepo dont la racine globale n'est pas une racine valide pour tous les providers. MINOS distingue désormais :

```text
registeredProjectRoot
projectRoot          = racine réelle d'exécution provider
projectRelativeRoot  = position portable de cette racine dans le projet
```

Exemple de qualification :

```text
fixtures/polyglot/m29-scoped-modules
├── pom.xml                         -> scip-java à la racine
├── src/main/java/...
└── ui
    ├── app/package.json            -> scip-typescript dans ui/app
    ├── app/tsconfig.json
    ├── lib/package.json            -> scip-typescript dans ui/lib
    └── lib/tsconfig.json
```

Il n'existe volontairement aucun `package.json` ni `tsconfig.json` à la racine globale.

Les exécutions scoped d'un même provider sont isolées sous :

```text
/var/lib/minos/runs/<run-id>/<provider>/scopes/module-<sha16>
```

Un chemin SCIP relatif au module comme `src/app.ts` est transformé en chemin projet `ui/app/src/app.ts` avant création du file ID et de l'identité structurelle path-based. Le snapshot projet n'est promu qu'après réussite de tous les scopes et du staging.

## Configuration sémantique persistante S5

Le workflow Docker persiste désormais la sélection sémantique dans le fichier runtime `.env` et dans `installation.json` **format 5**. La même configuration est injectée dans `minos-admin` et `minos-mcp`, afin qu'un query container recréé relise le même store.

Modes packagés actuellement admis :

```text
disabled
local-hash
```

Installation de qualification S5 :

```powershell
.\docker\scripts\prod-mcp-release.ps1 `
  -Action Install `
  -Jar '.\target\minos-code-intelligence-1.0.1-SNAPSHOT-all.jar' `
  -Version '1.0.1-SNAPSHOT' `
  -Commit (git rev-parse HEAD) `
  -ProjectsRoot 'N:\workspace-dev' `
  -SemanticProvider local-hash
```

`local-hash` expose le provider `minos-local-hash`, 384 dimensions. C'est un provider déterministe zéro-réseau destiné à valider le plumbing provider/store/search. **Ce n'est pas un modèle appris et il ne remplace pas la qualification de qualité M23.**

## Vector store

Le store existant est conservé :

```text
/var/lib/minos/semantic-index/<projectId>/index-v2.bin
format v2
float32
exact scan
```

Aucun ANN, HNSW, Lucene ou vector DB externe n'est ajouté par M29. Le signal sémantique reste `HEURISTIC` et ne devient jamais un fait structurel.

Diagnostics :

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

En `READY_WITH_SEMANTIC`, la limitation `SEMANTIC_SIGNAL_IS_HEURISTIC_NOT_STRUCTURAL_FACT` reste explicitement exposée.

## Installation / administration

Workflow :

```powershell
$Docker = '.\docker\scripts\prod-mcp-release.ps1'
& $Docker -Action Admin -MinosArguments @('doctor', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'verify', '--all', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('project', 'add', '/workspace/projects/my-project', '--name', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index-status', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('semantic', 'status', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('hybrid', 'status', 'my-project', '--format', 'json')
```

Les sorties provider Java, TypeScript, C/C++, C#, Go et Rust restent sous le run directory MINOS. Tout provider exigeant une écriture dans `/workspace/projects` doit échouer et être corrigé ; le mount projet ne doit pas être rendu writable.

## Qualification courante

S3/S4 sont prouvés sur :

```text
3df1b40ca0daf50779596f6e955d966ed5eb4973
M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

S5 n'est pas encore PASS. Le gate exact-head est :

```powershell
.\scripts\m29\run-s5.ps1 `
  -ExpectedHead <HEAD> `
  -ProjectsRoot N:\workspace-dev
```

Il doit prouver sur la même installation : provider scopes `ui/app` + `ui/lib`, structured READY, `index-v2.bin`, semantic READY, hybrid `READY_WITH_SEMANTIC`, second index `NONE/NO_CHANGES`, forced FULL, recreate query et worktree inchangé.

Aucune parité native/Docker n'est revendiquée avant M29-S8.

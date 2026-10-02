# Runtime Docker autonome MINOS

> **État courant : M29 est terminé et intégré.** Les références S3/S4/S5 ci-dessous sont des checkpoints historiques de qualification, pas l'état produit actuel. Le runtime Docker fait partie du routage `native|docker` décrit par [`STATUS.md`](../STATUS.md). Les claims de sécurité et de supply-chain restent capability-honest : MINOS ne revendique pas une reproductibilité bit-à-bit du bootstrap Coursier `scip-java` tant que son graphe transitif n'est pas verrouillé par un lockfile possédé par le dépôt.

M29 sépare volontairement le runtime Docker en plusieurs plans afin que l'administration et l'indexation puissent écrire l'état MINOS et les artefacts de build sans rendre le serveur MCP mutable.

## Plans d'exécution

| Plan | Service Compose | Durée | État MINOS | Provider tools | Projets | Réseau |
|---|---|---:|---|---|---|---|
| MCP query | `minos-mcp` | persistant | read-only | read-only | read-only | `none` |
| Administration | `minos-admin` | éphémère | read-write | read-only | read-only | egress (outils, dépendances) |
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

Le serveur MCP persistant, les bootstraps et le probe provider gardent `network_mode: none`. Le plan admin est éphémère et dispose d'une sortie réseau ; cette exception ne sert jamais à installer implicitement les providers MINOS. **Il ne lance aucun provider** : faute de sandbox OS réalisable dans le conteneur, chaque provider y est rapporté `UNSUPPORTED_BY_BACKEND` et `minos index` y est refusé (`provider runtime is not ready`), de même que `remote index` ; aucun code de projet n'y est compilé ni exécuté. L'indexation par provider se fait sur l'hôte natif ; le plan Docker administre et sert l'index construit (voir [disposition des sandboxes](../developer/remote-worker-sandbox-disposition.md)).

Le plan admin n'obtient jamais le droit de modifier le code source. Il écrit état métier, caches et staging uniquement sous `/var/lib/minos`.

## Mapping des projets

Configuration runtime versionnée :

```text
<MINOS_HOME>/runtime/project-paths.properties
N:/workspace-dev <-> /workspace/projects
```

Le registre persiste `rootRelativePath`, pas le chemin physique host/container. Dans Docker, les commandes utilisent `/workspace/projects/...`.

## Image provider-complete

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

## Provenance et reproductibilité

La construction provider-complete applique désormais les invariants suivants :

- images de base Docker épinglées par digest OCI ;
- paquets Ubuntu résolus depuis l'archive datée `20260814T000000Z`, et non depuis les miroirs mouvants `archive/security` ;
- Maven 3.9.16 vérifié par le SHA-256 possédé par le dépôt, identique au checksum du Maven Wrapper ;
- Node vérifié par les `SHASUMS256` upstream ;
- launcher Coursier, `scip-clang` et `rust-analyzer` vérifiés par SHA-256 attendu ;
- `scip-typescript` et `scip-python` installés avec les lockfiles npm v3 du dépôt et `npm ci --ignore-scripts` ;
- `scip-dotnet` téléchargé comme `.nupkg` 0.2.14 exact, vérifié par SHA-256, puis installé avec une configuration NuGet contenant `<clear/>` et uniquement la source locale vérifiée ;
- `scip-go` installé à la version exacte via `proxy.golang.org` avec `sum.golang.org`, les bypass `GONOSUMDB`, `GOPRIVATE` et `GONOPROXY` étant vidés ;
- les exécutables finaux sont hashés dans `provider-binary-sha256.txt`.

### Limite Coursier `scip-java`

La coordonnée autoritative reste :

```text
org.scip-code:scip-java:0.13.1
```

Le launcher standalone construit via Coursier retourne actuellement `scip-java version 0.0.0-SNAPSHOT`; cette chaîne n'est pas la provenance de l'artefact. L'inventaire conserve séparément la coordonnée/version attendue et le checksum du binaire réellement exécuté.

La coordonnée et le launcher Coursier sont épinglés, mais le graphe transitif Maven résolu par Coursier n'est pas encore matérialisé par un lockfile repository-owned. **MINOS ne présente donc pas cette étape comme bit-for-bit reproducible.** Cette limitation est explicitement suivie dans le registre des risques ; elle n'affecte pas la query plane persistante, qui reste offline et n'installe aucun provider à l'exécution.

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

## Routage provider → module/build root

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

## Configuration sémantique persistante

Le workflow Docker persiste la sélection sémantique dans le fichier runtime `.env` et dans `installation.json` **format 5**. La même configuration est injectée dans `minos-admin` et `minos-mcp`, afin qu'un query container recréé relise le même store.

Modes packagés actuellement admis :

```text
disabled
local-hash
```

Exemple d'installation :

```powershell
.\docker\scripts\prod-mcp-release.ps1 `
  -Action Install `
  -Jar '.\target\minos-code-intelligence-1.3.0-SNAPSHOT-all.jar' `
  -Version '1.3.0-SNAPSHOT' `
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
# `index` est refusé dans ce plan (aucun provider n'y tourne) : indexer sur l'hôte natif.
& $Docker -Action Admin -MinosArguments @('index-status', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('semantic', 'status', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('hybrid', 'status', 'my-project', '--format', 'json')
```

Quand un provider s'exécute (hôte natif), ses sorties Java, TypeScript, C/C++, C#, Go et Rust restent sous le run directory MINOS. Tout provider exigeant une écriture dans `/workspace/projects` doit échouer et être corrigé ; le mount projet ne doit pas être rendu writable.

## Limites de ressources

Chaque service des deux fichiers compose a un plafond CPU, mémoire et PID, défini **une fois par rôle** (blocs `x-limits-<rôle>` en tête de `docker/compose.mcp.prod.yaml` et `docker/compose.mcp.connected.yaml`, identiques dans les deux) et surchargeable depuis le `.env` du runtime. `memswap_limit` égale `mem_limit` : aucun swap. Le gate `scripts/quality/check-compose-limits.py` refuse un service sans plafond, un plafond écrit dans un service, ou deux fichiers qui divergent. Docker applique ces clés hors Swarm : vérifié par `docker inspect` et par les fichiers cgroup du conteneur (`memory.max`, `memory.swap.max`, `pids.max`, `cpu.max`).

| Rôle | Services | CPU | Mémoire | PID |
|---|---|---|---|---|
| requêtes | `minos-mcp` | `MINOS_MCP_CPUS` = `4` | `MINOS_MCP_MEM_LIMIT` = `3g` | `MINOS_MCP_PIDS_LIMIT` = `1024` |
| administration | `minos-admin` | `MINOS_ADMIN_CPUS` = `4` | `MINOS_ADMIN_MEM_LIMIT` = `2g` | `MINOS_ADMIN_PIDS_LIMIT` = `256` |
| tâches ponctuelles | `minos-bootstrap`, `minos-data-bootstrap`, `minos-tools-bootstrap`, `minos-provider-probe` | `MINOS_JOB_CPUS` = `2` | `MINOS_JOB_MEM_LIMIT` = `512m` | `MINOS_JOB_PIDS_LIMIT` = `128` |
| PostgreSQL géré | `minos-postgres` | `MINOS_POSTGRES_CPUS` = `2` | `MINOS_POSTGRES_MEM_LIMIT` = `1g` | `MINOS_POSTGRES_PIDS_LIMIT` = `128` |
| Ollama géré | `minos-ollama` | `MINOS_OLLAMA_CPUS` = `4` | `MINOS_OLLAMA_MEM_LIMIT` = `2g` | `MINOS_OLLAMA_PIDS_LIMIT` = `256` |

Les PID comptent les **threads** : une JVM MCP en pèse environ 35. Chaque client MCP (IDE, agent) lance sa propre JVM dans `minos-mcp`, donc la mémoire et les PID croissent avec le nombre de clients simultanés (environ 120 Mo et 35 PID chacun) : avec les plafonds par défaut, une vingtaine de clients. Un projet très volumineux, ou davantage de clients, se règle dans le `.env` (par exemple `MINOS_MCP_MEM_LIMIT=6g`). Une JVM dimensionne son tas à 25 % du plafond mémoire du conteneur.

### Mesures et marges

Mesurées le 2026-10-02 (cgroup v2 : `memory.peak`, `pids.peak`) ; la mémoire de pic inclut le cache de pages, que le noyau récupère avant tout OOM. Le « plancher » est le plus petit plafond pour lequel la charge réussit encore.

| Rôle | Charge | Pic mesuré | Plancher mesuré | Plafond retenu | Marge |
|---|---|---|---|---|---|
| requêtes | 9 clients MCP simultanés (`initialize`, `tools/list`, `minos_index_status`, `minos_search_code`), sans plafond ; le conteneur de production, observé après 37 h, avait 1,0 Gio et 317 PID | 961 Mio, 292 PID | 1 Gio / 150 PID (512 Mio : un client tué par l'OOM) | 3 Gio, 1024 PID | ×3,1 mémoire, ×3,5 PID |
| administration | `doctor`, `tools verify --all`, `project add` puis `index --dry-run` sur ce dépôt (45 modules), sans plafond | 344 Mio, 44 PID | moins de 64 Mio / 30 PID (24 PID : échec) | 2 Gio, 256 PID | ×6 mémoire, ×5,8 PID |
| tâches ponctuelles | sonde providers (`java`, `mvn`, `node`, `dotnet`, `go`, `cargo`…), les quatre amorçages sous plafond via `docker compose run` | 52 Mio, 19 PID | 128 Mio / 32 PID | 512 Mio, 128 PID | ×9 mémoire, ×6,7 PID |
| PostgreSQL | 30 000 vecteurs de 768 dimensions (121 Mo) et 8 recherches exactes simultanées ; production après 37 h : 120 Mio, 21 PID | 448 Mio, 29 PID | non cherché | 1 Gio, 128 PID | ×2,3 mémoire (×8 production), ×4,4 PID |
| Ollama | `ollama pull nomic-embed-text`, chargement et requête d'embedding sous plafond ; production après 37 h : 416 Mio, 26 PID | 593 Mio, 39 PID | non cherché | 2 Gio, 256 PID | ×3,4 mémoire, ×6,5 PID |

Limites des mesures : le plan `minos-admin` ne lance aucun provider (voir [disposition des sandboxes](../developer/remote-worker-sandbox-disposition.md)), il n'y a donc pas d'indexation réelle à mesurer dans Docker ; la planification d'indexation (`index --dry-run`) en tient lieu. Une indexation sémantique par `minos-admin` n'a pas été mesurée (aucun instantané d'index n'est construisible dans le conteneur) : la marge de 2 Gio sur un pic de 344 Mio est la réserve prévue pour elle. Le plafond CPU ne se mesure pas en pic : il borne l'usage de l'hôte (la JVM voit 4 CPU et crée moins de threads).

## Qualification et historique M29

L'état produit courant est celui de [`STATUS.md`](../STATUS.md) : **M29 terminé et intégré**. Les lignes suivantes sont conservées uniquement comme checkpoints historiques de la montée en qualification :

```text
3df1b40ca0daf50779596f6e955d966ed5eb4973
M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

À ce checkpoint historique, S5 n'était pas encore PASS et `run-s5.ps1` devait encore prouver provider scopes `ui/app` + `ui/lib`, structured READY, `index-v2.bin`, semantic READY, hybrid `READY_WITH_SEMANTIC`, second index `NONE/NO_CHANGES`, forced FULL, recreate query et worktree inchangé.

Cette phrase historique **ne décrit plus le HEAD courant** et ne doit pas être utilisée pour conclure que M29 ou la parité native/Docker sont encore en attente. Les nouveaux changements doivent être qualifiés sur leur propre exact HEAD par les gates actuels avant intégration.

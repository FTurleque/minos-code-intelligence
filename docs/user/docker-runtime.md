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
- Node vérifié par une empreinte SHA-256 épinglée dans `embedded-tools.json` (la description unique des outils, voir `docs/developer/quality-gates.md`) ;
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

Chaque service des deux fichiers compose a un plafond **mémoire** et **PID**, défini une fois par rôle (blocs `x-limits-<rôle>` en tête de `docker/compose.mcp.prod.yaml` et `docker/compose.mcp.connected.yaml`, identiques dans les deux) et surchargeable depuis le `.env` du runtime. `memswap_limit` égale `mem_limit` : aucun swap. Les variables, leurs défauts et leurs unités sont aussi listés dans [`docker/.env.example`](../../docker/.env.example). Une ligne `MINOS_<RÔLE>_…` ajoutée au `.env` survit à une mise à jour (l'installation régénère le `.env` et reporte ces lignes). Docker applique ces clés hors Swarm : vérifié par `docker inspect` et par les fichiers cgroup du conteneur (`memory.max`, `memory.swap.max`, `pids.max`).

| Rôle | Services | Mémoire (octets, `<n>k`, `<n>m` ou `<n>g`) | PID (processus **et** threads) | CPU (nombre de CPU) |
|---|---|---|---|---|
| requêtes | `minos-mcp` | `MINOS_MCP_MEM_LIMIT` = `3g` | `MINOS_MCP_PIDS_LIMIT` = `1024` | `MINOS_MCP_CPUS` = `0` |
| administration | `minos-admin` | `MINOS_ADMIN_MEM_LIMIT` = `2g` | `MINOS_ADMIN_PIDS_LIMIT` = `256` | `MINOS_ADMIN_CPUS` = `0` |
| tâches ponctuelles | `minos-bootstrap`, `minos-data-bootstrap`, `minos-tools-bootstrap`, `minos-provider-probe`, `minos-ollama-bootstrap` | `MINOS_JOB_MEM_LIMIT` = `512m` | `MINOS_JOB_PIDS_LIMIT` = `128` | `MINOS_JOB_CPUS` = `0` |
| PostgreSQL géré | `minos-postgres` | `MINOS_POSTGRES_MEM_LIMIT` = `1g` | `MINOS_POSTGRES_PIDS_LIMIT` = `128` | `MINOS_POSTGRES_CPUS` = `0` |
| Ollama géré | `minos-ollama` | `MINOS_OLLAMA_MEM_LIMIT` = `2g` | `MINOS_OLLAMA_PIDS_LIMIT` = `256` | `MINOS_OLLAMA_CPUS` = `0` |

**Aucun plafond CPU par défaut** (`0` = pas de plafond, la forme que Docker et Compose acceptent : une valeur vide fait échouer l'interpolation). Un plafond dur par défaut au-dessus du nombre de CPU de l'hôte fait refuser la création du conteneur par le démon (`range of CPUs is from 0.01 to N`) : un Docker Desktop ou une VM à 2 CPU ne démarrerait plus, avec une erreur du démon sans lien apparent avec MINOS. Un plafond de 1 CPU démarre partout, mais ralentirait le démarrage des JVM ; il reste donc au choix de l'opérateur (`MINOS_<RÔLE>_CPUS=2` dans le `.env`), qui connaît son hôte. La protection par défaut est la mémoire et les PID.

Les PID comptent les **threads** : une JVM MCP en pèse environ 30. Chaque client MCP (IDE, agent) lance sa propre JVM dans `minos-mcp` : la mémoire et les PID croissent avec le nombre de clients simultanés (environ 90 Mio et 30 PID chacun, mesuré). **Le nombre de threads d'une JVM dépend du nombre de CPU qu'elle voit** : sans plafond CPU (le défaut), chaque JVM voit tous les CPU de l'hôte et crée davantage de threads de ramasse-miettes et de compilation ; avec `MINOS_MCP_CPUS=4` le pic des 9 clients passe d'environ 280-313 à 218-240 PID. Un projet très volumineux, ou davantage de clients, se règle dans le `.env` (par exemple `MINOS_MCP_MEM_LIMIT=6g`). Une JVM dimensionne son tas à 25 % du plafond mémoire du conteneur (`UseContainerSupport`, vérifié : 128 Mio sous 512m, 512 Mio sous 2g, 768 Mio sous 3g) : aucun `-Xmx` n'est posé, le tas suit le plafond. Les tâches ponctuelles (JVM de `minos-bootstrap`, sonde providers) passent sous 512 Mio avec un tas de 128 Mio.

### Mesures et marges

Mesurées le 2026-10-02 sur un hôte à 16 CPU, Docker Desktop (cgroup v2 : `memory.peak`, `pids.peak`). La mémoire de pic inclut le cache de pages et le tas paresseux de la JVM, que le noyau et le ramasse-miettes réduisent sous plafond : un pic « sans plafond » est un majorant, le **plancher** (le plus petit plafond pour lequel la charge réussit) est la vraie mesure. Méthode, rejouable : un conteneur configuré comme le service (`docker run --read-only --cap-drop ALL --security-opt no-new-privileges`, uid 10001, mêmes variables d'environnement et tmpfs que le compose, avec `--memory M --memory-swap M --pids-limit P`), la commande mesurée, puis lecture de `/sys/fs/cgroup/memory.peak`, `pids.peak`, `memory.events` (`oom_kill`) et `pids.events` (`max` = créations refusées) avant l'arrêt du conteneur.

| Rôle | Charge | Sans plafond (majorant) | Plancher mesuré | Plafond retenu | Marge |
|---|---|---|---|---|---|
| requêtes | 9 clients MCP simultanés en stdio (`initialize`, `tools/list`, `minos_index_status`, `minos_search_code`), hôte à 16 CPU, sans plafond CPU | 961 Mio, 292 PID | PID : sur mes 10 runs à 300, 350, 400, 500 et 1024, 9 clients sur 9 sans création refusée, pic 280-289 ; sur un second banc indépendant, 300 donne 5 clients sur 9 (28 créations refusées), 400, 500 et 1024 donnent 9/9 avec un pic de 308-313 ; plus bas, des créations de threads sont refusées (≈ 930 à 256) et des clients échouent (200 : 8 sur 9 ; 150 : 5 sur 9 à 0 sur 9 selon le banc). **Plancher à retenir : environ 320-400 PID**, le chiffre exact variant d'un run à l'autre. Mémoire : 1 Gio réussit (571 Mio) ; 512 Mio : un client tué | 3 Gio / 1024 PID (aux plafonds : 789 Mio, 283 PID, 9/9) | mémoire ×3,8 sur 789 Mio ; PID ×3,3 sur le pic le plus haut observé (313), ×2,6 sur le plancher de 400 |
| administration | la charge la plus lourde que le plan sache produire : `git-activity` (dépôt de 1 667 commits : 333 Mio, 39 PID ; dépôt de 636 Mo de `.git` : 187 Mio, 40 PID), `project add` (237 Mio, 39 PID), `index --dry-run` (113 à 269 Mio, 37 à 41 PID), `tools verify --all` (209 à 212 Mio, 43 PID), sonde providers (81 Mio, 25 PID) | **333 Mio, 43 PID** | `git-activity` : 128 Mio réussit ; `index --dry-run` : 64 Mio / 32 PID réussit, 24 PID échoue (`OutOfMemoryError: unable to create native thread`) | 2 Gio / 256 PID (aux plafonds : 120 Mio, 34 PID) | mémoire ×6 sur le pire cas mesuré ; PID ×6 sur 43 et ×8 sur le plancher de 32 |
| tâches ponctuelles | sonde providers (commande exacte du compose, `network none`), les quatre amorçages sous plafond via `docker compose run` | 81 Mio, 25 PID | 128 Mio / 32 PID | 512 Mio / 128 PID | ×6 mémoire, ×5 PID |
| PostgreSQL | 30 000 vecteurs de 768 dimensions (121 Mo), 8 recherches exactes simultanées ; puis construction d'un index HNSW (voir ci-dessous) | 448 Mio, 29 PID ; HNSW : 602 Mio | non cherché | 1 Gio / 128 PID | ×1,7 sur la construction HNSW, ×2,3 sur les recherches |
| Ollama | modèle par défaut `nomic-embed-text` : pull, chargement, 4 embeddings simultanés | 312 Mio, 57 PID | 300 Mio réussit, 200 Mio échoue (voir Dépannage) | 2 Gio / 256 PID | ×6,6 mémoire, ×4,5 PID |

**Le plafond d'administration de 2 Gio est un plafond généreux, non dérivé du pire cas.** Le pire cas mesuré est de 333 Mio sans plafond (et le plancher est inférieur à 128 Mio) ; 2 Gio laisse de la place pour ce qui n'a pas pu être mesuré : une indexation sémantique ou hybride par `minos-admin` (aucun instantané d'index n'est construisible dans le conteneur, puisqu'il ne lance aucun provider), un `import-scip` volumineux (l'import d'un fichier de 2,4 Mo, et `project add` sur toute l'arborescence de travail, ont échoué en `AccessDeniedException` avant de mesurer quoi que ce soit), un très gros projet. Il n'y a pas d'indexation réelle à mesurer dans Docker : le plan admin ne lance aucun provider (voir [disposition des sandboxes](../developer/remote-worker-sandbox-disposition.md)) ; la planification (`index --dry-run`) en tient lieu.

**Ollama, 2 Gio : dimensionné pour le modèle d'embedding par défaut** (`nomic-embed-text`, 274 Mo). Le modèle est configurable (`MINOS_SEMANTIC_MODEL`) : un modèle plus gros demande un plafond plus haut (`MINOS_OLLAMA_MEM_LIMIT`).

**PostgreSQL, 1 Gio, et pgvector.** Réglages effectifs de l'image pgvector épinglée dans le compose (`SHOW`, conteneur réel, aucun réglage posé par MINOS) : `shared_buffers` = 160 Mo, `work_mem` = 4 Mo, `maintenance_work_mem` = 64 Mo, `max_connections` = 100, `max_parallel_maintenance_workers` = 2. Ils tiennent largement sous 1 Gio. MINOS ne crée pas d'index ANN (le classement pgvector est exact) ; une construction HNSW réelle sur 30 000 vecteurs de 768 dimensions, **sous le plafond de 1 Gio**, a donc été mesurée à titre de précaution : 92 Mo d'index, 9 s, pic 602 Mio sans OOM avec `maintenance_work_mem` = 64 Mo (pgvector signale qu'il manque de mémoire et termine sur disque) ; 839 Mio avec 512 Mo et 876 Mio avec 1 Go (toujours sans OOM, le cache de pages étant récupérable). Un index plus gros ou un `maintenance_work_mem` plus haut se règlent dans `MINOS_POSTGRES_MEM_LIMIT`.

## Dépannage des plafonds

Quand un plafond est atteint, Docker ne le dit pas de lui-même : le conteneur est tué (code de sortie 137), ou un processus du conteneur l'est, ou une création de processus échoue. `docker\scripts\prod-mcp-release.ps1 -Action Status` interroge l'état réel des conteneurs du projet et nomme la variable à relever et le plafond en vigueur ; `-Action Admin` fait de même quand la commande échoue. Ces messages disent **ce que le conteneur rapporte**, pas pourquoi (ils n'affirment jamais qu'un modèle ou un projet est « trop gros »).

| Ce que vous voyez | Ce que cela indique | Variable à relever (dans le `.env` du runtime) |
|---|---|---|
| un service sort avec le code 137, ou `docker inspect --format '{{.State.OOMKilled}}' <conteneur>` donne `true` | le noyau a tué un processus pour avoir atteint le plafond mémoire | `MINOS_<RÔLE>_MEM_LIMIT` |
| `OutOfMemoryError: unable to create native thread`, `Resource temporarily unavailable`, ou `cat /sys/fs/cgroup/pids.events` (dans le conteneur) affiche `max` > 0 | des créations de processus ou de threads ont été refusées par le plafond de PID (une JVM qui échoue ainsi affiche une `OutOfMemoryError` trompeuse : ce n'est pas la mémoire) | `MINOS_<RÔLE>_PIDS_LIMIT` |
| `semantic` échoue avec `local Ollama embedding request failed with HTTP 500` et `docker logs <conteneur ollama>` contient `llama-server process has terminated: signal: killed` | le processus de chargement du modèle a été tué, alors que le conteneur Ollama reste `healthy` ; `OOMKilled` est alors `true` si c'est le plafond mémoire | `MINOS_OLLAMA_MEM_LIMIT` |
| Docker refuse de créer un conteneur : `range of CPUs is from 0.01 to N` | un `MINOS_<RÔLE>_CPUS` supérieur au nombre de CPU de l'hôte | `MINOS_<RÔLE>_CPUS` |

`<RÔLE>` vaut `MCP`, `ADMIN`, `JOB`, `POSTGRES` ou `OLLAMA` (table ci-dessus). Après modification du `.env`, recréer le service (`docker compose up -d --force-recreate <service>`).

Testé avec un plafond volontairement trop bas : Ollama sous 200 Mio, requête d'embedding → HTTP 500, `docker logs` : `Load failed … llama-server process has terminated: signal: killed`, conteneur toujours `healthy`, `OOMKilled=true`, aucun redémarrage : **silencieux sans diagnostic**, d'où le message de `Status` ; sous 300 Mio la même requête réussit.

## Ollama sans privilèges

`minos-ollama` s'exécute sous l'uid `10002`, sans capacité, avec `no-new-privileges` et un système de fichiers racine en lecture seule ; son `HOME` est `/home/ollama` et le volume de modèles est monté sur `/home/ollama/.ollama` (même disposition que l'ancien `/root/.ollama` : les modèles déjà téléchargés sont retrouvés tels quels).

Les volumes de modèles créés par les versions précédentes appartiennent à `root`. Le service ponctuel `minos-ollama-bootstrap` (image MINOS, `user: 0:0`, capacités `CHOWN` et `FOWNER` seulement, aucun réseau) remet le volume à l'uid `10002` avant le démarrage d'Ollama ; il ne touche rien quand la propriété est déjà correcte. Aucune action n'est requise sur un volume existant : le prochain `up` le migre. Retour arrière vers une version antérieure (Ollama en `root`, sans capacité `DAC_OVERRIDE`) : remettre d'abord la propriété du volume à `root` (`docker run --rm --user 0:0 -v <volume>:/v --entrypoint chown <image-minos> -R 0:0 /v`), sans quoi la clé privée de l'instance (mode `0600`, propriétaire `10002`) n'est plus lisible.

Prouvé en local avec l'image épinglée dans le compose : volume neuf (téléchargement de `nomic-embed-text` par `docker exec ollama pull`, chargement, requête d'embedding de 768 valeurs) ; volume préexistant créé en `root` (migration, `ollama list`, embedding sans nouveau téléchargement) ; second démarrage sans rien changer. Sans l'amorçage, le même conteneur échoue sur un volume appartenant à `root` : `Error: remove /home/ollama/.ollama/models/manifests: permission denied`.

## Qualification et historique M29

L'état produit courant est celui de [`STATUS.md`](../STATUS.md) : **M29 terminé et intégré**. Les lignes suivantes sont conservées uniquement comme checkpoints historiques de la montée en qualification :

```text
3df1b40ca0daf50779596f6e955d966ed5eb4973
M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

À ce checkpoint historique, S5 n'était pas encore PASS et `run-s5.ps1` devait encore prouver provider scopes `ui/app` + `ui/lib`, structured READY, `index-v2.bin`, semantic READY, hybrid `READY_WITH_SEMANTIC`, second index `NONE/NO_CHANGES`, forced FULL, recreate query et worktree inchangé.

Cette phrase historique **ne décrit plus le HEAD courant** et ne doit pas être utilisée pour conclure que M29 ou la parité native/Docker sont encore en attente. Les nouveaux changements doivent être qualifiés sur leur propre exact HEAD par les gates actuels avant intégration.

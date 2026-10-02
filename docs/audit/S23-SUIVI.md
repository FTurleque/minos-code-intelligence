# Suivi du chantier « Sprints 2–3, derniers constats » (S11, D1, C2, S14)

Chantier ouvert le 2026-10-02 sur `docs/audit/AUDIT-2026-09.md`. Quatre constats, les seuls de l'échéance sprints 2–3
encore ouverts. Après ce chantier il ne reste que des constats d'échéance **Trimestre**.

| Lot | Branche | Constats | PR | État |
|---|---|---|---|---|
| 1 | `sec/s11-chaine-et-conteneurs` | S11 | — | implémenté, validé en local (`clean verify`), en attente de PR |
| 2 | `rel/d1-distribution-auto-portante` | D1 | — | à faire |
| 3 | `ci/c2-un-seul-build` | C2 | — | à faire |
| 4 | `sec/s14-assainissement` | S14 | — | à faire |

Base : `origin/develop` au 2026-10-02 (`2663bbda`, merge de #321). Une branche, un worktree (`minos-wt/…`) par lot, lots
séquentiels, chacun rebasé sur le précédent. #322 (promotion `develop`→`main`) et #323 (correctif Sonar S2259) sont
ouvertes et hors de ce chantier : rien n'est fusionné ici.

Règle de CI de ce chantier : **un seul passage de validation par lot**, un second seulement sur demande motivée au
propriétaire. Aucun `workflow_dispatch` ni push « pour voir ».

## Constats préliminaires du pilote (avant tout code)

Relevés le 2026-10-02 en relisant le dépôt réel, à confirmer ou infirmer par l'implémenteur de chaque lot.

| # | Constat | Conséquence |
|---|---|---|
| P1 | S14 est **déjà partiellement fermé** : `AutonomousIndexOperations.ResumeView` (`minos-cli`) passe `refusalReason` par `CliCommandSupport.publicDiagnostic` à la construction (introduit par R1 lot 3, `90ab9197`). Les chemins sont donc déjà remplacés par le texte de repli. **Reste à prouver** : `PublicErrorMessages.sanitize` ne retire que `\r`/`\n`, pas les autres caractères de contrôle (ESC, etc.) — la règle unique pour les caractères de contrôle est `DegradedEntry.printable` (`minos-engine`, `com.minos.registry`). | Le lot 4 doit d'abord dire ce qui fuit **réellement** aujourd'hui (texte ? JSON ?) par un test rouge, puis corriger ce qui fuit, sans écrire un troisième assainisseur. |
| P2 | Le « plan admin » est le service compose `minos-admin` de `docker/compose.mcp.{prod,connected}.yaml` : réseau `minos-admin-egress`, `MINOS_DATA_DIR` en écriture, lance `com.minos.cli.MinosLauncher`. Il faut établir **par le code** si du code venu d'un projet non fiable y est compilé sans passer par la décision d'ADR 0041, et par quel chemin exact. | Le premier travail du lot 1 est cette preuve, pas la ligne YAML. Si la prémisse de l'audit ne se vérifie pas dans le code actuel, on le dit et on ne fabrique pas de test rouge. |
| P3 | Les trois points de conteneur sont dans `docker/compose.mcp.prod.yaml` et `docker/compose.mcp.connected.yaml` ; les services `minos-postgres` (`pgvector/pgvector:0.8.2-pg17`) et `minos-ollama` (`ollama/ollama:0.32.0`) n'existent que dans le fichier *connected*. Ollama monte son volume sur `/root/.ollama`. | L'épinglage par digest et les limites portent sur deux fichiers : une seule source pour les valeurs. |
| P4 | Docker 29.7.2 (moteur Linux, 25 Go) est disponible localement ; `actionlint`, `act`, `yamllint` ne le sont pas (`actionlint` possible via l'image `rhysd/actionlint`). | La mesure d'empreinte (lot 1) et la preuve hors ligne (lot 2) sont faisables localement. |

## Lot 1 — S11 (chaîne d'approvisionnement et conteneurs)

Implémenteur : `impl-s23`. Branche `sec/s11-chaine-et-conteneurs`, worktree `minos-wt/sec-s11`. Ordre imposé : point 5, 2, 3, 4, 1.
Mesures et preuves Docker faites avec l'image locale `minos-code-intelligence:1.2.0-730b760020b8` (toolchains complets, révision
`730b760`, antérieure à l'ADR 0041) **dont le JAR `/opt/minos/minos.jar` est remplacé par le JAR ombré construit depuis ce
worktree** (`-v …:/opt/minos/minos.jar:ro`), sur des volumes jetables `minos-s11-*` : le code exécuté est celui de la branche, les
toolchains sont ceux de l'image. Le prod de l'utilisateur (`minos-mcp-prod*`, volume `minos-mcp-prod-ollama-models`) n'est jamais touché.

### Baseline des gates (origin/develop `2663bbda`, avant tout commit du lot)

| Gate | Résultat |
|---|---|
| `scripts/architecture/check-module-boundaries.py` | SUCCESS (modules=14, sources=511, packages=45) |
| `scripts/architecture/check-private-io.py` | SUCCESS (sources=511, allowlisted=37, forbidden=8, primitives=4) |
| `scripts/architecture/test_check_private_io.py` | 30 tests OK |
| `scripts/docs/check-current-docs.py` | SUCCESS |
| `scripts/docs/product-facts.py --check` | SUCCESS |
| `scripts/quality/check-milestone-artifact-references.py` | SUCCESS (scripts checked=98) |
| `scripts/quality/check-workflow-pins.py` | SUCCESS (external uses=70) |
| `scripts/quality/check-jacoco.py` | non évaluable sans `clean verify` (rapports) : relevé en fin de lot |

### Point 5 — le plan admin compile-t-il du code non fiable sans bac à sable ?

**Verdict : issue (iii). La prémisse de l'audit ne se vérifie pas dans le code actuel.** Dans le plan `minos-admin`, aucun code venu
d'un projet indexé n'est compilé ni lancé : tous les providers y sont refusés (`UNSUPPORTED_BY_BACKEND`), `index` échoue avant le
moindre processus, et `remote index` est refusé avant toute matérialisation. Aucun test rouge n'est fabriqué. Ce qui subsiste : un
écart de documentation (le plan est décrit comme « exécutant les providers gérés ») et une garde sans test (voir « Décisions »).

#### (a) Chemins qui compilent ou lancent du code venu d'un projet indexé, et décision qui les gouverne

Inventaire par `grep` de `ProcessBuilder|ProcessHandle|Runtime.exec|ToolProvider|ServiceLoader|URLClassLoader` sur `*/src/main` (le 2026-10-02).

| # | Chemin | Code | Exécute du code du projet ? | Décision qui le gouverne |
|---|---|---|---|---|
| 1 | Providers gérés locaux (scip-java → Maven et plugins du projet ; scip-typescript → `tsc`/`npm` ; scip-python ; scip-clang ; scip-dotnet → MSBuild ; scip-go ; rust-analyzer → `cargo`, build scripts) | `StrongOwnedProcessExecutors.required` → `new ProcessIndexerExecutor` enveloppé par `StrongProcessOwnershipIndexerExecutor` (3 arguments) | **oui** (seul chemin local) | `WorkerSandboxBackends.strongestAvailableForManagedLocalProvider` : `executeLocallyIsolated` exige `backend.supportsManagedLocalProvider()` puis, si `DENY`, la preuve de refus réseau. En amont, `StrongOwnedProcessExecutors.qualifyOwnership` rend le provider non `READY` : `UNSUPPORTED_BY_BACKEND` sous `MINOS_RUNTIME_LOCATION=docker`, `BLOCKED` sur hôte natif ; `LocalAutonomousIndexOperations.executeLocked` refuse tout provider non `READY` ; `ManagedScip*RuntimeManager.executor()` refuse aussi. |
| 2 | `remote index` (sources distantes matérialisées puis indexées) | `LocalRemoteIndexOperations.index` → refus anticipé ; `LocalIsolatedIndexWorker.execute` ; `DistributedIndexerExecutor` | oui s'il s'exécutait | `WorkerSandboxBackends.selectForUntrustedCode` (alias `strongestAvailable`) : refus avant matérialisation (`refuseUnlessUntrustedCodeSandboxIsQualified`), puis en profondeur `sandboxBackend.supportsUntrustedCode()` dans le worker. Fermé par décision (ADR 0041). |
| 3 | `remote materialize` | `JGitRemoteRepositoryMaterializer` (JGit, Java pur) | non (clone et copie ; aucun hook ni filtre exécuté) | hors décision : aucune exécution |
| 4 | Analyse Java avancée | `JavaAstParser` : `JavacTask.parse()` avec `-proc:none` | non (parse seulement : ni attribution ni processeur d'annotations) | hors décision : aucune exécution |
| 5 | Installation des outils (`tools install`) | `ManagedScipProviderRuntimeManager.run`, `…Polyglot…`, `…Python…` : `npm ci --ignore-scripts`, Coursier, NuGet, `go install` | non : répertoire d'exécution = racine des outils, versions et sommes épinglées (chaîne d'approvisionnement des outils, pas du projet) | hors décision |
| 6 | Latent : `StrongProcessOwnershipIndexerExecutor` à 2 arguments (« ownership only ») et `ProcessIndexerExecutor.execute` nu ; `NativeEphemeralWorkspaceBackend.execute` avec `ALLOW` (appelle `delegate.execute`) | publics, sans isolation propre | oui s'ils étaient câblés | **aucun appelant en production** (un seul site de construction, `StrongOwnedProcessExecutors.required`, version à 3 arguments) ; le backend natif n'est atteignable que si les gardes 1 et 2 l'ont laissé passer, ce qu'elles refusent. Latent, noté dans « à traiter plus tard ». |
| 7 | Côté hôte | `DockerMcpTransport`, `minos-intellij` (`ProcessBuilder`) | non : clients qui lancent `docker`/`minos` | hors décision |
| 8 | Services compose | `minos-provider-probe` (`mvn --version`, `go version`… sans mount projet, `network_mode: none`), `minos-tools-bootstrap` (`cp`) | non (aucun projet monté) | hors décision |

Aucune variable d'environnement, option ou propriété ne désactive ces gardes (`grep unsafe|MINOS_ALLOW|insecure` : néant ;
`docs/user/remote-indexing.md:39` le dit aussi).

#### (b) Ce qui est vrai aujourd'hui dans `minos-admin`

Preuves exécutées (image locale + JAR de la branche, conteneur configuré comme `minos-admin` : `read_only`, `cap_drop: [ALL]`,
`no-new-privileges`, uid 10001, `MINOS_RUNTIME_LOCATION=docker`) :

| Commande | Résultat réel |
|---|---|
| `doctor --format json` | 7 providers sur 7 `UNSUPPORTED_BY_BACKEND` (diagnostic : « managed local provider sandbox tier is not provided by the Docker MCP backend … native-process-ephemeral-workspace-v1 ») |
| `project add /workspace/projects/java --name fjava` puis `index fjava` | `error: index failed: provider runtime is not ready: scip-java — managed local provider sandbox tier is not provided by the Docker MCP backend …` : aucun processus fils |
| `remote index https://github.com/octocat/Hello-World … --worker-network allow` | `remote index is refused before any materialization: … no OS sandbox backend is available on this host (missing prerequisite: LINUX_BUBBLEWRAP_NOT_FOUND, LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING)` : refusé avant toute copie |
| (même image, **ancien** JAR 1.2.0, pré-ADR 0041) `remote index … allow` | « changed project requires at least one selected indexer » : le dépôt a été **matérialisé** (réseau de l'admin) mais aucun provider n'a été lancé (Hello-World n'a pas de source indexable) ; ce comportement est antérieur au refus anticipé de l'ADR 0041 et n'existe plus dans le code actuel |

Pourquoi le plan ne peut pas non plus « se donner » une sandbox : l'image n'embarque pas `bwrap` ; dans le conteneur
`cap_drop: [ALL]` avec le profil seccomp par défaut, `unshare --user --map-root-user` et `unshare --user --pid --fork` échouent
(`Operation not permitted`) et `/sys/fs/cgroup` est monté `ro` : ni bubblewrap (namespaces utilisateur) ni la délégation cgroup
v2 ne sont réalisables. Le plan admin ne peut donc ni acquérir `supportsManagedLocalProvider()` ni `supportsUntrustedCode()`.
La CI le sait déjà : `scripts/ci/qualify-docker-upgrade.ps1:255-268` exige que `index` échoue avec `UNSUPPORTED_BY_BACKEND`.

Ce que le conteneur garantit : système de fichiers racine en lecture seule, `cap_drop: [ALL]`, `no-new-privileges`, uid 10001,
tmpfs bornés, projets montés en lecture seule, volume des outils en lecture seule, réseau `internal` pour `minos-runtime` plus
`minos-admin-egress` (sortie réseau ouverte). Ce qu'il ne garantit pas : aucune limite CPU/mémoire/PID (point 4), `MINOS_DATA_DIR`
entièrement inscriptible, sortie réseau non filtrée. Mais comme aucun code de projet n'y tourne, ces absences ne sont pas
exploitables par un projet non fiable aujourd'hui.

#### Écart documentaire constaté

`docs/developer/remote-worker-sandbox-disposition.md` (tableau « Trois responsabilités ») décrit le plan admin comme
« exécute les providers gérés (scip-java, scip-typescript, …) sur du code local » ; `docs/user/docker-runtime.md` (l. 12, 27, 230)
présente `index` dans le plan admin comme fonctionnel et parle d'un egress « dépendances projet ». C'est faux dans le code actuel :
`index` y est refusé. Le plan admin sert aujourd'hui à `doctor`, `tools`, `project add`, `index-status`, `semantic`/`hybrid` et
aux bootstraps ; l'indexation par provider se fait sur l'hôte natif (sandbox AppContainer/bubblewrap) et le plan Docker sert
l'index déjà construit.

#### Décisions (écrites avant le code)

- **D5.1 — pas de correctif de code d'exécution ni de test rouge** : rien n'est accepté sans bac à sable.
- **D5.2 — verrouiller par test la garde qui l'empêche** : la seule ligne qui interdit au plan Docker de lancer un provider une
  fois `READY` contourné est `executeLocallyIsolated` ; aucun test ne l'exerce hors hôte qualifié. Ajouter une couture
  package-private (sélecteur de sandbox) sur `StrongProcessOwnershipIndexerExecutor`, et des tests : hôte sans sandbox géré →
  refus avant copie du projet et avant création du répertoire de run ; sandbox géré sans preuve de refus réseau + `DENY` → refus.
  Ces tests sont **verts dès leur naissance** (la garde existe) ; leur efficacité est prouvée par mutation (garde supprimée →
  test rouge), sortie jointe au commit. Pas de nouvelle entrée de liste blanche : le code ne crée aucun fichier.
- **D5.3 — corriger les documents** (`remote-worker-sandbox-disposition.md`, `docker-runtime.md`) pour dire ce que fait le plan
  admin ; réaligner les gates de documentation qui verrouillent ce texte (sans les retirer).
- **D5.4 — hors périmètre, noté** : API latente à 2 arguments / `ProcessIndexerExecutor.execute` public (ligne 6).

### Point 2 — Dependabot `docker`

**Ce que la source officielle dit** (code de `dependabot/dependabot-core`, lu le 2026-10-02 ; `docs.github.com` confirme les deux écosystèmes `docker` et `docker-compose`) :

| Question | Réponse vérifiée |
|---|---|
| `docker` met-il à jour une image épinglée par digest dans un `Dockerfile` ? | **Oui, à condition qu'elle ait un tag.** `FileFetcher` prend les fichiers dont le nom contient `dockerfile`/`containerfile` (donc `Dockerfile.mcp` et `Dockerfile.mcp.release` : un répertoire à la fois, non récursif). `FROM_LINE` lit `image:tag@sha256:<64 hex>`. Pour `image@sha256:…` **sans tag**, la « version » est le digest seul et l'`UpdateChecker` le propose vers le digest de **`latest`** (« Pure digest pin (no tag): the proposed digest resolves from `latest` ») : pour `eclipse-temurin` ce serait un autre JDK/JRE. **Constat de verif-s23 (2) confirmé et aggravé** : l'ancienne forme `FROM eclipse-temurin@sha256:…` aurait été déplacée vers `latest`. |
| `docker` lit-il les `compose*.yaml` ? | **Non.** Les YAML qu'il prend ne sont retenus que s'ils portent `apiVersion` et `kind` (manifestes Kubernetes). |
| `docker-compose` lit-il un `image:` épinglé par digest ? | Oui (`tag@sha256` ou `digest` seul, fixtures `digest_and_tag`, `literal_digest`…). |
| … et une valeur `${VAR:-image:tag}` ? | **Oui, contrairement à l'hypothèse de départ** : `service_image` extrait la valeur par défaut d'un `${VAR:-défaut}` (regex `ENV_VAR`), et le `FileUpdater` réécrit ce défaut (`(?:\$\{[^\}:]+:-)?`). `${MINOS_IMAGE}` sans défaut est ignoré. |
| … mais voit-il nos deux fichiers compose ? | **Non.** `FILENAME_REGEX = /(docker-)?compose(-[\w]+)?(?>\.[\w-]+)?\.ya?ml/i` n'accepte qu'**un** segment pointé : `compose.mcp.prod.yaml` et `compose.mcp.connected.yaml` (deux segments) ne correspondent pas (vérifié en exécutant la regex : `compose.yaml`, `compose.prod.yaml`, `docker-compose.mcp.yaml`, `compose-mcp.prod.yaml` passent ; les deux nôtres non). Déclarer `docker-compose` ferait échouer chaque exécution (« no docker-compose file »). |

**Décisions.**
- **D2.1** — écosystème `docker` sur `/docker` (hebdo lundi 05:45 Europe/Paris, `target-branch: develop`, `open-pull-requests-limit: 10`, groupe `docker-base-images`), qui couvre les 5 `FROM` des deux Dockerfile.
- **D2.2** — tous les `FROM` passent de `image@sha256:…` à `image:tag@sha256:…` **sans changer un seul digest** (le build produit exactement les mêmes images) : tags `eclipse-temurin:24.0.2_12-jre`, `eclipse-temurin:24.0.2_12-jdk`, `rust:1.97.1-bookworm`, `golang:1.26.5-bookworm`, `mcr.microsoft.com/dotnet/sdk:10.0.302-noble`, tirés des commentaires d'origine. Vérifié au registre : `24.0.2_12-jdk` et `10.0.302-noble` pointent encore sur le digest épinglé ; `24.0.2_12-jre`, `1.97.1-bookworm`, `1.26.5-bookworm` ont été **reconstruits** depuis (digests d'index actuels `8cb2387a…`, `0e2bcaef…`, `53eeac89…`), alors que les digests épinglés (`b416d023…`, `14bc9c59…`, `6c5605ab…`) existent toujours et portent bien JDK 24.0.2+12, Rust 1.97.1 et Go 1.26.5 (config d'image lue). Dependabot proposera donc d'emblée une PR de rafraîchissement de digest : voulue, à relire.
- **D2.3** — `ignore` du saut de **majeure** pour `eclipse-temurin` : le JRE/JDK doit rester celui de `maven.compiler.release` (24) ; une majeure est un changement délibéré, pas une PR automatique.
- **D2.4 (dette nommée)** — les images `pgvector` et `ollama`, dans `compose.mcp.connected.yaml`, restent hors de portée de Dependabot tant que ces fichiers gardent leur nom. **Décision du propriétaire demandée** : renommer en `compose-mcp.prod.yaml` / `compose-mcp.connected.yaml` (le motif accepte `compose-mcp.prod.yaml`) rendrait le défaut `${VAR:-image:tag@digest}` lisible et mettable à jour, mais touche l'installateur, les scripts de mise à jour et 12 scripts/tests (38 références) : hors périmètre S11, risqué pour les installations existantes. Alternative écartée : un `Dockerfile` miroir des deux images (une seconde copie, contraire à « une seule source »). Les digests se mettent à jour à la main, procédure dans `docs/developer/quality-gates.md`.
- Gates réalignés **sans les retirer** et durcis (exigent tag **et** digest) : `docker/scripts/verify-run-configurations.ps1`, `M29DockerAdministrationContractTest`, `scripts/remediation/check-audit-remediation-v2.py`.

### Point 3 — pgvector et ollama épinglés par digest

**Relocalisation.** Les deux tags se trouvaient à **trois** endroits (grep `pgvector/|ollama/ollama|OLLAMA_IMAGE|POSTGRES_IMAGE`), pas deux : (1) défauts `${MINOS_POSTGRES_IMAGE:-…}` / `${MINOS_OLLAMA_IMAGE:-…}` de `docker/compose.mcp.connected.yaml` ; (2) `docker/scripts/configure-m30-docker-services.ps1:24-25`, valeurs par défaut des paramètres injectées dans `.env` (constat de verif-s23 (1) confirmé : épingler le seul compose aurait laissé le script réécrire le tag) ; (3) `minos-storage-postgresql/src/test/java/…/PostgresTestSupport.java:38` (Testcontainers, `"pgvector/pgvector:0.8.2-pg17"`). Aucune occurrence dans les docs utilisateur, les workflows ni `scripts/ci`.

**Digests résolus au registre** (`docker buildx imagetools inspect`, le 2026-10-02), digests d'**index multi-arch** (amd64 + arm64) :

| Image | Digest d'index | Type |
|---|---|---|
| `pgvector/pgvector:0.8.2-pg17` | `sha256:feb68f4f15446397d8cac7f4fe48fe4586de83160d1fc48b46283312d1a33966` | OCI image index |
| `ollama/ollama:0.32.0` | `sha256:57f573b47f1f71ebb445789f279fe3e596a8beab182f7cf486db9205bad87c5a` | manifest list Docker v2 |

Ce sont aussi les identifiants des images déjà présentes localement (donc celles que l'utilisateur fait tourner). La référence `tag@digest` complète est re-résolue par `imagetools inspect` (même digest, 2 plateformes).

**Décisions.**
- **D3.1 — une seule source par image : le défaut du compose connecté**, au format `image:tag@sha256:…`. `docker compose … --profile postgresql --profile ollama config` rend bien les deux références complètes.
- **D3.2 — le script ne duplique plus** : `-PostgresImage` / `-OllamaImage` valent `''` par défaut ; vides, le script **retire** `MINOS_POSTGRES_IMAGE` / `MINOS_OLLAMA_IMAGE` du `.env` (une valeur laissée par un ancien configure, un tag nu, ne doit pas masquer le digest) ; explicites, il les écrit. Aucun appelant dans le dépôt ne les passe. **Effet sur l'existant** : un `.env` écrit par une version antérieure garde son tag nu jusqu'au prochain `configure-m30-docker-services.ps1` ; le digest s'applique à partir de là.
- **D3.3 — `PostgresTestSupport` lit l'image dans le compose** (`pinnedPostgresImage()` : remonte jusqu'à `docker/compose.mcp.connected.yaml`, regex sur le défaut de `MINOS_POSTGRES_IMAGE`) : les tests d'intégration tournent sur l'image exacte des utilisateurs. Hors du `try` de démarrage : une épingle absente ou illisible est un défaut du dépôt, jamais un skip. Prouvé : `PostgresSchemaMigratorTest` 6/6 avec `-Dminos.postgresql.tests.required=true` (conteneur réel `pgvector/pgvector:0.8.2-pg17@sha256:feb68f4f…`, 0 test sauté).
- **D3.4 — gate `scripts/quality/check-image-pins.py` + auto-test (15 cas)**, branché dans `pr-ci.yml` à côté de `check-workflow-pins.py` (et dans `run-final.ps1`/`.sh`), documenté dans `docs/developer/quality-gates.md`. Il exige `image:tag@sha256:<64 hex>` pour tout `FROM` de `docker/Dockerfile*` et tout `image:` (ou défaut `${VAR:-…}`) de `docker/compose*.yaml` (seule `${MINOS_IMAGE}` est exemptée), et interdit toute seconde copie `pgvector/pgvector:<tag>` / `ollama/ollama:<tag>` hors du compose. Un seul point d'entrée CI, pour ne pas ajouter de passages (C2 consolidera).

**Preuve rouge → vert du gate** : exécuté sur l'arbre `origin/develop` (`git archive origin/develop docker scripts .github`) il échoue sur **7 références** (`Dockerfile.mcp:2`, `Dockerfile.mcp.release:2,4,6,9`, `compose.mcp.connected.yaml:243,276`, ex. « FROM eclipse-temurin@sha256:b416… is not <image>:<tag>@sha256:<64 hex> ») ; sur la branche : `CONTAINER IMAGE PIN GATE SUCCESS (files checked=4)`. L'auto-test contient un témoin par échappatoire (digest sans tag, tag sans digest, digest court, `--platform`, indirection `ARG`, défaut compose sans digest, `image:` littéral, variable autre que `MINOS_IMAGE`, copie dans un `.ps1`).

### Point 4 — plafonds CPU, mémoire, PID

**Relocalisation et mécanisme.** Aucun `mem_limit`, `pids_limit` ni `cpus` dans les deux fichiers (huit services au total : 6 communs + `minos-postgres` et `minos-ollama` dans le connecté). `docker compose` hors Swarm honore les clés de service `cpus`, `mem_limit`, `memswap_limit`, `pids_limit` : vérifié par `docker compose … config --format json` (valeurs interpolées en octets) **et** par exécution réelle (`docker compose run -d`, puis `docker inspect` : `Memory`, `MemorySwap`, `PidsLimit`, `NanoCpus`, et dans le conteneur `memory.max`, `memory.swap.max=0`, `pids.max`, `cpu.max=400000 100000`). `deploy.resources` n'est pas utilisé (seconde définition possible, refusée par le gate).

**Mesure** (cgroup v2 `memory.peak` / `pids.peak` / `cpu.stat` lus dans le conteneur ; image locale 1.2.0 + JAR de la branche ; outils `/tmp/s11*` du scratchpad : `s11measure.py`, `s11mcp.py`). Les charges :

| Charge | Sans plafond | Avec plafond (chaque ligne = un run réel) |
|---|---|---|
| `doctor` | 211 Mio, 42-44 PID | 2 Gio / 256 PID / 4 CPU : 97 Mio, 34 PID |
| `tools verify --all` | 211 Mio, 43 PID | idem : 96 Mio, 34 PID ; plancher 64 Mio, 32 PID réussit |
| `project add` (ce dépôt : 9 langages, 45 modules, 53 s sur bind Windows) | 233 Mio, 39 PID | idem : 100 Mio, 31 PID |
| `index --dry-run` (planification) | 262 Mio, 42 PID | idem : 118 Mio, 34 PID ; **planchers** : 64 Mio réussit ; 30 PID réussit (29 refus de thread), 24 PID échoue (`OutOfMemoryError: unable to create native thread`) |
| sonde providers (commande exacte du compose, `network none`) | 344 Mio, 25 PID | 128 Mio / 32 PID : 52 Mio, 19 PID |
| 9 clients MCP simultanés (stdio, `initialize`, `tools/list`, `minos_index_status`, `minos_search_code`) | **961 Mio, 292 PID** | 3 Gio / 1024 PID / 4 CPU : 778 Mio, 219 PID, 9/9 réussis ; **planchers** : 512 Mio → un client tué par l'OOM, 1 Gio / 150 PID → 9/9 |
| PostgreSQL géré (30 000 vecteurs 768 dim, 8 recherches exactes simultanées) | — | 1 Gio / 128 PID / 2 CPU : 448 Mio, 29 PID, aucun OOM |
| Ollama géré (pull, chargement, embedding) | — | 2 Gio / 256 PID / 4 CPU : 316 à 593 Mio, 38-39 PID |
| Les quatre amorçages + la sonde via `docker compose run` | — | sous les plafonds par défaut : rc 0 (dont `minos-tools-bootstrap`, copie des outils) |

Calage sur la production : le conteneur `minos-mcp-prod` de l'utilisateur (37 h d'activité réelle ; lecture seule de `/sys/fs/cgroup/memory.peak`, `pids.peak` par `docker exec cat`, rien modifié) montrait 1 009 Mio et 317 PID, avec 7 JVM MCP simultanées de ~117 Mio et 34 threads : mon pilote de 9 clients (961 Mio, 292 PID) reproduit ce pic à 5 % près. `minos-ollama-prod` : 416 Mio, 26 PID ; `minos-postgres-prod` : 120 Mio, 21 PID.

**Constat honnête, la consigne de départ ne tient pas telle quelle.** « Lancer une indexation réelle dans le plan admin » est impossible : le plan admin ne lance aucun provider (point 5). La planification d'indexation sur le plus gros projet disponible (ce dépôt) et les commandes que le plan exécute réellement la remplacent. Une indexation sémantique par `minos-admin` (le plus gros consommateur futur possible) n'a pas été mesurée : aucun instantané d'index n'est construisible dans le conteneur ; la marge de 2 Gio sur 344 Mio en tient lieu.

**Décisions (marges écrites).**
- **D4.1 — plafonds** : requêtes 3 Gio / 1024 PID / 4 CPU (×3,1 sur 961 Mio, ×3,5 sur 292 PID) ; administration 2 Gio / 256 PID / 4 CPU (×6 sur 344 Mio, ×5,8 sur 44 PID ; le tas JVM vaut 25 % du plafond, soit 512 Mio, et le plancher mesuré est < 64 Mio) ; tâches ponctuelles 512 Mio / 128 PID / 2 CPU (×9 sur la sonde, plancher 128 Mio / 32 PID) ; PostgreSQL 1 Gio / 128 PID / 2 CPU (×2,3 sur le pic de contrainte, ×8 sur la production) ; Ollama 2 Gio / 256 PID / 4 CPU (×3,4 sur 593 Mio, ×6,5 sur les PID). La marge de ×3 ou plus est choisie parce que la mémoire de pic inclut le cache de pages et que chaque JVM dimensionne son tas sur le plafond.
- **D4.2 — une définition par rôle, par fichier** : blocs `x-limits-<rôle>` (ancre YAML `&limits-<rôle>`, fusionnée par `<<: *limits-<rôle>`), valeurs `"${MINOS_<RÔLE>_<TYPE>:-défaut}"` : le défaut est documenté (`docs/user/docker-runtime.md`, « Limites de ressources ») et un utilisateur peut le surcharger dans son `.env` (plus de clients MCP, projets plus gros). **Écart assumé avec la consigne** « les valeurs ne se recopient pas entre fichiers » : les deux fichiers compose se déploient séparément (le connecté remplace le prod comme compose actif, et duplique déjà ses services communs) ; une définition inter-fichiers exigerait un troisième fichier compose (`extends`/`include`) à livrer par l'installateur, ce qui touche ~12 scripts de packaging. À la place, **un gate** `scripts/quality/check-compose-limits.py` (+ auto-test, 13 cas) exige que chaque service tire son plafond d'un bloc, que les deux fichiers portent des blocs identiques et le même rôle par service commun, que `memswap_limit` égale `mem_limit`, qu'aucun service n'écrive de plafond ni de `deploy`, et que chaque variable et son défaut figurent dans la documentation. À valider par le propriétaire (variante : fichier de limites partagé).
- **D4.3** — gate branché dans `pr-ci.yml` (à côté de `check-image-pins.py`), `run-final.*`, `quality-gates.md`.

**Preuve du gate** : sur l'arbre `origin/develop` il échoue (14 services sans plafond) ; sur la branche : `COMPOSE RESOURCE LIMITS GATE SUCCESS (files checked=2)`.

### Point 1 — Ollama en non-root

**Relocalisation.** `docker/compose.mcp.connected.yaml`, service `minos-ollama` : image tournant en `root` (aucun `user:`), volume externe `minos-ollama-models` monté sur `/root/.ollama`. Aucun autre appelant du chemin `/root/.ollama` (grep). Les scripts qui interagissent avec le service (`configure-m30-docker-services.ps1` : `up --wait`, `docker exec … ollama pull`, `ollama list`) fonctionnent sous n'importe quel utilisateur et n'ont pas besoin de changer. Modèle par défaut de MINOS : `nomic-embed-text` (768 dimensions, 274 Mo), repris de la valeur par défaut `SemanticModel` du configurateur.

**Décisions.**
- **D1.1 — uid `10002:10002`** (distinct de l'uid `10001` de MINOS : un Ollama compromis ne partage aucun uid avec l'état MINOS ; haut numéro, sans entrée dans `/etc/passwd` de l'image, ce qui suffit à Go). `cap_drop: [ALL]`, `no-new-privileges`, racine en lecture seule conservés.
- **D1.2 — `HOME=/home/ollama`, volume monté sur `/home/ollama/.ollama`** : c'est exactement la disposition de l'ancien `/root/.ollama` (clé `id_ed25519`, `models/`, `cache/` à la racine du volume) ; les modèles déjà téléchargés sont retrouvés sans `OLLAMA_MODELS` ni copie. Seul `.ollama` est inscriptible, plus `/tmp` (tmpfs).
- **D1.3 — migration d'un volume créé en root** : service ponctuel `minos-ollama-bootstrap` (profil `ollama`, **image MINOS** déjà présente, donc pas de seconde copie de l'image Ollama dans le compose ; `user: 0:0`, `cap_add: [CHOWN, FOWNER]` seulement, `network_mode: none`, plafonds « tâches ») qui lance `chown -R 10002:10002` **seulement si** `find -xdev ! -user 10002 -o ! -group 10002` trouve quelque chose ; `minos-ollama` en dépend (`service_completed_successfully`). Idempotent, sans option ni script à lancer : le prochain `up` migre. Précédent suivi : `minos-data-bootstrap` (même schéma, mêmes capacités).

**Preuves** (`ollama/ollama:0.32.0@sha256:57f573b4…` réel, via `docker compose` et les plafonds du point 4) :

| Scénario | Résultat |
|---|---|
| Rouge : conteneur non-root sur un volume créé en root, sans amorçage | le conteneur s'arrête : `Error: remove /home/ollama/.ollama/models/manifests: permission denied` |
| Volume préexistant créé en root par l'**ancienne** configuration (`ollama pull nomic-embed-text` fait en root, `/root/.ollama`) | l'amorçage log « ownership handed to uid 10002 » ; `id` = `uid=10002` ; `ollama list` retrouve `nomic-embed-text:latest` (274 Mo) sans nouveau téléchargement ; requête `/api/embed` : HTTP 200, vecteur de 768 valeurs ; le serveur charge le modèle (`llama-server`) |
| Volume neuf | amorçage sur volume vide, `up --wait` sain ; réseau de provisionnement temporaire comme le script (`network create/connect`) ; `docker exec ollama pull nomic-embed-text` **en non-root** réussit ; `ollama list` ; embedding de 768 valeurs ; pic 593 Mio, 38 PID sous 2 Gio / 256 PID |
| Second démarrage (`--force-recreate`) | « ownership already matches », embedding toujours 200 (768 valeurs) |
| Conteneur | `user=10002:10002 ro=true capdrop=[ALL] no-new-privileges mem=2 Gio pids=256 cpus=4` |

Non prouvé : GPU (aucun GPU ici ; la configuration n'en monte pas) ; volume nommé sous SELinux (`:Z`) ; l'installateur Windows complet (`configure-m30-docker-services.ps1` de bout en bout : il demande une installation réelle, voir « à traiter plus tard »). Le retour arrière vers une version antérieure demande de remettre le volume à root (commande dans `docker-runtime.md`) : sans `DAC_OVERRIDE`, root ne lit plus la clé 0600 de l'uid 10002.

### Résultats de fin de lot (le 2026-10-02, Windows, JDK 24)

| Contrôle | Résultat |
|---|---|
| `./mvnw clean verify` (complet) | **BUILD SUCCESS**, 15 min 01 s, 0 échec. Un premier passage a échoué sur `HybridCorpusWeightTest.estimateBoundsTheRetainedHeapForEveryAlphabetAndLineLength` (« estimate below retained heap: latin1-long estimate=12061681 retained=12138968 ratio=0.99 ») : test de mesure de tas, au ras de la borne (0,99), étranger au lot (aucun fichier de `minos-bootstrap` touché) ; il passe seul (1/1) et au second `clean verify` complet. Noté en S11-L10. |
| `check-jacoco.py` | 25 jalons PASS, **m24-polyglot-provider-platform FAIL** (`ManagedPolyglotScipRuntimeManager` line 0,227 < 0,280, branch 0,146 < 0,200) : rouge préexistant sous Windows (classe non touchée par le lot) |
| `check-module-boundaries.py` | SUCCESS (14 modules, 511 sources, 45 packages), identique à la baseline |
| `check-private-io.py` | SUCCESS (511 sources, **37** occurrences en liste blanche, 8 interdits, 4 primitives) : identique, aucune entrée ajoutée ; `test_check_private_io.py` OK |
| `check-current-docs.py` / `product-facts.py --check` | SUCCESS / SUCCESS |
| `check-milestone-artifact-references.py` | SUCCESS (102 scripts, 98 à la baseline + les 4 nouveaux scripts de gate) |
| `check-workflow-pins.py` | SUCCESS (70 usages externes, identique) |
| `check-image-pins.py` (nouveau) / auto-test 15 cas | SUCCESS / OK |
| `check-compose-limits.py` (nouveau) / auto-test 13 cas | SUCCESS / OK |
| `check-audit-remediation-v2.py` | SUCCESS |
| 12 golden de `characterization/` | inchangés (`git diff --name-only origin/develop -- minos-app/src/test/resources/characterization` : 0 fichier) |
| `docker buildx build --check` des deux Dockerfile | « no warnings found » |
| Aucun push, aucune PR, aucun workflow lancé | respecté |

**Prod de l'utilisateur** : `minos-mcp-prod`, `…-ollama-1`, `…-postgres-1` jamais redémarrés ni modifiés (lecture des compteurs cgroup par `docker exec cat`). Ressources Docker de la mesure (`minos-s11-*`, `s11lim*`, conteneurs de test) supprimées.

### Journal du lot 1 (un commit = une entrée)

| # | Commit | Contenu | Preuve |
|---|---|---|---|
| 1 | `fix(s11): le plan admin ne lance aucun provider, garde verrouillee par test (point 5)` | couture package-private `StrongProcessOwnershipIndexerExecutor(…, Function<Path, WorkerSandboxBackend>)` ; 3 tests (`nativeOnlyHostRefusesManagedProviderBeforeCopyingTheProjectOrCreatingARun`, `managedSandboxWithoutNetworkDenyProofRefusesDenyBeforeCopyingTheProject`, témoin positif `qualifiedManagedSandboxIsReachedOnlyThroughTheSelectedBackend`) ; docs `remote-worker-sandbox-disposition.md` et `docker-runtime.md` alignées sur le code | **mutation** : les deux gardes d'`executeLocallyIsolated` neutralisées (`if (false)`) → 2 tests sur 7 rouges (`managedSandbox…`: « fixture sandbox reached ==> expected true but was false » ; `nativeOnly…` : « native worker cannot prove OS-level network denial » car le provider natif est alors atteint) ; gardes restaurées → 7/7 verts. Pas de test rouge « avant correctif » : il n'y a pas de défaut de sécurité (issue iii). |
| 2 | `build(s11): Dependabot docker et references image:tag@digest dans les Dockerfile (point 2)` | `.github/dependabot.yml` (écosystème `docker`, `/docker`) ; 5 `FROM` réécrits `image:tag@sha256` à digest identique ; gates `verify-run-configurations.ps1`, `M29DockerAdministrationContractTest`, `check-audit-remediation-v2.py` réalignés et durcis | lecture de la source Dependabot (cf. tableau point 2) ; digests inchangés (`git diff` : seules les parties `:tag` s'ajoutent) ; `M29Docker*ContractTest` 5/5 verts, `check-audit-remediation-v2.py` SUCCESS |
| 3 | `build(s11): pgvector et ollama epingles par digest, source unique, gate check-image-pins (point 3)` | défauts du compose connecté en `tag@sha256` ; `configure-m30-docker-services.ps1` ne les réécrit plus ; `PostgresTestSupport` les lit dans le compose ; gate + auto-test branchés dans `pr-ci.yml`, `run-final.*`, `quality-gates.md` | rouge sur `origin/develop` (7 violations), vert sur la branche ; 15 tests d'auto-test ; `docker compose config` ; `PostgresSchemaMigratorTest` 6/6 sur le conteneur épinglé |
| 4 | `build(s11): plafonds CPU, memoire et PID sur tous les services compose (point 4)` | blocs `x-limits-<role>` dans `compose.mcp.prod.yaml` et `compose.mcp.connected.yaml` ; gate `check-compose-limits.py` + auto-test branchés (`pr-ci.yml`, `run-final.*`, `quality-gates.md`) ; section « Limites de ressources » de `docker-runtime.md` | mesures et planchers ci-dessus ; plafonds vérifiés par `docker inspect` et cgroup ; gate rouge sur `origin/develop` (14 services), vert ici ; `M29*ContractTest` 12/12 |
| 5 | `fix(s11): Ollama s'execute en non-root, migration du volume de modeles (point 1)` | `minos-ollama` sous `10002:10002`, `HOME=/home/ollama`, volume sur `/home/ollama/.ollama` ; service ponctuel `minos-ollama-bootstrap` (chown conditionnel) ; section « Ollama sans privilèges » de `docker-runtime.md` | rouge (permission denied sans amorçage), puis vert : volume neuf, volume créé en root par l'ancienne config, second démarrage ; modèle téléchargé **et** chargé, embedding 768 valeurs |

Commits du lot, dans l'ordre : `ffa09905` (1, point 5), `53da76e7` (2, point 2), `bc8ddfdb` (3, point 3), `56937d49` (4, point 4), `ac8aeda7` (5, point 1), puis les commits de suivi (`f59bae27` et le dernier).

## Constats de `verif-s23`

| Id | Lot | Fichier:ligne | Constat | Sévérité | Résolution |
|---|---|---|---|---|---|
| V1 | 1 | `docker/compose.mcp.connected.yaml` (défauts `${MINOS_*_IMAGE:-…}`) et `docker/scripts/configure-m30-docker-services.ps1:24-25` | les tags pgvector et ollama sont écrits à deux endroits ; épingler un seul laisserait le script écraser le digest par le tag | bloquant | **traité (commit 3)** : constat confirmé (et un troisième endroit : `PostgresTestSupport`) ; une seule source, le défaut du compose ; le script ne réécrit plus (D3.2), les tests lisent le compose (D3.3), le gate refuse toute copie |
| V2 | 1 | `docker/Dockerfile.mcp`, `docker/Dockerfile.mcp.release` | `FROM image@sha256:…` sans tag : Dependabot ne suit probablement pas ; il faut `image:tag@sha256` ; les défauts `${VAR:-image}` du compose probablement non lus | bloquant | **traité (commit 2)** : confirmé dans la source de Dependabot (une référence sans tag est déplacée vers `latest`) ; les 5 `FROM` passent en `image:tag@sha256` à digest identique. **Infirmé en partie** : l'écosystème `docker-compose` lit bien le défaut d'un `${VAR:-défaut}` ; c'est le **nom** des fichiers (`compose.mcp.*.yaml`, deux segments pointés) qui le met hors de portée : dette nommée D2.4 |
| V3 | 1 | point 5 | vérifier si `bwrap` peut s'exécuter dans le conteneur `cap_drop: [ALL]` / `read_only` | information | **traité (commit 1)** : non, `unshare --user` refusé (seccomp par défaut), cgroup en lecture seule, `bwrap` absent de l'image ; le plan admin ne peut acquérir aucune sandbox |
| V4 | 1 | goldens | les 12 golden sont dans `minos-app/src/test/resources/characterization/`, pas à la racine | information | pris en compte : `git diff --stat origin/develop -- minos-app/src/test/resources/characterization` vide, relevé en fin de lot |
| V5 | 1 | baseline | module-boundaries 14/511/45 ; private-io 511/37/8/4 (30 tests) ; workflow-pins 70 | information | recoupé : identique à la baseline relevée ci-dessus |

## À traiter plus tard

- **S11-L1 (latent, point 5)** — `StrongProcessOwnershipIndexerExecutor(ProcessIndexerExecutor, Path)` (ownership seul), `ProcessIndexerExecutor.execute` et `NativeEphemeralWorkspaceBackend.execute(ALLOW)` sont publics et n'isolent rien ; aucun appelant en production aujourd'hui. À rendre non publics ou à retirer quand `minos-runtime-local` sera ré-ouvert (A2/A3).
- **S11-L2 (point 5)** — le diagnostic `UNSUPPORTED_BY_BACKEND` dit « this is not a failure and does not block installation or use » alors que `index` est refusé dans le plan Docker : libellé à corriger (`StrongOwnedProcessExecutors.qualifySandbox`, `StrongOwnedProcessExecutorsQualificationTest`, `docs/user/cli.md:96`). Non touché : texte de diagnostic verrouillé par plusieurs tests, hors périmètre.
- **S11-L3 (point 5)** — la composition `minos-admin` configure encore `HOME`, `COURSIER_CACHE`, `MAVEN_OPTS`, `DOTNET_CLI_HOME`, `NUGET_PACKAGES` et un tmpfs `exec` pour des providers qui n'y tournent pas : à réduire si le propriétaire confirme que l'indexation Docker n'est pas prévue.
- **S11-L4 (point 2, décision du propriétaire)** — renommer `compose.mcp.prod.yaml` / `compose.mcp.connected.yaml` en `compose-mcp.prod.yaml` / `compose-mcp.connected.yaml` (motif Dependabot `docker-compose`) pour que les digests pgvector et ollama soient proposés automatiquement. Touche l'installateur, les scripts de mise à jour et 38 références.
- **S11-L5 (point 3)** — les installations existantes gardent le tag nu écrit dans leur `.env` par une version antérieure (`MINOS_POSTGRES_IMAGE`, `MINOS_OLLAMA_IMAGE`) jusqu'au prochain `configure-m30-docker-services.ps1`, qui le retire. Un contrôle au démarrage (`doctor`) pourrait le signaler.
- **S11-L6 (points 1, 3, 4)** — `configure-m30-docker-services.ps1` et l'installateur n'ont pas été exécutés de bout en bout (installation réelle, Windows) : seuls les services compose, les fichiers `.ps1` (analyse syntaxique) et les tests de contrat l'ont été. Qualification attendue sur la chaîne CI Windows/Docker du propriétaire.
- **S11-L7 (point 4)** — `docker/scripts/verify-run-configurations.ps1` échoue dès sa première assertion (« Le conteneur PROD doit rester disponible entre deux sessions STDIO »), y compris sur `origin/develop` : script périmé, non branché ; son assertion Dockerfile a été réalignée (vérifiée à part).
- **S11-L8 (point 4)** — indexation sémantique par `minos-admin` non mesurée (aucun instantané d'index dans le conteneur) ; Postgres et Ollama : plancher non cherché ; plafond CPU non mesuré en pic. À réviser si les plafonds par défaut gênent un usage réel (surcharge par `.env`).
- **S11-L9 (point 3)** — la première exécution de Dependabot proposera des PR de rafraîchissement de digest (tags `24.0.2_12-jre`, `1.97.1-bookworm`, `1.26.5-bookworm` reconstruits depuis l'épinglage) : à relire, ce sont des changements de la chaîne de build de l'image release.
- **S11-L10 (fin de lot)** — `HybridCorpusWeightTest` (`minos-bootstrap`) est instable au ras de sa borne (ratio 0,99 vu une fois sous la charge d'un `clean verify`) : à reprendre côté qualité (marge de l'estimation du tas).

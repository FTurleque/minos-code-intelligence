# Suivi du chantier « Sprints 2–3, derniers constats » (S11, D1, C2, S14)

Chantier ouvert le 2026-10-02 sur `docs/audit/AUDIT-2026-09.md`. Quatre constats, les seuls de l'échéance sprints 2–3
encore ouverts. Après ce chantier il ne reste que des constats d'échéance **Trimestre**.

| Lot | Branche | Constats | PR | État |
|---|---|---|---|---|
| 1 | `sec/s11-chaine-et-conteneurs` | S11 | — | en cours |
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

### Journal du lot 1 (un commit = une entrée)

| # | Commit | Contenu | Preuve |
|---|---|---|---|
| 1 | `fix(s11): le plan admin ne lance aucun provider, garde verrouillee par test (point 5)` | couture package-private `StrongProcessOwnershipIndexerExecutor(…, Function<Path, WorkerSandboxBackend>)` ; 3 tests (`nativeOnlyHostRefusesManagedProviderBeforeCopyingTheProjectOrCreatingARun`, `managedSandboxWithoutNetworkDenyProofRefusesDenyBeforeCopyingTheProject`, témoin positif `qualifiedManagedSandboxIsReachedOnlyThroughTheSelectedBackend`) ; docs `remote-worker-sandbox-disposition.md` et `docker-runtime.md` alignées sur le code | **mutation** : les deux gardes d'`executeLocallyIsolated` neutralisées (`if (false)`) → 2 tests sur 7 rouges (`managedSandbox…`: « fixture sandbox reached ==> expected true but was false » ; `nativeOnly…` : « native worker cannot prove OS-level network denial » car le provider natif est alors atteint) ; gardes restaurées → 7/7 verts. Pas de test rouge « avant correctif » : il n'y a pas de défaut de sécurité (issue iii). |

## Constats de `verif-s23`

| Id | Lot | Fichier:ligne | Constat | Sévérité | Résolution |
|---|---|---|---|---|---|
| — | — | — | aucun à ce stade | — | — |

## À traiter plus tard

(rien pour l'instant)

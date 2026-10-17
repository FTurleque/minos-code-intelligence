<!-- Annexe A de l'audit 2026-10. Rapport d'analyse brut, conservé tel que remis ; les identifiants A-NN y valent MINOS-AUD-ANN dans docs/audit/archive/2026-10-06/constats.md. Les chemins « scratchpad/ » cités désignent des reproductions jetables hors dépôt, non conservées. -->
# Audit A — Confinement du code non fiable et indexation distante

Dépôt : `N:\workspace-dev\minos-code-intelligence`, HEAD `develop` (bc1d3421), lecture seule.
Périmètre : `minos-runtime-local` (sandbox, cgroup, Job Object, copie éphémère, environnement, `CommandLocator`), `minos-engine` package `remote`, `minos-integration-git` (matérialisation distante), ADR 0033, 0036, 0038, 0041.
Méthode : lecture du code, traçage des chemins, recherche de tests existants (Grep). Rien n'a été exécuté (pas de Maven, pas de Windows/WSL). Toute affirmation sur un comportement noyau/OS non rejoué est marquée « hypothèse ».

Rappel de contexte : ADR 0041 ferme volontairement l'indexation distante de code non fiable (aucun backend n'est qualifié, `LocalRemoteIndexOperations.index` refuse avant toute matérialisation, `LocalIsolatedIndexWorker.execute` refuse en profondeur). Tout le code « worker/bundle » est donc **dormant** en production. Les constats qui touchent du code dormant sont étiquetés comme tels. Le chemin **vivant** est : (1) `remote materialize` (JGit), (2) l'exécution locale des providers SCIP via `StrongProcessOwnershipIndexerExecutor` → copie éphémère + backend `strongestAvailableForManagedLocalProvider` (bubblewrap/cgroup v2 ou AppContainer/Job Object).

---

## Constats

### A-01 — Windows : le lanceur AppContainer détruit les ACL et le profil d'un autre sandbox encore vivant

- **Qualification** : DÉFAUT CONFIRMÉ (chemin de code sans ambiguïté, aucun test) ; impact en scénario concurrent PLAUSIBLE.
- **Priorité proposée** : P1. C'est l'équivalent Windows de S3 (Linux : le balayeur de cgroup tuait des jobs vivants d'un autre processus MINOS, corrigé au sprint 1 par la marque de propriétaire). Ici aucun correctif équivalent n'existe.
- **Preuves** :
  - `minos-runtime-local/src/main/resources/com/minos/runtime/local/windows-appcontainer-sandbox-v4.ps1.template:498-513` : `Recover-Stale` parcourt **tous** les `*.json` du répertoire de reprise, retire l'ACE du SID (`icacls /remove:g`, `:486-489`) sur chaque chemin listé puis appelle `DeleteProfile` et supprime le journal.
  - Même fichier `:535-536` : appelé inconditionnellement au démarrage de **chaque** lanceur ; `:538-541` le journal du lanceur courant (`Minos.Worker.<guid>.json`) vit dans le même répertoire jusqu'au `finally` (`:577-584`).
  - Le journal ne porte aucune identité de propriétaire : `Write-Recovery` (`:491-496`) écrit seulement `profile`, `sid`, `paths`. Pas de PID, pas d'instant de démarrage, pas de verrou, pas de mutex (`grep -i "mutex|lock|owner|pid"` sur le template et les fragments : aucun résultat pertinent).
  - Le répertoire de reprise est **partagé par MINOS_HOME** : `WindowsAppContainerWorkerSandboxBackend.java:314-316` (`minosHome/sandbox/appcontainer-recovery`).
  - Le sonde de capacité lance elle aussi ce lanceur réel (`WindowsAppContainerWorkerSandboxBackend.java:357-401`, `:376-380`), et elle est déclenchée par `discover()` (`:92-122`, cache par JVM `:74`, `:103`), donc par toute commande qui qualifie le sandbox : `DoctorCommand.java:98-99`, et les statuts de providers (`StrongOwnedProcessExecutors.java:52-56` appelé par `ManagedScipProviderRuntimeManager.java:150`, `ManagedPolyglotScipRuntimeManager.java:81`, `ManagedScipPythonRuntimeManager.java:98`).
  - Aucun test : `grep -rn "Recover-Stale\|stale.*journal"` sous `minos-runtime-local/src/test` ne trouve rien de lié.
- **Comportement actuel** : un second processus MINOS (CLI `doctor` ou `tools status`, autre `index`, serveur MCP/IDE qui indexe) qui lance un lanceur supprime les droits de lecture/écriture du provider en cours de l'autre processus et supprime son profil AppContainer. Le provider vivant échoue en « accès refusé » ou son artefact devient illisible ; l'échec est sans rapport apparent avec la cause.
- **Comportement attendu + source** : ne récupérer qu'un sandbox dont le propriétaire est prouvé mort. Source : le correctif S3 (`AUDIT-2026-09.md` § S3, « ne récupérer que les cgroups dont le propriétaire est mort »), `CgroupJobOwnership` (marque de propriétaire) côté Linux, et la règle « jamais d'effet de bord sur un job vivant » de `ProcessTreeTermination` (Javadoc : « two concurrent jobs can never terminate one another »).
- **Cause** : la reprise sur incident (journal) a été conçue pour un lanceur unique ; la concurrence inter-processus n'a pas été traitée côté PowerShell.
- **Impact** : indexation Windows concurrente (CLI + MCP/IDE) non fiable ; diagnostic trompeur. Aucune fuite de confidentialité, mais perte de disponibilité et risque de résultat partiel.
- **Correction minimale proposée** : le lanceur tient, pendant toute la vie du sandbox, un verrou exclusif sur son fichier journal (`[IO.File]::Open(path,'Open','ReadWrite','None')` conservé jusqu'au `finally`, avec un fichier de verrou distinct du `.tmp`/`Move-Item` utilisé par `Write-Recovery`), et `Recover-Stale` ignore tout journal dont l'ouverture exclusive échoue. Alternative : inscrire PID + instant de démarrage dans le journal et appliquer la preuve de mort de `CgroupJobOwnership`. Mettre à jour le nom du template (`-v4`) et les gates qui affirment des chaînes littérales du script (`scripts/**/check-*.py` : grepper `windows-appcontainer-sandbox-v4` avant de renommer).
- **Validation (test)** : test Windows uniquement (`@EnabledOnOs(OS.WINDOWS)`) dans `minos-runtime-local/src/test/java/com/minos/runtime/local/WindowsAppContainerWorkerSandboxBackendTest.java` (ou une classe voisine) :
  1. `WindowsAppContainerWorkerSandboxBackend.discover(home)`, construire un plan A via `sandboxPlan` avec une commande qui dort 20 s puis écrit `ok.txt` dans son répertoire de travail ; démarrer le processus (comme `probeOsIsolation`).
  2. Attendre l'apparition de `home/sandbox/appcontainer-recovery/Minos.Worker.*.json` avec des chemins non vides.
  3. Lancer un second plan B (`exit 0`, autre répertoire de run) jusqu'à sa fin.
  4. Asserter : le journal A existe toujours, `icacls <writeRoot A>` liste encore le SID de A, et après 20 s `ok.txt` existe. Échoue aujourd'hui.
- **Dépendances** : lié à A-07 (la sonde déclenche le lanceur depuis des commandes de lecture). Gate à rejouer : scripts `check-*.py` affirmant des littéraux du template ; scope JaCoCo m24 (rouge connu sous Windows, comparer à develop).

---

### A-02 — Répertoires de travail et de transit jamais récupérés après un arrêt brutal

- **Qualification** : DÉFAUT CONFIRMÉ (absence de balayeur établie par recherche exhaustive) ; déclenché uniquement par une mort du processus (SIGKILL, plantage JVM, coupure) pendant l'exécution. Même famille que R8 mais sur d'autres racines, non répertorié.
- **Priorité proposée** : P2 (fuite disque, jusqu'à 2 Gio par run interrompu, sans plafond global ni plainte visible).
- **Preuves** :
  - `LocalProviderWorkspace.java:42-96` : copie du projet enregistré (jusqu'à `SourceBudgetPolicy.DEFAULT` = 100 000 fichiers / 2 Gio, `SourceBudgetPolicy.java:9-10`) sous `MINOS_HOME/local-provider-workspaces/<runId>/<provider>/workspace`. Seuls `close()` (`:106-119`) et l'échec de `create` (`:91-95`) la suppriment ; `create` ne supprime que le répertoire du **même** runId (`:64-66`).
  - `LocalIsolatedIndexWorker.java:154-167` et `:228-237` : même schéma sous `distributed-workers/` (dormant, ADR 0041) ; `:220` crée aussi `.bundle-*.zip`.
  - `DistributedArtifactBundleStore.java:175` (`.accept-*` sous `distributed-artifacts/`, supprimé seulement dans le `finally` `:234-236`) ; `evict` ignore les entrées commençant par `.` (`:481-482`).
  - `JGitRemoteRepositoryMaterializer.java:169-170` (`.entry-<uuid>.tmp`, clone potentiellement de plusieurs Gio, supprimé seulement dans le `finally` `:198-200`) ; `evict` ignore aussi les `.`-entrées (`:279-282`).
  - Recherche : `grep -rn "local-provider-workspaces\|distributed-workers\|\.entry-\|\.accept-\|\.bundle-"` : aucune autre occurrence en code de production ; `RunDirectoryRetention` ne parcourt que `runs/` (`ProcessIndexerExecutor.java:401`), `grep addShutdownHook` : aucun.
  - La reprise ADR 0039 réutilise le même runId : seul cas où l'orphelin est nettoyé (`LocalProviderWorkspace.java:64-66`).
- **Comportement actuel** : un run tué laisse sa copie complète du projet (et les clones temporaires distants) indéfiniment.
- **Comportement attendu + source** : ADR 0038 § « scratchReclamation » (`WorkerResourceContainment.scratchReclamation` exigé `enforcedDuringExecution` pour la qualification managed-local, `WorkerResourceContainment.java:115-118`) et `config.yaml` (cache « reconstructible et borné », ADR 0033 §3 : « cache reconstructible et borné ») : aucun résidu non borné ne doit survivre à un arrêt anormal.
- **Cause** : le nettoyage repose uniquement sur le `finally`/`close()` du processus qui meurt ; aucun balayage à l'ouverture.
- **Impact** : fuite disque cumulative sur poste développeur (MINOS_HOME), pas de borne ; copie du code source du projet (confidentialité locale inchangée car répertoire privé).
- **Correction minimale proposée** : au `create` de `LocalProviderWorkspace` (et au constructeur des magasins distants), supprimer les sous-répertoires de la racine dont l'âge dépasse une durée de vie unique (même constante que la rétention des runs) et qui ne sont pas sous bail, avec la primitive `ProviderWorkspaceFiles.deleteTree`. Idem pour les `.accept-*`, `.entry-*.tmp`, `.bundle-*` vieux de plus de N heures.
- **Validation (test)** : dans `minos-runtime-local/src/test/java/com/minos/runtime/local/LocalProviderWorkspaceTest.java` : créer `local-provider-workspaces/<uuid>/scip-java/workspace/f.txt`, positionner son mtime à −48 h, appeler `LocalProviderWorkspace.create(home, request)` pour un autre runId, asserter que l'ancien répertoire est supprimé et que celui d'un run récent (mtime proche) est conservé. Échoue aujourd'hui.
- **Dépendances** : R8 (même motif, autre racine) ; peut partager la constante de durée de vie du lot 1 ; test de gate pour les chaînes littérales `distributed-workers` (`scripts/quality/check-remote-distributed-consistency.py:115` cite `.bundle-`).

---

### A-03 — Linux : `prlimit --cpu` et `pids.max` plus serrés que le budget agrégé annoncé

- **Qualification** : RISQUE (plausible, à mesurer ; aucune preuve par exécution). Deux sous-risques indépendants.
- **Priorité proposée** : P2 pour `pids.max` (échec d'indexation sans cause claire), P3 pour `RLIMIT_CPU`.
- **Preuves** :
  - `LinuxBubblewrapWorkerSandboxBackend.java:339-346` et `:268` : `--cpu=<timeout+5 s>` appliqué par `prlimit` à chaque processus. `RLIMIT_CPU` compte le temps CPU cumulé de tous les threads du processus (hypothèse noyau classique : SIGXCPU puis SIGKILL à la limite dure, ici soft = hard). Pour un JVM Gradle/Maven multi-thread, le temps CPU croît plus vite que l'horloge murale.
  - `:51` : le cgroup, lui, accorde 8 CPU (`MAX_CPU_MICROS_PER_PERIOD = 8 × période`) ; `WindowsAppContainerWorkerSandboxBackend.java:60` et `:456-462` accordent `JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND = 8` CPU-s par seconde de timeout. La limite Linux par processus (1 CPU-s par seconde) est donc incohérente avec le budget annoncé (8) sur Windows et dans le cgroup.
  - Timeouts réels : `AbstractScipProcessPlanFactory.java:21` (30 min), `ScipJavaProcessPlanFactory.java:125,143,161` (1 h) : un seul processus qui consomme en moyenne plus de 1 cœur est tué avant l'échéance murale (30 min → 1 805 CPU-s ; 1 h → 3 605 CPU-s).
  - `LinuxBubblewrapWorkerSandboxBackend.java:48` et `:343` : `pids.max = 128` et `--nproc=128`. `docs/user/docker-runtime.md:256` établit que les PID comptent les **threads**, qu'une JVM pèse environ 30 threads et que ce nombre dépend du nombre de CPU visibles. Une chaîne scip-java (lanceur + Maven/Gradle + compilateur forké) approche ou dépasse 128 sur un hôte à 16 CPU.
  - Aucune mesure du pic de PID d'un provider sous bwrap dans le dépôt (`grep -rn "pids.peak\|MAX_PROCESSES"` : seulement la définition et les assertions de commande).
  - Tests existants : `LinuxBubblewrapWorkerSandboxBackendTest.java:56` n'asserte que `--as=`. Aucun test ne couvre `--cpu` ou le comportement sous charge.
- **Comportement actuel** : échec possible du provider (SIGKILL, `EAGAIN` à la création de thread) avant son timeout.
- **Comportement attendu + source** : ADR 0038 § 4 / `remote-worker-sandbox-disposition.md:52` : `prlimit` est « défense en profondeur par processus » ; il ne doit pas être plus restrictif que la borne agrégée. `LinuxBubblewrapWorkerSandboxBackend.java:28-36` : la borne agrégée est le cgroup.
- **Cause** : valeurs `prlimit` héritées de l'ère pré-cgroup (ADR 0038 § Contexte) non réalignées sur la borne agrégée.
- **Impact** : faux échecs d'indexation Java/Kotlin sous Linux sur grosse machine ou gros projet ; difficile à diagnostiquer (R14 est l'équivalent connu sous AppContainer).
- **Correction minimale proposée** : retirer `--cpu` (le cgroup `cpu.max` et le timeout mural bornent déjà) ou le multiplier par le plafond de cœurs (comme Windows) ; mesurer `pids.peak` sur scip-java/scip-typescript/scip-python et dimensionner `MAX_PROCESSES` avec marge (le dépôt le fait déjà pour Docker, `S23-SUIVI.md:214`).
- **Validation (test)** : test d'intégration Linux dans `minos-runtime-local/src/test/java/com/minos/runtime/local/LinuxBubblewrapWorkerSandboxIsolationTest.java` (modèle existant) : plan de timeout 10 s (`--cpu=15`) lançant 4 threads actifs pendant 5 s ; asserter que le processus n'est pas tué par signal (exit ≠ 137/152). Pour `pids` : plan qui crée 100 threads/processus et lit `pids.peak` du cgroup.
- **Dépendances** : R14 (même famille de « provider Java échoue sous bac à sable ») ; `WorkerSandboxQualification` ne change pas.

---

### A-04 — Windows : une variable de toolchain ouvre en lecture récursive tout son répertoire à l'AppContainer (ex. `CARGO_HOME` et `credentials.toml`)

- **Qualification** : RISQUE (dépend d'une variable d'environnement posée par l'opérateur et d'un projet Rust ; secret exposé à du code de projet non fiable).
- **Priorité proposée** : P3.
- **Preuves** :
  - `WindowsAppContainerWorkerSandboxBackend.java:298-302` : toute variable d'environnement assainie dont la clé est dans `TOOLCHAIN_HOME_KEYS` (`:711-717` : `JAVA_HOME, JDK_HOME, DOTNET_ROOT, CARGO_HOME, RUSTUP_HOME, COURSIER_CACHE`) devient une racine de lecture ; `:743-761` ne l'écarte que si `isOverBroadGrant` (`:768-780`).
  - `isOverBroadGrant` n'écarte un candidat que si une position « large » (USERPROFILE, APPDATA, etc.) **commence par** le candidat, c'est-à-dire un ancêtre ou égal. Un descendant comme `%USERPROFILE%\.cargo` n'est donc jamais écarté.
  - Le template accorde `(OI)(CI)RX` sur ces racines (`windows-appcontainer-sandbox-v4.ps1.template:546-549`), donc tout le contenu de `CARGO_HOME`, y compris `credentials.toml` (jeton de registre cargo) et `config.toml`, devient lisible.
  - Ces variables sont héritées d'office par `ProviderProcessEnvironment.java:36-38` (`CARGO_HOME`, `RUSTUP_HOME`, `COURSIER_CACHE`), et un provider Rust existe (`RustAnalyzerScipProcessPlanFactory.java:27-35`) dont le projet exécute des `build.rs`/proc-macros.
  - Le Javadoc affirme l'inverse : `:737-741` « never to a profile-wide or system location » et `:763-767` « a manipulated toolchain variable cannot widen the sandbox beyond the toolchain it is supposed to name ». Aucun test de `isOverBroadGrant` (`grep OverBroad` sous `src/test` : rien).
  - Côté Linux l'équivalent n'existe pas (seuls `/usr`, `/bin`, `/lib*`, `tools/<provider>/<version>` et les fichiers en argument sont montés, `LinuxBubblewrapWorkerSandboxBackend.java:367-400`).
- **Comportement actuel** : lecture possible de `credentials.toml` par du code du projet indexé ; avec réseau DENY (défaut local) pas d'exfiltration directe, mais le contenu peut être écrit dans l'artefact SCIP/les logs.
- **Comportement attendu + source** : « WINDOWS_MINOS_RUNTIME_ROOT_ACL_ONLY » (`:161`) et le Javadoc ci-dessus : le sandbox ne lit que les racines gérées par MINOS ou la toolchain strictement.
- **Cause** : le garde-fou teste « ancêtre d'une zone sensible » mais pas « contient un fichier sensible » ni « descendant d'une zone sensible ».
- **Impact** : confidentialité d'un secret de registre pour les utilisateurs qui posent `CARGO_HOME`.
- **Correction minimale proposée** : pour `CARGO_HOME`, ne pas accorder la racine entière (accorder `bin` et `registry` en lecture seule, jamais `credentials*`), ou refuser une variable qui n'est pas sous `MINOS_HOME/tools` ; ajouter le test manquant.
- **Validation (test)** : dans `WindowsAppContainerWorkerSandboxBackendTest.java` (Windows) : `CARGO_HOME` = répertoire temporaire contenant `credentials.toml`, plan avec `environment` correspondante ; lire le `windows-appcontainer-plan.txt` écrit (`sandboxPlan`, `:313-327`) et asserter que `credentials.toml` ni son parent n'apparaît dans `read`. À décider : politique exacte (liste blanche de sous-répertoires).
- **Dépendances** : S7/S16 (famille ACL Windows) ; aucune dépendance de gate connue.

---

### A-05 — `DistributedArtifactBundleStore` : clé de cache indépendante du run, mais « hit » exige un manifeste identique (run, horodatages) — le cache n'est jamais réutilisé entre runs

- **Qualification** : DÉFAUT CONFIRMÉ par lecture (conception incohérente) mais **dormant** (ADR 0041).
- **Priorité proposée** : P3 (à corriger avant toute réouverture du chemin distant).
- **Preuves** :
  - `DistributedArtifactBundleStore.java:531-542` : `cacheKey` = format, dépôt, commit, scope, langage, provider, version, SHA-256 de l'artefact. Elle exclut volontairement runId, projectId, workerId, horodatages.
  - `:200` et `:449-469` : `validCached` exige `expected.equals(actual)` sur le record complet (`DistributedArtifactManifest` inclut `runId`, `projectId`, `workerId`, `startedAt`, `completedAt`). `DistributedIndexerExecutor.java:180-181` impose que le manifeste porte le `runId` du run courant : deux runs ne produisent jamais un manifeste égal.
  - Conséquence : pas de réutilisation inter-runs ; au second accept, `:207-209` supprime l'entrée existante de même clé puis la réécrit (`:210-214`), alors qu'un handle du premier accept (même JVM : le bail est partagé et compté par référence, `SharedCacheLeaseRegistry.java:64-69`) peut encore pointer sur `entry/index.scip`.
  - Test existant : `DistributedArtifactBundleStoreTest.java:45-66` ne couvre que « le même bundle accepté deux fois » ; aucun test « même artefact, deux manifestes ».
  - Détail secondaire : `:199` calcule `bundleSha` en rouvrant le bundle après extraction (`:178`) : si le fichier est remplacé entre-temps par un fichier de même taille, `bundleSha256` (preuve exposée à la CLI) ne décrit pas les octets extraits.
- **Comportement attendu + source** : ADR 0033 § 3 : « cache reconstructible et borné », optimisation locale ; la clé sans runId indique l'intention d'un partage.
- **Cause** : égalité de record trop stricte (ou clé trop lâche).
- **Impact** : `cacheHit` toujours faux en pratique ; suppression d'un fichier possiblement en cours d'usage dans la même JVM. Aucun impact aujourd'hui (chemin fermé).
- **Correction minimale proposée** : comparer dans `validCached` seulement les champs d'identité de la clé (et la somme de contrôle), et écrire un manifeste « premier accepté » ; ou inclure runId dans la clé. Calculer `bundleSha` pendant l'extraction (DigestInputStream autour du flux ZIP).
- **Validation (test)** : dans `DistributedArtifactBundleStoreTest.java`, avec l'assistant `manifest(provider, artifact, Instant)` (le runId est dérivé du provider, `:291`), créer deux manifestes de même artefact avec `startedAt` différents ; `first = accept(b1)` conservé, `second = accept(b2)` ; asserter `second.cacheHit()` et `Files.exists(first.artifact())` pendant toute la durée. Échoue (hit faux, entrée réécrite).
- **Dépendances** : réouverture éventuelle d'ADR 0041.

---

### A-06 — Résidus de provider conservés par leur nom sans contrôle de type ; `index.scip` périmé non nettoyé avant exécution

- **Qualification** : AMÉLIORATION (durcissement) ; le second point est un RISQUE faible (nécessite une reprise ADR 0039 et un provider qui sort 0 sans écrire).
- **Priorité proposée** : P3.
- **Preuves** :
  - `ProviderResidueReclamation.java:26-33` et `:69` : toute entrée portant un nom de `RETAINED_ENTRIES` (`provider.stdout.log`, `provider.stderr.log`, `process.txt`, `index.scip`, `failed-index.scip`, `preexisting-artifact.scip`, `scopes`) est conservée **quel que soit son type**. Le répertoire de run est inscriptible par le provider (`LinuxBubblewrapWorkerSandboxBackend.java:278`, `WindowsAppContainerWorkerSandboxBackend.java:306`). Sous Linux le provider peut remplacer `provider.stderr.log` par un lien symbolique ou un répertoire peuplé (borné par le quota, `ProviderWriteQuota.DEFAULT` 8 Gio / 400 000 entrées). Le message d'échec invite à ouvrir ce fichier (`ProcessIndexerExecutor.java:154` : « see <stderr> »).
  - `ProcessIndexerExecutor.java:99-100` : la préservation ne vaut que si l'artefact généré est hors du répertoire de run ; pour les providers SCIP, `generated == final` (`AbstractScipProcessPlanFactory.java:44`, `ScipJavaProcessPlanFactory.java:87`) ; `prepareRunDirectory` (`:395-413`) supprime seulement `index.partial.scip`, jamais `index.scip`. Un `index.scip` d'une tentative précédente du même répertoire (reprise ADR 0039, commentaire `:407-410`) est donc promu par `promoteArtifact` (`:331-341`) si le provider sort 0 sans le réécrire (`requireValidArtifact` ne contrôle que « fichier régulier, taille ≥ 1 »).
- **Comportement attendu + source** : `ProviderResidueReclamation` Javadoc (`:14-19`) : « Only MINOS-authored diagnostics survive » ; ADR 0039 : une reprise ne réutilise que ce qui est vérifié.
- **Correction minimale proposée** : ne conserver un nom retenu que s'il est un fichier régulier non lien ; supprimer `index.scip` (et son `.sha256`) avant de lancer un provider qui n'est pas reconduit depuis un point de contrôle valide.
- **Validation (test)** : dans `ProviderResidueReclamationTest` (nouveau) créer `provider.stderr.log` comme lien/répertoire dans un run et asserter sa suppression ; dans `ProcessIndexerExecutorTest.java`, un provider factice qui sort 0 sans écrire alors que `index.scip` périmé existe doit échouer.
- **Dépendances** : aucune.

---

### A-07 — Des commandes de lecture/diagnostic mutent le système (cgroup, ACL, script lanceur) pour qualifier le sandbox

- **Qualification** : DÉCISION À CLARIFIER (règle « une lecture ne mute rien » du sprint 6 ; voir R10, R12). Les écritures sont idempotentes mais réelles.
- **Priorité proposée** : P3 (P2 sous Windows tant que A-01 est ouvert, car la sonde y lance le lanceur qui récupère).
- **Preuves** :
  - Linux : `WorkerSandboxBackends.java:149-162` → `LinuxBubblewrapWorkerSandboxBackend.discover` (`:88`) → `LinuxCgroupJob.delegatedRoot()` → `qualifyRoot` (`LinuxCgroupJob.java:132-146`) : crée `minos-controller`, **déplace toute la JVM** dans ce cgroup (`relocateSelf` `:158-167`), active `cgroup.subtree_control` (`:169-180`), crée/supprime un cgroup sonde (`:422-434`), et **tue et supprime** les cgroups de propriétaires morts (`reclaimAndReportStaleJobs` `:139`, `:245-259`, kill `:318-320`). Déclenché par `doctor` (`DoctorCommand.java:98-99`) et par tout statut de provider (`StrongOwnedProcessExecutors.java:52-56`).
  - Windows : `WindowsAppContainerWorkerSandboxBackend.java:87-89` (`removeLegacyCopy` supprime un fichier dans MINOS_HOME, `materialize` écrit sous `%LOCALAPPDATA%`), `:103` (lancement PowerShell réel, profil AppContainer, `icacls`).
  - Les commandes MCP n'y passent pas : `grep ProviderRuntimeStatus|ProviderRuntimeManager` sous `minos-mcp/src/main` : aucun résultat (ADR 0017 non contredit).
- **Comportement attendu + source** : `AUDIT-2026-09.md` § Sprint 6/7 : une lecture ne mute rien (R10, R12 ouverts pour d'autres chemins) ; `config.yaml` : MCP lecture seule. Pour la CLI, la règle est explicite pour l'état projet, pas pour l'hôte.
- **Correction minimale proposée** : décider et documenter (`docs/user/cli.md`) que `doctor`/`tools status` qualifient le sandbox avec effets de bord, ou scinder en une sonde passive (lecture de `cgroup.controllers`, `/proc/self/cgroup`) et une qualification active seulement avant exécution.
- **Validation** : test unitaire sur un faux système de fichiers cgroup (le `SweepContext` est déjà injectable) vérifiant qu'une sonde passive n'écrit pas.
- **Dépendances** : A-01, R10, R12.

---

### A-08 — `RemoteRepositoryRequest.projectSubdirectory` : rejet tardif (après un clone complet) des formes Windows non absolues mais ancrées

- **Qualification** : AMÉLIORATION ; **à confirmer par exécution sous Windows** (comportement de `Path.resolve` sur `C:foo`/`\foo`, non rejoué).
- **Priorité proposée** : P3.
- **Preuves** :
  - `RemoteRepositoryRequest.java:161-171` : `isAbsolute()` puis `startsWith("..")` ; sous Windows `C:foo` (relatif au lecteur) et `\foo` (racine sans lecteur) ne sont pas « absolus » et ne commencent pas par `..` ; elles passent donc le constructeur.
  - Le refus intervient seulement dans `JGitRemoteRepositoryMaterializer.ensureProjectRoot` (`:247-254`), après le clone (`:175`) et `validateCheckout` (`:180-181`), c'est-à-dire après jusqu'à 10 Gio/10 minutes de transfert.
  - `portableSubdirectory` (`:353-355`) donne `C:foo` ≠ `foo` : deux clés de cache pour le même répertoire si `resolve` les confond.
  - `DistributedArtifactManifest.normalizeManifestScope` (`:78-110`) a le même trou (compensé par l'égalité à la requête).
- **Comportement attendu + source** : Javadoc de la classe (`:16-22`) et ADR 0033 § 1 : sous-répertoire relatif confiné au dépôt, refus fail-closed.
- **Correction minimale proposée** : rejeter dans `canonicalSubdirectory` toute valeur dont `getRoot() != null` ou qui contient `:`, et normaliser le séparateur ; refuser avant tout I/O réseau.
- **Validation (test)** : `RemoteRepositoryRequestTest` (engine) : `of(url, "main", sha, "C:foo", null)` et `"\\foo"` doivent lever `IllegalArgumentException` (test valable sous Windows et Linux : `:` est refusé explicitement).
- **Dépendances** : aucune.

---

### A-09 — Cache distant : une entrée épinglée au contenu illisible bloque toute nouvelle matérialisation ; entrées valides re-clonées sur la foi de `git status`

- **Qualification** : RISQUE (scénario de corruption disque ou de configuration Git globale changeante ; non démontré).
- **Priorité proposée** : P3.
- **Preuves** :
  - `JGitRemoteRepositoryMaterializer.java:283-291` : une entrée dont `entry.properties` est illisible devient `invalid` ; `:304` saute toute entrée épinglée (`registered.pin`) ; `:315-317` lève alors `IOException("remote cache limits cannot be satisfied…")` pour **tout** nouveau `materialize` (l'entrée neuve est même supprimée, `:191-196`). Aucune voie d'opérateur : `unpin` exige une matérialisation valide (`validatedEntry` `:146-157`).
  - `:212-233` / `:256-267` : un `git status` non propre ou une différence d'origine invalide l'entrée (`catch Exception → Optional.empty`), puis `:167` supprime l'entrée **sans tenir compte de `registered.pin`** et re-clone. Le Javadoc du port dit que les implémentations évinçantes « must preserve pinned entries or fail closed » (`RemoteRepositoryMaterializer.java:243-245`).
  - Aucun test de « entrée épinglée corrompue » (`JGitRemoteRepositoryMaterializerTest` à confirmer).
- **Comportement attendu + source** : `RemoteRepositoryMaterializer.java:243-251` et ADR 0033 § 3.
- **Correction minimale proposée** : message d'erreur nommant l'entrée épinglée invalide et une commande d'opérateur de réparation ; ne pas supprimer une entrée épinglée sur invalidation sans l'échange `unpin` explicite ; garder `registered.pin` lisible indépendamment de `entry.properties`.
- **Validation (test)** : dans `JGitRemoteRepositoryMaterializerTest` : créer une entrée épinglée, corrompre `entry.properties`, appeler `materialize` d'une autre requête, asserter que l'échec nomme l'entrée et qu'une réparation documentée la débloque.
- **Dépendances** : aucune.

---

### A-10 — Prérequis à lever avant de rouvrir ADR 0041 : `ALLOW` n'a pas la même portée sous Linux et Windows

- **Qualification** : DÉCISION À CLARIFIER (dormant : `ALLOW` n'est atteignable que par `remote index --worker-network allow`, refusé d'avance ; `StrongOwnedProcessExecutors.localNetworkPolicy` laisse `DENY` par défaut, `:117-125`).
- **Priorité proposée** : P3.
- **Preuves** :
  - Linux : `LinuxBubblewrapWorkerSandboxBackend.java:352-354` (`--share-net`) partage le **namespace réseau de l'hôte** : loopback et services locaux, métadonnées cloud, sockets Unix abstraits ; `:370-376` monte aussi `/etc/passwd`, `/etc/group`, `/etc/hosts`, `/etc/resolv.conf`.
  - Windows : `windows-appcontainer-sandbox-v4.ps1.template:215-225` n'ajoute que la capacité `INTERNET_CLIENT` (S-1-15-3-1), l'isolement loopback d'AppContainer restant actif (hypothèse OS).
  - Absence de filtre seccomp côté Linux : aucune occurrence de `seccomp` en production sous `minos-runtime-local/src/main` (confirme le point de S3 resté ouvert : « Le profil bwrap n'a pas de seccomp », `AUDIT-2026-09.md:425`).
- **Correction minimale proposée** : documenter la sémantique exacte d'`ALLOW` par OS dans ADR 0041 / `remote-worker-sandbox-disposition.md` et, si l'on rouvre, remplacer `--share-net` par un réseau avec règles de sortie ou un proxy contrôlé.
- **Validation** : à définir à la réouverture (test Linux : processus sandboxé qui tente `connect(127.0.0.1)` sous `ALLOW`).
- **Dépendances** : ADR 0041, S3 (seccomp).

---

## Confirmations ou infirmations de constats déjà connus (au HEAD)

- **A1** : toujours clos par décision, aucune régression. Refus avant effet de bord confirmé : `LocalRemoteIndexOperations.java:102` (refus) précède `:103` (`materialize`) ; `LocalIsolatedIndexWorker.java:141-146` refuse avant `ensurePrivateDirectory` (`:154`).
- **R13** : confirmé. `SandboxLauncherScript` crée un répertoire par empreinte (`SandboxLauncherScript.java:62-98`), sans ménage des anciennes versions.
- **S3 (seccomp)** : confirmé non traité (A-10).
- **S5/S17** : `createBundle` (`DistributedArtifactBundleStore.java:129-136`) et `ProviderWorkspaceFiles.copyBounded` (`:109`) utilisent encore `Files.newOutputStream`/`Files.createDirectories` : cohérent avec S17 (hors gate), sans risque exploitable ici (fichiers neufs sous répertoires privés, `CREATE_NEW`).
- **S9** : la limitation « seul le sommet de branche est indexable » subsiste (`JGitRemoteGitClient.java:32` `setDepth(1)` ; `JGitRemoteRepositoryMaterializer.java:260`).
- **R14, R12, R10, R8, D3, G4, G6, S12–S14, A8–A11, T1, C2** : non examinés ici (hors capacité), sauf A-02 qui est de même famille que R8.

## Points vérifiés sans défaut (pour éviter de les re-signaler)

- `RemoteRepositoryRequest` : HTTPS seul, hôte épinglé `github.com`/`gitlab.com`, pas d'information d'utilisateur ni de requête, port 443, segments `.`/`..` rejetés après décodage, SHA-1 complet obligatoire, nom de variable de credential contraint (`isAllowedCredentialVariable`).
- `JGitCloneDeadline` : revalide l'hôte à chaque connexion JGit (redirections hors hôte refusées, `JGitCloneEndpointPinTest` existe) ; `JGitRemoteGitClient` n'écrit pas le secret dans la config ; `resolveSecret` ne nomme pas la variable dans le message.
- Bundle : le ZIP n'accepte que `manifest.properties` et `index.scip`, chaque entrée bornée, doublons refusés, taille et SHA-256 relus avant publication (`DistributedArtifactBundleStore.extract` `:259-286`, `accept` `:164-238`).
- Copie éphémère : liens symboliques rejetés, ouverture confinée sans suivre les liens, budgets de fichiers/octets/traversée (`ProviderWorkspaceFiles.copyWorkspace`).
- Environnement : liste blanche (`ProviderProcessEnvironment`), lanceur de confiance à environnement minimal, `PATH` seulement absolu (`CommandLocator.findInPath`), `bwrap`/`prlimit`/`sh` résolus dans des répertoires système appartenant à root (`CommandLocator.findSystemExecutable`).
- Linux : ordre « création du cgroup, entrée avant `exec`, `cgroup.kill` vérifié par relecture de membres » (`LinuxCgroupJob.enterThenExec`, `killInternal`), marque de propriétaire pour le balayage.
- Windows : job configuré et relu avant création du processus suspendu, vérification du jeton AppContainer avant reprise, `JOB_OBJECT_LIMIT_BREAKAWAY` interdit, lanceur vérifié (SHA-256, propriétaire, ACL) avant chaque lancement.
- Exécuteur : métadonnées sans arguments de commande (`ProcessIndexerExecutor.redactedCommand`), sinks de logs ouverts avant le provider, tueur d'arbre qui n'atteint jamais la JVM hôte.

---

## (1) Carte réelle du périmètre

| Classe / groupe | Rôle | Dépendances sortantes principales | Tests existants | Lacunes de test |
|---|---|---|---|---|
| `WorkerSandboxBackends`, `WorkerSandboxSelection`, `WorkerSandboxQualification`, `WorkerResourceContainment`, `WorkerSandboxBackend` | Sélection et qualification (fail-closed par décision ADR 0041) | `LinuxBubblewrap…`, `WindowsAppContainer…`, `CommandLocator`, `LinuxCgroupJob` | `WorkerSandboxBackendsTest`, `WorkerSandboxQualificationTest`, `WorkerResourceContainmentTest` | pas de test des effets de bord de la découverte (A-07) |
| `LinuxBubblewrapWorkerSandboxBackend`, `LinuxCgroupJob`, `CgroupJobOwnership` | bwrap + cgroup v2 : plan, job, balayage de résidus | `prlimit`, `bwrap`, `/sys/fs/cgroup`, `/proc` | `LinuxBubblewrapWorkerSandboxBackendTest`, `…IsolationTest`, `LinuxCgroupJob*Test`, `LinuxCgroupStaleRecoveryTest` | aucun test de `--cpu`, de charge `pids.max`, ni de seccomp (A-03, A-10) |
| `WindowsAppContainerWorkerSandboxBackend`, template `windows-appcontainer-sandbox-v4.ps1.template`, fragments, `SandboxLauncherScript`, `WindowsContainmentScript`, `WindowsJobObjectProcessOwnership` | AppContainer + Job Object, ACL, script lanceur | `icacls`, PowerShell 5.1, `PrivateLocalStorage` | `WindowsAppContainerWorkerSandboxBackendTest`, `WindowsLauncherScriptPlacementTest`, `WindowsJobObjectContainmentTest`, `WindowsNonElevatedIndexingTest` | aucun test de concurrence de lanceurs (A-01), ni de `isOverBroadGrant` (A-04) |
| `ProcessIndexerExecutor`, `ProcessOwnershipTracker`, `ProcessTreeTermination`, `BoundedProcessOutput`, `ProviderWriteQuotaSupervisor`, `ProviderResidueReclamation`, `ProviderProcessEnvironment`, `CommandLocator` | Exécution d'un provider, supervision, quota d'écriture, résidus, environnement, résolution d'exécutables | cgroup/Job Object, `PrivateLocalStorage`, `ConfinedFileOpener` | `ProcessIndexerExecutorTest`, `…ArtifactPreservationTest`, `ProviderWriteContainmentTest`, tests d'environnement et de localisation | résidus de type inattendu, `index.scip` périmé (A-06) |
| `StrongProcessOwnershipIndexerExecutor`, `LocalProviderWorkspace`, `ProviderWorkspaceFiles` | Exécution locale managée : copie bornée puis backend managed-local | backends ci-dessus | `StrongProcessOwnershipIndexerExecutorTest`, `LocalProviderWorkspaceTest`, `ProviderWorkspaceFilesTest` | pas de test de ménage d'orphelins (A-02) |
| `LocalIsolatedIndexWorker`, `DistributedIndexerExecutor`, `DistributedArtifactBundleStore`, `DistributedArtifactCachePolicy`, `SharedCacheLeaseRegistry` (engine) | Chemin distant : worker, transport ZIP, cache vérifié (dormant) | magasin de baux, `PrivateLocalStorage` | `LocalIsolatedIndexWorkerTest`, `DistributedArtifactBundleStoreTest`, `DistributedIndexerExecutorScopeTest`, `ScopeSwapRejectionTest` | hit inter-runs (A-05), orphelins `.accept-` (A-02) |
| `minos-engine/.../remote` : `RemoteRepositoryRequest`, `RemoteRepositoryMaterializer`, `IdempotentRemoteRepositoryMaterializer`, `DistributedArtifactManifest`, `DistributedIndexing`, `RemoteIndexingRuntime` | Contrat de requête, manifeste, ports | `Preconditions` | `DistributedArtifactManifestTest`, `IdempotentRemoteRepositoryMaterializerTest` | formes de chemin Windows (A-08) |
| `minos-integration-git/.../remote` : `JGitRemoteRepositoryMaterializer`, `JGitRemoteGitClient`, `JGitCloneDeadline`, `RemoteCloneBudget`, `RemoteRepositoryCachePolicy` | Clone JGit borné, cache, baux, éviction | JGit, `PrivateLocalStorage`, `SharedCacheLeaseRegistry` | `JGitRemoteRepositoryMaterializerTest`, `…LockTest`, `JGitCloneDeadlineTest`, `JGitCloneEndpointPinTest`, `RemoteCachePrivateStorageTest` | entrée épinglée corrompue (A-09) |
| Composition : `LocalRemoteIndexingRuntime` (bootstrap), `LocalRemoteIndexOperations`, `RemoteIndexCommand`, `RemoteIndexLease` (cli) | Refus anticipé, enchaînement matérialisation/index | port `RemoteIndexingRuntime` | `LocalRemoteIndexOperations*Test`, `RemoteIndexCommandTest` | — |

## (2) Ce que je n'ai PAS examiné

- Tout code exécuté en vrai : aucun test lancé, rien rejoué sous Linux/WSL/Windows. Les affirmations sur `RLIMIT_CPU`, `RLIMIT_NPROC`, `Path.resolve` Windows, la portée de `INTERNET_CLIENT` et le comptage de PID par cgroup sont des hypothèses de comportement noyau/JDK à mesurer.
- Le contenu complet des fragments PowerShell/C# (`windows-fragments/*.ps1frag`, `appcontainer-private-storage.ps1frag` : quota de stockage privé, jeton, héritage de handles `bInheritHandles=true` dans `createprocess-call.ps1frag`) : lus partiellement.
- `WindowsJobObjectProcessOwnership` et `windows-job-object-owner-v1.ps1.template`, `SddlReplaceRights`, `WindowsExecutionPathIdentityProvider`, `ProcessIdentity`.
- `RunDirectoryRetention`, `FileResumableRunMarkers`, `CgroupJobOwnership` (marque, décisions) en détail : seule leur existence et leurs appelants.
- Les classes de `minos-engine` hors package `remote` (`PrivateLocalStorage`, `ConfinedFileOpener`, `FileTreeOperations`, `BoundedFileLease`) et le parseur SCIP qui consomme l'artefact : traités comme des primitives de confiance.
- Les gestionnaires de runtime de providers (`ManagedScipProviderRuntimeManager`, `Managed*Polyglot…`, installation `npm`/`coursier`/`dotnet`, `PinnedArtifactSource`) : le code d'installation des outils s'exécute hors sandbox par conception (chaîne d'approvisionnement épinglée, S23/S11).
- Docker/compose, `docker/`, plans `minos-admin` : hors capacité ; le backend Docker « UNSUPPORTED_BY_BACKEND » n'a été lu que dans `StrongOwnedProcessExecutors`.
- `GitIntelligenceService` (historique Git local) : hors périmètre de la matérialisation distante.
- Les scripts de gate `scripts/**/check-*.py` : non lus, seule leur existence pour les renommages.
- Comportement de JGit (limites de taille de paquet côté client, `http.followRedirects`, filtres `.gitattributes`) : non vérifié dans le code source de JGit ; je me suis appuyé sur l'épinglage d'hôte de `JGitCloneDeadline` et sur le budget d'échantillonnage de `RemoteCloneBudget` (dépassement d'octets borné par l'intervalle d'échantillonnage, non prouvé).

<!-- Annexe D de l'audit 2026-10. Rapport d'analyse brut, conservé tel que remis ; les identifiants D-NN y valent MINOS-AUD-DNN dans docs/audit/archive/2026-10-06/constats.md. Les chemins « scratchpad/ » cités désignent des reproductions jetables hors dépôt, non conservées. -->
# Audit D — Cycle de vie de l'indexation (MINOS, HEAD develop bc1d3421)

Périmètre : `minos-engine` (`orchestration`, `incremental`, `discovery`, `source`, `io`), `minos-application` (`ProjectIndexStateReconciler`, `ProjectInspectionService`, `LocalProjectOperations`), `minos-cli` (`LocalAutonomousIndexOperations`, qui est le vrai point d'entrée de `minos index`), `minos-storage-local` (baux, `FileIndexStateStore`, `FileProjectFingerprintSnapshotStore`, `FileSymbolSnapshotStore`, rétentions), `minos-runtime-local` (`ProcessIndexerExecutor`, `RunDirectoryRetention`, marqueurs), `minos-provider-scip` (`ScipProjectSnapshotLifecycle`, `ScipIndexerCatalog`).

Lecture seule : aucun fichier du dépôt modifié, aucun Maven lancé, aucun test exécuté. Toute reproduction ci-dessous est donc **proposée, non rejouée**. Les chemins:lignes ont été relus.

Les constats déjà clos (R1-R7, R9, Q2-Q5, P1) n'ont pas été rouverts. Confirmations de constats connus ouverts, au HEAD :
- **R8 confirmé, et plus large que décrit.** Aucune rétention ne référence `staged-snapshots` (`grep` : seul `ScipProjectSnapshotLifecycle.java:50` y touche). Le répertoire `staged-snapshots/<runId>` n'est nettoyé qu'à la promotion réussie (`ScipProjectSnapshotLifecycle.java:143-148`) ou à un nouveau `stage` du même runId (`:67`). Il fuit donc aussi pour **tout run échoué par exception après le staging** (pas seulement un run tué), pour tout run supplanté et pour tout run interrompu non repris : `SnapshotStager` n'a pas d'opération d'abandon et `IndexingRunExecutor.persistFailure` (`:637-649`) n'en appelle aucune.
- **R10 confirmé** : `FileIndexStateStore.java:86-87` migre les runs au format historique dans le constructeur, sans bail.
- **R12 confirmé** : `MinosApplication.java:220` (`ensurePrivateDirectory(home)`) et les constructeurs de stores (`FileIndexStateStore.java:79-85`) créent des répertoires à l'ouverture.
- R13, R14 : hors de mes capacités (lanceurs, bac à sable), non examinés.

---

## Constats

### D-01 — Un répertoire illisible, même ignoré (`.gitignore`) ou « durci » (`node_modules`, `target`…), fait échouer découverte, empreinte et statut du projet

- **Qualification** : DÉFAUT CONFIRMÉ pour les répertoires ignorés ou durcis ; DÉCISION À CLARIFIER pour les répertoires non ignorés (l'échec fail-closed est défendable, mais il n'offre aucune issue à l'utilisateur).
- **Priorité proposée** : P1. L'indexation et le statut du projet deviennent impossibles tant que le répertoire est illisible, et aucune règle d'ignore n'y remédie. Contexte typique : volume Docker `pgdata/` en 0700 appartenant à root, ou ACL Windows refusant la lecture. Le correctif est local et peu risqué.
- **Preuves** :
  - `minos-engine/src/main/java/com/minos/discovery/ProjectDiscoveryService.java:150-154` : `visitFileFailed` fait `throw exception`. Seuls les répertoires durcis sont écartés, et seulement dans `preVisitDirectory` (`:134-136`) ; un répertoire ignoré par `.gitignore` est traversé (`:137` n'empêche que l'enregistrement du module).
  - `minos-engine/src/main/java/com/minos/incremental/ProjectFingerprintService.java:103-133` : aucun override de `visitFileFailed`, donc le comportement par défaut de `SimpleFileVisitor` relance l'`IOException`. `preVisitDirectory` ne saute que les répertoires durcis (`:110-113`).
  - `minos-engine/src/main/java/com/minos/discovery/ProjectIgnorePolicy.java:121-144` (`scanVisibleFileNames`) : même défaut, sur `src/` et les autres racines de sources.
  - Comportement du JDK (`FileTreeWalker`) : le répertoire est ouvert **avant** `preVisitDirectory`. Un répertoire dont `newDirectoryStream` échoue va donc directement à `visitFileFailed` : même `node_modules` ou `.git`, durcis, ne sont jamais « sautés » s'ils sont illisibles.
  - `minos-application/src/main/java/com/minos/application/ProjectInspectionService.java:139` : `view()` appelle `discover` sans protection, donc `minos_index_status` et `project inspect` échouent aussi (voir D-02).
- **Comportement actuel** : `minos index`, `project inspect` et `minos_index_status` lèvent une `IOException` (`AccessDeniedException`) pour tout le projet. Q8 n'a réparé que le symptôme : `project list` dégrade l'entrée (`ProjectInspectionService.java:95-103`) mais le projet reste inindexable.
- **Comportement attendu + source** :
  - `ProjectIgnoreRules` (Javadoc `:21-27`) : les répertoires durcis sont « always hidden », donc jamais lus.
  - `.gitignore` et `.minosignore` doivent écarter ce qu'ils nomment.
  - `SourceBudgetPolicy.Tracker.accountTraversalEntry` (Javadoc `:70-74`) justifie la traversée des répertoires ignorés par la seule sémantique de négation, ce qui ne justifie pas d'échouer sur ce qu'on ne peut pas lire.
  - config.yaml : « Fail-closed » vaut pour le code non fiable, pas pour les fichiers que la politique exclut.
- **Cause** : l'exclusion est évaluée trop tard (après l'ouverture du répertoire par le JDK) et `visitFileFailed` ignore la politique d'exclusion.
- **Impact** : indexation, statut et inspection bloqués par un répertoire sans rapport avec les sources. Contournement actuel : changer les droits ou déplacer le répertoire.
- **Correction minimale proposée** :
  - Dans les trois visiteurs, `visitFileFailed` consulte la politique : si le chemin relatif est durci ou ignoré (`isHardIgnored(rel) || isIgnored(rel, true)`), retourner `CONTINUE` (ou `SKIP_SUBTREE`) avec une trace WARNING bornée (`reportUnreadable` existe déjà, `ProjectIgnorePolicy.java:71`). Sinon, conserver l'échec mais avec un message actionnable (nom relatif du répertoire, suggestion `.minosignore`).
  - Idéalement, ne pas descendre dans un répertoire ignoré quand aucune règle de négation n'existe.
- **Validation (test proposé)** :
  - `minos-engine/src/test/java/com/minos/discovery/UnreadableDirectoryDiscoveryTest.java`, à côté de `UnreadableMarkerDiscoveryTest` (qui ne couvre que des *fichiers* marqueurs).
  - Arborescence : `pom.xml`, `src/main/java/A.java`, `pgdata/` (permissions POSIX `Set.of()`, ou ACL deny `LIST_DIRECTORY` sous Windows, avec le même contrôle « est-ce vraiment illisible » que `UnreadableFile.deny`), `.gitignore` contenant `pgdata/`.
  - Appeler `new ProjectDiscoveryService().discover(root)` : attendu aucune exception (actuellement `AccessDeniedException`).
  - Variantes : `new ProjectFingerprintService().capture(root)` dans `minos-engine/src/test/java/com/minos/incremental/`, et un répertoire durci nommé `node_modules`.
- **Dépendances** : D-02 (le statut en hérite), D-11 (même politique de traversée).

### D-02 — Le statut d'index dépend d'une découverte complète du système de fichiers et d'un décodage complet du snapshot actif

- **Qualification** : RISQUE (performance et disponibilité) ; AMÉLIORATION.
- **Priorité proposée** : P2. `minos_index_status` est l'outil que les agents interrogent le plus, et il est censé répondre « sans bail ni écriture » (P1, ADR 0039 (l)). Il fait pourtant un parcours intégral du projet et un décodage intégral du snapshot à chaque appel.
- **Preuves** :
  - `minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java:94-101` : `indexStatus` appelle `projects.inspectProject`.
  - `ProjectInspectionService.java:132-147` : `view()` fait `discoveryService.discover(project.rootPath())` (`:139`), uniquement pour calculer `languages`, `buildSystems` et `moduleCount`. Puis `reconciler.observeStatus` (`:147`), puis `stateStore.listRuns` (`:158`).
  - `ProjectIndexStateReconciler.java:179-186` : `loadActive` appelle `snapshotStore.loadActiveKnowledge`, deux fois par tentative (`:79-81`). Voir D-03 pour le coût.
- **Comportement actuel** : un appel de statut = marche récursive de tout l'arbre (y compris les répertoires ignorés, voir D-01/D-11), avec 12 à 25 ouvertures de fichiers marqueurs par répertoire dont la plupart lèvent une `NoSuchFileException` (`DefaultDiscoveryPlugins.java:200-208`), plus le décodage complet du snapshot. Une erreur de découverte (budget dépassé, répertoire illisible) fait échouer le statut.
- **Comportement attendu + source** : `ProjectIndexStateReconciler` (Javadoc `:25-34`) et ADR 0039 (l) : une lecture de statut est légère et indépendante de l'état en vol. Découverte et statut d'index sont deux informations distinctes.
- **Cause** : `ProjectView` agrège des faits « structure » (découverte) et « index » (état) dans un seul appel synchrone.
- **Impact** : latence de plusieurs secondes sur gros projets pour un simple statut ; échec du statut quand la découverte échoue ; charge FS pendant qu'une indexation tourne.
- **Correction minimale proposée** :
  - Rendre la découverte optionnelle ou paresseuse dans `view()` pour le statut (par exemple une méthode `indexStatus(project)` qui ne calcule ni langues ni modules), ou réutiliser les langues et modules du dernier run (`IndexingRun`).
  - Capturer l'échec de découverte et dégrader `languages` et `modules` plutôt que d'échouer.
  - Pour le décodage : voir D-03.
- **Validation (test proposé)** :
  - `minos-bootstrap/src/test/java/com/minos/bootstrap/application/`, à côté de `ProjectStatusReadIsLeaseFreeTest`.
  - Projet enregistré dont la racine contient un répertoire illisible, ou un `DiscoveryService` d'application injecté qui lève : `inspectProject` doit répondre avec l'état (actuellement échec).
  - Variante de comptage : `FileSymbolSnapshotStore.cacheStats().fullSnapshotLoads()` après `observeStatus` sur un store neuf (actuellement ≥ 1).
- **Dépendances** : D-01, D-03.

### D-03 — Observer le snapshot actif décode le snapshot entier ; au-delà de 512 MiB de poids estimé, aucun cache : au moins six décodages complets par `minos index`

- **Qualification** : AMÉLIORATION (apparentée à A9, mais l'API d'observation est le point précis).
- **Priorité proposée** : P2. Il n'y a pas d'erreur de correction, mais le coût croît avec la taille du snapshot et se paie à chaque lecture d'état.
- **Preuves** :
  - `ScipProjectSnapshotLifecycle.java:156-160` : `observeActiveSnapshot` = `activeStore.loadActiveKnowledge(...)`. Cela passe par `FileSymbolSnapshotStore.java:158-160` → `buildQueryView` (`:269-283`) : lecture entière, SHA-256, décodage, index mémoire.
  - `FileSymbolSnapshotStore.java:246-250` : une vue dont le poids estimé dépasse `maxQueryCacheWeightBytes` (512 MiB par défaut, `:41`) n'est pas mise en cache. Le poids vaut `max(symboles×1 Ko + occurrences×640 o + relations×1 Ko, taille×8)` (`:361-369`) : à partir d'environ 64 MiB de fichier.
  - `AuthoritativeProjectStateReconciler.java:113,119,155,211` : 2 à 4 `observe` par appel.
  - Chemin `minos index` sans erreur : `ProjectIndexStateReconciler.reconcile` (2 chargements, `ProjectIndexStateReconciler.java:79-81`) + `validatePlanStillCurrent` (2, `IndexingLifecycleService.java:176`) + `reconcilePreviousState` (2, `IndexingRunExecutor.java:263`) = 6, sans compter `stage`/`promote` ni `ScipSymbolSnapshotImporter.java:91`.
- **Comportement attendu + source** : `AuthoritativeProjectStateReconciler` n'a besoin que de l'identifiant du snapshot actif ; ADR 0006 : « ancien snapshot disponible », sans surcoût proportionnel à sa taille. L'intégrité (checksum) est vérifiée par `buildQueryView`, mais pas besoin de la refaire 6 fois par run.
- **Cause** : `CodeKnowledgeSnapshotStore` (`minos-engine/.../store/CodeKnowledgeSnapshotStore.java:21-31`) n'offre aucune lecture du descripteur de pointeur, pourtant disponible via `ActiveSnapshotRepository.read` (`:41-52`).
- **Correction minimale proposée** : ajouter à `CodeKnowledgeSnapshotStore` une méthode `Optional<SnapshotDescriptor> activeDescriptor(UUID)` (le `FileSymbolSnapshotStore` l'implémente avec `activeSnapshotRepository.read`) et l'utiliser dans `observeActiveSnapshot` et `ProjectIndexStateReconciler.loadActive`. Garder le chargement complet pour les requêtes. Option : vérifier le checksum une seule fois par run.
- **Validation (test proposé)** :
  - `minos-storage-local/src/test/java/com/minos/storage/local/store/` (constructeur package-private `new FileSymbolSnapshotStore(root, 32, 1L)`).
  - Publier un snapshot, puis 6 appels à `loadActiveKnowledge` : `cacheStats().fullSnapshotLoads() == 6` (cache désactivé de fait).
  - Test de non-régression après correction : `ScipProjectSnapshotLifecycle.observeActiveSnapshot` ne doit incrémenter ni `fullSnapshotLoads` ni `queryViewBuilds`.
- **Dépendances** : A9 (connu), D-02.

### D-04 — Une reprise est « offerte » et affichée pour des indexeurs qui ne déclarent pas `RESUMABLE_ARTIFACT`

- **Qualification** : DÉFAUT CONFIRMÉ (capability-honesty).
- **Priorité proposée** : P2. L'invariant est central dans le projet (config.yaml : « une capacité absente [...] n'est jamais présentée comme acquise »), l'information est visible dans la CLI et dans MCP, et elle est fausse.
- **Preuves** :
  - `IndexingRun.java:133-135` : `offersResume()` ne regarde que le format, les points de contrôle présents et le snapshot préparé, jamais la capacité de l'indexeur.
  - `IndexingRunExecutor.java:330-336` : un point de contrôle est enregistré pour **tout** indexeur ; `artifactPolicy` n'est consulté qu'au `plan` (`:61`, puis `IndexingResumePlanner.java:229`, `qualifies(...)`). `grep qualifies(` : une seule utilisation en production.
  - `AuthoritativeProjectStateReconciler.java:264-271` et `IndexingRunExecutor.java:546` : le run devient `INTERRUPTED` avec « resumable targets=N/M », et le marqueur `.resumable` est posé.
  - Affichage : `ResumableRunSummary.java:60-69` compte les points de contrôle présents (`:62-66`) sans policy ; `ProjectCommand.java:214-215` et `ProjectJson.java:71` l'exposent ; `LocalAutonomousIndexOperations.java:188` : « was interrupted and can be resumed ».
  - Mais seul `scip-java` déclare `RESUMABLE_ARTIFACT` (`ScipIndexerCatalog.java:88`) ; `scip-typescript`, `scip-python` et les providers M24 la déclarent `UNSUPPORTED` (ADR 0039 écart (h)).
- **Comportement actuel** : un projet TypeScript, Python ou polyglote interrompu affiche « resumableTargets: 3/3 » et « can be resumed ». Le planificateur refuse ensuite systématiquement (`IndexingResumePlanner.java:177` : « no reusable target in the interrupted run »), supplante le run en FAILED et refait un run complet. En attendant, `.resumable` retient jusqu'à 7 jours un répertoire de run pouvant peser des centaines de Mio (`RunDirectoryRetention.java:391-393`). `--resume-only` échoue avec la raison.
- **Comportement attendu + source** : ADR 0039 §3 et écart (h) : « `ResumableArtifactPolicy` dérive de cette capacité » ; ADR 0039 §6 : « la reprise est explicite et visible » ; pas de fausse promesse.
- **Cause** : la règle « offre de reprise » vit à deux endroits (`offersResume` et le planificateur) et seul le second applique la policy.
- **Impact** : fausse information utilisateur et agent, disque retenu sans bénéfice, avertissements WARNING à chaque relance (`IndexingResumePlanner.java:143-146`).
- **Correction minimale proposée** :
  - Passer la `ResumableArtifactPolicy` et les descripteurs aux endroits qui décident l'offre. Au minimum : `IndexingRunExecutor.checkpoint(...)` ne persiste pas de point de contrôle pour un indexeur non qualifié (la cible reste « non reprenable », comme un point de contrôle retenu).
  - Alternative : `ResumableRunSummary.resumableTargets` ne compte que les cibles qualifiées.
  - Si aucune cible n'est reprenable, ne pas poser le marqueur et finaliser en FAILED (ou conserver INTERRUPTED « diagnostic » sans le présenter comme reprenable).
- **Validation (test proposé)** :
  - `minos-engine/src/test/java/com/minos/orchestration/IndexingResumeTest.java` (fixtures existantes : exécuteur factice, stager et promoteur factices, store en mémoire).
  - Indexeur sans `RESUMABLE_ARTIFACT`, stager qui lève `InterruptedException` après le premier provider, `new IndexingLifecycleService(executors, stager, promoter, store, markers, ResumableArtifactPolicy.DEFAULT)`.
  - Attendu : pas de run offert (`ResumableRunSummary.of(store, projectId)` vide, pas de marqueur). Actuellement INTERRUPTED + offert.
- **Dépendances** : ADR 0039 écart (h).

### D-05 — La capture d'empreinte post-run n'est pas protégée : une erreur d'E/S rapporte un échec alors que le snapshot est déjà promu

- **Qualification** : DÉFAUT CONFIRMÉ pour le chemin ; le déclencheur est environnemental (fichier verrouillé sous Windows, droits retirés, fichier disparu, budget dépassé pendant l'indexation).
- **Priorité proposée** : P2. L'état disque est cohérent (le snapshot est promu), mais la commande échoue, la baseline n'est pas publiée, la synchronisation sémantique et la compaction sont sautées, et le message suggère que rien n'a eu lieu.
- **Preuves** :
  - `LocalAutonomousIndexOperations.java:186-200` : un run non `SUCCEEDED` sort à la ligne 186 ; au-delà, le run est `SUCCEEDED` et le snapshot promu, puis `fingerprintService.capture(...)` à la ligne 200 hors de tout `try`. `executeLocked` déclare `throws Exception` : l'`IOException` remonte.
  - `ProjectFingerprintService.java:103-133,320-335` : la capture lit tous les octets de tous les fichiers visibles ; un fichier qui disparaît ou est verrouillé lève `NoSuchFileException` / `FileSystemException`.
  - Le même motif existe dans `IncrementalIndexingCoordinator.java:121` (non branché en production, voir carte).
- **Comportement actuel** : `minos index` sort en erreur après avoir promu le snapshot. L'utilisateur relance : `NO_CHANGES` impossible (pas de baseline pour ce snapshot, `BASELINE_INDEX_MISMATCH`), donc un FULL complet inutile.
- **Comportement attendu + source** : ADR 0014 « le fallback complet est une propriété de sûreté, pas un échec » ; ADR 0006 / M1 : le run réussi est un succès. Un échec de publication de baseline est déjà traité en diagnostic ailleurs : `IncrementalIndexingCoordinator.java:149-160`.
- **Cause** : la capture « after » est traitée comme une étape qui ne peut pas échouer ; la publication de la baseline est « traitée » (`LocalAutonomousIndexOperations.java:205-216`) mais relance l'`IOException` à la ligne 215 : même défaut, un échec de publication après promotion fait aussi échouer la commande.
- **Impact** : succès rapporté comme échec ; synchronisation sémantique et compaction sautées ; FULL inutile au prochain run.
- **Correction minimale proposée** : entourer la capture « after » (et la publication) d'un `try/catch IOException | RuntimeException` qui produit `fingerprintPromoted=false` et un `diagnostic` assaini, puis poursuit sémantique et compaction. Le run réussi est rapporté `SUCCEEDED`.
- **Validation (test proposé)** :
  - `minos-cli/src/test/java/com/minos/cli/`, à côté de `RetentionFailureAfterIndexTest` (même motif : `MinosApplication.builder(home).providerRuntimeManager(...)` + `snapshotLifecycle(fakeStager, fakePromoter)` + décorateur d'exécuteur `LocalAutonomousIndexOperations(application, decorator)`).
  - Le décorateur rend un fichier source illisible (permissions POSIX ou ACL via un helper comme `UnreadableFile`) après le provider.
  - Attendu : `view.status() == "SUCCEEDED"`, `fingerprintPromoted == false`, diagnostic non vide. Actuellement exception.
- **Dépendances** : D-06.

### D-06 — TOCTOU : la découverte précède l'empreinte, donc une structure créée entre les deux est promue en baseline sans avoir été indexée

- **Qualification** : DÉFAUT CONFIRMÉ (ordre des opérations) ; fenêtre étroite.
- **Priorité proposée** : P3. L'effet est un « NO_CHANGES » faux, mais la fenêtre est courte (durée de la capture d'empreinte) et le prochain changement de fichier déclenche un FULL qui répare.
- **Preuves** :
  - `LocalAutonomousIndexOperations.java:314-315` : `discover(...)` puis `fingerprintService.capture(...)`.
  - `IncrementalIndexingCoordinator.java:85-86` : même ordre.
  - Les portées d'exécution viennent de la découverte (`IndexingLifecyclePlanSupport.scopedTargets`, `IndexerExecutionScopeResolver.resolve`) et non d'un nouveau parcours au moment du run.
  - La comparaison `stable = before.equals(after)` (`LocalAutonomousIndexOperations.java:201`) prouve l'absence de changement après `before`, pas depuis la découverte.
- **Comportement actuel** : un module créé (`pom.xml`, `package.json`) après que la découverte a visité son répertoire mais avant que l'empreinte ne le lise est présent dans `before` et `after` (stable) mais absent des portées. La baseline le déclare couvert ; les runs suivants répondent `NO_CHANGES` tant qu'aucun fichier ne change.
- **Comportement attendu + source** : ADR 0014 : les empreintes décrivent ce qui a été indexé ; une indexation « conservatrice ».
- **Cause** : l'invariant « baseline = état indexé » suppose que la structure découverte est celle de l'empreinte ; l'ordre inverse (empreinte d'abord, découverte ensuite) rendrait l'écart visible (`after != before` si quelque chose apparaît entre les deux).
- **Impact** : module non indexé et non signalé jusqu'au prochain changement.
- **Correction minimale proposée** : capturer l'empreinte `before` **avant** la découverte, dans `prepare` et `refreshLocked` (deux lignes inversées). Aucune autre modification.
- **Validation (test proposé)** : `minos-cli/src/test/java/com/minos/cli/`. Injecter via `MinosApplication.builder(home)` un service de découverte qui crée un second module après avoir rendu sa découverte (si le builder n'expose pas le service de découverte, passer par une sous-classe de test de `ProjectDiscoveryService`). Après un run complet : la baseline ne doit pas être promue (`fingerprintPromoted == false`).
- **Dépendances** : D-05.

### D-07 — Deux chemins écrivent un état `READY` sans clore l'offre de reprise (même motif que R7, non corrigé ici)

- **Qualification** : DÉFAUT CONFIRMÉ (code, aucun test ne couvre le cas) ; conséquence limitée.
- **Priorité proposée** : P3. Fuite bornée : run `INTERRUPTED` orphelin et marqueur `.resumable` retenant le répertoire de run jusqu'à 7 jours (`RunDirectoryRetention.java:391-393`) ; aucun résultat faux.
- **Preuves** :
  - `ProjectIndexStateReconciler.java:160-178` (`repairedState`, appelé en mode `PERSIST` à `:101`) : construit un `ProjectIndexState` à six arguments (donc `resumableRunId` vide, contraint par `ProjectIndexState.java:44-47` pour READY) **sans** `supersede` ni `unmark`. L'équivalent moteur le fait (`AuthoritativeProjectStateReconciler.java:181-183`, `endResumeOffer`, correctif R7).
  - `LocalProjectOperations.java:122-127` (`importScipLocked`) : écrit `READY`, `latestRunId` vide et pas de `resumableRunId`, sans réconcilier ni clore un run offert.
  - Atteignable : état `STALE` avec `resumableRunId=R` et snapshot autoritaire différent de l'état persisté (crash de `import-scip` entre la publication du snapshot et l'écriture de l'état ; ou import explicite après une interruption).
- **Comportement attendu + source** : `AuthoritativeProjectStateReconciler.endResumeOffer` (Javadoc `:328-332`) : « The one place that ends an offer for a current project, whichever recovery path made the project current » ; ADR 0039 écart (d) : « un projet n'offre jamais plus d'un run à la reprise ».
- **Cause** : deux réconciliateurs aux sémantiques parallèles (moteur et application) ; R7 n'a été corrigé que dans le premier.
- **Correction minimale proposée** : exposer `AuthoritativeProjectStateReconciler.endResumeOffer` (ou un point équivalent côté `IndexStateStore` + `ResumableRunMarkers`) et l'appeler dans `ProjectIndexStateReconciler.repairedState` (mode `PERSIST`) et dans `importScipLocked` avant d'écrire `READY`. À défaut, faire déléguer l'application au réconciliateur moteur.
- **Validation (test proposé)** :
  - `minos-bootstrap/src/test/java/com/minos/bootstrap/application/ProjectIndexStateReconcilerTest.java` (aucun test ne mentionne `resumable` ou `INTERRUPTED`, vérifié).
  - État `STALE(resumable=R)` + run R `INTERRUPTED` + snapshot actif ≠ état persisté. `reconcile(projectId)` doit finaliser R en FAILED (« superseded ») et retirer son marqueur. Actuellement R reste INTERRUPTED.
- **Dépendances** : R7 (clos côté moteur).

### D-08 — Observation `UNSUPPORTED` + bail exclusif : un état `INDEXING` laissé par un run mort n'est jamais récupéré (blocage permanent)

- **Qualification** : RISQUE. Non atteignable avec le promoteur de production (`ScipProjectSnapshotLifecycle` implémente l'observation), mais atteignable via l'API publique `MinosApplication.Builder.snapshotLifecycle(stager, promoter)` avec un `SnapshotPromoter` qui n'implémente que `promote` (interface fonctionnelle).
- **Priorité proposée** : P3 (P2 si l'API d'assemblage est considérée comme un point d'extension supporté).
- **Preuves** :
  - `IndexingRuntimePorts.java:100-102` : le défaut est `UNSUPPORTED`.
  - `AuthoritativeProjectStateReconciler.java:113-117` : sur `UNSUPPORTED`, retourne `Decision.resolved(persisted)` **avant** `recoverIfRequired` (`:122`), donc même sous bail exclusif (dont le Javadoc `:38-43` dit qu'un RUNNING est abandonné par définition).
  - `IndexingRunExecutor.java:270-273` : l'état persisté `INDEXING` ou `REFRESHING` lève « project already has an indexing run in progress », pour toujours.
  - Test existant : `AuthoritativeProjectStateReconcilerTest.java:138-149` couvre uniquement la variante non exclusive avec un état `READY`.
- **Comportement attendu + source** : l'ADR 0039 et le Javadoc `:38-43` ci-dessus : sous bail exclusif, tout RUNNING est abandonné ; les runs doivent être finalisés (au minimum FAILED/INTERRUPTED) même sans autorité observable.
- **Correction minimale proposée** : sous bail exclusif et observation `UNSUPPORTED`, exécuter `recoverAbandonedRuns` avec `active = unsupported` (jamais « committed ») et ramener `INDEXING` à `FAILED`/`STALE`. À défaut, documenter et faire échouer `Builder.build()` si le promoteur ne supporte pas l'observation.
- **Validation (test proposé)** : `minos-engine/src/test/java/com/minos/orchestration/AbandonedIndexingLifecycleRecoveryTest.java` : promoteur lambda sans observation, run RUNNING + état INDEXING persistés, appel `IndexingLifecycleService.execute(...)`. Attendu : récupération. Actuellement `IllegalStateException`.
- **Dépendances** : aucune.

### D-09 — Une `Error` (OOM, `StackOverflowError`) pendant le staging laisse run `RUNNING` et projet `INDEXING` sans cause

- **Qualification** : AMÉLIORATION. L'état est récupérable au prochain run (même chemin qu'un kill), mais la cause est perdue et le statut ment (« INDEXING ») jusqu'au run suivant.
- **Priorité proposée** : P3.
- **Preuves** :
  - `IndexingRunExecutor.java:96` : `catch (Exception failure)` ; idem `:171`. Une `OutOfMemoryError` à `stage()` n'est pas interceptée.
  - `ScipProjectSnapshotLifecycle.java:61-95` : le staging charge les trois collections en mémoire (A9/A6).
  - `ProjectIndexStateReconciler.observeStatus` rapporte l'état en vol tel quel (Javadoc `:62-67`).
- **Comportement attendu + source** : ADR 0039 (« INTERRUPTED distingue enfin "le processus est mort" de "l'indexation a échoué" ») ; un échec explicite doit laisser une trace de cause.
- **Correction minimale proposée** : intercepter `OutOfMemoryError | StackOverflowError` (et `LinkageError`) autour du staging, persister `FAILED` avec un message de classe uniquement, puis relancer l'`Error`.
- **Validation (test proposé)** : `minos-engine/src/test/java/com/minos/orchestration/IndexingInterruptionTest.java` : stager qui lève `OutOfMemoryError` ; attendu run FAILED avec la classe dans le message, état `STALE`/`FAILED`.
- **Dépendances** : A9.

### D-10 — Sémantique d'ignore divergente de Git sur Windows : BOM UTF-8 et casse

- **Qualification** : DÉFAUT CONFIRMÉ (BOM, par lecture de code ; aucun traitement du BOM dans `minos-engine/src/main`, `grep` vide) ; RISQUE (casse).
- **Priorité proposée** : P3. Effet limité à la première règle (BOM) et aux répertoires durcis ou ignorés à casse différente sous NTFS ; non bloquant.
- **Preuves** :
  - `ProjectIgnoreRules.java:138-148` : lecture UTF-8 sans retrait du BOM U+FEFF. `String.strip()` ne l'enlève pas (non blanc pour Java), donc la première ligne devient `﻿build/`, règle qui ne correspond à rien. Git, lui, ignore le BOM de `.gitignore`. Un `.gitignore` produit par un éditeur Windows (Visual Studio, Notepad) en UTF-8 avec BOM perd sa première règle.
  - `ProjectIgnoreRules.java:45-46,115-120,199` : noms durcis comparés avec `Set.contains` exact, et `Pattern.compile` sans `CASE_INSENSITIVE`. `Target/`, `Node_Modules/` ou `Build/` (règle `build/`) ne sont pas ignorés sur NTFS, alors que Git avec `core.ignorecase=true` les ignore.
- **Comportement attendu + source** : `ProjectIgnoreRules` (Javadoc `:21-27`) : « preserve the historical MINOS contract » ; config.yaml : « Tout changement doit tenir sur [...] Windows Server 2022 ». (AUDIT-2026-09 A10 traite des limites de chemins Windows, pas de la sémantique d'ignore.)
- **Correction minimale proposée** : retirer un éventuel `﻿` en tête de la première ligne de chaque fichier d'ignore ; sous Windows (`FileSystem` insensible à la casse), comparer les noms durcis sans casse et compiler les motifs avec `CASE_INSENSITIVE`. Test de non-régression sur Linux inchangé.
- **Validation (test proposé)** : `minos-engine/src/test/java/com/minos/discovery/ProjectIgnorePolicyTest.java` : `.gitignore` = `"﻿generated/\nout2/\n"` en UTF-8 : `isIgnored(Path.of("generated/X.java"), false)` doit être vrai (actuellement faux).
- **Dépendances** : A10.

### D-11 — Les budgets (100 000 fichiers, 2 Gio) comptent tous les fichiers non ignorés, binaires inclus, et l'échec est sans issue actionnable

- **Qualification** : DÉCISION À CLARIFIER.
- **Priorité proposée** : P2 (bloquant pour les dépôts avec gros fichiers non ignorés ; contournement connu des seuls mainteneurs).
- **Preuves** :
  - `SourceBudgetPolicy.java:9-11` : 100 000 fichiers, 2 Gio, traversée à 8× le nombre de fichiers.
  - `ProjectFingerprintService.java:124-130,320-335` : l'empreinte hache tous les octets de tous les fichiers réguliers non ignorés (assets, jeux de données, archives, `.jar`…).
  - `ProjectIgnorePolicy.java:157-170` : la découverte comptabilise la taille de chaque fichier non ignoré (`accountRegularFile`).
  - `SourceBudgetPolicy.java:99-104` : message « exceeds source budget: files=…, bytes=… », sans mention de `.minosignore` (aucune occurrence dans la documentation consultée : `grep` sur `docs/*.md` et `README.md` vide).
- **Comportement attendu + source** : config.yaml « capability-honest » : une limite doit être déclarée avec son remède ; ADR 0014 : l'empreinte est « bornée ». À trancher : fichiers binaires et volumineux comptés comme sources, ou classés par extension/langage ?
- **Correction minimale proposée** : message d'erreur actionnable (premier répertoire contributeur, `.minosignore`) ; option de ne hacher que les fichiers pertinents (sources reconnues + descripteurs) ; ne pas descendre dans les répertoires ignorés (voir D-01).
- **Validation (test proposé)** : `ProjectFingerprintServiceTest` avec `SourceBudgetPolicy(10, 1024)` et un fichier de 2 Kio ignoré par `.minosignore` : doit passer ; non ignoré : message citant `.minosignore`.
- **Dépendances** : D-01.

### D-12 — `NO_CHANGES` silencieux face aux liens symboliques et aux répertoires durcis

- **Qualification** : DÉCISION À CLARIFIER (limite documentée dans le code, non exposée à l'utilisateur).
- **Priorité proposée** : P3.
- **Preuves** :
  - `ProjectFingerprintService.java:107-108,117-121` : les liens symboliques et les jonctions ne sont pas parcourus ; Javadoc `:69-73` (limite assumée) : ni répertoires durcis (`target/`, `node_modules/`, `dist/`, `out/`), ni dépendances externes, ni liens.
  - Les providers, eux, suivent ces liens (Maven, tsc).
  - `LocalAutonomousIndexOperations.java:150-158` : sans changement, répond `NO_CHANGES` sans caveat.
- **Comportement attendu + source** : config.yaml « capability-honest ».
- **Correction minimale proposée** : à défaut de suivre les liens, compter les liens et jonctions rencontrés et les inclure au diagnostic d'un `NO_CHANGES` (« N liens non suivis »).
- **Validation (test proposé)** : `ProjectFingerprintServiceTest` : projet avec un lien vers un répertoire de sources ; attendu : diagnostic de couverture partielle.
- **Dépendances** : aucune.

### D-13 — La reprise ne fige ni la version de MINOS ni celle de l'importateur : un snapshot préparé périmé peut être promu tel quel

- **Qualification** : RISQUE. Interruption puis mise à jour de MINOS dans les 24 h.
- **Priorité proposée** : P3.
- **Preuves** :
  - `IndexingRunExecutor.java:147-148,158-160` : en `promoteOnly`, le snapshot préparé par la tentative interrompue est promu sans être reconstruit.
  - `IndexingRun.ExecutionCheckpoint` (`IndexingRun.java:239-248`) enregistre la version du provider, jamais celle du moteur ni du format d'import.
  - `runFormatVersion` ne couvre que le format du run.
  - `ScipProjectSnapshotLifecycle.promote` (`:104-115`) republie `staged.symbols()/occurrences()/relationships()` tels quels.
- **Comportement attendu + source** : ADR 0039 §3 : « au moindre doute, on réindexe entièrement ».
- **Correction minimale proposée** : inclure un identifiant de version du moteur (ou un hash du code d'import/normalisation) dans le point de contrôle ou la clé de cible, et refuser la reprise en cas de différence.
- **Validation (test proposé)** : `IndexingResumePlannerTest` : point de contrôle avec version d'importateur différente ; attendu `Refused`.
- **Dépendances** : aucune.

### D-14 — Deux sources de vérité pour les capacités : `IndexerDescriptor.capabilities` (négociation) et `ProviderCapabilityProfile` (public)

- **Qualification** : AMÉLIORATION (latent : aucun appelant de production n'exige de capacité autre que `SYMBOLS` et `REFERENCES`).
- **Priorité proposée** : P3.
- **Preuves** :
  - `IndexerRegistry.java:109-112` : la négociation utilise le `Set` positif du descripteur.
  - `ScipIndexerCatalog.java:79-88` (descripteur `scip-java`) n'inclut ni `UNRESOLVED_REFERENCES` ni `STRUCTURAL_RELATIONS`, que son profil (`:232-238`) déclare `PARTIAL`.
  - `scip-typescript` : profil `UNRESOLVED_REFERENCES = FULL` (`:262+`), absent du descripteur (`:105-112`).
  - `ProviderConformanceKit.java` ne vérifie pas la cohérence entre les deux.
  - `IndexingRequirements.baseline()` (`:25-29`) exige seulement `SYMBOLS` et `REFERENCES`.
- **Comportement attendu + source** : ADR 0008 §3 (« une capacité signifie "scénario observé/qualifié" ») et ADR 0026 (profils explicites exhaustifs) : la négociation et la publication doivent dériver de la même table.
- **Correction minimale proposée** : dériver `IndexerDescriptor.capabilities` de `profile.usable(cap, false)` (FULL/PARTIAL) dans le catalogue, ou ajouter à `ProviderConformanceKit` un contrôle d'égalité (`descriptor.capabilities() == {c : profile.usable(c, allowExperimental=false)}`).
- **Validation (test proposé)** : `ProviderConformanceKitTest` : pour chaque fournisseur du catalogue M24, `descriptor.capabilities()` égal aux capacités `FULL`/`PARTIAL` du profil. Échoue aujourd'hui pour `scip-java`, `scip-typescript` et `scip-python`.
- **Dépendances** : ADR 0008, 0026.

---

## Questions tranchées « sans défaut » (vérifiées, pour éviter les doublons)

- **Réentrance et ordre des baux.** `FileIndexStateStore.acquireProjectLease` (`:90-108`) est réentrant par thread (`ThreadLocal<Map<UUID, HeldProjectLease>>`). La seconde acquisition ne rouvre donc pas un `FileChannel` sur le même fichier (qui bloquerait par `OverlappingFileLockException`, `BoundedFileLease.java:151-157`). Les appelants de `acquireProjectLease` et de `SnapshotProjectLease.acquire` (relevés par `grep`) respectent l'ordre L1 puis rétention puis mutation de snapshot ; aucune acquisition inverse trouvée. Les blocages par rayures JVM (`SnapshotProjectLease.java:31`) sont bornés à 10 s, jamais indéfinis.
- **Interruption.** `BoundedFileLease.acquireJvmLock` et `LeaseDeadline.pauseBeforeRetry` rétablissent le drapeau et enchaînent la cause ; `IndexingRunExecutor.persistTerminalFailure` efface puis rejoue le drapeau (`:540-554`).
- **Atomicité de la promotion.** `ScipProjectSnapshotLifecycle.promote` (`:104-120`) : `activeStore.publish` est le point de validation ; le nettoyage après commit est non fatal. `AuthoritativeProjectStateReconciler.recoverAbandonedRuns` (`:254-262`) récupère « promotion faite, métadonnées perdues ». Pas de fenêtre « nouveau Java / ancien TypeScript ».
- **Fichiers supprimés ou renommés.** Aucun fournisseur de production ne déclare `INCREMENTAL_INDEXING` (`ScipIndexerCatalog.java:244,264,282,327` : `UNSUPPORTED` ; `AbstractScipProcessPlanFactory.java:37` et `ScipJavaProcessPlanFactory.java:81` lèvent sur `INCREMENTAL`). Tout changement non nul donne donc `FULL` (`IncrementalIndexingPlanner.java:75-97`) ; un fichier supprimé ne persiste pas dans l'index. Le chemin incrémental (`ProjectInvalidationService`, `changedFiles`) est du code non exercé en production.
- **Reprise.** Vérifications confirmées : TTL, confinement physique, SHA-256, empreinte de scope, égalité des clés de cible (R4), revérification avant staging.
- **Fuites de ressources.** `ProcessIndexerExecutor` ferme ses sinks (try-with-resources, `:119-121`) et le suivi de processus (`:126`) ; `BoundedProcessOutput.Drainer` est un thread démon avec fermeture forcée (`:152-156`) ; `ProcessOwnershipTracker` s'arrête et se joint (`:107-117`). `DirectoryStream` et `Files.list` sont fermés partout où lus. Aucun `ExecutorService` dans le périmètre.

---

## (1) Carte réelle du périmètre

| Classe | Rôle | Dépend de | Tests | Lacunes |
|---|---|---|---|---|
| `LocalAutonomousIndexOperations` (`minos-cli`, 386 l.) | Point d'entrée réel de `minos index` : bail, `prepare` (découverte, empreinte, plan), exécution, empreinte post-run, sémantique, compaction | `IndexingLifecycleService`, `ProjectIndexStateReconciler`, `FileProjectFingerprintSnapshotStore` | `RetentionFailureAfterIndexTest`, `NoResumeIndexingTest`, `LocalAutonomousIndexOperations*Test` | D-05, D-06 : post-run non protégé, ordre découverte/empreinte |
| `IndexingLifecycleService` (`minos-engine`, 227 l.) | Façade : bail projet, plan, exécution, `recoverProjectState` | `IndexingRunExecutor`, `AuthoritativeProjectStateReconciler` | `IndexingLifecycle*Test`, `AbandonedIndexingLifecycleRecoveryTest` | — |
| `IndexingRunExecutor` (965 l.) | Machine à états du run, points de contrôle, reprise, échecs, interruption | `IndexingResumePlanner`, `ArtifactConfinement`, `ExecutionCheckpoints` | `IndexingResumeTest`, `IndexingInterruptionTest`, `InterruptedRunRecoveryTest`, `IndexingCommitRecoveryTest` | D-04, D-09 |
| `AuthoritativeProjectStateReconciler` (510 l.) | Réconciliation état projet / snapshot autoritaire, récupération des runs abandonnés | `IndexStateStore`, `SnapshotPromoter` | `AuthoritativeProjectStateReconcilerTest`, `IndexingPreRunReconciliationTest` | D-08 : branche `UNSUPPORTED` + bail exclusif sans test |
| `IndexingResumePlanner` (282 l.) | Plan de reprise, validation des cibles | `ExecutionCheckpoints`, `ArtifactConfinement` | `IndexingResumePlannerTest` | D-13 |
| `ProjectIndexStateReconciler` (`minos-application`, 237 l.) | Réconciliation côté application : `reconcile` / `observe` / `observeStatus` | `CodeKnowledgeSnapshotStore`, `IndexStateStore` | `ProjectIndexStateReconcilerTest`, `ProjectStatusReadIsLeaseFreeTest` | D-07 (aucun test `resumable`), D-03 |
| `ProjectInspectionService` (233 l.) | Vue de projet (statut, inventaire) | découverte, réconciliateur | `ProjectInventoryToleranceTest` | D-01, D-02 |
| `ProjectDiscoveryService`, `DefaultDiscoveryPlugins`, `ProjectIgnorePolicy`, `ProjectIgnoreRules`, `SourceBudgetPolicy` | Découverte, règles d'ignore, budgets | `FileTreeOperations`, `ConfinedFileOpener` | `ProjectDiscoveryServiceTest`, `UnreadableMarkerDiscoveryTest`, `ProjectIgnorePolicyTest` | Pas de test de répertoire illisible (D-01) ; BOM et casse (D-10) |
| `ProjectFingerprintService` (348 l.), `ProjectInvalidationService`, `IncrementalIndexingPlanner`, `IncrementalIndexingCoordinator` | Empreintes projet et scope, invalidation, plan ; **le coordinateur n'est pas appelé en production** (seulement par des tests) | `ProjectIgnorePolicy`, `SourceBudgetPolicy` | `ProjectFingerprint*Test`, `IncrementalIndexingCoordinatorTest` | D-01, D-11, D-12 |
| `FileIndexStateStore` (686 l.) | État projet et runs : propriétés, remplacement atomique durable, bail réentrant | `DurableAtomicFile`, `ProjectIndexLease` | tests de stockage | Un fichier de run illisible (enum inconnu, date invalide) fait échouer `listRuns`, donc l'indexation du projet (V4 ne couvre que la version de format) ; non retenu comme constat (cas de corruption ou de rétrogradation) |
| `ProjectIndexLease`, `BoundedFileLease`, `SnapshotProjectLease`, `LeaseDeadline`, `SharedCacheLeaseRegistry` | Baux inter-processus bornés (10 s), couche JVM | `FileChannel.tryLock` | tests de concurrence (relus 50 fois selon FIAB-SUIVI) | — |
| `FileProjectFingerprintSnapshotStore` (766 l.) | Baselines d'empreinte : publication et promotion, compaction, sous bail de mutation | `SnapshotProjectLease` | `…StoreTest`, `…ConcurrencyTest`, `…SymlinkTest` | — |
| `FileSymbolSnapshotStore`, `ActiveSnapshotRepository`, `SnapshotRepository`, `SnapshotRetentionService` | Snapshot actif : publication, pointeur, cache de vues | `SnapshotProjectLease` | tests de store | D-03 |
| `ScipProjectSnapshotLifecycle` (`minos-provider-scip`, 210 l.) | Staging et promotion SCIP, observation du snapshot actif | `FileSymbolSnapshotStore` | tests provider | R8 (confirmé et élargi), D-03 |
| `ProcessIndexerExecutor`, `RunDirectoryRetention`, `FileResumableRunMarkers` | Exécution de processus, rétention des répertoires de run, marqueurs | `ProcessOwnershipTracker`, `BoundedProcessOutput` | `FileResumableRunMarkersTest` et autres | — |
| `IndexerRegistry`, `ScipIndexerCatalog` | Négociation par capacités, catalogue | `IndexerDescriptor`, `ProviderCapabilityProfile` | `IndexerRegistryTest`, `ScipIndexerCatalogTest` | D-14 |

## (2) Ce que je n'ai PAS examiné

- Les contenus de `minos-runtime-local` hors `ProcessIndexerExecutor`, `ProcessOwnershipTracker`, `BoundedProcessOutput`, `RunDirectoryRetention`, `FileResumableRunMarkers` : bacs à sable (cgroup, AppContainer, Job Objects), `ProviderResidueReclamation`, `ProcessTreeTermination`, `ConfinedFileOpener` (comportement Windows des réparse points). R11, R13, R14 non examinés.
- `ScipSymbolSnapshotImporter` et le codec de snapshot (collisions d'identifiants, `putUnique`), l'indexation distante (`LocalRemoteIndexOperations`, `DistributedArtifactBundleStore`), le backend PostgreSQL (`ProjectMutationIndexStateStore`, verrous), le registre inter-processus (`InterProcessLocalProjectRegistry`) et la synchronisation sémantique (`SemanticIndexService`).
- Aucun test n'a été exécuté, aucun comportement du JDK vérifié par exécution : D-01 repose sur la lecture de `FileTreeWalker` (le répertoire est ouvert avant `preVisitDirectory`) ; le test proposé le confirmera. Les chemins Windows (ACL, partage, chemins > 260) n'ont pas été éprouvés ; le JDK ajoute le préfixe `\\?\` aux chemins absolus longs mais je n'ai pas relu `ConfinedFileOpener` sur ce point.
- Charge et concurrence réelles entre deux processus (seuls le code des baux et les tests existants ont été lus) ; aucune mesure de performance (D-02, D-03 sont des estimations de coût par lecture de code).

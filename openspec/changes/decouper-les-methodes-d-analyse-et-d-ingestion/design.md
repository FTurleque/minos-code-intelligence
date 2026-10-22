# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-07** (voir `proposal.md`, Why). HEAD relu : `5ce7b79b` (`develop` du 2026-10-22). Abréviations : `EXEC` = `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java` (984 lignes), `LIFE` = `minos-engine/src/main/java/com/minos/orchestration/IndexingLifecycleService.java`, `IMPACT` = `minos-application/src/main/java/com/minos/impact/ImpactAnalysisService.java` (316 lignes), `SCIP` = `minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionAdapter.java` (409 lignes).

## État vérifié au HEAD

### Méthode de mesure (approchée)

Script local non versionné : commentaires, chaînes et blocs de texte retirés ; une méthode est une déclaration `nom(...) [throws ...] {` hors en-tête de `record`, `class`, `interface`, `enum` (constructeurs compris) ; longueur = lignes physiques de la ligne du nom à l'accolade fermante ; points de décision = 1 + `if`, `for`, `while`, `case`, `catch`, `&&`, `||`, `?` ternaire. Sur `*/src/main/**` hors `minos-intellij` : **4 430 méthodes, 18 de 80 lignes ou plus**. Ni le chiffre de l'audit (40 sur 4 876, « accolades, hors records », `docs/project/audits/2026-10-10/findings.md:322`) ni celui d'une contre-vérification antérieure (24 sur 4 399) n'est reproduit ; la tâche 1.1 fixe une méthode écrite.

Méthodes de 80 lignes ou plus au HEAD : `SCIP:51` `ingest` 154 ; `IMPACT:84` `analyze` 126 ; `CodeSearchService.java:43` `search` 119 ; `TeamCommand.java:139` `operationTable` 112 (table) ; `MinosApplicationAssembler.java:40` `build` 108 ; `WindowsAppContainerWorkerSandboxBackend.java:255` `sandboxPlan` 108 ; `LocalIsolatedIndexWorker.java:153` `execute` 107 ; `GitIntelligenceService.java:99` `analyze` 101 ; `IndexerRegistry.java:60` `negotiate` 98 ; `LocalRemoteIndexOperations.java:130` `indexUnderSourceLease` 90 ; `ProjectInvalidationService.java:52` `assess` 88 ; `MinosCli.java:127` (constructeur) 87 ; `ArchitectureConcentrationService.java:26` `analyze` 85 ; `SemanticIndexService.java:116` `synchronize` 84 ; `ScipJavaProcessPlanFactory.java:227` `prepareWritableWorkspace` 84 ; `ScipSymbolNormalizer.java:81` `normalize` 82 ; `ProjectFingerprintService.java:74` `captureScope` 81 ; `ArchitectureDependencyService.java:36` `build` 81.

### AUD-QUA-07 — confirmé pour les trois méthodes du lot

- **`ingest`** (`SCIP:51-204`, 154 lignes, environ 10 points de décision) : enchaîne cinq étapes lisibles à l'œil — métadonnées de définition (`:59-63`), normalisation des symboles et `store.putSymbols` (`:65-97`), occurrences et `store.putOccurrences` (`:99-166`), relations fournies et dérivées et `store.putRelationships` (`:168-184`), rapport (`:186-203`). Ordre des effets observables : `putSymbols`, puis `putOccurrences`, puis `putRelationships`. Appelant de production unique : `minos-provider-scip/.../ingestion/ScipSymbolSnapshotImporter.java:98`.
- **`analyze`** : la longueur est portée par la surcharge **privée** `IMPACT:84-209` (126 lignes, environ 21 points de décision, 5 lignes de paramètres) ; les surcharges publiques `IMPACT:60` (vue de requête, index partagé de la vue — AUD-PERF-11) et `IMPACT:71` (snapshot seul, refus d'un identifiant de symbole dupliqué `:77-78`) font 9 et 12 lignes. Étapes de la surcharge privée : refus de la racine (`:90-97`), parcours inverse borné par file de priorité (`:99-172`), troncature et `MAX_RESULTS_REACHED` (`:174-181`), assemblage des impacts et des tests (`:183-208`). Appelant de production : `LocalProjectImpactQuery` (`minos-application/.../impact/LocalProjectImpactQuery.java:45`, surcharge vue de requête).
- **`execute`** (`EXEC:48-104`, 57 lignes) : le défaut est l'arité, pas la longueur. **16 paramètres** : `projectId`, `projectRoot`, `targets`, `mode`, `changedFiles`, `executors`, `stager`, `promoter`, `stateStore`, `markers`, `artifactPolicy`, `resumePolicy`, `moduleAssignment`, `clock`, `fingerprints`, `rootScopeFingerprint`. Ordre des validations : `resumePolicy` (`EXEC:58`), racine (`validateExecutionRoot`, `EXEC:59`), puis le constructeur de `Ports` (`EXEC:60-61`), qui vérifie `fingerprints`, `rootScopeFingerprint`, `executors`, `stager`, `promoter`, `stateStore`, `markers`, `moduleAssignment`, `clock` dans cet ordre (`EXEC:870-878`).
- **`EXEC:858-880`** : `private record Ports(executors, stager, promoter, stateStore, markers, moduleAssignment, clock, fingerprints, rootScopeFingerprint)`. Il ne porte ni `artifactPolicy` ni `resumePolicy`.
- **Appelant unique** : `LIFE:209-212`, dans `run` (`LIFE:201-214`) sous le bail exclusif du projet, après `Objects.requireNonNull(policy, "policy")` (`LIFE:206`). Huit des seize arguments sont des champs `final` du service, tous vérifiés non nuls à la construction (`LIFE:24-31`, `:93-106`) : `executors`, `stager`, `promoter`, `stateStore`, `markers`, `artifactPolicy`, `clock`, `fingerprints`. Les huit autres sont propres au run (`projectId`, `projectRoot`, `targets`, `mode`, `changedFiles`, `resumePolicy`, `moduleAssignment`, `rootScopeFingerprint`) ; seul `moduleAssignment` n'est vérifié non nul que par `Ports`.
- **Autres usages de la classe** : `IndexingResumePlanner.java:235` appelle `IndexingRunExecutor.scopedChangedFiles` ; `IndexingRunExecutorTest` appelle `awaitReadable`. Aucun n'est touché.

### Tests existants qui traversent les méthodes (relus, couverture non mesurée)

- `analyze` : `ImpactAnalysisServiceTest` (`MAX_DEPTH_REACHED` `:131`, `MAX_RESULTS_REACHED` `:154`, `GENERATED_SYMBOLS_NOT_TRAVERSED` `:190`), `ImpactIndexReuseTest` (égalité des deux chemins, ordre compris), `ImpactReferenceReportsTest` et `AdvancedImpactReferenceReportsTest` contre les rapports de référence `minos-application/src/test/resources/impact/reference-reports.txt` (120 lignes) et `advanced-reference-reports.txt` (80 lignes), `minos-app/.../impact/ImpactAnalysisRealFixtureTest`. **Aucun test n'affirme** les refus `unknown symbol in snapshot` (`IMPACT:92`), `impact root must be a local non-generated symbol` (`:95-96`), `duplicate symbol id in snapshot` (`:78`) ni la limitation `EXTERNAL_TARGETS_NOT_TRAVERSED` (`:130`) : recherche de ces chaînes dans `*/src/test` sans résultat pour `ImpactAnalysisService`.
- `ingest` : `ScipIngestionAdapterTest` (six cas), `ScipRelatedTestIngestionTest`, `ScipRelatedTestRealFixtureTest`, `ScipSymbolSnapshotImporterBehaviorTest` ; `ScipRealIndexExperiment` et `InMemoryBackendBenchmark` sont des expériences, pas des preuves. `ScipIngestionAdapterTest` n'affirme `skippedSymbolCount` et `skippedOccurrenceCount` qu'à `0` (`:78`, `:82`, `:205`) : les branches « occurrence sans symbole » (`SCIP:108-111`), « plage non convertible » (`:118-121`) et « symbole non normalisé » (`:92-93`) ne sont pas affirmées à une valeur non nulle.
- `execute` : `IndexingLifecycleServiceTest`, `IndexingResumeTest` (dont `RESUME_ONLY`), `IndexingRunExecutorCheckpointTest`, `IndexingInterruptionTest`, `IndexingCommitRecoveryTest`, `IndexingPreRunReconciliationTest`, `IndexingExecutionPathContainmentTest`, `RunDirectoryHoldTest`, `StagedSnapshotIdValidationTest`, `InterruptedRunRecoveryTest`, `AbandonedIndexingLifecycleRecoveryTest`, `IndexingLifecycle{Incremental,ScopedExecution}Test` (`minos-engine`) ; `minos-bootstrap` (`InterruptionDuringIndexingTest`, `FileAuthoritativeSnapshotRecoveryTest`) ; `PostgresAuthoritativeSnapshotConsistencyTest` (`minos-storage-postgresql`). L'ordre des validations de `EXEC:58-61` n'est affirmé par aucun test relu.

### Gates, couverture, SpotBugs

- **Gates littéraux** : `grep -rn` dans `scripts/`, `quality/`, `.github/` et `.claude/scripts/` : `ImpactAnalysisService` n'apparaît nulle part ; `ScipIngestionAdapter` seulement dans `scripts/history/m0/README.md` (historique, non exécuté) ; `IndexingRunExecutor` et `IndexingLifecycleService` seulement dans `scripts/quality/check-jacoco.py` (préfixes de scopes et commentaire) ; aucun nom de méthode du lot (`analyze`, `ingest`, `execute`) n'est affirmé avec sa classe.
- **`LocalIsolatedIndexWorker` (hors lot)** : cité par six gates — `scripts/quality/check-jacoco.py` (scope `m25-remote-distributed-indexing`, `:128`), `scripts/quality/check-remote-distributed-consistency.py`, `scripts/quality/check-vertical-decomposition-consistency.py`, `scripts/remediation/check-minos-01.py`, `scripts/remediation/check-p0-p2.py`, `scripts/remediation/check-post-mne.py` — ainsi que par `scripts/architecture/private-io-allowlist.json` et `quality/spotbugs-baseline/minos-runtime-local.xml`.
- **JaCoCo** : `IndexingRunExecutor` est dans les scopes `critical-orchestration` (`scripts/quality/check-jacoco.py:254-258`, 75 % lignes / 55 % branches) et `resume-orchestration` (`:263-271`, mêmes seuils). `ImpactAnalysisService` et `ScipIngestionAdapter` ne relèvent que du scope `global` (`:19-23`). Le scope `m24-polyglot-provider-platform` (`:102-122`) ne nomme aucune classe du package `com/minos/adapter/scip/ingestion/` : il ne garde pas `ScipIngestionAdapter`.
- **SpotBugs** : `quality/spotbugs-baseline/*.xml` ne contient aucune entrée pour les quatre classes touchées. La comparaison se fait par `instanceHash` (`scripts/quality/audit-report-summary.py spotbugs --baseline-dir quality/spotbugs-baseline`, workflow `code-audit.yml`, déclenché à la main seulement, non exigé à la fusion) : une alerte nouvelle sur une méthode extraite apparaît comme absente de la base.

**Non mesuré / non vérifié** : la couverture JaCoCo actuelle des branches d'`analyze`, d'`ingest` et d'`execute` (aucun build ni test exécuté pour cette révision) ; la méthode exacte des décomptes de l'audit et de la contre-vérification antérieure ; les points de décision sont approchés par motif, pas par un analyseur Java.

### Écarts avec l'action proposée par l'audit

1. **« Le record `Ports` existant » ne suffit pas** : il est privé, porte neuf entrées et ni `artifactPolicy` ni `resumePolicy` ; `execute` a 16 paramètres, pas 14 (`fingerprints` et `rootScopeFingerprint` s'y ajoutent). Voir D2.
2. **La ligne citée pour `analyze` (`:56`, 131 lignes, 22 décisions) ne correspond plus** : AUD-PERF-11 (changement archivé `2026-10-15-indexer-les-aretes-d-impact-avec-la-vue`) sépare deux surcharges publiques courtes et une surcharge privée de 126 lignes (`IMPACT:84`) ; c'est elle qui est découpée.
3. **`MinosApplicationAssembler.build` (108 lignes) n'est pas citée par l'audit** ; relevée, hors lot, comme les onze autres méthodes de la liste ci-dessus qui ne figurent pas dans le lot.
4. **`LocalIsolatedIndexWorker.execute` est citée par l'audit mais gardée par six gates littéraux** et modifiée par `migrer-les-ecritures-d-artefacts-du-runtime-local` ; hors lot.
5. **Extraire vers des classes nouvelles coûterait en couverture** : SonarCloud juge le code neuf module par module. Des méthodes privées dans la même classe restent couvertes par les tests existants.

## Goals / Non-Goals

**Goals** : des étapes nommées et relisibles dans `analyze` et `ingest` ; un appel d'`execute` lisible ; zéro différence observable (sorties, messages, ordre des effets et des validations).

**Non-Goals** : un seuil ; une nouvelle abstraction ; un gain de performance ; toucher aux classes gardées par des gates littéraux ; changer une surface publique.

## Decisions

### D1. La preuve d'un remaniement est une caractérisation, pas un rouge

Aucun comportement ne change : il n'existe pas de test qui échoue avant et réussisse après. Dans chaque section, l'étape « rouge » est remplacée par : (a) relever les tests existants qui traversent la méthode et, par le rapport JaCoCo du module, les branches non traversées ; (b) pour chaque branche non traversée ou non affirmée (liste de départ : refus et limitation d'`analyze`, compteurs `skipped*` non nuls d'`ingest`, ordre des validations d'`execute`), **écrire d'abord le test de caractérisation**, vert au HEAD ; (c) découper ; (d) rejouer, tout vert, rapports de référence et goldens identiques octet pour octet. La longueur avant/après est consignée, à titre d'information. Alternative écartée : s'en remettre aux seuls tests existants — les refus d'`analyze` ne sont affirmés par aucun test.

### D2. Deux records pour `IndexingRunExecutor.execute`

Recommandation (décision P1) : `execute(RunRequest request, Collaborators collaborators)`, deux records de visibilité package imbriqués dans `IndexingRunExecutor`, **sans validation dans leurs constructeurs** :

- `RunRequest(projectId, projectRoot, targets, mode, changedFiles, resumePolicy, moduleAssignment, rootScopeFingerprint)` — ce qui change à chaque run ;
- `Collaborators(executors, stager, promoter, stateStore, markers, artifactPolicy, clock, fingerprints)` — les champs `final` du service, construit **une fois** dans le constructeur canonique de `IndexingLifecycleService` (`LIFE:89-107`, où tous sont déjà vérifiés non nuls).

Le `Ports` privé est conservé et construit dans `execute` à partir des deux records, au même endroit qu'aujourd'hui : l'ordre `resumePolicy` → racine → `Ports` (et l'ordre interne de `Ports`) reste identique, avec les mêmes messages. Alternatives écartées : élargir `Ports` et l'ouvrir au package (il mélangerait entrées du run et collaborateurs, et sa validation passerait avant celle de la racine si l'appelant le construit) ; un seul record de seize champs (déplace l'arité sans la réduire).

### D3. Découpage en méthodes privées de la même classe

`analyze` (surcharge privée) et `ingest` deviennent une suite d'appels à des étapes nommées d'après ce qu'elles font (pour `ingest` : métadonnées de définition, symboles, occurrences, relations, rapport ; pour `analyze` : refus de la racine, parcours, sélection, assemblage). Chaque étape est `private` (ou `private static`), dans la même classe, sans état partagé nouveau : ce qui passait par des variables locales passe par des paramètres ou par un petit record privé (par exemple les compteurs d'occurrences). Aucune classe de premier niveau n'est créée ; les surcharges publiques d'`analyze` ne changent pas.

### D4. Ordre et isolement des lots

Une PR par classe (sections 2, 3, 4), indépendantes entre elles. La section 2 (`ImpactAnalysisService`) passe **avant** la tâche 3.2 de `exposer-un-port-de-lecture-de-snapshot`, comme ce changement le prévoit (`openspec/changes/exposer-un-port-de-lecture-de-snapshot/design.md:108`). La dépendance envers AUD-PERF-11 est levée : le changement est archivé et son code est au HEAD.

### Recouvrements avec les changements actifs

| Changement actif | Fichiers communs | Ordre recommandé |
|---|---|---|
| `exposer-un-port-de-lecture-de-snapshot` (tâches 3.2, 4.2) | `IMPACT` (entrée de l'analyse, interface `IncomingRelationships`, liste fermée du cliquet A17) | section 2 d'abord ; la tâche 3.2 se rebase sur les étapes nommées |
| `decouper-les-classes-de-plus-de-800-lignes` | aucun (`PrivateLocalStorage`, `ManagedScipProviderRuntimeManager`, `WindowsDacl`, `ProcessOwner`, `BoundedZipExtraction`) ; même méthode D1 « caractérisation au lieu du rouge » | indépendant |
| `calculer-le-rang-de-symbole-une-fois` | aucun (`InMemoryCodeKnowledgeStore`) | indépendant |
| `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` | aucun (caches des stores, services sémantiques et program graph) | indépendant |
| `faire-consommer-l-indexation-aux-surfaces` | aucun fichier ; ses appelants passent par `ProjectIndexingUseCase` → API publique de `LIFE`, inchangée ici | indépendant |
| `migrer-les-ecritures-d-artefacts-du-runtime-local` | aucun dans le lot (`LocalIsolatedIndexWorker`, hors lot) | sans objet |
| `rendre-l-echec-de-scip-java-sous-windows-actionnable` | aucun fichier modifié ; cite `EXEC:837` pour `failureMessage`, qui est au HEAD `EXEC:845` et se décalera encore avec la section 4 | sans objet (citation à relire dans ce changement) |

## Direction des dépendances

Aucun import ajouté entre modules ni aucun package créé ; chaque remaniement reste dans sa classe (`minos-application`, `minos-provider-scip`, `minos-engine`), D2 reste dans le package `com.minos.orchestration`. ADR 0022, ADR 0044 et ADR 0062 (racines de packages gelées) respectés ; règles A10 à A16 de `scripts/architecture/check-module-boundaries.py` non concernées (aucune ne cite ces classes), rejouées par `run_gates.py --fast`.

## Qualification des capacités

Sans objet : aucune capacité ajoutée, modifiée ni requalifiée. L'analyse d'impact, l'ingestion SCIP et l'indexation gardent leur qualification actuelle.

## Windows et Linux

Aucune différence de comportement : code pur Java sans E/S propre à une plateforme dans les étapes extraites. Les preuves Maven tournent sur le poste (Windows) et en CI sur les deux systèmes (`Verify (ubuntu-24.04)`, `Verify (windows-2022)`). Le scope JaCoCo `m24-polyglot-provider-platform`, rouge hors régression sur poste Windows, ne garde aucune classe du lot ; ne pas lui attribuer d'écart.

## Risks / Trade-offs

- **Régression silencieuse dans une branche non affirmée** → D1 (b) : sans test qui traverse et affirme la branche, on ne la déplace pas.
- **Ordre des effets** : `ingest` écrit dans le store en trois temps et `execute` valide puis écrit sous bail ; une étape extraite ne doit ni réordonner ces effets ni changer le moment où une exception sort → test de caractérisation de l'ordre (store espion pour `ingest`, ordre des refus pour `execute`).
- **SonarCloud** : les lignes déplacées comptent comme nouveau code dans leur module (80 %) ; elles restent couvertes parce qu'elles restent dans la même classe et que D1 ajoute les cas manquants → vérifier le rapport de chaque PR. Le nouveau constructeur de `Collaborators` dans `LIFE` est couvert par chaque test qui construit le service.
- **SpotBugs** : aucune entrée de base pour ces classes → toute alerte nouvelle se voit ; comparaison locale avant PR.
- **Gain modeste** : sévérité faible ; si la lecture montre qu'un découpage obscurcit plus qu'il n'éclaire, le dire dans `SUIVI.md` et laisser la méthode.

## Décisions en attente du propriétaire

- **P1 — forme de l'objet requête d'`execute`** : deux records sans validation, `Ports` privé conservé (D2), ou `Ports` élargi et ouvert au package. **Recommandation : D2**, seule forme qui garde l'ordre et les messages des validations sans test de plus.
- **P2 — sort des méthodes hors lot** (`LocalIsolatedIndexWorker.execute`, `CodeSearchService.search`, `sandboxPlan`, `MinosApplicationAssembler.build` et les autres de la liste) : nouveau changement ou simple consignation. **Recommandation** : consignation dans `SUIVI.md` ; un changement dédié pour `LocalIsolatedIndexWorker.execute` seulement après `migrer-les-ecritures-d-artefacts-du-runtime-local`.

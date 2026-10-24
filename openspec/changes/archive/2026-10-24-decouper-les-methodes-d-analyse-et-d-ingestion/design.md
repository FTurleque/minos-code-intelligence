# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-07** (voir `proposal.md`, Why). Rédigé au HEAD `5ce7b79b` (`develop` du 2026-10-22) ; **relu au HEAD de mise en œuvre `a8288b43`** (`develop` du 2026-10-24), après l'archivage de `exposer-un-port-de-lecture-de-snapshot` qui a modifié `IMPACT`. Les `fichier:ligne` ci-dessous sont ceux de `a8288b43`. Abréviations : `EXEC` = `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java` (984 lignes), `LIFE` = `minos-engine/src/main/java/com/minos/orchestration/IndexingLifecycleService.java` (270 lignes), `IMPACT` = `minos-application/src/main/java/com/minos/impact/ImpactAnalysisService.java` (327 lignes), `SCIP` = `minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionAdapter.java` (409 lignes).

## État vérifié au HEAD

### Méthode de mesure (tâche 1.1)

Script Python local, **non versionné** (aucun script ajouté à `scripts/`, aucun seuil ajouté à un gate) :

- **texte analysé** : commentaires `//` et `/* */`, chaînes, caractères et blocs de texte `"""` remplacés par des blancs, retours à la ligne conservés ;
- **méthode** : un identifiant suivi de `(...)`, d'un `throws ...` facultatif puis de `{` ; sont exclus les mots-clés de contrôle (`if`, `for`, `while`, `switch`, `catch`, `synchronized`, `try`…), un nom précédé de `record`, `class`, `interface`, `enum` ou `new`, et un appel chaîné (précédé de `.`). Constructeurs compris ; constructeurs compacts de record (sans parenthèses) et lambdas exclus ; une méthode d'une classe anonyme ou locale compte pour elle-même ;
- **longueur** : lignes physiques, de la ligne du nom à l'accolade fermante appariée (Javadoc et annotations exclues) ;
- **points de décision** : 1 + occurrences de `if`, `for`, `while`, `case`, `catch`, `&&`, `||` et de `?` hors jokers de génériques ;
- **périmètre** : `*/src/main/**/*.java` hors `minos-intellij`.

Résultat au HEAD `a8288b43` : **4 550 méthodes, 18 de 80 lignes ou plus**. Le nombre de méthodes longues confirme celui de la contre-vérification du 2026-10-22 (18) ; le total diffère (4 550 contre 4 430) parce que du code a été ajouté entre les deux HEAD. Ni le décompte de l'audit (40 sur 4 876, `docs/project/audits/2026-10-10/findings.md:322`) ni celui de la première contre-vérification (24 sur 4 399) n'est reproduit : leurs méthodes de comptage ne sont pas écrites.

| Longueur | Décisions | Méthode (`fichier:ligne` au HEAD `a8288b43`) | Sort |
|---:|---:|---|---|
| 154 | 10 | `SCIP:51` `ingest` | lot (section 3) |
| 126 | 21 | `IMPACT:95` `analyze` (surcharge privée) | lot (section 2) |
| 119 | 13 | `minos-application/.../context/CodeSearchService.java:43` `search` | hors lot |
| 112 | 1 | `minos-cli/.../cli/command/TeamCommand.java:139` `operationTable` (table) | hors lot |
| 108 | 32 | `minos-application/.../application/MinosApplicationAssembler.java:40` `build` | hors lot |
| 108 | 6 | `minos-runtime-local/.../sandbox/WindowsAppContainerWorkerSandboxBackend.java:255` `sandboxPlan` | hors lot |
| 107 | 17 | `minos-runtime-local/.../runtime/local/LocalIsolatedIndexWorker.java:153` `execute` | hors lot (gates) |
| 101 | 9 | `minos-integration-git/.../git/GitIntelligenceService.java:99` `analyze` | hors lot |
| 98 | 10 | `minos-engine/.../orchestration/IndexerRegistry.java:60` `negotiate` | hors lot |
| 90 | 17 | `minos-application/.../application/remote/RemoteIndexingUseCase.java:170` `indexUnderSourceLease` | hors lot |
| 88 | 18 | `minos-engine/.../orchestration/ProjectInvalidationService.java:52` `assess` | hors lot |
| 87 | 17 | `minos-cli/.../cli/MinosCli.java:127` (constructeur) | hors lot |
| 85 | 6 | `minos-application/.../architecture/ArchitectureConcentrationService.java:26` `analyze` | hors lot |
| 84 | 19 | `minos-application/.../application/semantic/SemanticIndexService.java:116` `synchronize` | hors lot |
| 84 | 16 | `minos-provider-scip/.../runtime/plan/ScipJavaProcessPlanFactory.java:227` `prepareWritableWorkspace` | hors lot |
| 82 | 8 | `minos-provider-scip/.../ingestion/ScipSymbolNormalizer.java:81` `normalize` | hors lot |
| 81 | 8 | `minos-application/.../architecture/ArchitectureDependencyService.java:36` `build` | hors lot |
| 81 | 16 | `minos-engine/.../incremental/ProjectFingerprintService.java:74` `captureScope` | hors lot |

Écart avec la liste du 2026-10-22 : `LocalRemoteIndexOperations.java:130` `indexUnderSourceLease` (90 lignes) est devenue `RemoteIndexingUseCase.java:170`, même longueur ; `IMPACT:84` est devenue `IMPACT:95`. `execute` (`EXEC:48`, 57 lignes) n'est pas dans la table : son défaut est l'arité.

### AUD-QUA-07 — confirmé pour les trois méthodes du lot

- **`ingest`** (`SCIP:51-204`, 154 lignes, 10 points de décision) : enchaîne cinq étapes lisibles à l'œil — métadonnées de définition (`:59-63`), normalisation des symboles et `store.putSymbols` (`:65-97`), occurrences et `store.putOccurrences` (`:99-166`), relations fournies et dérivées et `store.putRelationships` (`:168-184`), rapport (`:186-203`). Ordre des effets observables : `putSymbols`, puis `putOccurrences`, puis `putRelationships`. Appelant de production unique : `minos-provider-scip/.../ingestion/ScipSymbolSnapshotImporter.java:98`.
- **`analyze`** : `exposer-un-port-de-lecture-de-snapshot` a changé l'entrée de l'analyse (lecture par `CodeKnowledgeReader`, `SnapshotReadView` et `IncomingRelationships`). La longueur reste portée par la surcharge **privée** `IMPACT:95-220` (126 lignes, 21 points de décision), qui reçoit maintenant un `IncomingRelationships` et une recherche `Function<String, Symbol>`. Trois surcharges publiques courtes la précèdent : `IMPACT:61` (vue de lecture `SnapshotReadView`, 9 lignes ; appelant de production `LocalProjectImpactQuery.java:45`), `IMPACT:77` (ancienne vue de requête `SnapshotQueryView`, dépréciée depuis 1.3.0, délègue à la précédente ; seule raison de la présence de la classe dans `LEGACY_SNAPSHOT_VIEW_USERS`, `scripts/architecture/check-module-boundaries.py:831`) et `IMPACT:82` (snapshot seul, 12 lignes, refus d'un identifiant dupliqué `:88-90`). Étapes de la surcharge privée : refus de la racine (`:101-108`), parcours inverse borné par file de priorité (`:110-183`), troncature et `MAX_RESULTS_REACHED` (`:185-192`), assemblage des impacts et des tests (`:194-219`).
- **Refus dupliqué non atteignable** : `CodeKnowledgeSnapshot` refuse déjà un identifiant de symbole dupliqué à sa construction, avec le même message (`minos-engine/.../store/CodeKnowledgeSnapshot.java:46`, `requireUniqueIds`) ; `IMPACT:89` ne peut donc pas être atteint. Il est conservé tel quel (comportement constant).
- **`execute`** (`EXEC:48-104`, 57 lignes) : le défaut est l'arité, pas la longueur. **16 paramètres** : `projectId`, `projectRoot`, `targets`, `mode`, `changedFiles`, `executors`, `stager`, `promoter`, `stateStore`, `markers`, `artifactPolicy`, `resumePolicy`, `moduleAssignment`, `clock`, `fingerprints`, `rootScopeFingerprint`. Ordre des validations : `resumePolicy` (`EXEC:56`), racine (`validateExecutionRoot`, `EXEC:57`), puis le constructeur de `Ports` (`EXEC:58-59`), qui vérifie `fingerprints`, `rootScopeFingerprint`, `executors`, `stager`, `promoter`, `stateStore`, `markers`, `moduleAssignment`, `clock` dans cet ordre (`EXEC:870-878`).
- **`EXEC:858-880`** : `private record Ports(executors, stager, promoter, stateStore, markers, moduleAssignment, clock, fingerprints, rootScopeFingerprint)`. Il ne porte ni `artifactPolicy` ni `resumePolicy`.
- **Appelant unique** : `LIFE:209-212`, dans `run` (`LIFE:201-214`) sous le bail exclusif du projet, après `Objects.requireNonNull(policy, "policy")` (`LIFE:206`). Huit des seize arguments sont des champs `final` du service, tous vérifiés non nuls à la construction (`LIFE:24-31`, `:93-106`) : `executors`, `stager`, `promoter`, `stateStore`, `markers`, `artifactPolicy`, `clock`, `fingerprints`. Les huit autres sont propres au run (`projectId`, `projectRoot`, `targets`, `mode`, `changedFiles`, `resumePolicy`, `moduleAssignment`, `rootScopeFingerprint`) ; seul `moduleAssignment` n'est vérifié non nul que par `Ports`. `executors`, `stager` et `artifactPolicy` ne servent qu'à cet appel.
- **Autres usages de la classe** : `IndexingResumePlanner.java:235` appelle `IndexingRunExecutor.scopedChangedFiles` ; `IndexingRunExecutorTest` appelle `awaitReadable`. Aucun n'est touché.

### Tests existants qui traversent les méthodes (tâche 1.2)

- `analyze` : `ImpactAnalysisServiceTest` (`MAX_DEPTH_REACHED`, `MAX_RESULTS_REACHED`, `GENERATED_SYMBOLS_NOT_TRAVERSED`, `OCCURRENCE_REFERENCES_NOT_PROJECTED`), `ImpactIndexReuseTest` (égalité des chemins, ordre compris), `ImpactReferenceReportsTest` et `AdvancedImpactReferenceReportsTest` (`minos-application/.../program/analysis/`) contre les rapports de référence `minos-application/src/test/resources/impact/reference-reports.txt` (120 lignes) et `advanced-reference-reports.txt` (80 lignes), `AdvancedImpactAdjacencyReuseTest`, `minos-app/.../impact/ImpactAnalysisRealFixtureTest`. Avant la section 2, **aucun test n'affirmait** les refus `unknown symbol in snapshot`, `impact root must be a local non-generated symbol`, `duplicate symbol id in snapshot` ni la limitation `EXTERNAL_TARGETS_NOT_TRAVERSED` levée par le parcours.
- `ingest` : `ScipIngestionAdapterTest` (six cas avant la section 3), `ScipIngestionGuardsTest`, `ScipRelatedTestIngestionTest`, `ScipRelatedTestRealFixtureTest`, `ScipSymbolSnapshotImporterBehaviorTest` ; `ScipRealIndexExperiment` et `InMemoryBackendBenchmark` sont des expériences, pas des preuves. Avant la section 3, `skippedSymbolCount` et `skippedOccurrenceCount` n'étaient affirmés qu'à `0`, et l'ordre des écritures n'était pas affirmé.
- `execute` : `IndexingLifecycleServiceTest`, `IndexingResumeTest` (dont `RESUME_ONLY`, message affirmé par `contains` seulement), `IndexingRunExecutorCheckpointTest`, `IndexingInterruptionTest`, `IndexingCommitRecoveryTest`, `IndexingPreRunReconciliationTest`, `IndexingExecutionPathContainmentTest`, `RunDirectoryHoldTest`, `StagedSnapshotIdValidationTest`, `InterruptedRunRecoveryTest`, `AbandonedIndexingLifecycleRecoveryTest`, `IndexingLifecycle{Incremental,ScopedExecution}Test` (`minos-engine`) ; `minos-bootstrap` (`InterruptionDuringIndexingTest`, `FileAuthoritativeSnapshotRecoveryTest`) ; `PostgresAuthoritativeSnapshotConsistencyTest` (`minos-storage-postgresql`). Avant la section 4, l'ordre des validations de `EXEC:56-59` n'était affirmé par aucun test.

**Couverture JaCoCo mesurée** (`./mvnw -B -ntp -pl minos-application,minos-provider-scip,minos-engine -am verify`, rapports `*/target/site/jacoco/jacoco.xml`, lus par méthode) :

- `execute` au HEAD, **sans** test de caractérisation : 33 lignes sur 33, 11 branches sur 11.
- `ingest`, **avec** les tests de caractérisation de 3.1 : 92 lignes sur 92, 18 branches sur 18.
- surcharge privée d'`analyze`, **avec** les tests de caractérisation de 2.1 : 78 lignes sur 78, 38 branches sur 40. Les deux branches manquées ne sont pas atteignables : `IMPACT:122` (`bestKnown != current` avec un candidat qui ne serait pas plus mauvais que le meilleur connu : un candidat n'entre dans la file que s'il améliore strictement le meilleur connu) et `IMPACT:140` (`impacted == null` : `incomingRelationships` écarte déjà toute source absente de la table des symboles, `:247`). Surcharge snapshot seul : `IMPACT:89` non couverte (refus non atteignable, voir plus haut).
- Le premier `verify` a échoué sur un attendu de caractérisation mal deviné (`derivedRelationshipCount` vaut 1, pas 0, au HEAD) ; l'attendu a été corrigé à la valeur du HEAD avant tout découpage, puis le `verify` est passé (`BUILD SUCCESS`). La couverture d'`analyze` et d'`ingest` **sans** les tests de caractérisation n'a donc pas été relevée séparément.

### Gates, couverture, SpotBugs

- **Gates littéraux** (`grep -rn "ImpactAnalysisService\|ScipIngestionAdapter\|IndexingRunExecutor\|IndexingLifecycleService" scripts quality .github .claude/scripts`, rejoué au HEAD `a8288b43`) : `ScipIngestionAdapter` seulement dans `scripts/history/m0/README.md` (historique, non exécuté) ; `IndexingRunExecutor` et `IndexingLifecycleService` seulement dans `scripts/quality/check-jacoco.py` (préfixes de scopes et commentaire). **Correction** : `ImpactAnalysisService` est désormais cité par `scripts/architecture/check-module-boundaries.py:831` (règle A18, liste fermée `LEGACY_SNAPSHOT_VIEW_USERS`, à cause de la surcharge `analyze(SnapshotQueryView, …)`) et par son test `scripts/architecture/test_check_module_boundaries.py:1187-1283` ; ce gate n'affirme aucun nom de méthode et la surcharge dépréciée est conservée. Aucun nom de méthode du lot (`analyze`, `ingest`, `execute`) n'est affirmé avec sa classe.
- **`LocalIsolatedIndexWorker` (hors lot)** : cité par six gates — `scripts/quality/check-jacoco.py` (scope `m25-remote-distributed-indexing`, `:124`), `scripts/quality/check-remote-distributed-consistency.py`, `scripts/quality/check-vertical-decomposition-consistency.py`, `scripts/remediation/check-minos-01.py`, `scripts/remediation/check-p0-p2.py`, `scripts/remediation/check-post-mne.py` — ainsi que par `scripts/architecture/private-io-allowlist.json` et `quality/spotbugs-baseline/minos-runtime-local.xml`.
- **JaCoCo** : `IndexingRunExecutor` est dans les scopes `critical-orchestration` (`scripts/quality/check-jacoco.py:257-261`, 75 % lignes / 55 % branches) et `resume-orchestration` (`:266-274`, mêmes seuils). `ImpactAnalysisService` et `ScipIngestionAdapter` ne relèvent que du scope `global` (`:19-24`). Le scope `m24-polyglot-provider-platform` (`:103-123`) ne nomme aucune classe du package `com/minos/adapter/scip/ingestion/` : il ne garde pas `ScipIngestionAdapter`.
- **SpotBugs** : `quality/spotbugs-baseline/*.xml` ne contient aucune entrée pour les quatre classes touchées. La comparaison se fait par `instanceHash` (`scripts/quality/audit-report-summary.py spotbugs --baseline-dir quality/spotbugs-baseline`, workflow `code-audit.yml`, déclenché à la main seulement, non exigé à la fusion) : une alerte nouvelle sur une méthode extraite apparaît comme absente de la base.

**Non mesuré / non vérifié** : la méthode exacte des décomptes de l'audit et de la première contre-vérification ; les points de décision sont approchés par motif, pas par un analyseur Java ; la couverture d'`analyze` et d'`ingest` sans les tests de caractérisation (voir plus haut).

### Écarts avec l'action proposée par l'audit

1. **« Le record `Ports` existant » ne suffit pas** : il est privé, porte neuf entrées et ni `artifactPolicy` ni `resumePolicy` ; `execute` a 16 paramètres, pas 14 (`fingerprints` et `rootScopeFingerprint` s'y ajoutent). Voir D2.
2. **La ligne citée pour `analyze` (`:56`, 131 lignes, 22 décisions) ne correspond plus** : AUD-PERF-11 (changement archivé `2026-10-15-indexer-les-aretes-d-impact-avec-la-vue`) sépare deux surcharges publiques courtes et une surcharge privée de 126 lignes (`IMPACT:95` au HEAD `a8288b43`) ; c'est elle qui est découpée.
3. **`MinosApplicationAssembler.build` (108 lignes) n'est pas citée par l'audit** ; relevée, hors lot, comme les quatorze autres méthodes de la table ci-dessus qui ne figurent pas dans le lot.
4. **`LocalIsolatedIndexWorker.execute` est citée par l'audit mais gardée par six gates littéraux** et modifiée par `migrer-les-ecritures-d-artefacts-du-runtime-local` ; hors lot.
5. **Extraire vers des classes nouvelles coûterait en couverture** : SonarCloud juge le code neuf module par module. Des méthodes privées dans la même classe restent couvertes par les tests existants.

## Goals / Non-Goals

**Goals** : des étapes nommées et relisibles dans `analyze` et `ingest` ; un appel d'`execute` lisible ; zéro différence observable (sorties, messages, ordre des effets et des validations).

**Non-Goals** : un seuil ; une nouvelle abstraction ; un gain de performance ; toucher aux classes gardées par des gates littéraux ; changer une surface publique.

## Decisions

### D1. La preuve d'un remaniement est une caractérisation, pas un rouge

Aucun comportement ne change : il n'existe pas de test qui échoue avant et réussisse après. Dans chaque section, l'étape « rouge » est remplacée par : (a) relever les tests existants qui traversent la méthode et, par le rapport JaCoCo du module, les branches non traversées ; (b) pour chaque branche non traversée ou non affirmée (liste de départ : refus et limitation d'`analyze`, compteurs `skipped*` non nuls d'`ingest`, ordre des validations d'`execute`), **écrire d'abord le test de caractérisation**, vert au HEAD ; (c) découper ; (d) rejouer, tout vert, rapports de référence et goldens identiques octet pour octet. La longueur avant/après est consignée, à titre d'information. Alternative écartée : s'en remettre aux seuls tests existants — les refus d'`analyze` ne sont affirmés par aucun test.

### D2. Deux records pour `IndexingRunExecutor.execute`

**Retenu par le propriétaire le 2026-10-24 (P1 = D2)** : `execute(RunRequest request, Collaborators collaborators)`, deux records de visibilité package imbriqués dans `IndexingRunExecutor`, **sans validation dans leurs constructeurs** :

- `RunRequest(projectId, projectRoot, targets, mode, changedFiles, resumePolicy, moduleAssignment, rootScopeFingerprint)` — ce qui change à chaque run ;
- `Collaborators(executors, stager, promoter, stateStore, markers, artifactPolicy, clock, fingerprints)` — les champs `final` du service, construit **une fois** dans le constructeur canonique de `IndexingLifecycleService` (`LIFE:89-107`, où tous sont déjà vérifiés non nuls).

Le `Ports` privé est conservé et construit dans `execute` à partir des deux records, au même endroit qu'aujourd'hui : l'ordre `resumePolicy` → racine → `Ports` (et l'ordre interne de `Ports`) reste identique, avec les mêmes messages. Alternatives écartées : élargir `Ports` et l'ouvrir au package (il mélangerait entrées du run et collaborateurs, et sa validation passerait avant celle de la racine si l'appelant le construit) ; un seul record de seize champs (déplace l'arité sans la réduire).

### D3. Découpage en méthodes privées de la même classe

`analyze` (surcharge privée) et `ingest` deviennent une suite d'appels à des étapes nommées d'après ce qu'elles font (pour `ingest` : métadonnées de définition, symboles, occurrences, relations, rapport ; pour `analyze` : refus de la racine, parcours, sélection, assemblage). Chaque étape est `private` (ou `private static`), dans la même classe, sans état partagé nouveau : ce qui passait par des variables locales passe par des paramètres ou par un petit record privé (par exemple les compteurs d'occurrences). Aucune classe de premier niveau n'est créée ; les surcharges publiques d'`analyze` ne changent pas.

### D4. Ordre et isolement des lots

Une PR par classe (sections 2, 3, 4), indépendantes entre elles. La section 2 (`ImpactAnalysisService`) passe **avant** la tâche 3.2 de `exposer-un-port-de-lecture-de-snapshot`, comme ce changement le prévoit (`openspec/changes/archive/2026-10-24-exposer-un-port-de-lecture-de-snapshot/design.md:108`). La dépendance envers AUD-PERF-11 est levée : le changement est archivé et son code est au HEAD. **Remplacé à la mise en œuvre** par une PR unique, un commit par section (choix du propriétaire, voir « Écarts de la mise en œuvre ») ; `exposer-un-port-de-lecture-de-snapshot` est archivé depuis le 2026-10-24 et la section 2 se fait sur son code.

### Recouvrements avec les changements actifs

| Changement actif | Fichiers communs | Ordre recommandé |
|---|---|---|
| `exposer-un-port-de-lecture-de-snapshot` (tâches 3.2, 4.2) — **archivé le 2026-10-24** | `IMPACT` (entrée de l'analyse, interface `IncomingRelationships`, liste fermée du cliquet A18) | sans objet : son code est au HEAD `a8288b43` et la section 2 le découpe tel quel |
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

## Décisions du propriétaire (2026-10-24)

- **P1 = D2** : deux records de visibilité package `RunRequest` et `Collaborators`, sans validation dans leurs constructeurs ; le record privé `Ports` est conservé et construit dans `execute`.
- **P2** : les méthodes hors lot (`LocalIsolatedIndexWorker.execute`, `CodeSearchService.search`, `sandboxPlan`, `MinosApplicationAssembler.build` et les autres de la table) sont consignées dans `docs/project/audits/2026-10-10/SUIVI.md`, sans changement OpenSpec nouveau.
- **Livraison** : une seule PR pour tout le changement, un commit par section 2, 3 et 4 (écart à D4, ci-dessous).

## Écarts de la mise en œuvre

1. **Une PR au lieu de trois (D4)** : choix du propriétaire ; les trois sections restent des commits séparés et indépendants, chacun avec ses tests de caractérisation.
2. **HEAD de mise en œuvre `a8288b43`, pas `5ce7b79b`** : `IMPACT` a changé (lecture par `CodeKnowledgeReader`, `SnapshotReadView`, `IncomingRelationships` ; surcharge `SnapshotQueryView` dépréciée ajoutée). Les lignes sont corrigées en place dans « État vérifié au HEAD » ; le découpage porte sur la nouvelle surcharge privée, qui reçoit `IncomingRelationships` et une recherche de symbole.
3. **Refus « duplicate symbol id in snapshot » d'`IMPACT` non atteignable** : `CodeKnowledgeSnapshot` refuse le doublon avant le service, avec le même message. Le test de caractérisation affirme ce refus à la construction du snapshot ; le code du service est laissé tel quel.
4. **`execute` n'est pas plus court** : 57 lignes avant, 58 après (Javadoc exclue) ; 16 paramètres avant, 2 après. Les accès aux deux records sont posés en variables locales en tête de méthode pour garder le corps inchangé.
5. **Champs retirés d'`IndexingLifecycleService`** : `executors`, `stager` et `artifactPolicy` ne servaient qu'à l'appel d'`execute` ; ils ne vivent plus que dans `Collaborators`. Leur vérification à la construction reste au même endroit, dans le même ordre, avec les mêmes messages.
6. **Tests de caractérisation d'`execute`** : dans une classe nouvelle `IndexingRunExecutorValidationTest` (`minos-engine`) plutôt que dans `IndexingLifecycleServiceTest`, parce que l'ordre des refus de `Ports` (`moduleAssignment`, `fingerprints`) ne se voit qu'en appelant `execute` directement.

### Longueur avant / après (méthode de mesure ci-dessus)

| Méthode | Avant (HEAD `a8288b43`) | Après | Étapes extraites (méthodes privées de la même classe) |
|---|---|---|---|
| `ImpactAnalysisService.analyze` (privée) | 126 lignes, 21 décisions | 20 lignes, 1 décision | `requireLocalRoot` 11, `traverse` 35, `follow` 42, `rankWithinLimit` 16, `impactedSymbols` 15, `impactedTests` 15, `Traversal.retain` 15 |
| `ScipIngestionAdapter.ingest` | 154 lignes, 10 décisions | 37 lignes, 1 décision | `normalizeSymbols` 38, `normalizeOccurrences` 59, `unresolvedReference` 17, `deriveRelationships` 14, `report` 25 |
| `IndexingRunExecutor.execute` | 57 lignes, 16 paramètres | 58 lignes, 2 paramètres | aucune (records `RunRequest`, `Collaborators`) |

<!-- Annexe E de l'audit 2026-10. Rapport d'analyse brut, conservé tel que remis ; les identifiants E-NN y valent MINOS-AUD-ENN dans docs/audit/archive/2026-10-06/constats.md. Les chemins « scratchpad/ » cités désignent des reproductions jetables hors dépôt, non conservées. -->
# Audit E — Persistance des snapshots et frontières de modules

Base : `develop` à `bc1d3421` (arbre de travail : `?? Claude outputs/`, `?? docs/audit/archive/2026-10-06/CAPACITES.md`). Lecture seule : aucun Maven, aucun test exécuté, aucune écriture dans le dépôt. Seul `python scripts/architecture/check-module-boundaries.py` a été exécuté sur le dépôt (sans `--write-doc`, donc sans effet de bord ; résultat : SUCCESS, 14 modules, 517 sources, 45 packages chacun possédé par un seul module). Les expériences de contournement du checker ont été faites sur une copie des POM et sources dans le scratchpad (`scratchpad/exp/`), jamais dans le dépôt. Script d'analyse de cycles : `scratchpad/pkgcycles.py`.

Les chemins:lignes ont tous été relus. Là où une valeur dépend d'un comportement externe non mesuré (pgjdbc, tas JVM), c'est dit.

## 0. Constats clos ou connus, non re-signalés

- A2 (application → adaptateurs) : vérifié clos. Les POM de `minos-application`, des surfaces et du domaine/engine ne déclarent aucun adaptateur ; le checker passe et ses règles hexagonales sont actives. A3 et A7 : clos (45 packages, un module chacun ; liste de modules confrontée au reactor).
- A8 (`ServiceLoader` sans chargeur explicite) : toujours ouvert, suivi par SH-10. Sites réels : `minos-bootstrap/.../StorageBackendSelection.java:23` et `minos-engine/.../orchestration/StableFileSystemIdentity.java:46`. `MinosApplicationComposers.java:36` utilise déjà un chargeur explicite. Voir E-04 pour l'erreur de chemin du backlog.
- A9 / ADR 0047 : toujours ouvert ; le poids estimé reste `8 ×` (`FileSymbolSnapshotStore.java:44`, `PostgresCodeKnowledgeSnapshotStore.java:44`) et le double chargement hybride subsiste (`HybridSearchService.java:59` puis `SemanticIndexService.java:68`).
- R8 (`staged-snapshots/` jamais balayé) et R12 (`MINOS_HOME` en lecture seule : `LocalStorageBackend.java:36-49` crée les 6 espaces à l'ouverture) : toujours ouverts, non re-signalés.
- Q3 : les publications/promotions/compactions du snapshot structurel passent bien par `SnapshotProjectLease` (`FileSymbolSnapshotStore.java:310`, `SnapshotRetentionService.java:46,81`, `SnapshotCompactionService.java:76`). Le fingerprint store n'a pas été relu.
- Q17 (testcontainers 2.0.5 + 1.21.4 mélangés, `minos-storage-postgresql/pom.xml:51-72`, dupliqué dans `minos-bootstrap/pom.xml`) : connu.

## 1. Constats

### E-01 — Le backend PostgreSQL ne persiste jamais `resumableRunId` : la reprise ADR 0039 est inopérante avec PostgreSQL, et la rétention PG ne protège pas le run reprenable
- **Qualification** : DÉFAUT CONFIRMÉ
- **Priorité** : P2. Aucune perte de données (repli silencieux sur une indexation complète), mais une fonctionnalité acceptée (ADR 0039) disparaît sans diagnostic sur le backend « avancé », et laisse des résidus sur disque.
- **Preuves** :
  - `minos-engine/.../orchestration/ProjectIndexState.java` : record à 7 composantes dont `resumableRunId` ; constructeur de compatibilité à 6 arguments qui le force à `Optional.empty()`.
  - `minos-storage-postgresql/.../PostgresIndexStateStore.java:27-40` : `findProjectState` sélectionne `availability,active_snapshot_id,latest_run_id,updated_at,detail` et construit l'état par le constructeur à 6 arguments (donc `resumableRunId` toujours vide). `:77-90` : `saveProjectState` n'écrit que ces 5 colonnes (jamais `state.resumableRunId()`).
  - `PostgresSchemaMigrator.java:85` : la table `project_index_state` n'a pas de colonne de run reprenable, et aucune des migrations 2 à 4 n'en ajoute.
  - `PostgresStorageRetentionService.java:101-125` (`deleteHistoricalRuns`) : ne protège que `p.latest_run_id=target.id` (`:120`). Le local protège aussi `resumableRunId` (`IndexRunRetentionService.java:30-31, 49`, ADR 0039 : « protégé exactement comme le dernier run »).
  - Contre-exemple local : `FileIndexStateStore.java:50` (`RESUMABLE_RUN_ID_PROPERTY`) et `:259` (`putOptional(... state.resumableRunId() ...)`).
  - Consommateurs : `IndexingResumePlanner.java:137` (`request.previous().resumableRunId()` → `NotOffered` si vide), `AuthoritativeProjectStateReconciler.java:394-435` (calcule et enregistre l'offre), `:289` (`markers.mark(runId)` pose `runs/<id>/.resumable` dans `MINOS_HOME`, quel que soit le backend : `DefaultMinosApplicationComposer.java:118-119` compose toujours `RunDirectoryResumableRunMarkers`).
  - Aucun test PostgreSQL ne mentionne la reprise : `grep -i resum minos-storage-postgresql/src/test` est vide. `grep -i postgres` dans `docs/adr/0039*.md` ne trouve rien sur ce point. `FIAB-SUIVI.md` ne mentionne PostgreSQL que pour l'inventaire du registre.
- **Comportement actuel** : un run interrompu est sauvegardé `INTERRUPTED` (jsonb) et le marqueur fichier est posé, mais l'état projet relu ne porte aucune offre de reprise. Le prochain `index` repart de zéro. Le marqueur `.resumable` et le snapshot préparé sous `staged-snapshots/<runId>` ne sont jamais libérés (l'identifiant n'est jamais relu pour `endResumeOffer`). La rétention PG peut supprimer la ligne du run `INTERRUPTED`.
- **Comportement attendu + source** : ADR 0039 §2 et le Javadoc de `ProjectIndexState` : « désigne l'unique run `INTERRUPTED` que le projet offre à la reprise ». Le port `IndexStateStore` est backend-neutre ; `StorageBackend` doit être substituable (ADR 0003, 0025).
- **Cause** : la colonne a été ajoutée au modèle et au backend fichier au lot de reprise, pas au schéma PostgreSQL. Aucun test de contrat commun aux deux backends n'aurait pu le voir.
- **Impact** : indexation complète (potentiellement longue) au lieu d'une reprise, résidus disque, état trompeur (`project status` ne peut pas annoncer la reprise).
- **Correction minimale** : migration PG v5 `ALTER TABLE project_index_state ADD COLUMN resumable_run_id uuid NULL`, lecture/écriture dans `PostgresIndexStateStore`, protection dans `deleteHistoricalRuns` (`OR p.resumable_run_id=target.id`). Ne pas modifier le schéma existant autrement.
- **Validation** : voir le test ci-dessous ; ajouter ensuite un test de contrat `IndexStateStoreContractTest` abstrait (dans `minos-engine/src/test`, publié par le test-jar déjà produit par engine) exécuté contre `FileIndexStateStore` et `PostgresIndexStateStore`.
- **Dépendances** : migration de schéma = nouvelle version (`PostgresSchemaMigrator.CURRENT_VERSION = 4`, `:10`) ; à coordonner avec SH-02 (fusion des modules) pour ne pas conflicter.

Reproduction minimale (non écrite dans le dépôt) : `minos-storage-postgresql/src/test/java/com/minos/storage/postgresql/PostgresResumableRunStateTest.java`, `extends PostgresTestSupport` (fournit `connections`).
```java
@Test
void resumableRunReferenceSurvivesAPersistenceRoundTrip() {
    UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
    PostgresIndexStateStore store = new PostgresIndexStateStore(connections, new PostgresJsonCodec());
    ProjectIndexState offered = new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
            Optional.empty(), Optional.of(runId), Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Optional.of("interrupted"), Optional.of(runId));
    store.saveProjectState(offered);
    assertEquals(Optional.of(runId), store.findProjectState(projectId).orElseThrow().resumableRunId()); // échoue aujourd'hui
}
```
Second test : sauvegarder un `IndexingRun` `INTERRUPTED` + l'état ci-dessus avec `maxNonSucceededRuns=0`, appeler `PostgresStorageRetentionService.compact`, vérifier que le run existe encore.

---

### E-02 — Le garde `check-module-boundaries.py` : règles A2 sans auto-test (écart avec l'ADR 0042 §8.4) et trois contournements prouvés
- **Qualification** : DÉFAUT CONFIRMÉ (absence de test, écart de documentation) + RISQUE (contournements)
- **Priorité** : P2. C'est la protection structurelle centrale d'ADR 0022/0042/0044 et le backlog SH-02/SH-11 va la modifier en profondeur : sans auto-test des règles A2, une modification qui l'affaiblit reste verte, car la CI exécute le script sur le dépôt réel (`pr-ci.yml:93`), qui passe de toute façon.
- **Preuves** :
  - ADR 0042 §8.4 (`docs/adr/0042-racine-de-composition.md:336-342`) affirme un « auto-test de chaque règle » (import, import statique, joker, nom qualifié, nom simple, surface → adaptateur en `compile/runtime/test/provided`). Or `scripts/architecture/test_check_module_boundaries.py` (196 lignes) n'importe que `check_package_ownership` et `check_reactor_modules` (`:30-31`) ; ses 13 cas portent sur A3/A7. `grep check_source_boundaries|check_hexagonal_boundaries|check_no_hidden|check_dependency_policy` dans ce fichier : aucun résultat. Le commit A2 `6ed87cc9` ne touche que `check-module-boundaries.py`. La CI lance bien ce test (`pr-ci.yml:96`), mais il ne couvre pas A2.
  - Contournement 1 (POM) : `check-module-boundaries.py:144-148` et `:181` ne reconnaissent une dépendance interne que si `groupId == "com.minos"` littéral. Avec `<groupId>${project.groupId}</groupId>` dans `minos-application/pom.xml`, `application → minos-storage-local` n'est pas vu. Prouvé sur la copie : SUCCESS.
  - Contournement 2 : `check_pom_layout` (`:121-141`) n'examine que `build/sourceDirectory`, `build/testSourceDirectory` et le plugin compilateur direct. Un `build-helper-maven-plugin:add-source` pointant `../minos-storage-local/src/main/java` n'est pas vu. Prouvé : SUCCESS.
  - Contournement 3 : `<profiles><profile><build><sourceDirectory>../minos-storage-local/src/main/java</sourceDirectory>` (le script traite les profils pour les dépendances internes, `:171-185`, pas pour le build). Prouvé : SUCCESS.
  - Limite déjà assumée (ADR 0042 §8.3) : sources de test et dépendances transitives de test non contrôlées. plusieurs fichiers de `minos-cli/src/test` importent des classes d'adaptateurs (ex. `FileSymbolSnapshotStore`, `LocalProjectRegistry`, `ManagedScipProviderRuntimeManager`). Non re-signalé.
- **Comportement actuel** : voir ci-dessus. **Attendu** : ADR 0042 §2 et §8.4 (« jamais assoupli, auto-test de chaque règle »), ADR 0044 §3 (« règle stricte »).
- **Cause** : le durcissement A2 a été prouvé « rouge avant correction » à la main, sans pérenniser les cas.
- **Impact** : régression silencieuse possible lors des refactorings SH-02/03/11 ; contournements sans intention malveillante possibles (propriété Maven, plugin).
- **Correction minimale** : (1) ajouter à `test_check_module_boundaries.py` des cas A2 qui construisent une arborescence jetable (même schéma que `Tree`) et exécutent `check_hexagonal_boundaries`, `check_source_boundaries`, `check_no_hidden_internal_dependencies`, `check_dependency_policy` : un cas rejeté et un cas accepté par règle ; (2) rejeter dans `minos_dependencies` toute dépendance dont le `groupId` contient `${` ; rejeter dans `check_pom_layout` tout `<build>` de `<profile>` et tout plugin hors liste blanche (`build-helper-maven-plugin`) ; (3) corriger l'ADR 0042 §8.4 si l'auto-test ne devait pas exister.
- **Validation** : les nouveaux cas ; confirmer que les trois variantes ci-dessus deviennent rouges et que le dépôt reste vert.
- **Dépendances** : SH-11 (qui prévoit « un contre-exemple rejeté par règle nouvelle » mais pas pour les règles existantes) ; à faire avant SH-02.

Reproduction (Python, `scripts/architecture/test_check_module_boundaries.py`) : générer un POM de module avec la dépendance `${project.groupId}:minos-storage-local`, appeler `_MODULE.minos_dependencies("minos-application", root)` ; l'assertion « la dépendance est signalée » échoue aujourd'hui.

---

### E-03 — Aucun garde-fou mémoire avant le décodage d'un snapshot : un snapshot valide au plafond de 256 Mio peut provoquer un `OutOfMemoryError`
- **Qualification** : RISQUE (non mesuré dans ce travail ; l'amplification est une estimation depuis le code et l'ADR 0047)
- **Priorité** : P2. Le plafond d'octets persistés est explicite, mais le plafond de tas ne l'est pas, et l'entrée vient de code indexé non fiable.
- **Preuves** :
  - `FileSymbolSnapshotStore.java:269-283` (`buildQueryView`) : vérifie le checksum puis décode et construit l'index sans comparer à `Runtime.maxMemory()`. `estimateQueryViewWeight` (`:361-369`) ne sert qu'à décider de la mise en cache (`:247`). `PostgresCodeKnowledgeSnapshotStore.java:131-155` : idem.
  - Les verrous de construction sont par projet, 64 stripes (`FileSymbolSnapshotStore.java:43, 339-341`), sans sémaphore global : N projets distincts se chargent en parallèle.
  - Amplification : ADR 0047 mesure 194,6 Mo de fichier → 413 Mo relus → 494,7 Mo de vue (ratio 2,1 à 2,5 sur un corpus réel). Les décodeurs créent un objet par entité : une occurrence minimale encodée en V3 tient dans environ 100 octets (huit chaînes courtes, quatre entiers, énumérations) et coûte plusieurs centaines d'octets de tas (record, `Origin`, `SymbolLocation`, `Set.copyOf`, chaînes), plus la copie `List.copyOf` et les trois index. Un fichier de 256 Mio à entités minimales peut donc viser plusieurs Gio (ordre de grandeur à vérifier par test, pas une mesure).
  - Précédent dans le même dépôt : `ScipIngestionLimits.enforceDecodeHeapBudget` (`minos-provider-scip/.../ScipIngestionLimits.java:64-80`, budget = `maxHeap/3` plafonné à 1 Gio, appelé à `ScipIndexReader.java:58`). Rien d'équivalent à la lecture d'un snapshot (`grep maxMemory` dans les sources de production : un seul résultat, celui du lecteur SCIP).
  - Constantes de lecture : `SnapshotBinaryCodecSupport.java:75-84` (jusqu'à 100 M d'occurrences déclarées ; seule la taille de fichier borne réellement).
- **Comportement actuel** : le décodage tente l'allocation ; un `OutOfMemoryError` (Error, jamais converti en `IOException`) tue le thread, voire le serveur MCP long-vivant.
- **Comportement attendu + source** : ADR 0023 (« limites défensives de lecture ») et ADR 0046 (« lecture strictement bornée ») : toute entrée est bornée ; A9 reconnaît que le plafond « rend la limite explicite avant l'ingestion au lieu de la faire découvrir après ». Un refus explicite vaut mieux qu'un OOM.
- **Cause** : le plafond est exprimé en octets de fichier ; le tas dépend de la cardinalité, que le descripteur connaît pourtant (`symbolCount`, `occurrenceCount`, `relationshipCount`, lus dans le pointeur avant le décodage).
- **Impact** : arrêt du processus sur un gros projet ou un snapshot adverse ; plusieurs chargements concurrents aggravent le pic.
- **Correction minimale** : avant `codec.read`, estimer le tas depuis les comptes du descripteur (constantes de `estimateQueryViewWeight`, une fois ramenées à la mesure : A6.8) et lever une `IOException` explicite si l'estimation dépasse un budget dérivé de `Runtime.maxMemory()` ; limiter les chargements simultanés par un `Semaphore` du store. Même logique pour PostgreSQL (les comptes de `ActiveMetadata` sont déjà lus avant le téléchargement, `:329-349`).
- **Validation** : voir ci-dessous.
- **Dépendances** : cohérent avec ADR 0047 option A et avec les corrections A6.8 (poids estimé) ; à faire avant tout mappage mémoire.

Reproduction : `minos-storage-local/src/test/java/com/minos/storage/local/store/SnapshotHeapAdmissionTest.java`, classes `FileSymbolSnapshotStore` (constructeur package-private du test, `@TempDir`), `SnapshotCodecV3`, générateur d'entités minimales. Publier N≈1,5 M occurrences minimales (fichier de l'ordre de 150 Mio), lancer le test dans une JVM forcée à `-Xmx512m` (`<argLine>` ou test forké), puis `assertThrows(IOException.class, () -> store.loadActiveQueryView(projectId))` et `assertEquals("", ...)` sur l'absence d'`OutOfMemoryError`. Observé attendu aujourd'hui : `OutOfMemoryError`.

---

### E-04 — Le backlog et l'ADR 0055/0056 ne décrivent pas tout le couplage réel (4 écarts avec le code)
- **Qualification** : DÉCISION À CLARIFIER (cohérence ADR et backlog ↔ code)
- **Priorité** : P2 : corriger le plan avant d'implémenter SH-02/03/10/11 évite un critère d'acceptation impossible à tenir.
- **Preuves** :
  1. PostgreSQL dépend du module local pour plus que les codecs. ADR 0055 (« PostgreSQL dépend encore du module local pour les codecs ») et SH-03 ne citent que les codecs. Le code : `PostgresProjectRegistry.java:5,46` importe `com.minos.storage.local.registry.ProjectPathMappingStore` et lit un fichier du `MINOS_HOME` dans son constructeur (`new ProjectPathMappingStore(home).loadOptional()`). Les imports vers `local` dans le module PG sont exactement : `KnowledgeSnapshotCodecs`, `SnapshotCodec`, `SnapshotIntegrityService` (`PostgresCodeKnowledgeSnapshotStore.java:8-9,284,411`, `PostgresSnapshotPayloadCodec.java:4-5`) et `ProjectPathMappingStore`. Le critère « aucun import backend croisé » (SH-03) est donc intenable tant que `ProjectPathMappingStore` (qui implémente le port `ProjectPathMappings` d'engine) reste dans `com.minos.storage.local.registry`.
  2. Le format du pointeur partage des primitives package-private avec le codec : `ActiveSnapshotRepository.java:76-78,91-103` appelle `SnapshotBinaryCodecSupport.writeString/readRequiredString/readCount/readHeaderVersion` (javadoc `SnapshotBinaryCodecSupport.java:61-65` : « doivent rester une implémentation unique »), et duplique les plafonds `MAX_SYMBOLS/OCCURRENCES/RELATIONSHIPS` (`ActiveSnapshotRepository.java:33-35` = `SnapshotBinaryCodecSupport.java:75-77`). Déplacer le codec dans `com.minos.storage.codec` casse cet accès : il faut exposer ces primitives (API publique du package codec) ou déplacer le pointeur, ce que SH-03 ne dit pas.
  3. Perte d'un garde-fou Maven. Aujourd'hui `minos-bootstrap` ne voit PostgreSQL qu'en `test` (`minos-bootstrap/pom.xml`, et `BOOTSTRAP_TEST_ONLY_ADAPTERS` dans `check-module-boundaries.py:57`) ; `StorageBackendSelection.java:9-11` s'en prévaut (« PostgreSQL n'est jamais une dépendance de compilation de la composition »). Après fusion, `minos-bootstrap` dépendra de `minos-storage` en compile (pour `LocalStorageBackend`) : une classe de bootstrap pourra importer `com.minos.storage.postgresql.*`, et le contrôle de sources exempte justement bootstrap (`check-module-boundaries.py:312`). SH-11 ne cite pas la règle de remplacement « bootstrap ↛ `com.minos.storage.postgresql` hors `StorageBackendSelection` via SPI », ni un test « le mode local ne charge aucune classe JDBC ».
  4. SH-10 : le « Point d'entrée » cite `minos-engine/src/main/java/com/minos/io/StableFileSystemIdentity.java`, qui n'existe pas (le fichier est `minos-engine/src/main/java/com/minos/orchestration/StableFileSystemIdentity.java`, `TASKS.md:223`) ; il cite aussi `MinosApplicationComposers.java`, qui utilise déjà un chargeur explicite. Les vrais sites restants sont `StorageBackendSelection.java:23` et `StableFileSystemIdentity.java:46`. Sous Windows, ce second site est critique : toute capture d'identité passe par le SPI `ExecutionPathIdentityProvider` implémenté par `WindowsExecutionPathIdentityProvider` (`minos-runtime-local/.../META-INF/services`) ; un chargeur de contexte différent donne `Optional.empty()` et `IndexingRuntimePorts.java:148-158,185-190` refuse alors le lancement (fail-closed : indisponibilité, pas contournement).
  - Compléments sur SH-04 (ADR 0056) : l'inventaire réel est plus petit que ce que laisse penser la prudence de l'ADR. `SnapshotQueryView.queryStore()` n'est consommé en production qu'à `ProjectQueryService.java:74-77` ; les consommateurs de lecture reçoivent `CodeKnowledgeStore` (port lecture+écriture) : `SymbolQueryService.java:16-18`, `RelationshipQueryService.java:19-21` (engine), `CodeSearchService.java:34-37` (application). Preuve de l'excès d'interface : `ScipSymbolSnapshotImporter.CapturingStore.java:247-269` implémente 4 méthodes de lecture par `UnsupportedOperationException("... write-only")`. Les producteurs de la vue sont `FileSymbolSnapshotStore.java:282` et `PostgresCodeKnowledgeSnapshotStore.java:152`.
  - JaCoCo : `scripts/quality/check-jacoco.py:17-19` et `:173` référencent des préfixes littéraux de classes (`com/minos/storage/local/store/SnapshotCodec`, `.../ActiveSnapshotRepository`, `com/minos/storage/postgresql/`, `com/minos/store/InMemoryCodeKnowledgeStore`) : tout déplacement de package doit rejouer ce script (config.yaml : « gates … affirment des chaînes littérales »). SH-02 mentionne JaCoCo mais pas ces préfixes.
- **Comportement attendu + source** : `openspec/config.yaml` (frontières vérifiables, gates littéraux) ; ADR 0055 (« local et postgresql ne dépendent pas l'un de l'autre »).
- **Cause** : le plan a été rédigé sur la structure des classes de codec, pas sur la totalité des imports.
- **Impact** : SH-03 bloquée à la recette ; SH-02 perd silencieusement une protection ; SH-10 vise un fichier inexistant.
- **Correction minimale** : amender TASKS.md/ADR 0055 : (a) déplacer `ProjectPathMappingStore` vers un package engine/neutre ou l'inclure explicitement dans le périmètre de SH-03 ; (b) décider qui possède le cadrage du pointeur (API codec publique vs déplacement de `ActiveSnapshotRepository`) ; (c) ajouter à SH-11 la règle « bootstrap ne référence `storage.postgresql` que par SPI » et un test de non-chargement JDBC en mode local ; (d) corriger le chemin de SH-10 et lister les deux vrais sites ; (e) lister les préfixes `check-jacoco.py` concernés.
- **Validation** : `python scripts/docs/check-current-docs.py` ; revue de TASKS.md ; test d'architecture négatif décrit en SH-11 pour (c).
- **Dépendances** : à traiter avant SH-02 ; (c) dépend de E-02.

---

### E-05 — `minos doctor` inspecte des répertoires qui n'existent pas et n'inspecte pas les vrais magasins
- **Qualification** : DÉFAUT CONFIRMÉ
- **Priorité** : P3 : diagnostic uniquement ; les droits sont posés à la création par `PrivateLocalStorage`, mais le contrôle de visibilité promis par `doctor` ne couvre pas les magasins sensibles.
- **Preuves** :
  - `minos-cli/src/main/java/com/minos/cli/DoctorCommand.java:238-248` boucle sur `"", "snapshots", "projects", "runs", "semantic-vectors", "runtime-observations", "remote-cache", "remote-cache/repositories", "distributed-artifacts", "postgresql-snapshot-scratch"` et rapporte `PrivateLocalStorage.privacyOf` (renvoie `ABSENT` pour un chemin inexistant : `PrivateLocalStorage.java:301-303`).
  - Les espaces réels sont définis dans `LocalStorageBackend.java:36-41` : `registry`, `symbol-snapshots`, `index-state`, `fingerprint-snapshots`, `semantic-index`, `runtime-observations` ; plus `staged-snapshots` (`ScipProjectSnapshotLifecycle.java:50`), `runs`, `retention-locks` (`LocalStorageRetentionService.java:62`), `.project-mutation-leases` (`SnapshotProjectLease.java:30`). Seuls `runtime-observations`, `runs`, `postgresql-snapshot-scratch` et la racine coïncident ; `snapshots`, `projects`, `semantic-vectors` n'existent pas (aucune occurrence dans `minos-storage-local/src/main`).
  - Le golden `minos-app/src/test/resources/characterization/cli-doctor.golden:28-35` pin ces mauvais noms (valeurs normalisées en `<host>`, donc le test ne voit pas que les clés sont fausses).
- **Comportement actuel** : le rapport affiche `ABSENT` pour des répertoires fantômes et ne dit rien de `symbol-snapshots`, `registry`, `index-state`, `fingerprint-snapshots`, `semantic-index`, `staged-snapshots`.
- **Attendu + source** : Javadoc de la méthode (`DoctorCommand.java:232-235` : EXPOSED = accès au-delà du propriétaire, réparé à la prochaine ouverture) ; politique `LOCAL_PERSISTENCE_POLICY.md` (état autoritatif et privé).
- **Cause** : liste codée en dur de l'ancienne disposition (avant M15/ADR 0044), jamais rapprochée du backend.
- **Impact** : fausse assurance de confidentialité sur le contenu dérivé du code.
- **Correction minimale** : faire exposer les espaces par le backend (liste fournie par `LocalStorageBackend`/`StorageBackend`, additive) plutôt que par une liste de la CLI ; le contrat CLI est versionné et additif, donc garder les anciennes clés si la compatibilité du golden l'exige et ajouter les vraies. Mettre à jour le golden avec une preuve explicite (config.yaml : ne pas régénérer les golden pour verdir).
- **Validation** : test dans `minos-cli/src/test/java/com/minos/cli/DoctorCommandTest.java` : home contenant les espaces créés par `new LocalStorageBackend(home)`, le rapport contient les clés `symbol-snapshots`, `registry`, `index-state`, `fingerprint-snapshots`, `semantic-index` avec `ENFORCED` ; sous POSIX, après `chmod 777` de `symbol-snapshots`, le rapport indique `EXPOSED`.
- **Dépendances** : ADR 0055 (si les espaces migrent), contrat CLI du golden.

---

### E-06 — PostgreSQL : un seul verrou advisory sert à la fois de bail de cycle de vie et de verrou de mutation, avec une attente serveur non bornée
- **Qualification** : RISQUE
- **Priorité** : P3 : contention et erreurs génériques, sans corruption.
- **Preuves** :
  - `PostgresProjectMutationLock.java:17-20` (clé = `msb ^ lsb` de l'UUID) et `:27` (`pg_advisory_xact_lock`) ; `ProjectMutationIndexStateStore.java:194` (`pg_try_advisory_lock` de session, même clé) : un bail de cycle de vie tenu pendant toute l'indexation bloque toute mutation du projet issue d'une autre session (retention, `saveProjectState` d'un autre processus, synchro sémantique).
  - Aucune borne côté serveur : `grep lock_timeout|statement_timeout` dans le module PG : aucun résultat. La seule limite est `socketTimeout=120 s` (`PostgresConnectionFactory.java:24,468`), qui fait échouer l'appel avec un `SQLException` d'état `08xxx` ; `isConnectionFailure` (`PostgresConnectionFactory.java:504-510`) marque alors la connexion non réutilisable, et l'erreur remontée est générique (« unable to apply PostgreSQL persistent retention », `PostgresStorageRetentionService.java:33-35`).
  - Le test `PostgresRetentionMutationLockTest.java:18` et `ProjectMutationIndexStateStoreTest.sameProjectMutationWaitsForSharedLock` valident l'attente, pas son terme.
  - `PostgresCodeKnowledgeSnapshotStore.java:229-239` : le verrou est tenu pendant l'envoi du payload (`insertSnapshot`, jusqu'à 256 Mio) ; en local, le bail ne couvre que le renommage et la promotion (`FileSymbolSnapshotStore.java:310-317`).
- **Attendu + source** : AUDIT R9 : « tous les autres baux sont bornés à 10 s » ; ordre de prise L1 < L3 < L2 < L4 (FIAB-SUIVI, ADR 0039). Le local lève un message clair après 10 s (`SnapshotProjectLease.DEFAULT_ACQUIRE_TIMEOUT`).
- **Cause** : réutilisation d'une clé unique pour deux rôles et absence de `lock_timeout`.
- **Impact** : avec PostgreSQL, une mutation concurrente d'une longue indexation échoue au bout de 120 s avec une erreur de connexion et détruit la connexion du pool, au lieu d'un refus borné et lisible.
- **Correction minimale** : `SET LOCAL lock_timeout = '10s'` avant `pg_advisory_xact_lock` (ou boucle `pg_try_advisory_xact_lock` bornée comme le bail), mapper l'échec `55P03` vers un message dédié ; ne pas modifier la clé (décision d'ordre de verrous à part).
- **Validation** : test `PostgresLifecycleLockBoundedWaitTest extends PostgresTestSupport` : une connexion tient `pg_advisory_lock(PostgresProjectMutationLock.key(id))` ; un second thread appelle `new PostgresStorageRetentionService(connections).compact(id, policy)` ; attendre une `IOException` dont le message nomme le délai, en moins de ~15 s, et vérifier que le pool a toujours le même nombre de connexions physiques.
- **Dépendances** : aucune ; indépendant de SH-02.

---

### E-07 — Hybride : snapshot et index sémantique lus à des instants différents ; l'identité de snapshot n'est pas garantie pendant une promotion
- **Qualification** : RISQUE
- **Priorité** : P3 (fenêtre étroite, résultat DERIVED/HEURISTIC), mais c'est le critère de validation « cohérence pendant promotion concurrente » de l'ADR 0056 et le correctif A6.8 doit le couvrir.
- **Preuves** :
  - `HybridSearchService.java:59` charge le snapshot A (`loadActiveKnowledge`) ; `:61` appelle `semanticIndex.status(...)`, qui recharge `loadActiveKnowledge` (`SemanticIndexService.java:66-68`) et compare à l'index stocké ; `:64` `activeIndex(...)` refait `status(project)` (`SemanticIndexService.java:59-63`) puis `store.load(...)` : trois lectures successives du snapshot actif et de l'index.
  - La clé de corpus mêle le `snapshotId` de A et le `providerId/modelId/builtAt` de l'index relu après (`HybridSearchService.java:68-72`), et la réponse annonce `snapshot.snapshotId()` (`:100`).
  - Une promotion (avec synchro sémantique) entre `:59` et `:64` donne : `status` READY pour B, documents sémantiques de B, graphe et corpus structuré de A, étiquetés A.
- **Attendu + source** : ADR 0056 : « préserver une identité de snapshot cohérente sur toute requête » ; ADR 0024 invariants.
- **Cause** : la vue cohérente (`SnapshotQueryView`) n'est utilisée que par `ProjectQueryService` ; les services hybrides recomposent plusieurs lectures.
- **Impact** : résultat hybride mélangé, rare, non reproductible.
- **Correction minimale** : passer `snapshot` à `SemanticIndexService` (statut et index évalués contre le snapshot déjà chargé, jamais rechargé) ; refuser/relire si `activeIndex.snapshotId() != snapshot.snapshotId()`. Cette même suppression de lecture résout le « double chargement » d'A6.8.
- **Validation** : test dans `minos-application` (`HybridSearchServiceSnapshotConsistencyTest`) avec un `CodeKnowledgeSnapshotStore` double qui promeut B après le premier `loadActiveKnowledge` ; la réponse ne doit contenir que des documents de A ou n'être jamais mixte.
- **Dépendances** : SH-04 (port de lecture), A6.8.

---

### E-08 — PostgreSQL : le répertoire scratch n'est jamais balayé, et chaque lecture non mise en cache passe trois fois par le disque
- **Qualification** : RISQUE (fuite de disque du même type que R8)
- **Priorité** : P3.
- **Preuves** :
  - `PostgresCodeKnowledgeSnapshotStore.java:49,63-72,380-394` : un fichier `snapshot-write-/-read-/-existing-<uuid>.knowledge` par opération, supprimé en `finally`. Un processus tué en cours d'écriture ou de lecture laisse jusqu'à 256 Mio (`SIGKILL`, arrêt de l'IDE).
  - Aucun balayage : `grep postgresql-snapshot-scratch` ne trouve que `DoctorCommand.java:242` (diagnostic de droits) et des tests ; le balayage des orphelins du magasin fichier (`SnapshotRetentionService.deleteOrphanPreparedSnapshots`, 24 h) ne couvre que `.snapshot-*.tmp` du répertoire projet local.
  - Chemin de lecture : `activeRow` (`:351-378`) copie tout le payload du `bytea` vers le scratch, `decodeVerified` (`:409-424`) le relit pour le checksum (`SnapshotIntegrityService.checksum`) puis une seconde fois pour le décodage (`codec.decode`). `Files.size(...)` (`:138`) est appelé avant `decodeVerified` et hors du `finally` qui supprime le fichier : s'il lève, le scratch fuit.
  - Les vues > 64 Mio ne sont jamais mises en cache (poids `8 ×`) : ce chemin est donc exécuté à chaque requête d'un serveur MCP PG sur un gros projet.
  - Non vérifié : le comportement mémoire de `ResultSet.getBinaryStream` de pgjdbc sur un `bytea` de 256 Mio (tamponnage éventuel du résultat entier avant la copie). À mesurer avant d'agir.
- **Attendu + source** : ADR 0039 §4 (orphelins réclamés après 24 h pour le local) ; `LOCAL_PERSISTENCE_POLICY.md`.
- **Impact** : disque rempli lentement ; latence de lecture ×3 en E/S.
- **Correction minimale** : balayer à l'ouverture les fichiers du scratch plus vieux que le TTL (même code que `deleteOrphanPreparedSnapshotsLocked`), `Files.size` dans le `try/finally`, et fusionner vérification de checksum et décodage en un seul parcours (digest pendant la copie depuis le `bytea`).
- **Validation** : test `PostgresSnapshotScratchSweepTest` : créer des fichiers anciens et récents dans le scratch d'un `MINOS_HOME` jetable, ouvrir le store, vérifier que seuls les anciens disparaissent.
- **Dépendances** : SH-02 (déplacement du store).

---

### E-09 — Toute lecture d'un snapshot actif construit l'index complet, même quand l'appelant n'a besoin que du snapshot
- **Qualification** : AMÉLIORATION
- **Priorité** : P3.
- **Preuves** :
  - `FileSymbolSnapshotStore.java:158-160` : `loadActiveKnowledge` = `loadActiveQueryView().map(SnapshotQueryView::snapshot)` ; `loadActiveQueryView` passe par `buildQueryView` (`:269-283`) qui construit toujours `new InMemoryCodeKnowledgeStore(snapshot)` (trois jeux d'index et leurs copies `List.copyOf`, `InMemoryCodeKnowledgeStore.java:244-248, 405-409`). Même chose côté PG (`PostgresCodeKnowledgeSnapshotStore.java:108-110, 142`).
  - Au moins 15 appels de production n'ont besoin que du `CodeKnowledgeSnapshot` : impact, architecture, graphe de programme, NEXUS, synchro sémantique, hôte, `ScipProjectSnapshotLifecycle.java:97,130,158` (un snapshot par provider et par scope, tous gardés simultanément en tas dans `stage`). ADR 0047 mesure la vue à 494,7 Mo contre 413,2 Mo pour le snapshot nu (≈ +20 % de tas, plus le temps de construction).
- **Attendu + source** : ADR 0024 : les index sont une optimisation reconstruisible, pas une obligation de chaque lecture.
- **Correction minimale** : ajouter au port une lecture du snapshot seul (ou construire l'index paresseusement dans `SnapshotQueryView`), sans changer les contrats existants ; à rattacher à SH-04.
- **Validation** : test de cache (`FileSymbolSnapshotStoreCacheTest`) : `loadActiveKnowledge` n'incrémente pas `queryViewBuilds` ; les résultats de requête restent identiques sur les fixtures existantes.
- **Dépendances** : SH-04.

---

### E-10 — Quatre cycles de dépendances entre packages, non gardés
- **Qualification** : AMÉLIORATION (hors contrat actuel, mais ADR 0057 §7 vise explicitement « cycles »)
- **Priorité** : P3.
- **Preuves** (`scratchpad/pkgcycles.py`, imports de production, composantes fortement connexes) :
  - `minos-application` : 8 packages dans une même composante (`com.minos.application`, `.dynamic`, `.semantic`, `com.minos.architecture`, `impact`, `output`, `program.analysis`, `workspace`). Les arêtes de retour viennent de `com.minos.application.ProjectResolver` (importé par `dynamic`, `semantic`, `architecture`, `impact`, `program.analysis`, `workspace`) et de `ProjectSummary` (importé par `output`) ; `output → semantic/dynamic/architecture/impact/program.analysis` ferme les cycles.
  - `minos-engine` : `com.minos.discovery` ↔ `com.minos.discovery.spi` (`ProjectDiscovery.BuildSystem` etc. d'un côté, détecteurs SPI de l'autre) ; `com.minos.incremental` ↔ `com.minos.orchestration` (14 imports vers orchestration, 3 retours).
  - `minos-intellij` (client externe) : `intellij.protocol` ↔ `ui` ↔ `service`.
  - Le checker ne contrôle aucun cycle de packages (seulement le graphe Maven, `check_dependency_policy`).
- **Impact** : coût de refactoring (SH-05/09) et empêche des extractions propres ; pas de défaut fonctionnel.
- **Correction minimale** : déplacer `ProjectResolver`/`ProjectSummary` dans un package feuille (`com.minos.application.project`), puis ajouter une règle de cycles figeant l'état courant (liste de cycles tolérés qui ne peut que diminuer) dans le checker, comme l'A3 l'a fait en cliquet.
- **Validation** : auto-test Python du détecteur sur arborescence jetable.
- **Dépendances** : SH-05, SH-09, SH-11.

---

### E-11 — ADR « Accepted » partiellement périmés et code mort public
- **Qualification** : DÉCISION À CLARIFIER
- **Priorité** : P3.
- **Preuves** :
  - ADR 0023 (« Accepted ») : (a) « fallback `REPLACE_EXISTING` lorsque `ATOMIC_MOVE` n'est pas supporté » ; le code lève `IOException("filesystem does not support required atomic ...")` (`DurableAtomicFile.java:111-112`), conforme à `LOCAL_PERSISTENCE_POLICY.md` ; (b) « aucune politique automatique de rétention » ; il existe désormais `SnapshotRetentionPolicy.defaults()` (2 historiques, `SnapshotRetentionPolicy.java`) et `LocalStorageRetentionService` ; (c) ne cite que les codecs V1/V2 (V3 est ADR 0046).
  - ADR 0024 : « cache borné en nombre d'entrées, pas encore en octets » ; le code borne aussi le poids (`FileSymbolSnapshotStore.java:41,247,344`).
  - ADR 0022 : « 12 modules enfants + le parent = 13 projets » ; le reactor en a 14 enfants (`pom.xml:23-38`, `minos-bootstrap` et `minos-storage-postgresql` ajoutés). `docs/architecture/SYNTHESE.md:11,79,116` (daté du 2026-08-06) répète « 13 projets » et que `minos-storage-postgresql` dépend de `minos-application` (« confirmé intentionnel »), alors que le POM ne le déclare plus (A2, `71e697c7`). Le checker de docs courantes n'attrape pas ce fichier.
  - Code mort public : `CodeKnowledgeSnapshotBinaryCodec.java` (pont « V2 canonique »), `SnapshotCodecV2.encodeToBytes/decodeFromBytes` (`:44-54`) et `SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV2ToBytes/readKnowledgeSnapshotV2FromBytes` (`:252-264, 300-313`) n'ont aucun appelant (production, tests, scripts). Le premier est cité dans `docs/user/java-api.md:332` comme classe renommée : à traiter comme API publique avant suppression (ADR 0055 impose de ne rien casser sans analyse). Il fige aussi V2 alors que la politique d'écriture est V3.
- **Correction minimale** : ajouter une ligne « amendé par » dans ADR 0022/0023/0024 ; mettre SYNTHESE.md au statut « historique » ou le régénérer ; décider (public vs supprimable) pour le pont binaire avant SH-03, qui déplace de toute façon ces classes.
- **Validation** : `python scripts/docs/check-current-docs.py` ; grep des appelants avant suppression.
- **Dépendances** : SH-03, SH-12.

---

### E-12 — PostgreSQL exécute des DDL (extension, schéma, migrations) à chaque ouverture du backend, y compris pour une commande de lecture
- **Qualification** : DÉCISION À CLARIFIER
- **Priorité** : P3.
- **Preuves** : `PostgresStorageBackend.java:30-31` (`new PostgresSchemaMigrator(connections).migrate()` dans le constructeur) ; `PostgresSchemaMigrator.java:20-65` : verrou advisory global, `CREATE EXTENSION IF NOT EXISTS vector`, `CREATE SCHEMA IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS schema_version`. Aucune séparation entre un rôle de migration (DDL) et un rôle d'exécution (DML).
- **Comportement actuel** : un rôle PostgreSQL « lecture/écriture de données seulement » ne peut pas ouvrir MINOS ; toute commande, même `project list`, prend le verrou de bootstrap global (sérialise tous les démarrages, tous schémas confondus, `:43`). Hypothèse à vérifier par test : `CREATE SCHEMA IF NOT EXISTS` exige le droit `CREATE` sur la base même quand le schéma existe.
- **Attendu + source** : ADR 0025 (backend avancé optionnel) n'impose rien ; R12 de l'audit (une lecture ne doit pas écrire) est l'équivalent local, déjà ouvert.
- **Décision à prendre** : accepter ce régime (documenter qu'un seul rôle propriétaire est supporté) ou séparer `migrate` d'`open` (option `MINOS_POSTGRES_MIGRATE=false` + vérification de version en lecture seule).
- **Validation** : test Testcontainers : rôle sans `CREATE` sur la base, schéma déjà migré, `PostgresStorageBackendProvider.open` doit réussir si la décision est de supporter le moindre privilège.
- **Dépendances** : R12 ; SH-02.

## 2. Ce que le code fait déjà correctement (vérifié, pas de constat)

- Lecture bornée du snapshot V3 : longueurs d'octets bornées avant lecture (`SnapshotBinaryCodecSupport.java:948-960`), UTF-8 strict en `REPORT` (`:931-933`), `readNBytes` sans préallocation de la longueur déclarée, comptes bornés (`readCount`, `:1018-1024`), capacité initiale plafonnée (`:92-95`), fichiers bornés (`requireSnapshotFileSize`, `:350-356`) et ouverts sans suivre les liens (`ConfinedFileOpener.openRegularFileNoFollow`). Les invariants de domaine (`Symbol`, `SymbolOccurrence`, `Relationship`, `Evidence`, `SymbolLocation`) sont symétriques avec `readRequiredString`/`readEnum` : aucun snapshot écrit par le codec ne devient illisible pour une raison de validation (relu un par un). Mineur, sans conséquence : `new ArrayList<>(evidenceCount)` (`:590`, jusqu'à 1 M) contredit la politique de `initialCapacity`, mais un compte déclaré doit être honoré par des données réelles.
- Publication : snapshot publié avant promotion du pointeur, sous bail de projet, avec `forceFile` avant le renommage (`DurableAtomicFile.java:94-110`) ; `Files.size == encodedBytes` vérifié (`FileSymbolSnapshotStore.java:302`) ; nom contenant le checksum du contenu (aucune écrasure d'un contenu différent).
- Cache : relecture du descripteur avant et après construction (`FileSymbolSnapshotStore.java:198-205`) ; la clé PG inclut le sha256 et se déduit des métadonnées fraîches à chaque requête (`PostgresCodeKnowledgeSnapshotStore.java:116-120`), donc un hit ne masque pas une promotion.
- Politique d'URL JDBC : paramètres autorisés limités à `sslmode`, hôte non loopback ⇒ `verify-full`, identifiants refusés dans l'URL, pas de multi-hôte (`getHost()` nul) ; résultats cohérents avec l'ADR de durcissement M30.
- Gestion du commit incertain côté PG (`PostgresConnectionFactory.commitTransaction`, `CommitUncertainException`).

## 3. Carte réelle (données pour un diagramme C4 niveau conteneur)

Reactor racine `com.minos:minos-parent` `${revision}` = `1.3.0-SNAPSHOT`, Java 24 (enforcer `[24,25)`), Maven `[3.9,4.0)`. 14 modules enfants (ordre `<modules>` : domain, engine, runtime-local, storage-local, provider-scip, integration-git, application, storage-postgresql, bootstrap, nexus, cli, api, mcp, app). Hors reactor : `minos-intellij` (Gradle, Java 21, client du protocole CLI ; aucune dépendance `com.minos:*` dans `build.gradle.kts`, aucun import `com.minos.{domain,engine,store,application,api,cli}` dans `src/main`).

Hérité du parent par tous les modules : `org.junit.jupiter:junit-jupiter:6.1.3` (test) ; plugins compilateur (`release 24`), surefire (`useModulePath=false`, `workingDirectory=${maven.multiModuleProjectDirectory}`, propriété `minos.postgresql.tests.required`), jacoco (`prepare-agent` en `append`, `report` en `verify`). `dependencyManagement` du parent : BOM Jackson 2 (`2.22.3`), BOM Jackson 3 (`3.2.3`), BOM MCP SDK (`2.0.1`), `slf4j-nop 2.0.20`.

Dépendances déclarées par POM (compile sauf mention ; « tj » = `<type>test-jar</type>`) :

| Module (artefact) | MINOS compile | MINOS runtime | MINOS test | Externes déclarés |
|---|---|---|---|---|
| `minos-domain` | — | — | — | — |
| `minos-engine` | domain | — | — | — (produit un test-jar) |
| `minos-runtime-local` | engine | — | — | — (surefire : `MINOS_TEST_SECRET_SENTINEL`) |
| `minos-storage-local` | engine | — | engine (tj) | — |
| `minos-storage-postgresql` | domain, engine, storage-local | — | — | `org.postgresql:postgresql:42.7.13` ; `jackson-databind`, `jackson-datatype-jdk8`, `jackson-datatype-jsr310` (BOM) ; test : `testcontainers:testcontainers:2.0.5`, `org.testcontainers:junit-jupiter:1.21.4`, `org.testcontainers:postgresql:1.21.4`, `slf4j-simple:2.0.20` ; produit un test-jar ; profil Windows Docker Desktop |
| `minos-provider-scip` | domain, engine, storage-local, runtime-local | — | — | `jackson-databind` ; `org.scip-code:scip-java-bindings:0.10.0` |
| `minos-integration-git` | engine | — | — | `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r` |
| `minos-application` | domain, engine | — | — | `jackson-databind` |
| `minos-bootstrap` | domain, engine, application, runtime-local, storage-local, provider-scip, integration-git | — | storage-postgresql, storage-postgresql (tj) | test : testcontainers 2.0.5 / junit-jupiter 1.21.4 / postgresql 1.21.4 ; produit un test-jar ; profil Windows Docker |
| `minos-nexus` | domain, application | — | bootstrap | — |
| `minos-cli` | domain, engine, application, nexus | bootstrap | bootstrap (tj), engine (tj) | — |
| `minos-api` | domain, engine, application | bootstrap | bootstrap (tj) | — |
| `minos-mcp` | application | bootstrap | bootstrap (tj) | `io.modelcontextprotocol.sdk:mcp` (BOM 2.0.1) |
| `minos-app` (`minos-code-intelligence`) | domain, engine, runtime-local, storage-local, provider-scip, integration-git, application, storage-postgresql, bootstrap, nexus, cli, api, mcp | — | — | `slf4j-nop` ; plugins : failsafe, jar (`mainClass com.minos.cli.MinosLauncher`), shade (`ServicesResourceTransformer`, classifieur `all`), jacoco `report-aggregate` ; répertoire de build = `target/` racine |

Services SPI (`META-INF/services`) : `MinosApplicationComposer` → `com.minos.bootstrap.DefaultMinosApplicationComposer` (bootstrap) ; `StorageBackendProvider` → `com.minos.storage.postgresql.PostgresStorageBackendProvider` (storage-postgresql) ; `ExecutionPathIdentityProvider` → `com.minos.runtime.local.WindowsExecutionPathIdentityProvider` (runtime-local) ; `McpLaunchRoute` → `com.minos.app.McpLaunchRouteProvider` (app) ; `StorageBackendProvider` de test (`CloseCountingStorageProvider`) dans les ressources de test de api/cli/mcp.

Taille et tests (fichiers Java `src/main` / `src/test`) :

| Module | main | test | Packages de production |
|---|---|---|---|
| domain | 37 | 6 | `com.minos.domain`, `.program`, `.semantic` |
| engine | 165 | 95 | `diagnostics, discovery(.spi), dynamic, git, hosted, incremental, io, orchestration, query, registry, remote, runtime, source, storage, store` |
| runtime-local | 37 | 58 | `com.minos.runtime.local` |
| storage-local | 31 | 62 | `com.minos.storage.local(.incremental,.orchestration,.registry,.store)` |
| storage-postgresql | 18 | 17 | `com.minos.storage.postgresql` |
| provider-scip | 40 | 39 | `com.minos.adapter.scip(.runtime)` |
| integration-git | 6 | 7 | `com.minos.integration.git` |
| application | 108 | 38 | `application(.dynamic,.semantic), architecture, context, impact, output, program.analysis, workspace` |
| bootstrap | 6 | 41 | `com.minos.bootstrap` |
| nexus | 4 | 3 | `com.minos.nexus` |
| cli | 38 | 71 | `com.minos.cli` |
| api | 13 | 17 | `com.minos.api` |
| mcp | 8 | 11 | `com.minos.mcp` |
| app | 7 | 39 | `com.minos.app`, `com.minos.integration.nexus` |

Classes principales de la persistance :
- Ports (engine) : `com.minos.store.CodeKnowledgeSnapshotStore`, `CodeKnowledgeStore`, `CodeKnowledgeSnapshot`, `SnapshotDescriptor`, `SnapshotQueryView` (expose `InMemoryCodeKnowledgeStore queryStore()`), `InMemoryCodeKnowledgeStore` ; `com.minos.storage.StorageBackend/StorageBackendProvider/StorageRetentionService` ; `com.minos.io.{DurableAtomicFile,PrivateLocalStorage,ConfinedFileOpener,BoundedFileLease,BoundedInputStream,BoundedOutputStream}`.
- Local (`com.minos.storage.local.store`) : `FileSymbolSnapshotStore` (façade, cache LRU 32 entrées / 512 Mio de poids estimé, 64 stripes de verrous), `SnapshotRepository`, `ActiveSnapshotRepository` (format du pointeur `0x4D4E4150`), `KnowledgeSnapshotCodecs` (politique V3, repli V2, V1 historique), `SnapshotCodecV1/V2/V3`, `SnapshotBinaryCodecSupport` (magic `0x4D4E5359`, plafond 256 MiB), `SnapshotIntegrityService`, `SnapshotRetentionService/Policy`, `SnapshotCompactionService`, `SnapshotProjectLease` ; `LocalStorageBackend`, `LocalStorageRetentionService` (ordre de verrous : cycle de vie, rétention, bail de mutation).
- PostgreSQL : `PostgresStorageBackend(Provider)`, `PostgresConnectionFactory` (pool maison 8 connexions, budget dédié aux baux de session), `PostgresSchemaMigrator` (version 4), `PostgresCodeKnowledgeSnapshotStore` (payload = fichier de snapshot dans un `bytea`, scratch local), `PostgresIndexStateStore` + `ProjectMutationIndexStateStore`, `PostgresStorageRetentionService`, `PostgresProjectMutationLock`, `PostgresJdbcUrlPolicy`.

Lacunes de tests constatées (non réexécutées ; selon les fichiers) :
- aucun test de contrat commun Local/PostgreSQL (état d'index, rétention, snapshots) ;
- aucun test PG sur la reprise (E-01) ni sur la borne d'attente de verrou (E-06) ;
- aucun test d'admission mémoire du décodage (E-03) ;
- aucun auto-test des règles A2 du checker (E-02) ;
- `DoctorCommandTest` ne valide pas les noms de répertoires (E-05) ;
- les tests de `InMemoryCodeKnowledgeStore` vivent dans `minos-storage-local` (package `com.minos.storage.local.store`), pas dans `minos-engine` où la classe est définie.

Cohérence ADR ↔ code (statuts « Accepted ») :
- 0003, 0006, 0022, 0023, 0024, 0025 : implémentés ; 0022, 0023, 0024 partiellement périmés (E-11).
- 0042, 0044, 0045 : implémentés (checker vert, un package = un module) ; l'auto-test annoncé en 0042 §8.4 n'existe que pour A3/A7 (E-02).
- 0046 : implémenté et conforme (V3 par défaut, repli V2 sur surrogate isolé, V1/V2 lisibles, pointeur 3 → V3, plafond identique PG/local via `KnowledgeSnapshotCodecs.select`).
- 0047 : `Proposed`, rien d'implémenté ; l'option A (internement au décodage) n'a pas été commencée (`readString` crée toujours une instance par champ).
- 0055 : rien d'implémenté (deux modules, PG dépend du local) ; plan incomplet (E-04).
- 0056 : rien d'implémenté ; périmètre réel plus petit qu'annoncé (E-04) ; E-07 et E-09 s'y rattachent.
- 0057 : la direction est partiellement amorcée (bootstrap en place, application sans adaptateur) ; points résiduels cités dans l'ADR vérifiés présents : `LocalAutonomousIndexOperations` dans la CLI, `OllamaEmbeddingProvider` dans application, `com.minos.io` dans engine, staging local concret dans `ScipProjectSnapshotLifecycle` (`new FileSymbolSnapshotStore(...)` lignes 40, 81, 110, 129).

## 4. Ce que je n'ai PAS examiné

- Les magasins hors snapshot : `FileIndexStateStore` (au-delà de la propriété `resumableRunId`), `FileProjectFingerprintSnapshotStore`, `FileSemanticVectorStore`, `FileHostedControlPlaneStore`, `FileRuntimeObservationStore`, le registre local ; côté PG : `PostgresFingerprintSnapshotStore`, `PostgresSemantic*`, `PostgresRuntimeObservationStore`, `PostgresProjectRegistry` (hors constructeur), `PostgresJsonCodec`.
- Les primitives d'E/S durcies (`PrivateLocalStorage`, `ConfinedFileOpener`, `BoundedFileLease`) au-delà de ce que le chemin des snapshots utilise ; `check-private-io.py`.
- Le comportement de pgjdbc sur les `bytea` volumineux (tamponnage), la cause exacte des erreurs de verrou PG au-delà du code, les droits PostgreSQL requis par `CREATE SCHEMA IF NOT EXISTS` : à mesurer ou à tester.
- Aucune mesure de tas ni d'exécution de test (Maven interdit) : E-03 repose sur le code et sur les mesures de l'ADR 0047.
- Les fixtures `snapshot-formats/`, le contenu de `scripts/remediation/check-*.py` (littéraux liés aux noms de classes de stockage : à rejouer avant tout renommage, SH-02/03).
- Les workflows CI au-delà de `pr-ci.yml:93-102`, le packaging, Docker, `minos-intellij` (hors dépendances), la sandbox, les surfaces CLI/API/MCP/NEXUS (hors imports et POM).

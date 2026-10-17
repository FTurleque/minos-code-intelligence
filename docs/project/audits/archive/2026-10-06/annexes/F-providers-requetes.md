<!-- Annexe F de l'audit 2026-10. Rapport d'analyse brut, conservé tel que remis ; les identifiants F-NN y valent MINOS-AUD-FNN dans docs/audit/archive/2026-10-06/constats.md. Les chemins « scratchpad/ » cités désignent des reproductions jetables hors dépôt, non conservées. -->
# Audit F — Providers SCIP, modèle de connaissance, requêtes bornées, couche sémantique, runtime

Dépôt : `N:\workspace-dev\minos-code-intelligence`, HEAD `bc1d3421` (develop). Lecture seule : aucun fichier du dépôt modifié, aucun build lancé, MCP `minos` non utilisé.
Tous les chemins sont relatifs à la racine du dépôt. Les numéros de ligne ont été relevés par lecture du code au HEAD ci-dessus.

Constats déjà clos (Q6, Q7, Q12, Q13) non re-signalés. A5 et A6 sont ouverts : A5 est complété par du nouveau (F-09, nuance sur les lignes en carte), et la partie A6 « `maxResults` appliqué après traversée » n'est pas re-signalée.

Synthèse :

| ID | Titre | Qualification | Priorité |
|---|---|---|---|
| F-01 | Impact, appelants et dépendances inter-modules aveugles aux occurrences SCIP, sans limitation déclarée | DÉFAUT CONFIRMÉ | P1 |
| F-02 | Tests liés : classification « test » non polyglotte et ancre unique par fichier | DÉFAUT CONFIRMÉ | P2 |
| F-03 | Corrélation runtime ligne→symbole : seule la plage de l'identifiant est connue | DÉFAUT CONFIRMÉ | P2 |
| F-04 | `Symbol.moduleId` jamais renseigné par l'indexation autonome | DÉFAUT CONFIRMÉ | P2 |
| F-05 | Recherche hybride : pas de repli quand le provider d'embeddings échoue, limitations sémantiques perdues | DÉFAUT CONFIRMÉ | P2 |
| F-06 | Aucun garde de fraîcheur entre source sur disque et snapshot (documents sémantiques, extraits) | DÉCISION À CLARIFIER | P2 |
| F-07 | Entrées SCIP anormales : politique incohérente (abandon, saut silencieux, identifiant opaque) | RISQUE | P3 |
| F-08 | `occurrenceId` sans les rôles : fusion silencieuse d'occurrences | RISQUE | P3 |
| F-09 | Descripteur vs profil de capacités : dérive concrète qui change la négociation (complète A5) | DÉFAUT CONFIRMÉ | P3 |
| F-10 | Extracteurs de nom/genre : surcharges `(+n).` et genres SCIP non mappés | DÉFAUT CONFIRMÉ (repli) + AMÉLIORATION | P3 |
| F-11 | Binaires opérés (scip-clang, rust-analyzer, coursier, Maven, Node) acceptés sans vérification de leur épinglage | DÉCISION À CLARIFIER | P3 |
| F-12 | Impact : chemin explicatif arbitraire (DEPENDS_ON vs fait), tests tronqués par `maxResults` | AMÉLIORATION | P3 |
| F-13 | Recherche de symboles : rang 6 sur `symbolKey` (hash) et coût de tri | AMÉLIORATION | P3 |
| F-14 | Index vectoriel : budget d'écriture plus large que le budget de lecture | RISQUE | P3 |

---

### F-01 — Impact, appelants et dépendances inter-modules aveugles aux occurrences SCIP, sans limitation déclarée

- **Qualification** : DÉFAUT CONFIRMÉ (honnêteté de capacité). La lecture du code est sans ambiguïté ; l'effet observable sur un vrai index n'a pas été exécuté (MCP/build interdits), il est à confirmer par la reproduction ci-dessous.
- **Priorité** : **P1**. Pour un outil d'impact, le faux négatif est le défaut grave, et ici il est silencieux. Le produit affiche seulement `DYNAMIC_DISPATCH_NOT_PROVEN`, `REFLECTION_NOT_PROVEN`, `RUNTIME_CONFIGURATION_NOT_PROVEN`, et le lecteur conclut à tort que l'absence de résultat est une absence de chemin statique.
- **Preuves**
  - `minos-provider-scip/.../ScipIngestionAdapter.java:168-184` : les seules relations produites viennent de `SymbolInformation.relationships` (`collectRelationships`, lignes 206-280), plus `DEPENDS_ON` dérivées de ces mêmes relations (ligne 173) et `RELATED_TEST` (ligne 176).
  - `ScipRelationshipNormalizer.java:85-115` : seuls `REFERENCES` (`is_reference`), `IMPLEMENTS`, `TYPE_DEFINITION`, `DEFINITION` sont émis. Aucun producteur de `CALLS`, `IMPORTS`, `EXTENDS`, `INSTANTIATES`, `CONTAINS`, `DECLARES` dans tout `src/main` (recherche exhaustive `RelationshipKind.X`).
  - `ScipIngestionAdapter.java:113-123,153-162` : les occurrences (le contenu principal d'un index SCIP) sont stockées comme `SymbolOccurrence` sans symbole source. Elles ne deviennent jamais une arête symbole→symbole.
  - `ScipSymbolCatalog.java:97` capture `enclosing_symbol`, mais `ScipSymbolNormalizer.java:149` passe `null` comme `parentSymbolId` (`Symbol.java:18`) : le champ n'est jamais renseigné. `Occurrence.enclosing_range` n'est jamais lu : les bindings 0.10.0 l'exposent (`ENCLOSING_RANGE_FIELD_NUMBER`, `getEnclosingRange*`, constaté par `javap` sur `scip-java-bindings-0.10.0.jar` ; version dans `pom.xml:50`).
  - `ImpactAnalysisService.java:27-42` (`PROPAGATING_KINDS`) et `:198-220` (`incomingRelationships`) : la traversée ne lit que `snapshot.relationships()`. `ImpactLimitation.java:6-14` ne nomme aucune limite « références par occurrence non exploitées ».
  - `ArchitectureDependencyService.java:49-52` : le graphe module→module n'agrège que `DEPENDS_ON`, donc l'architecture factuelle (ADR-0013) est bâtie sur les mêmes relations rares. Le message de preuve (`Aggregated N persisted DEPENDS_ON...`, lignes 103-106) ne le dit pas.
  - `RelationshipQueryService.java:60-67` (`findCallers`) : lit `CALLS`, jamais produit par SCIP (`ScipIndexerCatalog.java:96,119` : « CALLS relations are not emitted explicitly »).
  - Aveu partiel hors code : `docs/history/milestones/m8/IMPACT_ANALYSIS.md:210` (le replay TS doit s'appuyer sur `IMPLEMENTS` parce que les cibles d'appel ne sont « observables que via les occurrences »).
- **Comportement actuel** : sur un snapshot issu de SCIP, `minos impact <symbole>` ne remonte que les implémenteurs/surcharges (`is_implementation`), les `is_reference` rares et les tests liés. Un appelant ordinaire ou un module consommateur n'apparaît pas. Les graphes d'architecture, de centralité et de concentration sont calculés sur ce sous-ensemble. `minos_find_callers` est vide pour SCIP.
- **Comportement attendu + source** : ADR-0015 impose « chemins explicatifs » et « aucun résultat présenté comme preuve d'exhaustivité ». `openspec/config.yaml` (capability-honest : « une capacité absente ou non qualifiée n'est jamais présentée comme acquise »). ADR-0010 interdit de synthétiser un `CALLS` non émis ; il n'interdit pas de dériver une arête `REFERENCES` explicable depuis une occurrence résolue dont le symbole englobant est connu (ADR-0012 le fait déjà pour les tests liés, `RelatedTestDerivationService.java:182-219`). Au minimum, la limite doit être déclarée.
- **Cause** : le modèle persiste les occurrences et les relations fournisseur, mais aucune étape de dérivation occurrence→relation n'existe, et l'information nécessaire (plage englobante ou symbole englobant) est lue puis jetée.
- **Impact** : faux négatifs systématiques des surfaces CLI/API/MCP d'impact, d'appelants, de dépendances et d'architecture sur tout projet SCIP (Java, TS, Go, Rust, C#, C/C++). Pour Java, `minos_impact_v2` complète via l'AST (`JavaSourceProgramGraphProvider`, name+arity), pas pour les autres langages.
- **Correction minimale proposée**
  1. (Court terme, P1) Ajouter une limitation explicite, par exemple `OCCURRENCE_REFERENCES_NOT_PROJECTED`, dans `ImpactLimitation` et dans le message d'architecture, déclenchée quand le snapshot contient des occurrences résolues non définitionnelles.
  2. (Moyen terme) Lire `Occurrence.enclosing_range` (et `enclosing_symbol` pour renseigner `parentSymbolId`), puis dériver, à l'ingestion, des relations `REFERENCES` de nature `DERIVED` avec preuve `DIRECT_REFERENCE`, du symbole englobant la référence vers le symbole référencé. Ces dérivations doivent rester distinguables des faits fournisseur (ADR-0010). Un ADR (amendement 0010/0015) est requis (`openspec/config.yaml`, règle proposal).
- **Validation (test)**
  - Reproduction minimale, `minos-provider-scip/src/test/java/com/minos/adapter/scip/ScipOccurrenceReferenceProjectionTest.java`, calquée sur `ScipIngestionAdapterTest` (helper `occurrence(...)` ligne 444). Index d'un document `src/A.java` (language `java`) avec deux symboles `scip-java maven fx 1.0 p/A#foo().` et `.../A#bar().` (kind `Method`), occurrences : définition de `foo` (ligne 2), définition de `bar` (ligne 6), référence `ReadAccess` à `foo` ligne 7 avec `enclosing_range` de `bar`. `new ScipIngestionAdapter().ingest(index, new ScipIngestionRequest("p","m","scip-java","0.13.1","run",Map.of()), new InMemoryCodeKnowledgeStore())`, puis `new RelationshipQueryService(store).findIncoming("p", symbol(foo), Set.of(RelationshipKind.REFERENCES, RelationshipKind.CALLS), 10)`. Aujourd'hui : liste vide ; attendu après correction : une relation de `bar` vers `foo`.
  - Test bout-en-bout dans `minos-app/src/test/java/com/minos/app/impact/` (à côté de `ImpactAnalysisRealFixtureTest`) : import via `ScipSymbolSnapshotImporter.importSnapshot(file, new ScipSymbolSnapshotRequest(projectId, "s1", null, "scip-java", "0.13.1", "r1", Map.of()), new FileSymbolSnapshotStore(tmp))`, puis `new ImpactAnalysisService().analyze(store.loadActiveKnowledge(projectId).orElseThrow(), ImpactAnalysisRequest.defaults(fooId))` ; `impacts()` doit contenir `bar` (vide aujourd'hui).
- **Dépendances** : amende ADR-0010 et ADR-0015 ; touche les goldens de caractérisation (`minos-app/src/test/resources/characterization/`, ne pas les rendre dépendants de l'hôte) ; relancer `scripts/remediation/check-*.py` et `scripts/m*/check-*.py` après tout renommage (cf. mémoire projet : ils assertent des chaînes littérales) ; lien avec F-03 (même cause racine : plage englobante) ; relance du scope JaCoCo concerné.

---

### F-02 — Tests liés : classification « test » non polyglotte et ancre unique par fichier

- **Qualification** : DÉFAUT CONFIRMÉ (lacune fonctionnelle, pas une régression de Q12 : Q12 a volontairement resserré les conventions, le polyglotte M24 n'a pas été revisité).
- **Priorité** : **P2**. Les providers Go, C#, Rust, C/C++ sont qualifiés (ADR-0032, capacité `TEST_SOURCES`) mais les tests liés et le tri test/production y sont inopérants.
- **Preuves**
  - `minos-engine/.../query/RelatedTestDerivationService.java:452-488` (`isTestPath`), conventions reconnues : source sets JVM, `__tests__`, répertoire `test`/`tests`, fichiers `*.test.*` / `*.spec.*` (regex ligne 460 `.*\.(?:test|spec)\.[^/]+$`). Non reconnus : `foo_test.go` (Go, fichier voisin du code), `test_foo.py` / `foo_test.py` hors `tests/`, `Foo.Tests/FooTests.cs` (le segment `foo.tests` n'est pas `tests`), `foo_test.cc`, `FooTests.cs`.
  - La liste d'essais confirme le périmètre : `minos-engine/src/test/java/com/minos/query/RelatedTestHeuristicsTest.java:116-136` ne contient aucun de ces cas.
  - Ancre unique : `:114-116` (`uniqueTestAnchorByFile` seulement si `anchors.size() == 1`) et `:201-209` (les références par occurrence exigent une ancre unique) ; `:327-350` (`testAnchors` : conteneurs, sinon fonctions, sinon `OTHER`). Un fichier de test Go/Python/TS avec plusieurs fonctions n'a donc aucune ancre unique et ses références n'engendrent aucune relation (seule la convention de nommage subsiste, `:136-180`).
- **Comportement actuel** : pour un projet Go, tous les symboles d'un `_test.go` sont traités comme production : aucun lien `RELATED_TEST`, et les tests apparaissent comme symboles impactés ordinaires. Pour Python/TS/Go, même un fichier correctement classé perd les liens par référence/appel dès qu'il contient plusieurs fonctions.
- **Comportement attendu + source** : ADR-0012 (signaux de référence/appel prioritaires, heuristiques distinguables) ; ADR-0032 §3-4 (profil explicite par provider : les conventions de test font partie du profil opérationnel). `ProviderCapabilityProfile` annonce `TEST_SOURCES` PARTIAL/FULL (`ScipIndexerCatalog.java:262,280,…`).
- **Cause** : heuristique de chemin écrite pour JVM/Jest/pytest-`tests/`/Cargo ; aucun usage du rôle SCIP `Test` (`OccurrenceRole.TEST`, mappé par `ScipOccurrenceRoleMapper.java:24` mais jamais lu en aval) ; ancre unique par fichier faute de plage englobante (voir F-01).
- **Impact** : rappel nul des tests liés pour Go/C#/C++ et dégradé pour Python/TS/Go (fichiers à plusieurs fonctions) ; l'impact potentiel sur tests (ADR-0015) est vide pour ces langages, sans limitation affichée.
- **Correction minimale proposée** : étendre `isTestPath` (suffixes de fichier `_test.go`, `_test.py`, `test_*.py`, `*_test.cc|cpp|c`, segment se terminant par `.tests`/`.test` pour .NET), avec le même garde-fou « jamais le mot nu » ; utiliser le rôle SCIP `Test` comme signal supplémentaire quand l'indexeur le publie ; pour les fichiers multi-ancres, rattacher par plage englobante dès que F-01 la fournit, sinon déclarer la limite.
- **Validation** : ajouter à `RelatedTestHeuristicsTest.theProjectTestConventionsAreTestPaths` (`@ValueSource`) `"pkg/foo_test.go"`, `"pkg/test_foo.py"`, `"pkg/foo_test.py"`, `"src/Foo.Tests/FooTests.cs"`, `"src/foo_test.cc"` (échouent aujourd'hui) ; et dans `aDirectoryThat...` conserver `"src/main/java/com/acme/protests/Vote.java"`, `"contest/Runner.py"`. Test de dérivation : deux fonctions `TestA`, `TestB` dans `pkg/foo_test.go` référencent `Foo` → au moins une relation attendue.
- **Dépendances** : aucune dépendance de module ; relancer la suite `RelatedTestHeuristicsTest` et `ScipRelatedTestIngestionTest`/`ScipRelatedTestRealFixtureTest` ; goldens de caractérisation si un fixture Go/C# entre dans `minos-app`.

---

### F-03 — Corrélation runtime ligne→symbole : seule la plage de l'identifiant est connue

- **Qualification** : DÉFAUT CONFIRMÉ (conséquence directe de F-01, périmètre ADR-0034 §4).
- **Priorité** : **P2**. Aucune fausse affirmation (tout est `OBSERVED_PARTIAL`, `exhaustive=false`), mais la couverture observée est sous-estimée de façon structurelle.
- **Preuves**
  - `minos-application/.../dynamic/RuntimeIntelligenceService.java:323-337` (`SymbolIndex.resolve`) : pour une référence fichier+ligne, un symbole ne correspond que si `symbol.location().startLine() <= line <= endLine`.
  - `Symbol.location` d'un symbole SCIP est la plage de l'occurrence de définition, c'est-à-dire du nom : `ScipIngestionAdapter.java:322-331` (`definitionLocations` via `rangeMapper.map` sur la plage `range`), `ScipRangeMapper.java:26-47`. `enclosing_range` non lue (F-01).
  - Conséquence : une ligne `LINE_COVERAGE` à l'intérieur du corps d'une méthode ne correspond à aucun symbole (`UNRESOLVED`) ; une ligne portant deux déclarations donne `AMBIGUOUS`. Les ratios `observedSymbolRatio` et les compteurs `unresolved` du rapport (`RuntimeIntelligenceService.java:130-135`) en sont affectés.
- **Comportement actuel** : seules les lignes de déclaration se résolvent ; l'essentiel de la couverture par ligne reste non corrélé.
- **Comportement attendu + source** : ADR-0034 §4 (« clé exacte, puis qualified name, puis fichier/ligne »). La corrélation par ligne n'a de sens que si la plage du symbole couvre son corps.
- **Cause** : plage englobante non ingérée.
- **Impact** : couverture et hot paths par ligne peu exploitables sur SCIP ; aucun risque d'intégrité (les états `UNRESOLVED`/`AMBIGUOUS` restent visibles).
- **Correction minimale proposée** : stocker la plage englobante (ou une seconde `SymbolLocation` « corps ») à l'ingestion, et l'utiliser ici ; à défaut, documenter la limite dans `LIMITATIONS` (lignes 38-43) : « la corrélation par ligne ne couvre que la ligne de déclaration ».
- **Validation** : `RuntimeIntelligenceServiceTest` (déjà présent côté bootstrap/application) : snapshot où le symbole `foo` a `location = lignes 10-10` et une observation `LINE_COVERAGE` ligne 12 d'un corps 10-20 → attendu `RESOLVED` après correction, `UNRESOLVED` aujourd'hui.
- **Dépendances** : F-01 (format de stockage ; le codec snapshot `SnapshotBinaryCodecSupport.java` écrit déjà `parentSymbolId`, un champ de plage serait un changement de format v3, ADR-0046/0047).

---

### F-04 — `Symbol.moduleId` jamais renseigné par l'indexation autonome

- **Qualification** : DÉFAUT CONFIRMÉ.
- **Priorité** : **P2**. Un filtre documenté de la CLI retourne silencieusement zéro résultat.
- **Preuves**
  - `minos-provider-scip/.../runtime/ScipProjectSnapshotLifecycle.java:85-94` : `new ScipSymbolSnapshotRequest(request.projectId(), providerSnapshotId, null, descriptor.id(), ...)` : le 3ᵉ argument est `moduleId`, passé à `null`. Seul `minos-application/.../LocalProjectOperations.java:117` le renseigne, pour l'import SCIP manuel.
  - `minos-engine/.../store/InMemoryCodeKnowledgeStore.java:112` : `criteria.moduleId() == null || criteria.moduleId().equals(symbol.moduleId())` ⇒ faux pour tout symbole du chemin autonome. `FindSymbolCommand.java:31,39,74` expose `--module`, et `CodeSearchService.java:57` le propage.
  - `SemanticDocumentFactory.java:159` écrit `module ` vide dans le texte de chaque symbole.
- **Comportement actuel** : après `minos index`, `minos find-symbol X --module m` et le filtre `module` des recherches ne trouvent rien ; l'architecture (`ArchitectureModuleResolver`) résout par chemin de fichier et n'est pas affectée.
- **Comportement attendu + source** : le contrat CLI `--module <module>` « Filter by module identifier » (`FindSymbolCommand.java:39`).
- **Cause** : le cycle de vie autonome passe par un import par scope (`projectRelativeRoot`) sans mapping scope→module.
- **Impact** : filtre mort sur le chemin nominal ; recherche sémantique privée de l'information de module.
- **Correction minimale proposée** : renseigner `moduleId` depuis le module découvert correspondant au `projectRelativeRoot` du scope (`IndexingArtifact`) ; à défaut, rejeter `--module` quand le snapshot ne porte aucun `moduleId` plutôt que renvoyer une liste vide.
- **Validation** : test dans `minos-app` ou `minos-application` : indexer un projet Maven à deux modules via le chemin autonome, puis `SymbolSearchCriteria(null,null,null,"moduleA",10)` ⇒ résultats non vides (vide aujourd'hui).
- **Dépendances** : ADR-0022 (direction des dépendances ; la résolution module est dans `minos-application`) ; goldens de caractérisation si le moduleId apparaît dans une sortie.

---

### F-05 — Recherche hybride : pas de repli quand le provider d'embeddings échoue, limitations sémantiques perdues

- **Qualification** : DÉFAUT CONFIRMÉ (le repli n'est testé que pour « provider absent », `SemanticHybridIntelligenceTest.java:38`).
- **Priorité** : **P2**. Ollama est un composant opéré séparément ; son arrêt casse `minos_hybrid_search` et `minos_hybrid_context` alors que la couche est « optionnelle » (ADR-0029 §1, conséquence 1).
- **Preuves**
  - `SemanticIndexService.java:92-96` : l'état `READY` ne dépend que des métadonnées de l'index (snapshot + modèle), jamais de la joignabilité du provider.
  - `HybridSearchService.java:61-64,74` : `semanticAvailable` ⇒ `semanticScores(...)` ⇒ `SemanticSearchService.search` ⇒ `queryVector` ⇒ `provider.embed("query", query)` (`SemanticSearchService.java:47,98`) ; une `IOException` (Ollama HTTP, `OllamaEmbeddingProvider.java:88-94`) traverse `HybridSearchService.search` jusqu'au client, sans retomber sur `LEXICAL_GRAPH`.
  - `HybridSearchService.java:63-64` : `semanticIndex.activeIndex(...).orElseThrow()` rejoue un contrôle d'état ; une synchronisation concurrente entre les deux lectures lève `NoSuchElementException`.
  - `HybridSearchService.java:175-179` : la réponse `SemanticSearchService.SearchResponse` ignore ses `limitations` (`SEMANTIC_INDEX_CHANGED_DURING_QUERY`, `SEMANTIC_SEARCH_REQUIRES_READY_INDEX`, `SemanticSearchService.java:44,68`). Dans ce cas les hits sont vides, `semanticAvailable` reste vrai, et le score devient `0.50*0 + 0.35*lexical + 0.15*graph` (ligne 90), donc la moitié du poids lexical de l'autre mode (0,70) et un `minimumScore` qui change de sens, sans le dire (`rankingMode = LEXICAL_GRAPH_SEMANTIC`, ligne 93).
- **Comportement actuel** : échec dur, ou classement dégradé silencieux et mal étiqueté.
- **Comportement attendu + source** : ADR-0029 §1 et conséquences (« MINOS reste pleinement utilisable sans embeddings ») ; `openspec/config.yaml` (capability-honest) : une capacité indisponible doit être signalée, pas présentée comme active.
- **Cause** : le calcul de disponibilité et l'exécution de la requête vectorielle sont deux étapes sans compensation d'erreur ; les limitations de la réponse sémantique sont écartées.
- **Impact** : indisponibilité d'une surface MCP quand Ollama tombe ; scores incomparables entre modes.
- **Correction minimale proposée** : encadrer `semanticScores` (`catch IOException | RuntimeException`) ⇒ repli `LEXICAL_GRAPH` avec limitation `SEMANTIC_SIGNAL_UNAVAILABLE_PROVIDER_ERROR` ; propager `semantic.limitations()` ; traiter `activeIndex` vide comme « sémantique indisponible » ; si les hits sont vides parce que l'index a changé, retomber sur la formule `LEXICAL_GRAPH`.
- **Validation** : dans `minos-bootstrap/src/test/java/com/minos/bootstrap/semantic/SemanticHybridIntelligenceTest.java`, ajouter un `EmbeddingProvider` dont `embed("query", ...)` lève `IOException` après une synchronisation réussie (utiliser le provider de test de la ligne 207 comme base) ; `application.hybridSearchService().search(...)` doit renvoyer une réponse avec `semanticAvailable=false` ou la limitation d'erreur (lève aujourd'hui).
- **Dépendances** : surfaces MCP/CLI/API additives (ADR-0016/0018), ne pas changer le schéma ; `scripts/quality/check-semantic-retrieval-consistency.py` assert des littéraux sur ce service : le rejouer.

---

### F-06 — Aucun garde de fraîcheur entre source sur disque et snapshot (documents sémantiques, extraits)

- **Qualification** : DÉCISION À CLARIFIER (le choix « lire le fichier courant » est documenté et assumé pour `LocalSourceReader` ; ce qui manque est le garde, et il touche l'index vectoriel).
- **Priorité** : **P2** pour la dérive d'index vectoriel (silencieuse, états `READY` trompeurs) ; le reste est P3.
- **Preuves**
  - `SemanticDocumentFactory.java:74` : le texte `CHUNK` est `sourceReader.readExcerpt(location, 2, 768)`, lu dans l'arbre de travail courant, à partir de lignes issues du snapshot. `LocalSourceReader.java:131-137` (Javadoc : « Reads the current file contents for every excerpt request »).
  - `SemanticIndexService.java:92-96` : `READY` = même snapshotId + même modèle ; rien ne capte la dérive du source. `checksum` (`SemanticDocumentFactory.java:119`) couvre le contenu lu, mais n'est comparé qu'à la prochaine synchronisation.
  - `HybridSearchService.java:68-73,132-137` : identité de corpus = `snapshotId:structured` (sans état de source) ; sans index sémantique, le corpus est reconstruit à partir de l'arbre courant à la première requête et mis en cache sans invalidation jusqu'au redémarrage.
  - `CodeSearchService.java:114-131` : les extraits `includeSource` utilisent les numéros de ligne du snapshot sur le fichier courant, sans vérifier qu'il correspond au snapshot.
  - Sous-constat (performance) : `SemanticDocumentFactory.java:69-70` affirme que `LocalSourceReader` ne relit pas le fichier par symbole ; or `LocalSourceReader.java:131-137` a supprimé ce cache, donc le fichier est relu et décodé en entier pour chaque symbole (N symboles × taille du fichier).
- **Comportement actuel** : après une édition du source sans ré-indexation, la synchronisation sémantique produit un index `READY` dont des chunks ne correspondent plus aux symboles/lignes du snapshot ; deux synchronisations du même snapshot à des états de disque différents donnent des index différents ; en mode structuré, le résultat dépend du moment de la première requête.
- **Comportement attendu + source** : ADR-0029 §3 (« index aligné sur un snapshot actif », « reconstruisible ») ; ADR-0034 (immutabilité et alignement au snapshot) pour l'esprit.
- **Cause** : le snapshot ne persiste pas le contenu (ni le hachage) des fichiers sources ; la fraîcheur n'est pas modélisable. Le planificateur incrémental dispose de fingerprints (`ProjectFingerprintSnapshotStore`), non consultés ici.
- **Impact** : contenu de contexte hybride possiblement faux, étiqueté comme aligné au snapshot ; non-reproductibilité des index.
- **Correction minimale proposée** : décider d'une politique : (a) comparer l'empreinte des fichiers lus à celle du snapshot et ajouter une limitation `SOURCE_CHANGED_SINCE_SNAPSHOT` dans `Status`/réponses ; ou (b) documenter explicitement que le contenu des documents sémantiques est celui du disque au moment de la synchronisation (ADR 0029 note). Restaurer un cache d'une seule source dans `LocalSourceReader` clé `(chemin, taille, mtime, identité de fichier)` si (a) est retenu (performance).
- **Validation** : test `SemanticIndexService` : synchroniser, modifier le fichier source, vérifier que `status()` (ou une nouvelle limitation) signale la dérive ; mesure : compteur de lectures de fichier par synchronisation.
- **Dépendances** : ADR-0029 (amendement), ADR-0050 en cours (« Proposed ») ; `check-semantic-retrieval-consistency.py`.

---

### F-07 — Entrées SCIP anormales : politique incohérente (abandon, saut silencieux, identifiant opaque)

- **Qualification** : RISQUE.
- **Priorité** : **P3** (l'abandon est fail-closed, acceptable ; c'est l'incohérence et le diagnostic qui posent problème).
- **Preuves**
  - Abandon de toute l'ingestion par `IllegalArgumentException` non rattrapée : plage invalide (fin avant début, ligne `-1`, débordement `int` de `getLine()+1`) → `ScipRangeMapper.java:28-35,40-47,62-69` → `SymbolLocation.java:20-31` ; `SymbolInformation` à `symbol` vide → `ScipSymbolCatalog.java:31-37` → `ScipSymbolFact.java:25`. Aucun `catch` dans `ScipIngestionAdapter.ingest`, `ScipSymbolSnapshotImporter.importSnapshot` (lignes 63-106) ni `ScipProjectSnapshotLifecycle.stage`.
  - Saut silencieux comptabilisé : occurrence sans symbole (`ScipIngestionAdapter.java:108-111`) ou sans plage exploitable (lignes 118-121, `skippedOccurrenceCount`).
  - Identifiant opaque sans diagnostic : un `Document.relative_path` absolu ou contenant `..` n'a pas de `fileId` explicite (`ScipSymbolSnapshotImporter.java:207-229`, `safeRelativePath` renvoie `null`) ; `ScipIngestionAdapter.fileIdFor` (lignes 336-342) fabrique alors `file:<sha>`. Le lecteur de sources refuse ce préfixe (`LocalSourceReader`, `resolveReadableSource`), donc pas d'évasion, mais le document est ingéré sans compteur ni limitation.
  - Aucun test : `ScipRangeMapperTest.java` ne couvre que le cas « aucune plage » ; aucun `assertThrows` sur plage malformée dans `minos-provider-scip/src/test`.
- **Comportement actuel** : un index d'un indexeur légèrement fautif (ou d'un worker non fiable, ADR-0041) fait échouer le run avec un message sans fichier ni occurrence ; les mêmes classes d'anomalie sont traitées de trois façons différentes.
- **Comportement attendu + source** : ADR-0010 (« non-résolutions explicites ») et honnêteté de capacité : une anomalie doit être refusée avec contexte, ou ignorée et comptée, de façon uniforme.
- **Cause** : validations de domaine (`SymbolLocation`, `ScipSymbolFact`) utilisées comme garde d'entrée sans filtre amont.
- **Impact** : dénis de service par run (pas de corruption : fail-closed) ; métriques d'ingestion non exhaustives.
- **Correction minimale proposée** : dans `ScipRangeMapper.map`, valider avant construction et retourner `Optional.empty()` (compté en `skippedOccurrenceCount`) ; ignorer/compter un `SymbolInformation` sans symbole ; compter et refuser (ou ignorer avec limitation) les chemins de document non sûrs. Enrichir le message avec chemin relatif et ligne d'occurrence.
- **Validation** : `ScipIngestionAdapterTest` : une occurrence avec `SingleLineRange(line=3,start=10,end=5)` et une avec `line=-1` ; attendu `report.skippedOccurrenceCount()==2` et aucune exception (lève `IllegalArgumentException` aujourd'hui).
- **Dépendances** : `ScipIngestionReport` additif (compteur) ; `check-polyglot-provider-consistency.py` si des littéraux de rapport y sont assertés.

---

### F-08 — `occurrenceId` sans les rôles : fusion silencieuse d'occurrences

- **Qualification** : RISQUE (non reproduit sur un indexeur réel ; défaut de conception d'identité).
- **Priorité** : **P3**.
- **Preuves** : `ScipIngestionAdapter.java:344-359` : l'identifiant d'occurrence hache `projectId, fileId, plage, rawSymbol`, sans `roles`. Deux occurrences de même symbole et même plage mais de rôles différents (par exemple `Definition` et `ReadAccess`) produisent le même id ; `CapturingStore.putOccurrences` (`ScipSymbolSnapshotImporter.java:255-257`) garde la dernière (`put`), sans compteur, et `ScipIngestionReport.occurrenceCount` (`ScipIngestionAdapter.java:190`) compte les deux. Le rôle `DEFINITION` perdu fait apparaître la définition comme usage (`InMemoryCodeKnowledgeStore.findUsages`, ligne 156) ou l'inverse. À l'étape de cycle de vie, `putUnique` (`ScipProjectSnapshotLifecycle.java:201-205`) lèverait au contraire une collision si deux occurrences égales par id mais non égales par contenu venaient de scopes différents.
- **Comportement actuel** : dernier gagnant silencieux ; compteur du rapport supérieur au nombre persisté.
- **Comportement attendu** : identité stable et sans perte : ADR-0009/0010 (identités sans canonicité inventée ; provenance conservée).
- **Cause** : l'id suppose une occurrence unique par (symbole, plage).
- **Impact** : perte de rôle dans des cas rares (indexeurs qui émettent plusieurs occurrences de même plage) ; faux `findUsages`.
- **Correction minimale proposée** : fusionner les rôles (union) avant création, et compter les fusions dans le rapport ; ne pas changer le format de l'id existant (stabilité des snapshots), l'union se fait en amont.
- **Validation** : `ScipIngestionAdapterTest` : deux occurrences `rawSymbol` identique, même plage, rôles `Definition` puis `ReadAccess` ; attendu une occurrence persistée avec les deux rôles (une seule, avec le dernier rôle, aujourd'hui) et `occurrenceCount` cohérent avec le persisté.
- **Dépendances** : snapshots persistés existants inchangés ; golden M16 si le compteur d'occurrences y figure.

---

### F-09 — Descripteur vs profil de capacités : dérive concrète qui change la négociation (complète A5)

- **Qualification** : DÉFAUT CONFIRMÉ (preuves nouvelles sur un constat A5 ouvert).
- **Priorité** : **P3**.
- **Preuves** : `IndexerRegistry.java:109-121` négocie sur `candidate.capabilities()` (le descripteur) seulement. Écarts avec le profil `ProviderCapabilityProfile` dans `ScipIndexerCatalog.java` :
  - scip-java : le descripteur (lignes 76-98) n'inclut ni `UNRESOLVED_REFERENCES` ni `STRUCTURAL_RELATIONS` alors que le profil les donne PARTIAL (lignes 237, 239).
  - scip-typescript : le profil donne `UNRESOLVED_REFERENCES` FULL (ligne 257), absent du descripteur (lignes 100-120), qui liste pourtant `STABLE_SYMBOL_IDENTITY` (PARTIAL au profil, ligne 255) et `STRUCTURAL_RELATIONS` (PARTIAL) : la règle de saisie du descripteur n'est donc pas « uniquement FULL ».
  - scip-python : le descripteur (lignes 122-138) omet `STABLE_SYMBOL_IDENTITY` et `UNRESOLVED_REFERENCES` (profil PARTIAL, lignes 274-275).
  - Aucun test de cohérence : `ProviderConformanceKitTest` ne vérifie que l'exhaustivité du profil.
- **Comportement actuel** : exiger `UNRESOLVED_REFERENCES` ou `STRUCTURAL_RELATIONS` écarte scip-java et scip-python (`REJECTED_MISSING_CAPABILITIES`) alors que leur profil les qualifie PARTIAL ; scip-typescript est écarté sur `UNRESOLVED_REFERENCES` malgré FULL.
- **Comportement attendu + source** : ADR-0008 (négociation par capacités explicites) ; ADR-0032 §3 (profil exhaustif, comparable entre providers) ; une source unique de vérité.
- **Cause** : deux modèles saisis à la main (A5).
- **Impact** : sélection de provider fausse dès qu'une exigence de capacité est posée ; aujourd'hui peu exercé (`IndexingRequirements` par défaut).
- **Correction minimale proposée** : dériver `descriptor.capabilities` du profil (capacités FULL ou PARTIAL), ou ajouter l'invariant en test (ci-dessous) et corriger les trois descripteurs.
- **Validation** : dans `ScipIndexerCatalogTest`, pour chaque `IndexerProvider p` de `qualifiedM24Providers()` : `for (c : IndexerCapability.values()) if profile level(c) ∈ {FULL, PARTIAL} → assertTrue(p.descriptor().capabilities().contains(c))`. Échoue aujourd'hui sur scip-java (2), scip-typescript (1), scip-python (2).
- **Dépendances** : `scripts/quality/check-polyglot-provider-consistency.py` (grep des littéraux du catalogue avant de toucher) ; surfaces `tools list`/MCP qui exposent les descripteurs.

---

### F-10 — Extracteurs de nom/genre : surcharges `(+n).` et genres SCIP non mappés

- **Qualification** : DÉFAUT CONFIRMÉ pour l'extracteur de repli ; AMÉLIORATION pour les genres.
- **Priorité** : **P3**.
- **Preuves**
  - `ScipDescriptorNameExtractor.java:27-31,33-43` : seuls les suffixes `()` , `#` et `.` sont retirés ; pour une méthode surchargée `…Foo#bar(+1).` le nom devient `bar(+1)` (aucune suppression du désambiguïsateur). `ScipDescriptorKindMapper.java:24` (`!endsWith("().")` ⇒ `OTHER`) classe la même méthode en `OTHER`. Or `ScipQualifiedNameExtractor.java:130-136` gère correctement `name(+n).` : trois extracteurs divergent.
  - Usage : `ScipSymbolCatalog.java:88-90` (nom d'affichage quand l'indexeur n'en publie pas), `ScipIngestionAdapter.java:299` (cible de relation non résolue).
  - Genres : `ScipSymbolKindMapper.java:30-48` laisse en `OTHER` `Type`, `Union`, `Object`, `Constant`, `EnumMember`, `Module`, `Macro`, `Extension`, alors que `SymbolKind.TYPE` existe et n'est atteint que par le repli de descripteur (`ScipDescriptorKindMapper.java:21-23`) quand le genre est non spécifié. Ces genres sont courants en Go/Rust/C/C++ ; `OTHER` sert aussi d'ancre de test (`RelatedTestDerivationService.java:346-349`).
- **Comportement actuel** : nom `bar(+1)` et kind `OTHER` pour une surcharge sans `display_name`/`kind` publiés ; genres réduits à `OTHER`.
- **Comportement attendu + source** : ADR-0009 (« aucun qualifiedName, kind, surcharge… inventé » : ici le nom est faux, pas inventé).
- **Correction minimale proposée** : retirer `\(\+?\d*\)` avant extraction du nom/genre (réutiliser l'analyse de `ScipQualifiedNameExtractor`) ; mapper `Type→TYPE`, `Union→STRUCT` (ou conserver `OTHER` documenté) si l'ADR 0009 le permet.
- **Validation** : `ScipDescriptorNameExtractorTest` : `extract("scip-java maven fixture 1.0 io/example/Foo#bar(+1).")` doit valoir `bar` (donne `bar(+1)`) ; `ScipSymbolKindMapperTest` : `map(UnspecifiedKind, "…Foo#bar(+1).")` → `METHOD` (donne `OTHER`).
- **Dépendances** : identités structurelles : le nom d'affichage n'entre pas dans l'identité quand `qualifiedName` existe (`ScipSymbolNormalizer.java:178-202`), mais il entre dans le repli sans nom qualifié (lignes 204-215) : un changement de nom change ces ids ; ne le faire qu'avec un plan de migration (ADR-0009, stabilité des snapshots).

---

### F-11 — Binaires opérés acceptés sans vérification de leur épinglage

- **Qualification** : DÉCISION À CLARIFIER (le périmètre de confiance est partiellement documenté ; le profil dit « operator-managed pinned binary is inspected explicitly », `ScipIndexerCatalog.java:366`).
- **Priorité** : **P3**.
- **Preuves**
  - scip-clang et rust-analyzer : résolus par le `PATH` (`CommandLocator.find`) et validés par `output.contains("0.4.0")` / `contains("0.3.2989")` (`ManagedPolyglotScipRuntimeManager.java:137-141, 224-231`) : sous-chaîne, pas égalité, aucun hachage, alors que `embedded-tools.json` épingle leur SHA-256 (artefacts `scip-clang` linux-x64, `rust-analyzer` linux/windows).
  - Marqueur d'intégrité auto-attesté : `integrityManifestMatches` compare un condensé local à un fichier du même répertoire (`ManagedPolyglotScipRuntimeManager.java:336-341, 361-372`) ; il détecte la corruption, pas une altération par quelqu'un qui peut écrire dans `MINOS_HOME/tools` (il réécrit les deux).
  - Coursier, Maven, Node déjà présents sont acceptés sur la seule existence du fichier (`ManagedScipProviderRuntimeManager.java:356-357, 402, 440`) ; seuls les arbres « embarqués » sont revérifiés (`:587-614`). Hétérogénéité : Go/.NET portent un marqueur, Java/TS/Node/Maven téléchargés n'en portent pas.
  - `tools verify` (`ToolsCommand.java:46-50, 65-67`) ne vérifie que l'état `READY`, pas un condensé : le libellé « Verify managed provider runtimes » peut être lu comme une vérification de somme.
  - `scripts/quality/check-tools-manifest.py:172-175` compare `ScipIndexerCatalog.RUST_ANALYZER_SCIP_RELEASE` au premier artefact `rust-analyzer` seulement (`next(...)`) ; l'entrée Windows n'est pas recoupée.
- **Comportement actuel** : un binaire quelconque affichant la bonne sous-chaîne de version passe `READY`.
- **Comportement attendu + source** : ADR-0032 §4-8 (« pinned », « reproducible ») ; `openspec/config.yaml` (fail-closed). À trancher : la confiance est-elle limitée à l'intégrité contre corruption (alors dire « version probe » et non « pinned »), ou doit-on comparer le SHA-256 du catalogue pour les binaires opérés ?
- **Correction minimale proposée** : comparer l'égalité de version (regex ancrée) ; optionnellement hacher l'exécutable contre le catalogue (`EmbeddedToolsCatalog.findArtifact`) quand le binaire est celui du catalogue ; compléter `check-tools-manifest.py` pour toutes les plateformes.
- **Validation** : `ManagedPolyglotScipRuntimeManagerTest` : faux `rust-analyzer` imprimant `rust-analyzer 10.3.29890` (contient `0.3.2989`) ⇒ `INVALID` attendu (`READY` aujourd'hui) ; `scripts/quality/test_check_tools_manifest.py` : divergence de version sur l'entrée Windows.
- **Dépendances** : ADR-0040 (distribution), `check-tools-manifest.py` (le script interdit les hachages dans le source Java, `:241-244`, donc toute comparaison passe par le catalogue chargé).

---

### F-12 — Impact : chemin explicatif arbitraire, tests tronqués par `maxResults`

- **Qualification** : AMÉLIORATION.
- **Priorité** : **P3**.
- **Preuves**
  - `ImpactAnalysisService.java:27-42` : `DEPENDS_ON` (dérivée de chaque fait, `DependencyDerivationService.java:75-85,109-120`, confiance 1,0) et le fait d'origine propagent tous deux ; à profondeur et confiance égales, `CANDIDATE_ORDER` (lignes 49-53) départage par `pathSignature` (concaténation d'ids `rel:<sha>`), donc par un hasch : le type de relation affiché (`REFERENCES` ou `DEPENDS_ON`) est déterministe mais arbitraire.
  - `ImpactAnalysisService.java:154-157,170-174` : `maxResults` tronque la liste ordonnée par profondeur ; `potentiallyImpactedTests` est filtré sur les symboles retenus, donc un test à profondeur 2 est perdu quand 200 symboles de profondeur 1 existent, alors que `MAX_RESULTS_REACHED` ne précise pas que des tests ont été écartés.
- **Comportement attendu + source** : ADR-0015 (chemins explicatifs ; tests potentiellement impactés).
- **Correction minimale proposée** : préférer le fait d'origine à `DEPENDS_ON` (comparateur sur `kind`) ; calculer les tests impactés avant la troncature (ou borner séparément).
- **Validation** : `ImpactAnalysisServiceTest` : deux arêtes `CALLS` et `DEPENDS_ON` entre mêmes symboles ⇒ chemin `CALLS` ; 1 dépendant direct pour 5 000, `maxResults=3`, un test à profondeur 2 ⇒ présent dans `potentiallyImpactedTests`.
- **Dépendances** : goldens d'impact (ordre des chemins) ; `ImpactAnalysisRealFixtureTest`.

---

### F-13 — Recherche de symboles : rang 6 sur `symbolKey` (hash) et coût de tri

- **Qualification** : AMÉLIORATION.
- **Priorité** : **P3**.
- **Preuves** : `InMemoryCodeKnowledgeStore.java:332-334` : `matchRank` rang 6 si `symbolKey` contient la requête. `symbolKey` vaut `minos:structural:<sha256>` ou `minos:provider:<sha256>` (`ScipSymbolNormalizer.java:127-131`) : une requête `struct`, `provider`, `minos` ou un fragment hexadécimal (`ab`, `1f3`) renvoie des symboles quelconques en queue de liste, qui comblent la limite quand les vrais résultats sont rares. `:109-125` : `matchRank` est recalculé (jusqu'à 7 `toLowerCase`) dans le filtre puis à chaque comparaison du tri.
- **Comportement attendu + source** : ADR-0011 (recherche déterministe, bornée, extraits pertinents).
- **Correction minimale proposée** : supprimer le rang 6 ou le restreindre aux requêtes commençant par `minos:` ; précalculer le rang une fois par candidat.
- **Validation** : test `InMemoryCodeKnowledgeStoreTest` : requête `"minos"` sur un snapshot sans nom correspondant ⇒ liste vide (renvoie tout aujourd'hui) ; micro-benchmark sur 100 000 symboles.
- **Dépendances** : le backend PostgreSQL (`minos-storage-postgresql`) implémente sa propre recherche, non examinée : vérifier l'équivalence avant de changer le contrat.

---

### F-14 — Index vectoriel : budget d'écriture plus large que le budget de lecture

- **Qualification** : RISQUE (invariant « ce qui s'écrit se relit » non garanti ; peu atteignable en pratique, voir plus bas).
- **Priorité** : **P3**.
- **Preuves** : `SemanticIndexBudget.java:12-16` plafonne le contenu à 192 Mio en ne comptant que `content` (`:55-62`). `FileSemanticVectorStore.java:54` plafonne le décodage des chaînes à 192 Mio en comptant toutes les chaînes d'un document (id, stableKey, genre, sourceId, fileId, contenu, checksum, plus l'en-tête) via `readString` (`:404-410`), et `replace` (`:178-227`) ne contrôle que la taille de fichier (≤ 384 Mio, `:218`). Un index dont le contenu est proche de 192 Mio s'écrit, `status()` le déclare `READY` (lecture des métadonnées seules, `:146-175`), puis `load()` lève « decoded strings exceed heap budget » à chaque requête (`SemanticSearchService`, `HybridSearchService`).
- **Comportement actuel** : synchronisation réussie, requêtes en erreur.
- **Comportement attendu + source** : ADR-0029 §3 (index reconstruisible, état `READY` fiable).
- **Cause** : deux budgets calculés séparément (construction vs décodage).
- **Impact** : dans la pratique, le plafond de snapshot persisté (A6) et la borne de 768 jetons par document rendent le cas improbable ; reste un défaut d'invariant.
- **Correction minimale proposée** : faire appliquer par `replace` la même comptabilité que `load` (échec précoce avec message clair), ou ajouter la marge de métadonnées au suivi de construction.
- **Validation** : test lourd (`@Tag("slow")`, tas ≥ 1 Go) dans `minos-storage-local` : construire un `IndexSnapshot` de 12 documents de contenu `16 Mio − 1` octet, `replace` puis `load` ; attendu : `replace` refuse (aujourd'hui `replace` réussit, `load` échoue). Variante légère : test de cohérence entre `SemanticIndexBudget.DEFAULT.maxContentBytes()` et `MAX_DECODED_STRING_BYTES`.
- **Dépendances** : format d'index inchangé ; `check-semantic-retrieval-consistency.py`.

---

## Points vérifiés sans défaut (pour borner le périmètre de confiance)

- Préflight SCIP (`ScipIngestionLimits.java`) : compteurs bornés avant décodage, `addExact` partout, estimation de tas saturante, refus des lecteurs symboliques/vides (`ScipIndexReader.java:40-51`). Le plafond 512 Mio est unique (`IndexArtifactLimits.java`).
- Positions SCIP : lignes +1, colonnes 0-based, encodage conservé (`ScipRangeMapper.java`) ; cohérent avec `SymbolLocation.java` et ses tests ; symboles `local ` bornés au document (`ScipSymbolCatalog.java:65-70`).
- Rôles SCIP : table des bits conforme (`ScipOccurrenceRoleMapper.java:19-25`).
- Extraction zip : refus de sortie de racine, bornes d'entrées et d'octets (`ManagedScipProviderRuntimeManager.java:657-692`).
- Téléchargements : HTTPS imposé, SHA-256 comparé avant usage, partiel supprimé en cas d'échec (`PinnedArtifactSource.java:187-226`).
- Index vectoriel : score cosinus protégé contre la norme nulle et borné à [-1, 1], valeurs non finies et hors float32 refusées (`SemanticVectorStore.java:75-81`, `SemanticVector.java:36-44`), top-k correct avec départage par `stableKey`. Aucune division par zéro dans `HybridSearchService` (graphe : `maxDegree > 0`, ligne 82).
- Ollama : hôtes loopback ou `minos-ollama` uniquement, pas de redirection, réponse bornée à 16 Mio (`OllamaEmbeddingProvider.java:102-118,173-180`).
- Impact M8 : BFS Dijkstra-like avec la profondeur comme clé primaire, cycles gérés (la racine est ignorée, le meilleur candidat par nœud est conservé), ordre total déterministe ; `AdvancedImpactService` : visite unique par nœud, sortie dès `maxResults`.
- Recherche : ordre total (`id` en dernier critère), `Locale.ROOT` partout, limite appliquée après tri.

---

## (1) Carte réelle du périmètre

**Providers SCIP — `minos-provider-scip`** (≈ 6 300 lignes main)
- Ingestion : `ScipIndexReader` (lecture bornée, préflight) → `ScipIngestionLimits` → `ScipIngestionAdapter` (orchestration en deux passes, 409 l.) avec `ScipSymbolCatalog`, `ScipSymbolNormalizer` (identité STRUCTURAL/PROVIDER_SCOPED), `ScipQualifiedNameExtractor`, `ScipDescriptorNameExtractor`, `ScipDescriptorKindMapper`, `ScipSymbolKindMapper`, `ScipOccurrenceRoleMapper`, `ScipRangeMapper`, `ScipRelationshipNormalizer`. Pont persistant : `ScipSymbolSnapshotImporter` (gel d'artefact + SHA-256), `runtime/ScipProjectSnapshotLifecycle` (staging, promotion). Dépend de `minos-engine` (stores, `DependencyDerivationService`, `RelatedTestDerivationService`, `IndexerRegistry`) et de `minos-domain`.
- Catalogue : `ScipIndexerCatalog` (7 providers, descripteurs + profils + profils opérationnels), consommé par `IndexerRegistry` (négociation) et gardé par `scripts/quality/check-tools-manifest.py`, `check-polyglot-provider-consistency.py`.
- Outils/processus : `EmbeddedToolsCatalog` + `embedded-tools.json` (source unique des épinglages), `PinnedArtifactSource` (embarqué puis réseau), `ManagedScipProviderRuntimeManager` (Java/TS/Node/Maven/Coursier), `ManagedPolyglotScipRuntimeManager` (clang/.NET/Go/Rust), fabriques de plans de processus `*ProcessPlanFactory`, `StrongOwnedProcessExecutors`.
- Tests : `minos-provider-scip/src/test/java/com/minos/adapter/scip/` (≈ 22 classes : adaptateur, plages, rôles, catalogue, limites, intégrité d'import) et `runtime/`. Lacunes : plages/symboles malformés (F-07), collisions d'identité d'occurrence (F-08), surcharges `(+n).` (F-10), cohérence descripteur/profil (F-09), épinglage des binaires opérés (F-11), aucun test ingestion→impact (F-01).

**Modèle de connaissance — `minos-domain`** : `Symbol`, `SymbolOccurrence`, `Relationship`, `SymbolLocation`, `Origin`, `ProviderReference`, `UnresolvedSymbolReference`, `Evidence`, `RelationshipSearchCriteria`, `SymbolSearchCriteria`, `Preconditions`, `semantic/` (`SemanticDocument`, `SemanticVector`, `SemanticVectorStore`). Records à validation de constructeur, copies défensives (`Set.copyOf`, `List.copyOf`), pas de `equals` personnalisé. Nuance sur A5 : `SemanticDocument.startLine` autorise 0 (valeur sentinelle pour les documents FILE ou sans emplacement, `SemanticDocument.java:31`, `SemanticDocumentFactory.java:85,166-172`) ; les symboles et chunks portent des lignes 1-based : pas un décalage 0/1 généralisé, mais un sentinelle ambigu pour les consommateurs. `parentSymbolId` n'est jamais renseigné (F-01).

**Requêtes et analyses** (réparties sur trois modules malgré ADR-0044 : classes sous `com.minos.query` dans `minos-engine`, `com.minos.impact`/`context`/`architecture`/`program` dans `minos-application`)
- Moteur : `InMemoryCodeKnowledgeStore` (index secondaires reconstruits, tri total), `RelationshipQueryService`, `DependencyDerivationService`, `RelatedTestDerivationService` (567 l.).
- Application : `ImpactAnalysisService` (300 l.), `LocalProjectImpactQuery`, `CodeSearchService` (budget de jetons par symbole/usages/extraits), `LocalSourceReader`, `ArchitectureDependencyService`/`CentralityService`/`ModuleResolver`, `ProgramGraphService` (cache pondéré, budgets 100 k nœuds/500 k arêtes publics), `RelationshipProgramGraphProvider`, `AdvancedImpactService`.
- Tests : `ImpactAnalysisServiceTest`, `ImpactAnalysisRealFixtureTest` (minos-app), `RelatedTestHeuristicsTest`, tests d'architecture. Lacunes : occurrences→impact (F-01), langages non JVM pour les tests liés (F-02).

**Couche sémantique** : `EmbeddingProvider`, `LocalHashEmbeddingProvider`, `OllamaEmbeddingProvider`, `SemanticDocumentFactory`, `SemanticIndexService` (état, synchronisation avec réutilisation par `checksum`), `SemanticSearchService` (cache de requête LRU 256 / 8 Mio), `HybridSearchService` (lexical + graphe + sémantique, cache de corpus 64 / 256 Mio), `HybridContextBuilder`, `FileSemanticVectorStore` (format v2 float32, cache pondéré, verrou de synchronisation inter-processus). Tests : `OllamaEmbeddingProvider*Test`, `SemanticHybridIntelligenceTest` et `HybridCorpusWeightTest` (minos-bootstrap). Lacunes : provider en échec après `READY` (F-05), dérive de source (F-06), asymétrie de budgets (F-14).

**Runtime observé** : `minos-engine/.../dynamic` (`RuntimeObservation`, `RuntimeObservationSession`, `RuntimeSymbolReference/Resolution`), `RuntimeIntelligenceService` (corrélation, rapports), codec d'enveloppe TSV. Lacune : corrélation par ligne (F-03).

## (2) Ce que je n'ai PAS examiné

- **Non lus** : `RuntimeObservationEnvelopeCodec` (analyse TSV), `FileRuntimeObservationStore` et `PostgresRuntimeObservationStore` (immutabilité, SHA-256, publication atomique d'ADR-0034 §5) ; `JavaSourceProgramGraphProvider`, `FileProgramGraphProvider`, `FingerprintConstrainedJavaProgramGraphProvider`, `JavaProgramGraph*` (provider AST de l'ADR-0030, règles de sécurité taint) ; `ArchitectureConcentrationService`, `ArchitectureTopologyService`, `ArchitectureTechnologyService` ; `NexusSemanticSignalService` ; backends PostgreSQL (`PostgresSemantic*`, pgvector) et leur équivalence avec les stores locaux ; `ManagedScipPythonRuntimeManager`, `LockedNpmPackage`, `EmbeddedToolsPayload`, `StrongOwnedProcessExecutors`, `BoundedProviderSourceProbe`, scripts `scip-java-windows-runner.ps1` et `ScipWriter.java` ; le moteur d'exécution des processus (`minos-runtime-local`, sandbox) ; `ScipSymbolSnapshotImporter` côté chemins d'erreur de promotion (`CommitUncertainException`).
- **Non exécuté** : aucun test, aucun build, aucun MCP : les constats F-01, F-02, F-03, F-04, F-05, F-08 reposent sur le traçage statique du code et des tests existants ; les reproductions proposées n'ont pas été jouées. L'effet sur de vrais index (ratio d'occurrences locales non résolues, fréquence des collisions F-08, présence de `enclosing_range` selon l'indexeur) est à mesurer.
- **Non vérifié** : le comportement réel des indexeurs (scip-go, rust-analyzer, scip-clang, scip-dotnet) quant à `Document.language` vide (la fonction `inferLanguage` ne connaît que ts/js/java/py, `ScipSymbolCatalog.java:104-121`, et un symbole sans langue est ignoré, `ScipSymbolNormalizer.java:97-99` : risque non confirmé) ; les contrats des surfaces CLI/MCP/API (bornes de `limit`) ; la qualité du classement (Recall/MRR) ; ADR 0049/0050 (statut `Proposed`, aucune implémentation à auditer) ; `docs/audit/archive/2026-10-06/CAPACITES.md` (non suivi par git, lu seulement par Grep).
- **Écart documentaire noté** : ADR-0002 cite les bindings `0.9.0`, le `pom.xml:50` utilise `0.10.0` (sans conséquence fonctionnelle observée).

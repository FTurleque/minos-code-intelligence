# Design

## Context

Motivation et périmètre : voir `proposal.md`. État du code au HEAD, relu pendant la rédaction :

- **Aucun producteur** de `RelationshipKind.CALLS`, `IMPORTS`, `EXTENDS` ni `INSTANTIATES` dans
  `src/main` (recherche exhaustive). Le normalisateur SCIP n'émet que `REFERENCES` (rôle
  `is_reference` du fournisseur), `IMPLEMENTS`, `TYPE_DEFINITION` et `DEFINITION` ; les
  `DEPENDS_ON` sont dérivées de ces mêmes relations par `DependencyDerivationService`, les
  `RELATED_TEST` par `RelatedTestDerivationService`. Les occurrences sont stockées par
  `ScipIngestionAdapter` comme `SymbolOccurrence` (rôles, résolution) et ne servent qu'aux tests
  liés.
- **Impact** : `ImpactAnalysisService` (minos-application) ne lit que `snapshot.relationships()`.
  Son rapport expose déjà une liste `limitations` (enum `ImpactLimitation`) rendue par
  `ImpactResultRenderer` en JSON et en texte, et recopiée par `LocalMinosApi` dans
  `ImpactReportDto.limitations`. Les goldens CLI, MCP et API contiennent déjà
  `["DYNAMIC_DISPATCH_NOT_PROVEN","REFLECTION_NOT_PROVEN","RUNTIME_CONFIGURATION_NOT_PROVEN"]`.
  `AdvancedImpactService` (impact v2) embarque le rapport de base : il hérite de toute nouvelle
  limitation sans changement de code.
- **Architecture** : `ArchitectureDependencyService` n'agrège que `DEPENDS_ON`. Ni
  `ArchitectureDependencyGraph`, ni `ArchitectureIntelligenceView`, ni `ArchitectureResultRenderer`,
  ni `ArchitectureDto` n'ont de champ de limitations : ce n'est donc pas qu'un message à
  compléter, il faut un champ additif.
- **Appelants** : `RelationshipQueryService.findCallers` n'est appelé par aucune surface. La CLI
  (`RelationshipCommand`) et MCP (`MinosApplicationMcpBackend.findRelationships`) construisent un
  `RelationshipSearchCriteria` et appellent `ProjectSymbolQuery.findRelationships`, puis
  `CodeIntelligenceResultRenderer.renderRelationships`, qui émet `{"count", "relationships"}`. Un
  correctif posé dans `findCallers` n'atteindrait aucun utilisateur.
- **Module (F04)** : `ScipProjectSnapshotLifecycle.stage` passe `null` comme `moduleId` à
  `ScipSymbolSnapshotRequest` ; `InMemoryCodeKnowledgeStore` filtre `criteria.moduleId()` sur
  `symbol.moduleId()`. L'identifiant de module de l'architecture est
  `module:<sha256(projectId + 0x1F + chemin portable du module)>` (`ArchitectureModuleResolver`),
  attribué **par chemin de fichier**, pas par portée d'indexation.
- Caractérisation : `A2SurfaceCharacterizationTest` rejoue `impact` et `architecture` sur un index
  SCIP TypeScript réel ; les goldens `cli-json`, `cli-text`, `api`, `mcp`, `mcp-all-tools` (et
  `java-program-graph` pour le Java) portent la liste de limitations. `Golden` ne les réécrit que
  sur `-Dminos.characterization.write=true`, et sa Javadoc précise qu'un golden qui change pendant
  A2 est une régression : ce changement est une évolution volontaire, justifiée par le présent
  document, et la réécriture est une tâche explicite et relue.

## Goals / Non-Goals

**Goals :**

- Déclarer, dans chaque sortie qui l'affecte, la limite « références par occurrence non
  projetées » et « relations d'appel non produites », de façon déterministe, calculée à partir du
  contenu du snapshot (jamais de l'hôte ni du fournisseur nommé).
- Rendre le filtre `--module` honnête, puis effectif sur le chemin d'indexation autonome.
- Étendre la classification des fichiers de test aux conventions polyglottes qualifiées.

**Non-Goals :**

- Créer une relation, une arête ou un résultat d'impact de plus. Le contenu des résultats ne change
  pas ; seul son étiquetage change.
- Lire `enclosing_range` ou `enclosing_symbol`, étendre le format de snapshot, corriger F03 et
  l'ancre unique de F02 (lot ultérieur conditionnel, voir Questions ouvertes).
- Exposer les limitations dans la liste renvoyée par `MinosApi.findRelationships` (une liste ne
  peut pas porter un champ de plus sans nouvelle méthode).

## Décisions

### D1. Un prédicat unique, calculé sur le snapshot, partagé par toutes les sorties

Une classe utilitaire d'`minos-engine` (paquet des requêtes ; nom indicatif, à créer) porte :
les deux codes de limitation sous forme de constantes de chaîne, le prédicat « le snapshot contient
au moins une occurrence résolue non définitionnelle » (`SymbolOccurrence.isResolved()` et non
`isDefinitionOccurrence()`) et le prédicat « le snapshot ne contient aucune relation
`CALLS` ». Impact, architecture et requêtes de relations l'appellent ; aucune ne recopie la règle.

- *Pourquoi le contenu du snapshot et non « le fournisseur est SCIP »* : un snapshot peut mêler
  plusieurs fournisseurs, et la règle doit rester vraie quand le lot ultérieur projettera une
  partie des occurrences. Le nom `OCCURRENCE_REFERENCES_NOT_PROJECTED` est celui de la fiche F01.
  `CALL_RELATIONS_NOT_PRODUCED` est une proposition de ce changement (absente de la fiche) : à
  valider.
- *Alternative écartée* : lire le profil de capacités du fournisseur (`CALL_RELATIONS` = `UNSUPPORTED`).
  Le profil n'est pas persisté dans le snapshot et ne reflète pas un snapshot ancien ou mixte.
- *Coût* : balayage avec sortie au premier élément concordant. Le cas qui déclare la limite sort
  tôt (la première référence résolue) ; le cas « aucun appel » balaie les relations une fois par
  requête. `SnapshotQueryView` étant immuable et réutilisé entre requêtes, une mémoïsation est
  possible plus tard, sans changer le contrat.

Dépendances (ADR-0022) : engine fournit, application consomme, CLI/MCP/API consomment
l'application. Aucune arête nouvelle, aucune exception.

### D2. Impact : une valeur d'énumération de plus, aucune structure nouvelle

`ImpactLimitation` gagne `OCCURRENCE_REFERENCES_NOT_PROJECTED`, ajoutée à l'ensemble de base par
`ImpactAnalysisService` quand le prédicat de D1 est vrai. Tout le transport existe déjà
(rendu JSON et texte, DTO API, impact v2). Aucun changement de schéma : une valeur d'énumération
de plus dans un tableau de chaînes.

### D3. Architecture : champ de limitations additif de bout en bout

`ArchitectureDependencyGraph` gagne un composant `limitations` (liste de chaînes, vide par
défaut) ; `ArchitectureDependencyService` le renseigne avec D1 et précise le message de preuve
(`persisted DEPENDS_ON` reste, auquel s'ajoute l'exclusion des références par occurrence).
`ArchitectureResultRenderer` ajoute la clé `limitations` (JSON, à la fin de l'objet, avant ou
après `moduleDependencies` selon la décision de relecture, mais jamais au milieu de l'existant),
une ligne `limitations:` (texte) et un commentaire de limitation dans Mermaid (`%% limitation: …`,
syntaxe déjà utilisée par ce rendu) et DOT (`// limitation: …`). `ArchitectureDto` reçoit un
composant additif ; son constructeur canonique historique est **conservé** (constructeur secondaire
qui passe une liste vide) pour ne casser ni le code source ni le binaire des appelants de l'API Java.

- *Alternative écartée* : ne porter la limite que dans le message de preuve. Les rendus JSON/texte
  n'impriment pas les preuves : la limite resterait invisible.

### D4. Appelants, appelés, dépendances : un port additif, un rendu additif

`ProjectSymbolQuery` (interface fonctionnelle à méthodes par défaut) gagne une méthode par défaut
renvoyant les limitations applicables à un ensemble de genres de relations (nom indicatif, à
créer) ; `ProjectQueryService` l'implémente à partir de la vue du snapshot actif
(`loadActiveQueryView(...).snapshot()`) et de D1 : `CALLS` donne `CALL_RELATIONS_NOT_PRODUCED` si
aucun appel n'existe ; `DEPENDS_ON` donne `OCCURRENCE_REFERENCES_NOT_PROJECTED` ; `IMPLEMENTS` et
`RELATED_TEST` ne donnent rien. `CodeIntelligenceResultRenderer.renderRelationships` gagne une
surcharge additive qui prend les limitations : clé JSON `limitations` après `relationships`, ligne
texte `limitations:`. La surcharge à deux paramètres existante reste inchangée et produit la sortie
historique. CLI et MCP appellent la nouvelle surcharge. `MinosApi.findRelationships` renvoie une
`List` : elle est **inchangée** (non supporté dans ce lot, voir Questions ouvertes).

### D5. Module des symboles (F04) : refus honnête, puis attribution par fichier

1. **Refus explicite** (indépendant du mécanisme) : au point commun où le critère de module est
   appliqué (`SymbolQueryService` et le chemin de `CodeSearchService`), si le critère porte un
   module et qu'aucun symbole du snapshot n'en a, la requête échoue avec un message sans chemin.
   Cela couvre aussi les snapshots déjà persistés, qui ne gagnent un module qu'à la réindexation.
2. **Attribution à l'indexation autonome** : la fiche F04 propose de dériver le module du
   `projectRelativeRoot` de la portée. **C'est incorrect pour le cas qu'elle teste** : un indexeur
   qui déclare `MULTI_MODULE` et dont le système de build correspond (réacteur Maven) s'exécute
   **une seule fois à la racine** (`IndexerExecutionScopeResolver`), donc une portée couvre
   plusieurs modules. L'attribution se fait **par symbole, selon le chemin de son fichier**, avec
   la règle de `ArchitectureModuleResolver` (racine de source la plus spécifique, sinon module le
   plus profond), pour que l'identifiant saisi dans `--module` soit celui que l'architecture affiche.
3. **Porteur de la règle** : `ArchitectureModuleResolver` est dans `minos-application`, que
   `minos-provider-scip` et `minos-engine` ne peuvent pas atteindre (ADR-0022 : l'application vient
   après les adaptateurs). La règle pure d'attribution (chemin vers identifiant de module) est donc
   extraite dans `minos-engine`, paquet de découverte (nom indicatif, à créer) ;
   `ArchitectureModuleResolver` la **délègue** et conserve son nom et son API (les scripts et les
   scopes JaCoCo ne le citent pas, mais ses tests existants doivent passer inchangés). Le stager
   reçoit en donnée une table d'attribution (préfixes de chemin, identifiants de module) portée
   par la requête de mise en scène (`IndexSnapshotStageRequest`, additive : liste vide par défaut),
   que l'orchestration remplit depuis la découverte du projet (`IndexingLifecyclePlanSupport` la détient pour résoudre les portées ; que le contexte d'exécution de `IndexingRunExecutor` puisse la transmettre jusqu'à l'étape de mise en scène est **à confirmer par lecture** au début de la tâche 6.2) ;
   `ScipProjectSnapshotLifecycle.stage` l'applique aux symboles de chaque portée avant publication.
   Les symboles externes et ceux hors de tout module restent sans module.
- *Alternative écartée A* : traduire `--module` en préfixes de fichiers à la requête (sans toucher
  au snapshot). Elle laisse le texte des documents sémantiques et les sorties avec `moduleId` à
  `null`, et refait la découverte à chaque requête.
- *Alternative écartée B* : un port d'attribution injecté par `DefaultMinosApplicationComposer`.
  La découverte est par projet et par exécution ; un composant injecté à la construction
  l'ignorerait ou la referait.
- *Chemin d'import manuel* : `LocalProjectOperations` (import SCIP) passe déjà le module saisi
  par l'utilisateur ; il n'est pas modifié.

### D6. Tests liés : chemins d'abord, ancres plus tard

`RelatedTestDerivationService.isTestPath` est étendue par suffixes de nom de fichier
(`_test.go`, `_test.py`, `test_*.py`, `*_test.c`, `*_test.cc`, `*_test.cpp`) et par segment de
répertoire se terminant par `.tests` ou `.test`, avec le garde-fou existant : jamais le mot nu, et la
comparaison de nom de fichier sur le nom complet (`test_` en préfixe du nom seulement). Les
séparateurs `\` sont déjà normalisés en `/` en tête de méthode. Le rôle SCIP `Test`
(`OccurrenceRole.TEST`, mappé mais jamais lu) est un lot conditionnel, voir Questions ouvertes.

### D7. Qualification des capacités (règle du dépôt)

| Capacité | Qualification | Justification |
|---|---|---|
| Limite d'impact déclarée (CLI, MCP, API, impact v2) | **Qualifiée** | testée en unitaire et en caractérisation, indépendante de l'hôte |
| Limite d'architecture déclarée (JSON, texte, Mermaid, DOT, DTO) | **Qualifiée** | idem |
| Limites d'appelants, appelés, dépendances (CLI, MCP) | **Qualifiée** | idem |
| Limites des relations dans l'API Java | **Non supportée** | la liste renvoyée ne peut pas les porter ; hors lot |
| Projection occurrence vers relation (impact, architecture, appelants complets) | **Non supportée** | décision et ADR en attente |
| Module des symboles par indexation autonome | **Partielle** | fiable pour les snapshots produits après ce changement ; snapshots existants non migrés (refus explicite, réindexation) ; symboles hors module non attribués |
| Classification de test polyglotte | **Partielle** | chemins et nommage ; liens par référence pour les fichiers de test à plusieurs fonctions toujours absents, limite non encore déclarée (question ouverte 2) |

### D8. Windows et Linux

Rien de propre à une plateforme dans les limitations (calcul sur le contenu du snapshot ; sorties
construites avec `\n` ; goldens en fin de ligne LF imposée par `.gitattributes`). Points à ne pas
rater : (a) l'attribution du module hache le **chemin portable** (séparateur `/`), jamais le
séparateur de l'hôte, sinon les identifiants divergent entre Windows et Linux ; (b) `isTestPath`
normalise déjà `\` ; (c) le golden régénéré doit être produit puis rejoué sur les deux systèmes
(CI de qualification Ubuntu 24.04 et Windows Server 2022) ; (d) le scope JaCoCo
`m24-polyglot-provider-platform` est rouge hors régression sous Windows : comparer avec `develop`.

## Risks / Trade-offs

- [La limite est vraie sur tout index SCIP réel, donc constamment affichée] → c'est voulu : un
  bruit constant et exact vaut mieux qu'une assurance fausse. Le prédicat sera affiné quand une
  partie des occurrences sera projetée.
- [Goldens de caractérisation modifiés] → réécriture explicite
  (`-Dminos.characterization.write=true`), diff relu limité aux valeurs `limitations` ajoutées,
  vérifié identique sous Windows et Linux ; ne pas introduire de valeur dépendant de l'hôte.
- [Rupture involontaire de l'API Java publique (`ArchitectureDto`)] → constructeur historique
  conservé ; test de compatibilité dans `LocalMinosApiIntegrationTest`.
- [Client IntelliJ strict sur le JSON] → clés additives en fin d'objet ; à confirmer dans les tests
  du client Gradle (hors reactor, hors de ce changement).
- [Budget de sortie MCP] → quelques dizaines d'octets de plus ; le garde `MCP_RESULT_BUDGET_EXCEEDED`
  n'est pas modifié.
- [Texte des documents sémantiques modifié par le module renseigné] → ils sont reconstruits à la
  réindexation ; rejouer `SemanticDocumentKeysReachabilityTest` et
  `scripts/quality/check-semantic-retrieval-consistency.py`.
- [Attribution du module coûteuse sur gros snapshot] → table de préfixes triée, résolution par
  symbole en O(profondeur) ; mesurer avec `SnapshotScalabilityBenchmark` si un doute apparaît.

## Migration Plan

Additif : aucune migration de données. Les snapshots existants conservent `moduleId` nul jusqu'à la
prochaine indexation ; entre-temps `--module` refuse explicitement. Retour arrière : retirer les
valeurs et clés additives ; aucun format persistant n'est touché.

## Open Questions

Aucune ne change les exigences ni le découpage des tâches bloquantes ; les trois premières bornent
des lots ultérieurs conditionnels.

1. **Lot ultérieur conditionnel : dérivation occurrence vers relation.** Lire `enclosing_range` et
   `enclosing_symbol`, renseigner `parentSymbolId`, dériver des `REFERENCES` de nature `DERIVED`
   (preuve `DIRECT_REFERENCE`) distinguables des faits du fournisseur. **Exige l'amendement des
   ADR-0010 et ADR-0015, à décider par l'utilisateur.** Corrigerait F01 sur le fond, F03 (stockage
   d'une plage englobante : format v3, ADR-0046/0047) et l'ancre unique de F02. Aucune tâche
   bloquante ne dépend de cette décision ; `tasks.md` la porte en tâche conditionnelle marquée.
2. **Déclaration de la limite « ancre unique par fichier de test »** (F02) : où l'afficher
   (à l'ingestion, dans un profil, ou dans le rapport d'impact) et sous quel nom. Dépend de la
   décision 1 (si la plage englobante arrive, la limite disparaît).
3. **Rôle SCIP `Test` comme signal de test** : à n'engager qu'après mesure de ce que publient
   réellement les indexeurs qualifiés.
4. **Limitation héritée par l'impact v2 pour Java** : le complément AST apporte des appels pour
   Java ; faut-il nuancer la limite quand il est actif ? Par défaut elle est conservée
   (conservateur).
5. **Vocabulaire** : valider `CALL_RELATIONS_NOT_PRODUCED` (nouveau) et la position de la clé
   `limitations` dans l'objet JSON d'architecture.
6. **Mécanisme de F04** (D5.3) : confirmer l'extraction de la règle de module dans `minos-engine`
   plutôt que les alternatives A et B. Si le porteur change de module du reactor, **un ADR est à
   proposer (statut Proposed)** ; il n'est pas rédigé ici.
7. **API Java** : exposer les limitations des relations par une méthode additive de `MinosApi` dans
   un lot dédié.

## Écarts constatés à l'implémentation (2026-10-07)

Aucun ne change les exigences ; ils précisent D1 à D6. Le détail et les preuves sont dans la section « Évidence d'implémentation » de `tasks.md`.

- **D1** : le prédicat « références résolues par occurrence » exclut les occurrences de rôle définition **et** `FORWARD_DEFINITION`, et ignore les occurrences non résolues. `CALL_RELATIONS_NOT_PRODUCED` ne figure pas dans `ImpactLimitation` : il n'existe que comme code de requête d'appelants/appelés (`SnapshotCoverageLimitations`), l'impact déclarant déjà ses limites d'exécution. Le nom du code reste la question ouverte 4.
- **D3** : la clé `limitations` est la **dernière** clé du JSON d'architecture et la dernière ligne du texte ; en Mermaid et en DOT elle devient des commentaires de tête, qui n'ajoutent ni nœud ni arête. `ArchitectureDto` garde son constructeur historique (liste vide).
- **D4** : le port `ProjectSymbolQuery` reçoit une méthode **par défaut** (`relationshipLimitations`, liste vide), de sorte qu'aucune implémentation ni aucun double de test existant ne casse. `MinosApi.findRelationships` est inchangée, comme prévu.
- **D5, refus** : le refus vit dans une garde unique de `minos-engine` (`ModuleFilterGuard`), appelée par `SymbolQueryService.findSymbols` et par `CodeSearchService.search`, et s'appuie sur une méthode **par défaut** du port `CodeKnowledgeStore` (`lacksModuleAttribution`, faux) que seul `InMemoryCodeKnowledgeStore` surcharge, à partir de son index. Il ne se déclenche que si le projet **a des symboles et qu'aucun ne porte de module** : un projet vide ou un module inconnu sur un snapshot renseigné répondent liste vide. L'exception est une `IllegalArgumentException` au message sans chemin, traitée comme erreur client par la CLI et le MCP.
- **D5, attribution** : la règle extraite est `com.minos.discovery.ModuleAssignmentRule` (paquet déjà porté par `minos-engine`, aucune entrée à ajouter à la propriété des paquets). Elle porte aussi l'identifiant de module, le décodage sûr du chemin et la comparaison de préfixe ; `ArchitectureModuleResolver` la délègue et conserve son nom, sa visibilité et son type `Assignment`. La table d'attribution n'est pas une liste de préfixes : c'est **la règle elle-même**, immuable, portée par `IndexSnapshotStageRequest.moduleAssignment` (vide par défaut, constructeur à trois arguments conservé). Elle atteint le stager par `Ports` de `IndexingRunExecutor`, ce qui couvre l'exécution fraîche **et** la reprise sans toucher aux deux constructeurs de `RunContext`.
- **D5, couverture** : seules les entrées avec découverte (`IndexingLifecycleService.execute/executePlanned(..., discovery, ...)`, utilisées par `LocalAutonomousIndexOperations`) attribuent des modules. Les entrées sans découverte transmettent la règle vide ; `IncrementalIndexingCoordinator` (sans appelant de production aujourd'hui) en fait partie : si un appelant est ajouté, il devra passer la découverte, sans quoi un rafraîchissement ferait perdre les modules d'un snapshot indexé par la CLI (le refus de D5.1 le rendrait visible, pas silencieux).
- **D6** : `isTestPath` reconnaît `_test.go|py|c|cc|cpp|cxx`, `test_*.py` (préfixe, `.py` seulement) et un répertoire se terminant par `.tests` ou `.test`. Les suffixes `_test` sans nom propre devant (`_test.go`) et le segment nu `tests`/`Testing` ne comptent pas. Pas de `Test*.cs` ni de `*Tests.cs` : la convention .NET passe par le répertoire de projet de test.

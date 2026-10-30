# Design

## Context

Sprint 9 de l'audit du 10 octobre 2026 : reste d'**AUD-QUA-02** (le coordinateur d'indexation est traité par `unifier-l-orchestration-d-indexation`). HEAD analysé : `949e1569` (`develop`).

## État vérifié au HEAD

### AUD-QUA-02 — confirmé

Recherche `git grep -lw <Classe> -- '*/src/main/*'` hors du fichier de la classe : **0** pour chacune.

| Classe | Fichier | Lignes | Tests qui la nomment |
|---|---|---|---|
| `ProjectFingerprintSnapshotAlignmentService` | `minos-engine/src/main/java/com/minos/orchestration/ProjectFingerprintSnapshotAlignmentService.java` | 42 | `minos-engine` (1), `minos-bootstrap` (2) |
| `IndexerProviderRegistry` | `minos-engine/src/main/java/com/minos/orchestration/IndexerProviderRegistry.java` | 58 | `ProviderConformanceKitTest` (`minos-engine`), `M17ProviderPlatformTest` (`minos-app`) |
| `InterproceduralFlowService` | `minos-application/src/main/java/com/minos/program/analysis/InterproceduralFlowService.java` | 110 | `ProgramGraphAnalysisTest` (`minos-bootstrap`) |
| `ProgramGraphEvaluator` | `minos-application/src/main/java/com/minos/program/analysis/ProgramGraphEvaluator.java` | 46 | 3 classes de `minos-application`, 1 de `minos-bootstrap` |
| `SemanticSearchEvaluator` | `minos-application/src/main/java/com/minos/application/semantic/SemanticSearchEvaluator.java` | 70 | `SemanticHybridIntelligenceTest` (`minos-bootstrap`) |
| `NexusSemanticSignalService` | `minos-nexus/src/main/java/com/minos/nexus/NexusSemanticSignalService.java` | 52 | `NexusSemanticSignalServiceTest` (`minos-nexus`) |

- Gates : `scripts/quality/check-jacoco.py:29` (`ProgramGraphEvaluator`, `InterproceduralFlowService`), `:53` (`SemanticSearchEvaluator`), `:176` (scope `nexus-export`, par préfixe de package : il compte `NexusSemanticSignalService`) ; `scripts/quality/check-java-ast-provider-consistency.py:109`.
- Documents courants qui nomment ces classes : `docs/architecture/adr/BE-0026-discovery-provider-spi-and-explicit-capability-profiles.md:32` (`IndexerProviderRegistry`, « catalogue d'extensions ») ; `docs/developer/java-advanced-provider.md:206` (`ProgramGraphEvaluator`) ; `docs/developer/semantic-hybrid-intelligence.md:160` et `:206` ; `docs/developer/public-surfaces.md:39`, `:51`, `:266` et `docs/user/java-api.md:343` (`NexusSemanticSignalService`).
- `openspec/specs/frontieres-de-modules/spec.md:139-140` nomme `ProjectFingerprintSnapshotAlignmentService` ; le changement `unifier-l-orchestration-d-indexation` réécrit cette exigence sans noms de classes retirables.
- Le garde de frontières dit lui-même ce qu'il ne voit pas : les références par réflexion et par `ServiceLoader` (`scripts/architecture/check-module-boundaries.py:618-619`).

**Non vérifié** : aucun build ni test exécuté. L'invariant que vérifie `ProjectFingerprintSnapshotAlignmentService` (empreinte active alignée sur le snapshot actif) est peut-être déjà contrôlé par `ProjectInvalidationService` (`:70` compare l'identifiant du snapshot) : à établir en tâche 1.1. Le nombre de classes publiques sans référence de production dans tout le reactor n'a pas été calculé : il fixe la taille initiale de la liste.

### Écarts avec l'action proposée par l'audit

1. **`NexusSemanticSignalService` est un point d'entrée public, pas du code mort.** Elle est décrite comme surface NEXUS dans deux documents courants. Son absence d'appelant interne est normale pour une façade d'intégration ; ce qui manque est la preuve qu'un client l'emploie.
2. **`IndexerProviderRegistry` est nommé par un ADR.** L'ADR 0026 en fait le catalogue d'extensions ; le supprimer sans le dire contredirait une décision écrite.
3. **Déplacer `ProgramGraphEvaluator` en `src/test` demande un test-jar** : 4 classes de test dans 2 modules l'utilisent, et le gate `check-java-ast-provider-consistency.py:109` exige son appel dans un test. Un module « d'évaluation » reproduirait un package (interdit, ADR 0044).
4. **Le gate proposé a des faux positifs structurels** : implémentations découvertes par `ServiceLoader`, types publics des surfaces (`minos-api`, `minos-nexus`), points d'entrée (`main`), classes nommées seulement par un fichier `META-INF/services`. Il lui faut des exemptions **par règle** pour ce qui est prouvable, et une liste nominative pour le reste.
5. **Retirer une classe d'une portée JaCoCo change le ratio** de la portée : les seuils sont rejoués, pas supposés.

## Goals / Non-Goals

**Goals** : chaque classe a une décision écrite ; ce qui ne sert à rien est retiré avec ses gates ; ce qui reste sans appelant est déclaré avec sa raison ; une nouvelle classe sans appelant est refusée.

**Non-Goals** : brancher une capacité ; détecter le code mort à l'intérieur des classes ; couvrir le plugin IntelliJ.

## Decisions

### D1. Sort de chaque classe — **décidé par le propriétaire le 2026-10-21**

| Classe | Recommandation | Raison | Alternative |
|---|---|---|---|
| `ProjectFingerprintSnapshotAlignmentService` | **Supprimer** avec ses 3 tests, si 1.1 confirme que l'alignement est déjà vérifié sur le chemin du produit ; sinon **brancher** dans le cas d'usage d'indexation | 42 lignes d'un invariant que le produit vérifie ailleurs ou ne vérifie pas du tout | La garder sur la liste nominative |
| `IndexerProviderRegistry` | **Supprimer** et réécrire ses 2 tests sur le registre réellement utilisé (`IndexerRegistry`), avec une note dans l'ADR 0026 | Le « catalogue d'extensions » n'a pas d'appelant ; aucune extension tierce n'est supportée | La déplacer en `src/test` de `minos-engine` (le test de `minos-app` devrait alors être réécrit de toute façon) |
| `InterproceduralFlowService` | **Supprimer** avec la partie de `ProgramGraphAnalysisTest` qui la couvre | Capacité non exposée par une surface : la garder en production la présente comme acquise | Brancher, par un changement propre, si elle est promise |
| `ProgramGraphEvaluator` | **Garder en production, inscrite** sur la liste nominative comme outil de vérité terrain | 4 tests dans 2 modules et un gate en dépendent ; un test-jar coûte plus qu'il ne rapporte | Déplacer en `src/test` de `minos-application` avec un test-jar consommé par `minos-bootstrap` |
| `SemanticSearchEvaluator` | **Déplacer** dans les sources de test de `minos-bootstrap`, seul module qui l'utilise, et la retirer de `check-jacoco.py:53` | Outil d'évaluation à un seul utilisateur | La garder inscrite, comme `ProgramGraphEvaluator` |
| `NexusSemanticSignalService` | **Garder et inscrire** comme point d'entrée public de `minos-nexus` | Surface documentée ; la retirer serait une rupture de surface | Supprimer, par une décision de surface explicite (documents et contrat de signaux à retirer aussi) |

#### Décision du propriétaire (2026-10-21)

Le tableau de recommandations est retenu tel quel :

- `ProjectFingerprintSnapshotAlignmentService` : **supprimée** (condition remplie, voir le relevé 1.1 ci-dessous).
- `IndexerProviderRegistry` : **supprimée**, tests réécrits sur `IndexerRegistry`, note de « Mise en œuvre » dans l'ADR 0026.
- `InterproceduralFlowService` : **supprimée** avec le test `interproceduralTraversalIsBoundedAndReportsCycles`.
- `ProgramGraphEvaluator` : **gardée** et inscrite sur la liste.
- `SemanticSearchEvaluator` : **déplacée** dans `minos-bootstrap/src/test/java/com/minos/bootstrap/semantic/`.
- `NexusSemanticSignalService` : **gardée** et inscrite comme surface publique.
- `IncrementalIndexingCoordinator` : non touché (changement `unifier-l-orchestration-d-indexation`) ; inscrit **temporairement** sur la liste, avec cette raison, pour que la règle passe au HEAD. Le changement qui le traite retire l'entrée (le cliquet l'y oblige).

#### Relevé 1.1 (2026-10-21, `origin/develop` `1492113e`)

- **`ProjectFingerprintSnapshotAlignmentService`** : ses tests (`minos-engine` et `minos-bootstrap`) prouvaient le refus d'une empreinte active non alignée, ou présente sans index actif, et le retour vide sans empreinte. Sur le chemin du produit, `LocalAutonomousIndexOperations.prepare` (`minos-cli/.../LocalAutonomousIndexOperations.java:359-360`) charge l'empreinte active et la passe à `ProjectInvalidationService.assess`, qui compare son `indexSnapshotId` à l'index actif (`ProjectInvalidationService.java:72-81`) et impose une indexation complète (`NO_ACTIVE_INDEX`, `MISSING_FINGERPRINT_BASELINE`, `BASELINE_INDEX_MISMATCH`) ; `FileProjectFingerprintSnapshotStore.loadActive` refuse en outre un pointeur incohérent (`:247`). L'alignement est donc déjà vérifié, en mode conservateur (indexation complète plutôt qu'exception) : suppression. `ProjectFingerprintSnapshotRealFixtureTest` garde sa preuve de persistance par `loadActive`.
- **`IndexerProviderRegistry`** : `ProviderConformanceKitTest` attendait le refus d'un doublon et d'un descriptor dont l'identifiant diffère du profil ; `M17ProviderPlatformTest` l'utilisait seulement pour obtenir un `IndexerRegistry` de négociation. Le refus du doublon est réécrit sur `IndexerRegistry` ; le contrôle descriptor/profil, propre au catalogue supprimé, disparaît (aucun code de production ne le faisait passer par là).
- **`InterproceduralFlowService`** : un seul test (parcours borné, cycle signalé) ; aucune surface ne l'expose.
- **`ProgramGraphEvaluator`**, **`SemanticSearchEvaluator`** : outils de mesure de vérité terrain des tests ; **`NexusSemanticSignalService`** : un test de contrat dans `minos-nexus`.

#### Relevé 1.2 (règle A15 appliquée au HEAD après les suppressions)

Types publics de premier niveau sans référence de production : **12** avec les exemptions par règle (`minos-api` 4, `minos-application` 2, `minos-engine` 4, `minos-nexus` 1, `minos-storage-local` 1), **20** sans elles (8 de plus, tous exemptés par une méthode `main` ou un fichier `META-INF/services` : `minos-app` 2, `minos-bootstrap` 1, `minos-cli` 1, `minos-mcp` 1, `minos-provider-scip` 1, `minos-runtime-local` 1, `minos-storage-postgresql` 1). Hors D1 : les quatre façades `Local*Api` de `minos-api` (points d'entrée publics, inscrits un par un : la liste reste courte, pas d'exemption par module), `StorageBackends` (façade d'ADR 0042), `InMemoryIndexStateStore` (utilisée par les seuls tests de cinq modules ; la déplacer demanderait un test-jar, hors lot). `CodeKnowledgeSnapshotBinaryCodec`, `HostedAvailabilityPort`, `HostedTransportSecurityPort` sont bien dans le cas.

### D2. Le gate : exemptions par règle, puis liste nominative à cliquet

Une règle du garde de frontières (`scripts/architecture/check-module-boundaries.py`, numéro fixé à l'implémentation, après ceux que réserve `figer-la-dette-de-frontieres-par-des-cliquets`) : toute classe, interface ou record **public de premier niveau** d'un `src/main` du reactor est nommée par au moins un autre fichier de production du reactor, sauf :

- exemption par règle : classe nommée dans un fichier `META-INF/services` de production ; classe déclarant `public static void main` ;
- liste nominative `UNREFERENCED_PRODUCTION_TYPES` : une entrée par classe, avec sa raison écrite (surface publique, frontière déclarée, outil d'évaluation).

La liste est un **cliquet**, comme A8 et A10 : une entrée dont la classe a disparu ou a gagné un appelant fait échouer le garde, de sorte qu'elle ne peut que rétrécir. Les types publics de `minos-api` y entrent en bloc par leur module seulement si la tâche 1.2 montre qu'une liste nominative serait trop longue pour être relue ; ce choix est consigné.

**Numéro retenu : A15.** A11 à A14 sont réservés par `figer-la-dette-de-frontieres-par-des-cliquets` ; A16 est la règle « `ServiceLoader.load` à deux arguments » de `decouvrir-les-fournisseurs-par-chargeur-explicite`.

### D3. Les gates suivent dans la même tâche

Une classe supprimée ou déplacée est retirée de sa portée JaCoCo dans la même tâche ; le littéral de `check-java-ast-provider-consistency.py:109` est conservé tant que `ProgramGraphEvaluator` reste, ou mis à jour dans la tâche qui la déplace. Les portées touchées sont rejouées.

## Direction des dépendances

`domain → engine → runtime/storage → adapters → application → surfaces`. Aucune arête ajoutée. Un déplacement en sources de test ne crée pas de dépendance de production ; aucun module n'est créé (ADR 0044) ; `application ↛ adaptateurs` et `surfaces ↛ adaptateurs` inchangés.

## Qualification des capacités

- Flux interprocédural (`InterproceduralFlowService`) : **non supporté** par une surface au HEAD ; le retirer de la production le dit.
- Signaux sémantiques NEXUS v2 : surface publique présente, **non qualifiée** par un client ; déclarée telle sur la liste.
- Catalogue d'extensions de providers : **non supporté**.
- Évaluateurs : outils de test, pas des capacités.

## Windows et Linux

Aucune différence : gate Python et déplacements de sources. Le gate compare des chemins : il normalise les séparateurs.

## Risks / Trade-offs

- **SonarCloud** : une classe déplacée (même vers des tests d'un autre module) et ses tests réécrits comptent comme code neuf ; une suppression fait bouger la couverture des portées JaCoCo à préfixe (`nexus-export`, portées de `:29` et `:53`) : seuils rejoués, pas abaissés.
- Faux négatifs du gate : une classe nommée seulement par une autre classe elle-même sans appelant passe ; assumé et écrit dans le commentaire de la règle.
- Une liste initiale longue serait un catalogue de dette de plus : sa taille est mesurée avant d'écrire la règle (tâche 1.2), et chaque entrée porte une raison.
- Supprimer une classe documentée laisse des documents faux : les pages listées ci-dessus sont mises à jour dans la même tâche.

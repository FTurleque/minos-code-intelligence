# Proposal

## Why

Suite du changement archivé `lire-l-identite-du-snapshot-actif-sans-le-charger` (AUD-PERF-01, audit du 10 octobre 2026). Sa tâche 1.1 a relevé trois appelants de `loadActiveKnowledge` qui ne lisent que l'identifiant du snapshot actif et qui sont restés hors de son périmètre (design archivé, « Autres appelants »).

Le plus coûteux est `ProjectIndexStateReconciler` : **toute lecture de statut** (`observeStatus`, donc `minos_index_status` et `project inspect`) charge et décode le snapshot actif complet **deux fois** pour n'en comparer que l'identifiant, **cinq fois** quand une réparation est nécessaire (deux hors bail, deux sous bail, un à la vérification). Sous le store fichier, au-delà de la limite d'admission du cache de vues, ce sont deux décodages complets par statut ; sous PostgreSQL, deux lectures de tous les symboles, occurrences et relations. Le port `CodeKnowledgeSnapshotStore` offre déjà la lecture légère `activeSnapshotId`, redéfinie par les deux stores (lecture du seul pointeur actif).

Le snapshot chargé est rendu par le record public `ProjectIndexStateReconciler.Reconciliation.activeSnapshot`, dont le seul consommateur de production (`ProjectInspectionService`) ne lit que l'identifiant. Changer le type de ce composant serait une rupture silencieuse du contrat que l'ADR 0056 interdit.

## What Changes

- **Le réconciliateur compare des identifiants** : il lit l'identité du snapshot actif par `activeSnapshotId`, jamais par chargement complet.
- **Migration additive du contrat** : un nouveau record `ReconciledState` (identifiant du snapshot actif, état du projet, réparation) et trois lectures `reconcileState`, `observeState`, `observeStatusState`. L'ancien record `Reconciliation` et les méthodes `reconcile`, `observe`, `observeStatus` sont **conservés et dépréciés** ; ils rendent le même résultat qu'aujourd'hui au prix d'un seul chargement (au lieu de deux à cinq). Seuls `Reconciliation` et `reconcile` existaient dans la version publiée 1.2.0 ; `observe` et `observeStatus` sont apparus depuis et sont dépréciés par cohérence.
- **Les appelants de production passent au nouveau contrat** : `ProjectInspectionService` (statut) et `LocalAutonomousIndexOperations` (alignement de l'état avant indexation).
- **Deux autres contrôles d'identité ne chargent plus le snapshot** : la liaison hébergée de `MinosApplicationAssembler` et la reprise d'un run promu de `LocalAutonomousIndexOperations`.
- **Preuve par décompte** : faux store à compteurs, une lecture de statut passe de 2 chargements complets à 0, une réparation de 5 à 0.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : reçoit le coût borné de la lecture de l'identité du snapshot actif par le statut, la réconciliation, la liaison hébergée et la reprise d'un run promu.

## Hors périmètre

- `WorkspaceIntelligenceService.java:113` : il lit symboles et relations, le chargement complet y est requis.
- Trois autres appelants qui ne lisent que l'identifiant, relevés à la contre-vérification de ce changement et non relevés par le changement archivé : `minos-application/.../dynamic/RuntimeIntelligenceService.java:112` (`listSessions`, via `:245-247`), `minos-provider-scip/.../ingestion/ScipSymbolSnapshotImporter.java:91-93` (reprise après `CommitUncertainException`) et `minos-provider-scip/.../runtime/ScipProjectSnapshotLifecycle.java:160-162` (`observeActiveSnapshot`, appelé à chaque run par `IndexingRunExecutor` et `AuthoritativeProjectStateReconciler`). Suite à part : ils sont hors des chemins de statut visés ici.
- Le retrait de `Reconciliation` et des méthodes dépréciées : il demande une rupture versionnée explicite (ADR 0056), non décidée ici.
- La refonte du port de lecture neutre (`CodeKnowledgeReader`, ADR 0056, SH-04).
- Le chemin de requête hybride, déjà traité par AUD-PERF-01.
- Toute mesure de durée : la preuve est un décompte.

## Impact

- **Modules du reactor** : `minos-application` (`application/ProjectIndexStateReconciler`, `application/ProjectInspectionService`, `application/MinosApplicationAssembler`), `minos-cli` (`cli/operations/LocalAutonomousIndexOperations`), tests de `minos-bootstrap` (`ProjectIndexStateReconcilerTest`). Aucun port modifié, aucune dépendance nouvelle.
- **Surfaces publiques** : aucune sortie CLI, MCP, API Java (`minos-api`), IntelliJ ni NEXUS ne change. Le contrat Java de `ProjectIndexStateReconciler` gagne des ajouts et des dépréciations, sans retrait ni changement de signature.
- **ADR** : aucun nouvel ADR ; complète la mise en œuvre de l'ADR 0056 (migration additive d'un record public), à citer dans sa section « Mise en œuvre ».
- **Gates à rejouer** : `scripts/quality/check-hosted-control-plane-consistency.py` (exige `new HostedControlPlaneService` dans l'assembleur), `check-runtime-dynamic-consistency.py`, `check-polyglot-provider-consistency.py`, `check-remote-distributed-consistency.py` et `scripts/remediation/check-post-mne.py` (lisent `LocalAutonomousIndexOperations.java`), `scripts/architecture/check-module-boundaries.py`. Aucun scope JaCoCo de `check-jacoco.py` ne nomme les classes touchées.
- **Plateformes** : Windows et Linux, sans comportement propre à l'une.

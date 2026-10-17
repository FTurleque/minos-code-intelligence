# Proposal

## Why

**AUD-ARC-04** (moyenne, sprint 9 de l'audit du 10 octobre 2026 ; résidu connu de l'audit de septembre et tâche SH-10 de `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md`). Le code de production fait **4** découvertes `ServiceLoader`. Deux appliquent le bon motif — chargeur de classes explicite, échec si zéro ou plusieurs fournisseurs : `minos-application/src/main/java/com/minos/application/MinosApplicationComposers.java:36` et `minos-cli/src/main/java/com/minos/cli/McpLaunchRoutes.java:31`. Les deux autres utilisent le chargeur de contexte du thread et prennent le premier fournisseur qui convient :

- `minos-bootstrap/src/main/java/com/minos/bootstrap/StorageBackendSelection.java:23` (backend de stockage optionnel, PostgreSQL) ;
- `minos-engine/src/main/java/com/minos/orchestration/StableFileSystemIdentity.java:46` (identité physique d'un chemin, fournie sous Windows par `com.minos.runtime.local.sandbox.WindowsExecutionPathIdentityProvider`).

Dans un hôte Java qui embarque l'API publique (ADR 0018) avec un autre chargeur de contexte, le backend PostgreSQL est déclaré indisponible et, sous Windows, tout lancement de provider local est refusé : c'est fail-closed, donc une indisponibilité et non une faille. Deux fournisseurs de même identifiant donnent un choix arbitraire et silencieux.

## What Changes

- **Chargeur explicite** aux deux sites : celui qui a chargé MINOS, comme pour la racine de composition.
- **Refus des doublons** : deux fournisseurs de stockage de même identifiant, ou plus d'un fournisseur d'identité de chemin, font échouer la découverte avec un message qui nomme les classes.
- **Un gate** refuse tout `ServiceLoader.load` de production sans chargeur explicite.
- **Injection du fournisseur d'identité depuis `minos-bootstrap`** : reportée, elle touche une surface publique du moteur (voir `design.md`, D3).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées (découverte par le chargeur de MINOS ; refus des fournisseurs en double ; aucun `ServiceLoader.load` de production sans chargeur explicite).

## Hors périmètre

- Injecter le fournisseur d'identité de chemin depuis la racine de composition (D3) : décision en attente, changement séparé.
- La qualification d'un hôte embarqué complet et du JAR ombré au-delà des tests de chargeur isolé prévus ici : reste de SH-10.
- Supporter des extensions tierces chargées par un autre chargeur de classes : non qualifié, non promis.
- Les autres tâches du chantier hexagonal et les règles de liste fermée du sprint 10.

## Impact

- **Modules du reactor** : `minos-bootstrap` (`StorageBackendSelection`), `minos-engine` (`orchestration/StableFileSystemIdentity`) ; tests de `minos-api`, `minos-cli`, `minos-mcp` (ils enregistrent le double `CloseCountingStorageProvider`, d'identifiant `postgresql`). Aucune dépendance ajoutée.
- **Surfaces publiques** : aucune sortie nominale ne change. Un message d'erreur nouveau en cas de doublon (CLI, API Java, MCP au démarrage) ; le message « provider is not available » existant est conservé.
- **ADR** : amendement de l'ADR 0042 (note de « Mise en œuvre » : résidu de chargeur contextuel fermé, règle des doublons) ; aucun nouvel ADR.
- **Gates à rejouer** : `python scripts/architecture/check-module-boundaries.py` et `scripts/architecture/test_check_module_boundaries.py` (règle nouvelle), `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-jacoco.py` (scope `m30-storage-backend-selection`), `python scripts/quality/check-single-execution.py`.
- **Plateformes** : Windows (le fournisseur d'identité de chemin n'y est consulté que là où le système de fichiers n'expose pas d'identité) et Linux.

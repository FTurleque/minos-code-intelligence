# Proposal

## Why

Le constat **MINOS-AUD-E01** (audit d'octobre 2026) est toujours ouvert au HEAD `560a2fa9` : le backend PostgreSQL ne persiste pas `resumableRunId`. Sur ce backend, la reprise d'une indexation interrompue, décidée par l'ADR 0039 § 2, ne fonctionne donc pas. Rien ne le signale.

- Un run interrompu est bien enregistré `INTERRUPTED`, mais l'état du projet relu ne propose aucune reprise. L'`index` suivant repart de zéro.
- Le marqueur `runs/<id>/.resumable` et le snapshot préparé restent sur le disque : l'identifiant n'est jamais relu, donc l'offre de reprise n'est jamais close.
- La rétention PostgreSQL peut supprimer le run reprenable, alors que le backend local le protège comme le dernier run.

Le propriétaire a décidé le 2026-10-26 de livrer ce correctif **avant** la fusion des adaptateurs de stockage (SH-02, décision Q1 = A de `fusionner-les-adaptateurs-de-stockage`). Les trois fichiers corrigés sont parmi ceux que SH-02 déplace. Les tests naissent donc dans le module actuel, et aucune branche ouverte en même temps n'entre en conflit avec ce correctif.

## What Changes

- **Migration de schéma v5.** `PostgresSchemaMigrator.CURRENT_VERSION` passe de 4 à 5. `applyV5` ajoute la colonne `resumable_run_id uuid NULL` à `project_index_state`, et ne change rien d'autre au schéma. La migration est idempotente (`ADD COLUMN IF NOT EXISTS`) et s'exécute dans la transaction déjà sérialisée par le verrou consultatif.
- **`PostgresIndexStateStore`.**
  - `findProjectState` lit la colonne et passe par le constructeur à 7 arguments de `ProjectIndexState`.
  - `saveProjectState` écrit la colonne à l'insertion et à la mise à jour (`ON CONFLICT`).
  - Une valeur NULL se relit comme `Optional.empty()`.
- **`PostgresStorageRetentionService.deleteHistoricalRuns`.** Le run que désigne `resumable_run_id` est protégé exactement comme celui de `latest_run_id`.
- **Test de contrat commun aux deux backends.** L'interface de test `IndexStateStoreContract`, placée dans les tests de `minos-engine` et publiée par son test-jar, dont les cas sont des méthodes `@Test` par défaut, fixe ce que tout `IndexStateStore` garantit : aller-retour de l'état avec et sans offre de reprise, retrait de l'offre, relecture par une instance neuve. Elle s'exécute contre `FileIndexStateStore` et contre `PostgresIndexStateStore`.
- **Test de rétention.** Avec `maxNonSucceededRuns = 0`, un run `INTERRUPTED` offert à la reprise survit à `compact`, sur les deux backends.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : deux exigences ajoutées. L'état d'indexation conserve l'offre de reprise quel que soit le backend. La rétention protège le run offert à la reprise.

## Hors périmètre

- Le comportement de la reprise elle-même (`IndexingResumePlanner`, `AuthoritativeProjectStateReconciler`) et ses marqueurs, qui restent tels quels.
- La fusion des modules de stockage (SH-02) et le codec commun (SH-03).
- La reprise des runs `INTERRUPTED` antérieurs à la migration. Leur état relu n'a aucune offre, la valeur de la colonne étant NULL, et l'`index` suivant fait une indexation complète. C'est déjà le comportement actuel.
- Tout autre changement de schéma.

## Impact

- **Modules** : `minos-storage-postgresql` (3 classes de production et leurs tests) ; `minos-engine` (test-jar seulement : `IndexStateStoreContract`) ; `minos-storage-local` (test seulement : implémentation du contrat). Aucune dépendance ajoutée.
- **Surfaces publiques** : aucune signature ne change.
  - Avec PostgreSQL, `minos project status` et l'`index` suivant voient désormais l'offre de reprise, comme avec le backend local.
  - Une base migrée en v5 est refusée par un MINOS antérieur, par le contrôle déjà existant « schema is newer than this MINOS runtime ». C'est la règle de toutes les migrations ; les notes de version la signalent.
- **ADR** : aucun nouvel ADR. L'ADR 0039 (`docs/architecture/adr/BE-0039-reprise-indexation-apres-interruption.md`) reçoit une note de mise en œuvre : l'offre est persistée par les deux backends.
- **Gates** :
  - `check-jacoco.py`, portée `m30-postgresql-pgvector` (préfixe `storage/postgresql/`, seuils inchangés) ;
  - les gates de remédiation qui lisent `PostgresSchemaMigrator.java` ou `PostgresIndexStateStore.java` (`check-p0-p2.py`, à rejouer).
  - Aucun gate n'affirme `CURRENT_VERSION = 4`. Seule la documentation le cite (`docs/developer/quality/code-audit-couverture.md:64`).
- **Plateformes** : les tests PostgreSQL sont exigés sous Linux (`pr-ci.yml:380`, `minos.postgresql.tests.required=true`). Sous Windows, ils passent par Testcontainers et Docker Desktop. Le contrat s'exécute pour le backend local sur les deux systèmes.
- **Constat** : MINOS-AUD-E01.

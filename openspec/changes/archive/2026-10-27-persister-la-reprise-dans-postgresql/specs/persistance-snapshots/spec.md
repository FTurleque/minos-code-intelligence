## ADDED Requirements

### Requirement: L'état d'indexation conserve l'offre de reprise quel que soit le backend
Tout `IndexStateStore` SHALL relire `resumableRunId` tel qu'il a été enregistré, par la même instance et par une instance neuve, et SHALL relire une offre retirée comme absente. Le backend PostgreSQL SHALL stocker l'offre dans la colonne `resumable_run_id` de `project_index_state`, ajoutée par la migration de schéma v5. Une base v5 SHALL être refusée par un runtime de version de schéma inférieure.

#### Scenario: Aller-retour d'une offre
- **GIVEN** un état `FAILED` qui offre le run `R` à la reprise
- **WHEN** le contrat commun l'enregistre puis le relit avec `FileIndexStateStore` et avec `PostgresIndexStateStore`, chacun rouvert
- **THEN** les deux backends relisent `resumableRunId = R`

#### Scenario: Offre retirée
- **GIVEN** un état qui offrait `R`, remplacé par un état sans offre
- **WHEN** l'état est relu
- **THEN** `resumableRunId` est vide sur les deux backends

#### Scenario: Base v4 existante
- **GIVEN** une base au schéma v4 dont `project_index_state` contient un projet
- **WHEN** le stockage s'ouvre
- **THEN** `schema_version` contient 1 à 5, le projet se relit sans offre, et une nouvelle offre est ensuite conservée

#### Scenario: Base plus récente que le runtime
- **GIVEN** une base dont `schema_version` contient 6
- **WHEN** le stockage s'ouvre
- **THEN** l'ouverture échoue, avec un message qui dit que le schéma est plus récent que le runtime, sans rien modifier

### Requirement: La rétention protège le run offert à la reprise
La compaction des runs SHALL conserver le run désigné par `resumableRunId` de l'état du projet, comme celui désigné par `latestRunId`, quel que soit le plafond de runs non réussis et quel que soit le backend.

#### Scenario: Plafond à zéro
- **GIVEN** un run `INTERRUPTED` offert à la reprise et une politique `maxNonSucceededRuns = 0`
- **WHEN** la rétention compacte le projet sur le backend local puis sur PostgreSQL
- **THEN** le run existe encore sur les deux backends

#### Scenario: Run non offert
- **GIVEN** un second run `INTERRUPTED` qui n'est ni le dernier ni celui qui est offert
- **WHEN** la même compaction s'exécute
- **THEN** ce run est supprimé

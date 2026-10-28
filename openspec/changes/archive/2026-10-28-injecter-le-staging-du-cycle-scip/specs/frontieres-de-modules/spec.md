## MODIFIED Requirements

### Requirement: Les types d'un adaptateur nommés par un autre adaptateur le sont par liste fermée
Le garde de frontières SHALL tenir, par couple d'adaptateurs, les types de l'un que l'autre nomme par import ou nom qualifié, chacun avec le nombre de fichiers de production qui le nomment, et une raison écrite par couple. Un type hors liste, un compteur différent du nombre de fichiers observé, une entrée devenue inutile, un couple sans raison ou une raison sans couple SHALL faire échouer le garde.

#### Scenario: Nouveau type du backend local nommé par PostgreSQL
- **GIVEN** une copie du dépôt où `com.minos.storage.postgresql` nomme un type de `com.minos.storage.local`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue par la règle des packages du stockage, sans table qui l'admette, car les deux backends sont dans le même module

#### Scenario: Usage en nom qualifié sans import
- **GIVEN** une copie du dépôt où un type hors liste est utilisé par son nom qualifié complet
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue comme pour un import

#### Scenario: Nouveau fichier du provider SCIP qui nomme un type déjà listé
- **GIVEN** une copie du dépôt où un nouveau fichier de `minos-provider-scip` importe `runtime.local.process.CommandLocator`, déjà listé
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le couple, le type, le compteur listé et le compteur observé

#### Scenario: Couple listé sans raison
- **GIVEN** une table où le couple `minos-provider-scip` → `minos-runtime-local` n'a pas d'entrée dans `ADAPTER_COUPLING_REASONS`, ou une raison vide
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le couple et en demandant sa raison

#### Scenario: Raison d'un couple disparu
- **GIVEN** une raison écrite pour un couple d'adaptateurs absent de `ADAPTER_TYPES_NAMED_BY_ADAPTER`
- **WHEN** le garde est exécuté
- **THEN** il échoue en demandant de retirer la raison

## ADDED Requirements

### Requirement: Le provider SCIP ne nomme pas le stockage en production
Aucune source de production de `minos-provider-scip` SHALL nommer un type de `minos-storage`. Le provider SHALL recevoir le magasin actif et ses magasins de travail par les ports du moteur `CodeKnowledgeSnapshotStore` et `StagingSnapshotStores`, que `minos-bootstrap` lui fournit. Son POM SHALL déclarer `minos-storage` en portée `test` seulement, et A13 SHALL n'avoir aucune entrée `minos-provider-scip` → `minos-storage`.

#### Scenario: Retour d'une création de magasin dans le provider
- **GIVEN** une copie du dépôt où `ScipProjectSnapshotLifecycle` crée de nouveau un `FileSymbolSnapshotStore`
- **WHEN** le garde est exécuté
- **THEN** A13 échoue en nommant le fichier et le type absent de la table

#### Scenario: Dépendance de compilation rétablie
- **GIVEN** une copie du dépôt où le POM de `minos-provider-scip` déclare `minos-storage` sans portée
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le module, la dépendance et la portée attendue

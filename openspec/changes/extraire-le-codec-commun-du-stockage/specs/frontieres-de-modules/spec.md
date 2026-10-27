## ADDED Requirements

### Requirement: Les backends de stockage ne se nomment pas l'un l'autre
Aucune source de production ni de test de `com.minos.storage.local` SHALL nommer `com.minos.storage.postgresql` ou `org.postgresql`, et aucune de `com.minos.storage.postgresql` SHALL nommer `com.minos.storage.local`, par import simple, statique, joker ou nom qualifié. Le garde SHALL échouer en nommant le fichier, le type et la règle. Une mention en commentaire ou dans un littéral SHALL rester acceptée.

#### Scenario: Test PostgreSQL qui nomme le backend local
- **GIVEN** une copie du dépôt où une classe de test de `com.minos.storage.postgresql` importe `com.minos.storage.local.store.FileSymbolSnapshotStore`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` s'exécute
- **THEN** il échoue en nommant le fichier de test, le type et la règle

#### Scenario: Backend local qui nomme PostgreSQL par nom qualifié
- **GIVEN** une copie où une source de production de `com.minos.storage.local` nomme `com.minos.storage.postgresql.support.PostgresJdbcUrlPolicy` sans import
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier et le type

#### Scenario: Mention en commentaire
- **GIVEN** une copie où une Javadoc de `com.minos.storage.local` cite `com.minos.storage.postgresql`
- **WHEN** le garde s'exécute
- **THEN** il réussit

### Requirement: Le codec et le mapping de chemins ne dépendent d'aucun backend
Aucune source de production ni de test de `com.minos.storage.codec` ou de `com.minos.storage.home` SHALL nommer `com.minos.storage.local`, `com.minos.storage.postgresql`, `org.postgresql` ou `java.sql`. Le garde SHALL refuser toute violation en nommant le fichier, le type et la règle, et `ModuleArchitectureTest` SHALL refuser la même dépendance dans le bytecode de production.

#### Scenario: Codec qui ouvre une connexion JDBC
- **GIVEN** une copie où `com.minos.storage.codec.KnowledgeSnapshotCodecs` importe `java.sql.Connection`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier, `java.sql.Connection` et la règle du codec

#### Scenario: Mapping qui appelle le backend local
- **GIVEN** une copie où `com.minos.storage.home.ProjectPathMappingStore` nomme `com.minos.storage.local.registry.LocalProjectRegistry`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier et le type

#### Scenario: Dépendance du codec dans le bytecode
- **GIVEN** une classe compilée de `com.minos.storage.codec` qui dépend d'une classe de `com.minos.storage.local`
- **WHEN** `ModuleArchitectureTest` s'exécute
- **THEN** il échoue en nommant les deux classes

### Requirement: Le backend PostgreSQL ne dépend pas du backend local dans le bytecode
`ModuleArchitectureTest` SHALL refuser toute dépendance d'une classe de production de `com.minos.storage.postgresql` vers `com.minos.storage.local`. Le garde et cette règle SHALL rester vérifiables sans base PostgreSQL ni Docker.

#### Scenario: Registre PostgreSQL qui utilise le registre local
- **GIVEN** une classe compilée de `com.minos.storage.postgresql` qui appelle `com.minos.storage.local.registry.LocalProjectRegistry`
- **WHEN** `ModuleArchitectureTest` s'exécute
- **THEN** il échoue en nommant les deux classes

#### Scenario: Module conforme
- **GIVEN** le dépôt après ce changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest"` s'exécute sans Docker
- **THEN** toutes les règles réussissent

## REMOVED Requirements

### Requirement: Les backends de stockage ne se nomment l'un l'autre que dans une liste qui rétrécit
**Reason**: la table `BACKEND_TYPES_NAMED_ACROSS_PACKAGES` est vide : le codec et le mapping de chemins sortent du backend local.
**Migration**: l'exigence « Les backends de stockage ne se nomment pas l'un l'autre » interdit toute référence entre backends, sans table, sur la production et les tests.

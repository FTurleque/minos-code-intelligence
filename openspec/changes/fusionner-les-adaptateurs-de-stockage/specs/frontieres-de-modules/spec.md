## ADDED Requirements

### Requirement: Les adaptateurs de stockage forment un seul module
Le reactor SHALL compter un seul module de stockage, `minos-storage`, qui contient `com.minos.storage.local` et `com.minos.storage.postgresql` sans classe dupliquée ni package déclaré ailleurs. `ALLOWED_DEPENDENCIES` et `ModuleArchitectureTest.ALLOWED` SHALL lui donner `minos-domain` et `minos-engine` pour seules cibles. Le garde SHALL refuser un module `minos-storage-local` ou `minos-storage-postgresql` réapparu.

#### Scenario: Reactor fusionné
- **GIVEN** le dépôt après la fusion
- **WHEN** `python scripts/architecture/check-module-boundaries.py` s'exécute
- **THEN** il réussit, avec 13 modules, et les tables A2 et A17 nomment `minos-storage` sans aucun des deux anciens nœuds

#### Scenario: Ancien module réintroduit
- **GIVEN** une copie du dépôt dont le reactor déclare de nouveau `minos-storage-postgresql`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le module absent de `MODULES`

### Requirement: La composition et le provider SCIP ne nomment pas le backend PostgreSQL
Aucune source de production de `minos-bootstrap` ni de `minos-provider-scip` SHALL nommer un type de `com.minos.storage.postgresql` ou de `org.postgresql`, par import simple, statique, joker ou nom qualifié. Le backend PostgreSQL SHALL n'être enregistré que par `ServiceLoader`. Une mention en commentaire ou en littéral reste acceptée.

#### Scenario: Import du backend dans la composition
- **GIVEN** une copie du dépôt où `DefaultMinosApplicationComposer` importe `com.minos.storage.postgresql.PostgresStorageBackend`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier, le type et la règle

#### Scenario: Provider SCIP qui nomme le pilote
- **GIVEN** une copie où une source de `minos-provider-scip` nomme `org.postgresql.Driver` par son nom qualifié
- **WHEN** le garde s'exécute
- **THEN** il échoue ; la même chaîne dans un commentaire est acceptée

### Requirement: Le mode local ne charge aucune classe PostgreSQL
Une application composée avec le backend `local` SHALL fonctionner sans serveur PostgreSQL ni Docker, et SHALL ne charger aucune classe `org.postgresql.*` ni `com.minos.storage.postgresql.*`. Un test de `minos-bootstrap` SHALL le vérifier sur les deux systèmes de la CI.

#### Scenario: Composition locale
- **GIVEN** un `MINOS_HOME` temporaire configuré sur le backend `local`
- **WHEN** le test compose l'application puis interroge le chargeur de classes
- **THEN** aucune classe des deux espaces de noms n'est chargée et le test réussit sans Docker

#### Scenario: Chargement forcé
- **GIVEN** le même test, avec une variante qui charge `PostgresStorageBackend` avant la vérification
- **WHEN** la variante s'exécute
- **THEN** la vérification échoue en nommant la classe chargée

### Requirement: Les backends de stockage ne se nomment l'un l'autre que dans une liste qui rétrécit
Le garde SHALL tenir la table `BACKEND_TYPES_NAMED_ACROSS_PACKAGES` des types de `com.minos.storage.local` nommés par la production de `com.minos.storage.postgresql`, avec leur nombre de fichiers. Il SHALL refuser un type ou un fichier en plus, une entrée périmée, et toute référence de `com.minos.storage.local` vers `com.minos.storage.postgresql`.

#### Scenario: Nouveau type local nommé par PostgreSQL
- **GIVEN** une copie où une source de `com.minos.storage.postgresql` importe `com.minos.storage.local.store.FileSymbolSnapshotStore`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le fichier et le type absent de la table

#### Scenario: Sens interdit
- **GIVEN** une copie où une source de `com.minos.storage.local` nomme `com.minos.storage.postgresql.support.PostgresJdbcUrlPolicy`
- **WHEN** le garde s'exécute
- **THEN** il échoue, quelle que soit la table

#### Scenario: Entrée devenue inutile
- **GIVEN** une copie où plus aucune source de `com.minos.storage.postgresql` ne nomme `store.SnapshotIntegrityService`
- **WHEN** le garde s'exécute
- **THEN** il échoue en demandant de retirer l'entrée de la table

### Requirement: Le garde refuse l'ajout de sources par build-helper
`check_pom_layout` SHALL refuser `org.codehaus.mojo:build-helper-maven-plugin` dans tout `<build>` d'un POM du reactor, à la racine comme dans un profil. Les autres plugins déclarés dans un profil SHALL rester acceptés.

#### Scenario: Sources d'un autre module ajoutées
- **GIVEN** une copie où un POM déclare `build-helper-maven-plugin` avec `add-source` vers `../minos-storage/src/main/java`, à la racine ou dans un profil
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le POM et le plugin

#### Scenario: Plugin ordinaire dans un profil
- **GIVEN** un profil qui configure `maven-surefire-plugin`
- **WHEN** le garde s'exécute
- **THEN** la règle de mise en page réussit

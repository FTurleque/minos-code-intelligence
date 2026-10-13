## ADDED Requirements

### Requirement: Le message d'une primitive d'E/S privée ne contient pas de chemin absolu
Une exception levée par une primitive d'E/S privée pour une cible refusée (lien symbolique, type de fichier inattendu, durabilité non garantie) SHALL nommer cette cible par une étiquette fixe ou par son nom de fichier. Son message SHALL NOT contenir le chemin absolu de la cible ni celui de son répertoire.

#### Scenario: Répertoire à la place d'un fichier de propriétés
- **GIVEN** un répertoire temporaire `T` qui contient un répertoire nommé `minos.properties`
- **WHEN** `BoundedProperties.load` est appelé sur ce chemin
- **THEN** l'appel échoue et le message de l'exception ne contient pas le chemin de `T`

#### Scenario: Lien symbolique refusé
- **GIVEN** un lien symbolique vers un fichier régulier, sur un système où le lien a pu être créé
- **WHEN** un store de `minos-storage-local` le rencontre à la place de son fichier
- **THEN** la lecture est refusée et le message ne contient pas le chemin absolu du lien

### Requirement: Une configuration d'exécution présente mais non régulière est refusée
Le chargement de la configuration d'exécution SHALL tester l'existence de `config/minos.properties` sans suivre les liens. Si rien n'existe à cet emplacement, il SHALL réussir avec une configuration vide. Si l'emplacement est occupé par autre chose qu'un fichier régulier (répertoire, lien symbolique, lien cassé), il SHALL échouer et SHALL NOT appliquer les valeurs par défaut.

#### Scenario: Aucun fichier de configuration
- **GIVEN** un `MINOS_HOME` sans `config/minos.properties`
- **WHEN** `MinosRuntimeSettings.load` est appelé
- **THEN** le chargement réussit avec une configuration vide

#### Scenario: Répertoire à la place du fichier
- **GIVEN** un `MINOS_HOME` où `config/minos.properties` est un répertoire
- **WHEN** `MinosRuntimeSettings.load` est appelé
- **THEN** le chargement échoue, sans chemin absolu dans le message

#### Scenario: Lien cassé à la place du fichier
- **GIVEN** un `MINOS_HOME` où `config/minos.properties` est un lien symbolique vers une cible absente, sur un système où le lien a pu être créé
- **WHEN** `MinosRuntimeSettings.load` est appelé
- **THEN** le chargement échoue au lieu de rendre une configuration vide

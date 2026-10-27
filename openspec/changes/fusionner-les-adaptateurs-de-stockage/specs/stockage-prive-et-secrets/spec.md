## MODIFIED Requirements

### Requirement: Le message d'une primitive d'E/S privée ne contient pas de chemin absolu
Une exception levée par une primitive d'E/S privée pour une cible refusée (lien symbolique, type de fichier inattendu, durabilité non garantie) SHALL nommer cette cible par une étiquette fixe ou par son nom de fichier. Son message SHALL NOT contenir le chemin absolu de la cible ni celui de son répertoire.

#### Scenario: Répertoire à la place d'un fichier de propriétés
- **GIVEN** un répertoire temporaire `T` qui contient un répertoire nommé `minos.properties`
- **WHEN** `BoundedProperties.load` est appelé sur ce chemin
- **THEN** l'appel échoue et le message de l'exception ne contient pas le chemin de `T`

#### Scenario: Lien symbolique refusé
- **GIVEN** un lien symbolique vers un fichier régulier, sur un système où le lien a pu être créé
- **WHEN** un store du backend local de `minos-storage` le rencontre à la place de son fichier
- **THEN** la lecture est refusée et le message ne contient pas le chemin absolu du lien

### Requirement: Le magasin PostgreSQL n'écrit pas ses fichiers de travail par un appel brut
Le magasin de snapshots PostgreSQL SHALL écrire ses fichiers de travail par les primitives d'E/S privées. Un fichier de travail remplacé par un lien symbolique SHALL faire échouer l'opération sans modifier la cible du lien et sans laisser de fichier de travail.

#### Scenario: Lien substitué au fichier de travail
- **GIVEN** un fichier de travail remplacé par un lien symbolique vers un fichier sentinelle
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test` exécute le test du magasin
- **THEN** l'opération échoue, la sentinelle est intacte et le fichier de travail est supprimé

#### Scenario: Plus d'entrée de liste pour le magasin
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-storage/src/main/java/com/minos/storage/postgresql/`

### Requirement: Le stockage local écrit ses fichiers par les primitives d'E/S privées
Le code de production du backend local (`com.minos.storage.local`, dans `minos-storage`) SHALL écrire ses snapshots, pointeurs, empreintes, vecteurs, sessions d'observation, fichiers de propriétés et sauvegardes par les primitives d'E/S privées, sans appel brut de `Files` listé dans la liste de tolérance. Une écriture dont la cible est un lien symbolique SHALL échouer sans modifier la cible du lien.

#### Scenario: Lien substitué au fichier d'un snapshot
- **GIVEN** un lien symbolique vers un fichier sentinelle à l'emplacement du fichier de snapshot à écrire
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test` exécute les tests de lien du module
- **THEN** l'écriture échoue et la sentinelle est intacte

#### Scenario: Plus aucune entrée de liste pour le module
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-storage/src/main/java/com/minos/storage/local/`

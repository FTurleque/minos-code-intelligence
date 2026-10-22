## ADDED Requirements

### Requirement: Les types du moteur nommés par une surface le sont par liste fermée
Le garde de frontières SHALL tenir, pour chaque surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`), la liste des types de `minos-engine` qu'elle nomme, par import ou nom qualifié. Un type hors liste SHALL faire échouer le garde. Une entrée que la surface ne nomme plus, ou qui désigne un type inexistant, SHALL le faire échouer aussi.

#### Scenario: Nouveau type du moteur importé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe un type de `minos-engine` absent de la liste
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant la surface, le fichier et le type

#### Scenario: Entrée devenue inutile
- **GIVEN** une copie du dépôt où le dernier usage d'un type listé a été retiré sans retirer l'entrée
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche le nombre de types listés par surface

### Requirement: Le lancement de processus hors du runtime local est tenu par liste fermée
Dans `minos-engine`, `minos-application` et `minos-domain`, une classe qui lance un processus (`ProcessBuilder`, `Runtime.exec`) SHALL figurer dans une liste fermée du garde de frontières. Une classe hors liste SHALL faire échouer le garde ; une entrée qui ne lance plus de processus aussi.

#### Scenario: Second site de lancement dans le moteur
- **GIVEN** une copie du dépôt où une classe de `minos-engine` hors liste instancie `ProcessBuilder`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe

#### Scenario: Site existant
- **GIVEN** le dépôt, où seule la classe listée lance un processus
- **WHEN** le garde est exécuté
- **THEN** il réussit

### Requirement: Les types d'un adaptateur nommés par un autre adaptateur le sont par liste fermée
Le garde de frontières SHALL tenir, par couple d'adaptateurs, la liste des types de l'un que l'autre nomme, par import ou nom qualifié. Un type hors liste SHALL faire échouer le garde, ainsi qu'une entrée devenue inutile.

#### Scenario: Nouveau type du backend local nommé par PostgreSQL
- **GIVEN** une copie du dépôt où `minos-storage-postgresql` nomme un type de `minos-storage-local` absent de la liste
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant les deux modules et le type

#### Scenario: Usage en nom qualifié sans import
- **GIVEN** une copie du dépôt où un type hors liste est utilisé par son nom qualifié complet
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue comme pour un import

### Requirement: Un module ne crée pas de nouvelle racine de packages
Le garde de frontières SHALL tenir, par module, la liste des racines de packages `com.minos.<nom>` qu'il possède. Un package dont la racine n'est pas dans la liste de son module SHALL faire échouer le garde.

#### Scenario: Nouvelle racine dans le moteur
- **GIVEN** une copie du dépôt où `minos-engine` contient un package `com.minos.nouveau`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le module et la racine

#### Scenario: Sous-package d'une racine existante
- **GIVEN** une copie du dépôt où un module ajoute un sous-package sous une de ses racines listées
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il réussit

### Requirement: Les arêtes d'exécution par service figurent dans le document de dépendances
Le document de dépendances généré SHALL lister les arêtes d'exécution par service, où un module charge l'implémentation fournie par un autre. Son contrôle SHALL échouer quand une telle arête existe dans les sources et manque au document versionné.

#### Scenario: Arête de la CLI vers l'assemblage
- **GIVEN** le dépôt, où `minos-app` fournit le service de route MCP déclaré par `minos-cli`
- **WHEN** le document est généré
- **THEN** il contient l'arête d'exécution de `minos-cli` vers `minos-app`

#### Scenario: Document sans l'arête
- **GIVEN** une copie du dépôt où cette ligne est retirée du document versionné
- **WHEN** le contrôle du document est exécuté sur cette copie
- **THEN** il échoue en nommant l'arête manquante

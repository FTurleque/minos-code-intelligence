## MODIFIED Requirements

### Requirement: Les types du moteur nommés par une surface le sont par liste fermée
Le garde de frontières SHALL tenir, pour chaque surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`), les types de `minos-engine` qu'elle utilise, chacun avec le nombre de fichiers de production qui l'utilisent. Un type hors liste SHALL faire échouer le garde, ainsi qu'un compteur différent du nombre de fichiers observé, une entrée inutilisée ou une entrée désignant un type inexistant.

#### Scenario: Nouveau type du moteur importé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe un type de `minos-engine` absent de la liste
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant la surface, le fichier et le type

#### Scenario: Nouveau fichier qui nomme un type déjà listé
- **GIVEN** une copie du dépôt où un nouveau fichier de `minos-cli` importe `io.PrivateLocalStorage`, déjà listé pour un fichier
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le type, le compteur listé, le compteur observé et les fichiers qui l'utilisent

#### Scenario: Compteur devenu trop grand
- **GIVEN** une copie du dépôt où l'un des fichiers qui nomment un type listé ne le nomme plus
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en donnant le compteur à écrire

#### Scenario: Entrée devenue inutile
- **GIVEN** une copie du dépôt où le dernier usage d'un type listé a été retiré sans retirer l'entrée
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche, par surface, le nombre de types listés et de fichiers qui les utilisent

### Requirement: Le lancement de processus hors du runtime local est tenu par liste fermée
Dans `minos-engine`, `minos-application` et `minos-domain`, une classe qui lance un processus SHALL figurer dans une liste fermée du garde avec son nombre de sites de lancement (`new ProcessBuilder`, `ProcessBuilder::new`, `ProcessBuilder.startPipeline`, `Runtime.getRuntime().exec`). Une classe hors liste, un nombre de sites différent ou une entrée qui ne lance plus rien SHALL faire échouer le garde.

#### Scenario: Second site de lancement dans le moteur
- **GIVEN** une copie du dépôt où une classe de `minos-engine` hors liste instancie `ProcessBuilder`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe

#### Scenario: Troisième appel dans la classe listée
- **GIVEN** une copie du dépôt où `PrivateLocalStorage`, listé avec 2 sites, construit un troisième `ProcessBuilder`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe, le nombre de sites listé et le nombre observé

#### Scenario: Site retiré sans baisser le compteur
- **GIVEN** une copie du dépôt où la classe listée n'a plus qu'un site de lancement
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en donnant le nombre de sites à écrire

#### Scenario: Site existant
- **GIVEN** le dépôt, où seule la classe listée lance un processus, avec ses sites listés
- **WHEN** le garde est exécuté
- **THEN** il réussit

### Requirement: Les types d'un adaptateur nommés par un autre adaptateur le sont par liste fermée
Le garde de frontières SHALL tenir, par couple d'adaptateurs, les types de l'un que l'autre nomme par import ou nom qualifié, chacun avec le nombre de fichiers de production qui le nomment. Un type hors liste, un compteur différent du nombre de fichiers observé ou une entrée devenue inutile SHALL faire échouer le garde.

#### Scenario: Nouveau type du backend local nommé par PostgreSQL
- **GIVEN** une copie du dépôt où `minos-storage-postgresql` nomme un type de `minos-storage-local` absent de la liste
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant les deux modules et le type

#### Scenario: Usage en nom qualifié sans import
- **GIVEN** une copie du dépôt où un type hors liste est utilisé par son nom qualifié complet
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue comme pour un import

#### Scenario: Nouveau fichier du provider SCIP qui nomme un type déjà listé
- **GIVEN** une copie du dépôt où un nouveau fichier de `minos-provider-scip` importe `runtime.local.process.CommandLocator`, déjà listé
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le couple, le type, le compteur listé et le compteur observé

## ADDED Requirements

### Requirement: Un accesseur de MinosApplication appelé par une surface compte comme un usage du type rendu
Le garde SHALL lire dans la source de `MinosApplication` ses méthodes publiques d'instance dont le type de retour nomme un type de `minos-engine`. Dans une surface, un appel de même arité ou une référence `::nom` à l'une d'elles SHALL compter comme un usage du type rendu, dans le compteur de la règle A11. Un appel homonyme d'arité différente SHALL être ignoré.

#### Scenario: Accesseur appelé sans nommer le type
- **GIVEN** une copie du dépôt où un fichier de `minos-api` appelle `app.snapshotStore()` sans importer `CodeKnowledgeSnapshotStore`, absent de la liste de l'API
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la surface, le fichier et le type rendu

#### Scenario: Import remplacé par var
- **GIVEN** une copie du dépôt où un fichier qui importait `GitIntelligence` écrit `var g = app.gitIntelligence()` sans l'import
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il réussit avec le même compteur : le fichier utilise toujours le type

#### Scenario: Méthode homonyme d'arité différente
- **GIVEN** une copie du dépôt où une surface appelle `builder.gitIntelligence(supplier)`, à un argument
- **WHEN** le garde est exécuté sur cette copie
- **THEN** cet appel n'est pas compté comme un usage de `GitIntelligence`

### Requirement: Les types du moteur utilisés par le bytecode d'une surface le sont par liste fermée
Le test `ModuleArchitectureTest` SHALL tenir, par surface, les types de premier niveau de `minos-engine` dont dépendent ses classes compilées, chacun avec le nombre de classes de premier niveau qui en dépendent. Un type hors table, un compteur différent ou une entrée inutilisée SHALL faire échouer le test.

#### Scenario: Appel chaîné sur un résultat du moteur
- **GIVEN** une classe de `minos-cli` qui appelle une méthode d'un type de `minos-engine` absent de la table, sans le nommer
- **WHEN** `ModuleArchitectureTest` est exécuté
- **THEN** il échoue en nommant la surface, le type, le compteur attendu et les classes

#### Scenario: Usage retiré sans mettre la table à jour
- **GIVEN** une table du test qui liste un type dont plus aucune classe de la surface ne dépend
- **WHEN** le test est exécuté
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le reactor compilé
- **WHEN** `ModuleArchitectureTest` est exécuté dans les jobs `Verify` Linux et Windows
- **THEN** il réussit

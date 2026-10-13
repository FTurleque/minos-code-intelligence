## ADDED Requirements

### Requirement: Les tests du provider SCIP ne dépendent pas du JAVA_HOME de l'hôte
Les tests de `minos-provider-scip` SHALL réussir quand la variable `JAVA_HOME` de l'hôte est absente. Un test qui a besoin d'un JDK de projet SHALL poser lui-même l'environnement du processus qu'il observe. Un test qui lit le `JAVA_HOME` de l'hôte dans la JVM de test SHALL échouer sur tout système, y compris en CI.

#### Scenario: Poste sans JAVA_HOME
- **GIVEN** un shell où `JAVA_HOME` n'est pas définie et où `java` est dans le `PATH`
- **WHEN** `./mvnw -B -ntp -pl minos-provider-scip -am test` est exécuté
- **THEN** les tests du module réussissent

#### Scenario: Dépendance réintroduite
- **GIVEN** un test qui appelle la fabrique de plan scip-java dans la JVM de test sans poser d'environnement
- **WHEN** les tests du module sont exécutés sur un hôte où `JAVA_HOME` est définie
- **THEN** ce test échoue avec le message de refus du JDK de projet

### Requirement: Sans JDK de projet, le plan scip-java est refusé avant toute préparation
La fabrique de plan scip-java SHALL refuser de construire un plan quand `JAVA_HOME` est absente, vide, ou ne désigne pas un JDK contenant `javac`. Le refus SHALL précéder toute copie du projet et SHALL nommer la cause.

#### Scenario: Variable non posée
- **GIVEN** un processus dont l'environnement ne contient pas de `JAVA_HOME` utilisable
- **WHEN** un plan scip-java est demandé pour un projet Maven
- **THEN** la demande échoue avec « JAVA_HOME is not set » et aucun fichier n'a été préparé

#### Scenario: Dossier sans compilateur
- **GIVEN** un `JAVA_HOME` qui désigne un dossier sans `javac`
- **WHEN** un plan scip-java est demandé
- **THEN** la demande échoue en nommant l'absence de `javac` et aucun fichier n'a été préparé

### Requirement: Un test du provider propre à Windows est compté comme sauté ailleurs
Un test de `minos-provider-scip` qui ne vaut que sous Windows SHALL être déclaré comme tel et SHALL apparaître comme sauté dans le rapport de tests sous Linux. Il SHALL NOT être gardé par une condition interne qui le ferait compter comme réussi sans s'exécuter.

#### Scenario: Rapport sous Linux
- **GIVEN** les tests du module exécutés sous Linux
- **WHEN** le rapport surefire de la classe de test du gestionnaire de runtime est lu
- **THEN** le test de propagation au runtime Windows y figure comme sauté

#### Scenario: Exécution sous Windows
- **GIVEN** les tests du module exécutés sous Windows sans `JAVA_HOME` sur l'hôte
- **WHEN** le test de propagation au runtime Windows s'exécute
- **THEN** il réussit et le plan observé porte la langue Kotlin et le Maven géré

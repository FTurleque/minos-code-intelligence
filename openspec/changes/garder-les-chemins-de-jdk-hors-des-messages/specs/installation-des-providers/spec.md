## MODIFIED Requirements

### Requirement: Sans JDK de projet, le plan scip-java est refusé avant toute préparation
La fabrique de plan scip-java SHALL refuser de construire un plan quand `JAVA_HOME` est absente, vide, ou ne désigne pas un JDK contenant `javac`. Le refus SHALL précéder toute copie du projet et SHALL nommer la cause. Son message SHALL nommer la variable `JAVA_HOME` et MUST NOT contenir sa valeur.

#### Scenario: Variable non posée
- **GIVEN** un processus dont l'environnement ne contient pas de `JAVA_HOME` utilisable
- **WHEN** un plan scip-java est demandé pour un projet Maven
- **THEN** la demande échoue avec « JAVA_HOME is not set » et aucun fichier n'a été préparé

#### Scenario: Dossier sans compilateur
- **GIVEN** un `JAVA_HOME` qui désigne un dossier sans `javac`
- **WHEN** un plan scip-java est demandé
- **THEN** la demande échoue en nommant l'absence de `javac` et aucun fichier n'a été préparé
- **AND** le message de l'exception vaut « JAVA_HOME does not point to a JDK containing javac » et ne contient pas le chemin de ce dossier

## ADDED Requirements

### Requirement: Le diagnostic d'un JAVA_HOME sans javac ne contient pas son chemin
Quand le JDK de projet vient de `JAVA_HOME` et que ce dossier ne contient pas `javac`, le diagnostic du runtime scip-java, repris par `minos doctor` et par la sonde de la voie WSL, SHALL nommer la variable `JAVA_HOME` et l'absence de `javac`, et MUST NOT contenir la valeur de la variable.

#### Scenario: Diagnostic du JDK de projet
- **GIVEN** un environnement dont `JAVA_HOME` désigne un dossier temporaire sans `bin/javac`
- **WHEN** le diagnostic du JDK de projet est lu (`ScipJavaProjectJdkTest`)
- **THEN** il vaut « JAVA_HOME does not contain javac » et ne contient pas le chemin du dossier temporaire

#### Scenario: Diagnostic du runtime scip-java
- **GIVEN** un processus fils dont `JAVA_HOME` désigne un dossier sans `javac`
- **WHEN** le runtime scip-java est inspecté
- **THEN** ses diagnostics contiennent « machine prerequisite (not shipped by MINOS): JAVA_HOME does not contain javac »
- **AND** ce diagnostic est le texte du scénario précédent, préfixé sans autre ajout

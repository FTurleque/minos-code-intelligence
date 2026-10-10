## ADDED Requirements

### Requirement: Le lanceur de développement ne résout pas java depuis le répertoire courant
Le lanceur `minos.cmd` de la racine du dépôt SHALL appeler `%JAVA_HOME%\bin\java.exe` quand ce fichier existe et SHALL, sinon, interdire la recherche de `java` dans le répertoire courant. Un fichier `java.bat` ou `java.exe` du projet ouvert par le plugin SHALL NOT être exécuté.

#### Scenario: java.bat piégé dans le répertoire courant
- **GIVEN** un répertoire courant contenant un `java.bat` qui écrit un marqueur, un `java` valide dans le `PATH`, et la variable `NoDefaultCurrentDirectoryInExePath` retirée de l'environnement transmis au lanceur
- **WHEN** `minos.cmd` est exécuté depuis ce répertoire
- **THEN** le `java` du `PATH` s'exécute et le marqueur du `java.bat` n'est pas écrit

#### Scenario: JAVA_HOME défini
- **GIVEN** `JAVA_HOME` désignant un JDK de test dont `bin\java.exe` écrit un marqueur, et un `java.bat` piégé dans le répertoire courant
- **WHEN** `minos.cmd` est exécuté
- **THEN** le marqueur du JDK de `JAVA_HOME` est écrit et celui du piège ne l'est pas

#### Scenario: Aucune fuite vers l'appelant
- **GIVEN** un interpréteur appelant dont l'environnement ne contient pas `NoDefaultCurrentDirectoryInExePath`
- **WHEN** `minos.cmd` est appelé puis se termine, et l'appelant affiche ensuite la variable
- **THEN** l'appelant ne la voit toujours pas (portée limitée par `setlocal`)

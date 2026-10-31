# installation-des-providers Specification

## Purpose
Définit ce que MINOS garantit quand il installe ou sonde un outil de provider hors bac à sable : un environnement en liste d'autorisation où un motif de secret l'emporte toujours, y compris sur les variables déclarées par un plan d'indexation, et un classpath de scip-java vérifié jar par jar contre un verrou SHA-256 tenu par le catalogue, sans exécuter scip-java à l'installation. Le plan d'indexation Linux, qui relance encore Coursier, est hors de cette capacité.

## Requirements

### Requirement: Une commande d'installation ou de sondage ne reçoit pas l'environnement complet
Toute commande externe lancée par `minos tools install` ou par une sonde de toolchain (`coursier`, `npm`, `dotnet`, `go`) SHALL recevoir un environnement construit depuis une liste d'autorisation, jamais l'environnement hérité de MINOS. Le répertoire personnel, les variables de proxy et d'autorité de certification et les caches nécessaires SHALL y figurer.

#### Scenario: Secrets de MINOS absents de la commande
- **GIVEN** un processus MINOS dont l'environnement contient `MINOS_TEAM_KEY_ALPHA`, `MINOS_TEAM_TOKEN`, `GITHUB_TOKEN` et `NPM_TOKEN`
- **WHEN** une commande d'installation est lancée par chacun des trois gestionnaires de providers
- **THEN** l'environnement observé dans la commande ne contient aucune de ces quatre variables

#### Scenario: Variables nécessaires conservées
- **GIVEN** un environnement contenant `PATH`, `HOME` (Linux), `HTTPS_PROXY`, `NO_PROXY` et `COURSIER_CACHE`
- **WHEN** une commande d'installation est lancée
- **THEN** ces cinq variables sont présentes avec leur valeur

#### Scenario: Sonde de toolchain
- **WHEN** `dotnet --version` ou `go version` est lancé pour sonder une toolchain
- **THEN** l'environnement de la sonde est celui de la liste d'autorisation, sans variable à motif de secret

### Requirement: Un motif de secret l'emporte sur toute liste d'autorisation
Un nom de variable correspondant à `MINOS_*`, `*TOKEN*`, `*SECRET*`, `*PASSWORD*`, `*KEY*`, `*CREDENTIAL*`, `GITHUB_*` ou `NPM_*` SHALL être retiré de l'environnement d'une commande d'installation, même s'il figure dans la liste d'autorisation ou parmi les variables déclarées par l'appelant.

#### Scenario: Variable déclarée mais à motif de secret
- **GIVEN** un appelant qui déclare `MINOS_REMOTE_TOKEN_X` parmi les variables à transmettre
- **WHEN** la façade construit l'environnement
- **THEN** cette variable est absente du résultat

#### Scenario: Variable ordinaire déclarée
- **GIVEN** un appelant qui déclare `GOBIN` et `GOPROXY`
- **WHEN** la façade construit l'environnement
- **THEN** ces deux variables sont présentes

### Requirement: Une variable déclarée par un plan d'indexation n'échappe pas au motif de secret
L'environnement d'un provider d'indexation, et celui du lanceur de confiance qui le contient, SHALL appliquer aux variables déclarées par le plan le même motif de refus que les commandes d'installation : un nom à motif de secret déclaré par un plan SHALL être retiré. Le motif SHALL être défini une seule fois.

#### Scenario: Plan qui déclare un nom à motif de secret
- **GIVEN** un plan d'indexation qui déclare `GITHUB_TOKEN` et `CARGO_TARGET_DIR`
- **WHEN** `./mvnw.cmd -pl minos-runtime-local -am test "-Dtest=ProviderProcessEnvironmentDeclaredSecretsTest"` est exécuté
- **THEN** seul `CARGO_TARGET_DIR` atteint le provider et le lanceur

#### Scenario: Variable ordinaire déclarée par un plan
- **GIVEN** un plan qui déclare `PATH` pour remplacer la valeur héritée
- **WHEN** l'environnement du provider est construit
- **THEN** la valeur déclarée est transmise

### Requirement: Le classpath de scip-java est vérifié jar par jar avant tout usage
Après résolution par `coursier fetch --classpath`, l'installation de scip-java SHALL comparer l'ensemble des jars obtenus au verrou du catalogue (noms et SHA-256), SHALL refuser tout jar absent, en trop ou d'empreinte différente, et SHALL supprimer le résultat partiel avant de signaler l'échec. Elle SHALL NOT écrire `classpath.txt` avant la fin de la vérification.

#### Scenario: Classpath conforme
- **GIVEN** un faux `coursier` qui produit exactement les jars du verrou de test
- **WHEN** l'installation de scip-java est exécutée
- **THEN** `classpath.txt` est écrit et l'état du provider est prêt

#### Scenario: Jar modifié
- **GIVEN** un classpath dont un jar a un octet changé
- **WHEN** l'installation est exécutée
- **THEN** elle échoue en nommant le jar (nom de fichier sans chemin absolu), aucun `classpath.txt` n'existe et le répertoire partiel est supprimé

#### Scenario: Jar en trop ou manquant
- **GIVEN** un classpath qui contient un jar absent du verrou, puis un classpath auquel il en manque un
- **WHEN** l'installation est exécutée dans chaque cas
- **THEN** elle échoue dans les deux cas, avec un message distinct pour l'ajout et pour le manque

#### Scenario: Verrou absent
- **GIVEN** une ressource de verrou absente ou illisible
- **WHEN** l'installation est exécutée
- **THEN** elle échoue (fail-closed) sans lancer `coursier`

### Requirement: L'installation n'exécute pas scip-java pour en lire la version
L'installation de scip-java SHALL NOT lancer `coursier launch` ni la classe principale de scip-java. La version installée SHALL être celle du verrou dont les empreintes ont été vérifiées.

#### Scenario: Commandes lancées par l'installation
- **GIVEN** un faux `coursier` qui enregistre ses arguments
- **WHEN** l'installation de scip-java est exécutée
- **THEN** aucun enregistrement ne contient `launch` ni `--main`, et la seule sous-commande de résolution utilisée est `fetch`

### Requirement: Le verrou est tenu par le catalogue et contrôlé en CI
Le catalogue des outils embarqués SHALL déclarer le verrou du classpath de scip-java avec la version de scip-java, et `scripts/quality/check-tools-manifest.py` SHALL échouer quand le verrou ne correspond pas à la version du catalogue.

#### Scenario: Version du catalogue et verrou concordants
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur le dépôt corrigé
- **THEN** il réussit

#### Scenario: Version du catalogue changée sans régénérer le verrou
- **GIVEN** une copie du catalogue dont la version de scip-java est modifiée
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le verrou à régénérer

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

### Requirement: Le helper d'identité de chemin Windows n'hérite pas de l'environnement de MINOS
Le helper PowerShell qui capture l'identité physique d'une racine de projet sous Windows SHALL recevoir la liste d'autorisation des lanceurs de confiance et ses deux variables cibles, jamais l'environnement hérité de MINOS. Une variable à motif de secret et `PSModulePath` SHALL en être absentes. Si l'environnement autorisé ne suffit pas au helper, la capture SHALL échouer et le lancement être refusé.

#### Scenario: Secrets présents dans l'environnement de la JVM
- **GIVEN** un environnement parent qui contient `SystemRoot`, `MINOS_TEAM_KEY_1`, `GITHUB_TOKEN` et `PGPASSWORD`
- **WHEN** `./mvnw.cmd -pl minos-runtime-local -am test "-Dtest=WindowsExecutionPathIdentityHelperEnvironmentTest"` est exécuté
- **THEN** l'environnement du helper est exactement `SystemRoot` et les deux cibles `MINOS_*_PATH_IDENTITY_TARGET`

#### Scenario: Chemin de modules hérité
- **GIVEN** un environnement parent qui contient `PSModulePath`, `JAVA_HOME`, `TEMP` et `TMP`
- **WHEN** l'environnement du helper est construit
- **THEN** `TEMP` et `TMP` sont transmis, `PSModulePath` et `JAVA_HOME` ne le sont pas

#### Scenario: Capture réelle sous Windows
- **GIVEN** deux répertoires existants sur un poste Windows
- **WHEN** leur identité est capturée avec l'environnement autorisé
- **THEN** deux identités distinctes sont rendues, sans aucune variable de secret transmise

### Requirement: scip-java sous Windows n'utilise que le PowerShell du répertoire système
Sous Windows, le plan et la disponibilité de scip-java SHALL utiliser le Windows PowerShell 5.1 du répertoire système, le même que celui du bac à sable. Un `powershell.exe` ou un `pwsh.exe` trouvé dans le `PATH` MUST NOT être retenu, ni par le plan ni par la disponibilité.

#### Scenario: PowerShell 7 seul dans le PATH
- **GIVEN** sous Windows, un `PATH` qui contient un `pwsh.exe` et pas le répertoire de Windows PowerShell
- **WHEN** la disponibilité de scip-java est calculée
- **THEN** elle juge le `powershell.exe` du répertoire système

#### Scenario: PowerShell dans le PATH
- **GIVEN** sous Windows, un `PATH` qui ne contient qu'un `powershell.exe` et un `pwsh.exe` d'un autre répertoire
- **WHEN** le plan scip-java est construit
- **THEN** sa commande commence par le `powershell.exe` du répertoire système et ne nomme pas cet autre répertoire

### Requirement: scip-java sous Windows n'est pas déclaré prêt sans exécution qualifiée sous le bac à sable
Tant qu'aucun test n'exécute la chaîne scip-java Windows sous le bac à sable AppContainer, la disponibilité de scip-java sous Windows SHALL être bloquée, avec un prérequis qui dit que l'exécution sous le bac à sable n'est pas qualifiée et qu'aucune voie n'indexe un projet Java depuis Windows ; il MUST NOT renvoyer vers le runtime Docker, qui ne lance aucun provider. L'indexation Java SHALL alors être refusée avant tout lancement de provider.

#### Scenario: Doctor sous Windows
- **GIVEN** sous Windows, scip-java installé avec tous ses fichiers
- **WHEN** `minos doctor` est lancé
- **THEN** scip-java n'est pas `READY`, son diagnostic dit qu'aucune voie n'indexe un projet Java depuis Windows et ne nomme pas le runtime Docker

#### Scenario: Linux inchangé
- **GIVEN** sous Linux, scip-java installé avec tous ses fichiers
- **WHEN** la disponibilité est calculée
- **THEN** elle reste `READY`

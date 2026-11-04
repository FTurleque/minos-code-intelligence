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

### Requirement: L'installation d'un outil échoue fermée quand le déplacement atomique manque
L'installation d'un outil de provider SHALL publier ses fichiers et ses répertoires par un déplacement atomique et SHALL échouer, sans copie ni déplacement non atomique de repli, quand le système de fichiers ne le permet pas. L'arbre précédent SHALL être restauré quand le déplacement du nouvel arbre échoue.

#### Scenario: Déplacement atomique refusé
- **GIVEN** un déplaceur de répertoire qui signale l'absence de déplacement atomique
- **WHEN** `./mvnw -B -ntp -pl minos-provider-scip -am test` exécute le test d'installation d'un arbre
- **THEN** l'installation échoue, aucune copie n'est faite et la destination est inchangée

#### Scenario: Échec du déplacement du nouvel arbre
- **GIVEN** un arbre installé et un déplacement du nouvel arbre qui échoue
- **WHEN** l'échange est exécuté
- **THEN** l'arbre précédent est à sa place et l'échec est propagé

### Requirement: scip-java lance Maven hors ligne sur le dépôt désigné
Hors Windows, le plan scip-java d'un projet Maven, avec le classpath installé comme avec le lanceur du `PATH`, SHALL déclarer le dépôt désigné en racine de lecture et passer après `--` : `--batch-mode --offline`, `-s` et `-gs` vers le `settings.xml` de MINOS, un dépôt local de tête dans le run, le dépôt désigné en `maven.repo.local.tail`, puis `clean verify -DskipTests`. Le réseau reste `DENY`.

#### Scenario: Plan avec dépôt désigné
- **GIVEN** sous Linux, un projet Maven et un dépôt Maven désigné valide
- **WHEN** le plan scip-java est construit
- **THEN** sa commande contient `--offline`, `-s`, `-gs`, `-Dmaven.repo.local.tail=` suivi du dépôt, et sa racine de lecture est ce dépôt

#### Scenario: Indexation réelle hors ligne
- **GIVEN** sous Linux avec bac à sable qualifié, `java-simple` et un dépôt désigné qui contient ses plugins et dépendances
- **WHEN** `minos index` est lancé
- **THEN** l'artefact SCIP est produit et le dépôt désigné est inchangé

### Requirement: Une désignation de dépôt qui expose un dossier sensible est refusée avant préparation
Hors Windows, le plan SHALL être refusé avant toute copie du projet sans dépôt désigné, ou si la désignation est relative, absente, un lien, ou si elle est, ou est l'ancêtre de : le dossier personnel, `/mnt/<lecteur>/Users/<compte>`, `/etc`, `/proc`, `/sys`, `/dev`, `/root`, `MINOS_HOME`. Le message MUST nommer le réglage et MUST NOT contenir de chemin absolu.

#### Scenario: Aucun dépôt désigné
- **GIVEN** sous Linux, un projet Maven et aucun dépôt désigné
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé, le message nomme le réglage, et aucun espace de travail n'est créé dans le run

#### Scenario: Profil Windows désigné depuis WSL
- **GIVEN** sous Linux, la désignation `/mnt/c/Users/<compte>` ou `/mnt/c`
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé sans chemin absolu dans le message

### Requirement: Une désignation qui n'a pas la forme d'un dépôt Maven est refusée
Le plan SHALL refuser une désignation dont la racine contient `settings.xml`, `settings-security.xml`, `toolchains.xml`, un dossier caché (`.ssh`, `.m2`, `.config`…) ou un fichier ordinaire hors d'une liste fermée de métadonnées Maven, ou qui est nommée `.m2` ou située sous `MINOS_HOME` ou `/run`. Le message MUST NOT contenir de chemin absolu.

#### Scenario: Désignation qui contient les réglages de l'utilisateur
- **GIVEN** sous Linux, un dépôt désigné qui contient `settings.xml` à sa racine
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé sans chemin absolu dans le message

#### Scenario: Désignation qui contient un dossier de secrets
- **GIVEN** sous Linux, une désignation dont la racine contient `.ssh`
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé

### Requirement: Les réglages Maven et le dépôt de tête sont fixés par MINOS pour chaque run
Le `settings.xml` du run SHALL avoir un contenu constant, sans serveur, miroir, proxy, profil, dépôt local ni mode hors ligne. Le dépôt de tête SHALL être créé vide pour le run ; s'il existe déjà, le plan MUST être refusé. Aucun fichier de réglages de l'utilisateur ni son dossier MUST NOT être nommé par la commande ou les racines de lecture.

#### Scenario: Réglages de l'utilisateur présents à côté du dépôt
- **GIVEN** sous Linux, `~/.m2/settings.xml` avec un serveur à mot de passe et le dépôt désigné `~/.m2/repository`
- **WHEN** le plan scip-java est construit
- **THEN** ni la commande ni les racines ne nomment `~/.m2` ou ce `settings.xml`, et le `settings.xml` du run est égal octet pour octet au contenu constant

#### Scenario: Dépôt de tête déjà présent
- **GIVEN** sous Linux, un run dont le dépôt de tête existe déjà
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé avant tout lancement

### Requirement: Le dépôt désigné n'apparaît dans aucune sortie publique ni journal par son chemin
`doctor`, `tools verify`, les messages de refus et les journaux WARNING SHALL rapporter le dépôt désigné par le nom du réglage et son état (absent, refusé, présent), MUST NOT écrire son chemin absolu.

#### Scenario: Doctor avec dépôt désigné refusé
- **GIVEN** sous Linux, une désignation refusée
- **WHEN** `minos doctor` est lancé
- **THEN** la sortie nomme le réglage et l'état refusé, et ne contient pas le chemin désigné

### Requirement: Les réglages globaux du Maven de l'hôte sont masqués et seul Maven 3.9.x est accepté
Hors Windows, le plan scip-java SHALL masquer `conf/settings.xml` et `conf/toolchains.xml` du Maven résolu par un montage en lecture seule d'un fichier vide. Un Maven absent ou hors de la plage 3.9.x MUST faire refuser le plan avant toute copie du projet, par un message actionnable sans chemin absolu.

#### Scenario: Réglages globaux avec un serveur
- **GIVEN** sous Linux avec bac à sable qualifié, un Maven dont `conf/settings.xml` contient un serveur à mot de passe
- **WHEN** le build lancé par scip-java lit ce fichier
- **THEN** il lit un fichier vide

#### Scenario: Maven 4
- **GIVEN** sous Linux, un Maven 4.0.0 dans le `PATH`
- **WHEN** le plan scip-java est construit
- **THEN** il est refusé avant tout lancement, le message nomme la plage 3.9.x et aucun espace de travail n'est créé

### Requirement: Sous Linux, scip-java n'est prêt que s'il peut construire hors ligne
Sous Linux, l'état de scip-java SHALL être `BLOCKED`, avec un diagnostic de prérequis machine actionnable, quand aucun `mvn` 3.9.x n'est trouvé dans le `PATH` sous une racine montée par le bac à sable, ou quand le dépôt désigné est absent ou refusé. La version MUST être lue sur disque, sans exécuter Maven.

#### Scenario: Maven trop ancien
- **GIVEN** sous Linux, un `mvn` dans le `PATH` dont l'installation porte `lib/maven-core-3.8.8.jar`
- **WHEN** `minos doctor` inspecte scip-java
- **THEN** l'état est `BLOCKED`, un diagnostic nomme la plage 3.9.x, et `mvn` n'est pas exécuté

#### Scenario: Prérequis réunis
- **GIVEN** sous Linux, classpath installé, JDK du projet, Maven 3.9.x sous `/usr` et dépôt désigné valide
- **WHEN** `minos doctor` inspecte scip-java
- **THEN** l'état est `READY`

### Requirement: L'installation range une copie vérifiée du classpath de scip-java
L'installation de scip-java SHALL copier les jars résolus dans le dossier du classpath sous `MINOS_HOME/tools`, SHALL comparer les copies au verrou, puis SHALL écrire le lanceur et, en dernier, la liste des noms relatifs. Les fichiers SHALL hériter des droits du dossier privé. Un échec SHALL laisser l'arbre précédent et aucun arbre partiel. Une liste qui ne se vérifie plus SHALL être réinstallée.

#### Scenario: Copie conforme
- **GIVEN** des jars du cache Coursier conformes au verrou
- **WHEN** l'installation range le classpath
- **THEN** les jars copiés, le lanceur et `classpath.txt` (noms relatifs) existent et le classpath se vérifie

#### Scenario: Droits lisibles par le bac à sable Windows
- **GIVEN** une installation sous Windows
- **WHEN** les droits de chaque fichier du classpath sont lus
- **THEN** toutes leurs entrées sont héritées du dossier, aucune liste de droits n'est protégée

#### Scenario: Cache modifié après l'installation
- **GIVEN** un classpath installé
- **WHEN** un jar du cache Coursier est remplacé
- **THEN** le classpath installé se vérifie toujours et la liste ne nomme aucun chemin du cache

#### Scenario: Copie non conforme
- **GIVEN** un classpath installé et un verrou qui épingle d'autres octets pour un jar
- **WHEN** l'installation est rejouée
- **THEN** elle échoue en nommant le jar seul, l'ancienne liste est intacte et aucun dossier partiel ne reste

#### Scenario: Liste d'une version antérieure
- **GIVEN** un `classpath.txt` qui nomme des chemins absolus du cache
- **WHEN** l'installation vérifie le classpath installé
- **THEN** il est déclaré à refaire

### Requirement: Le plan d'indexation de scip-java n'exécute que le classpath revérifié
Avant tout plan scip-java, la fabrique SHALL comparer au verrou chaque jar de la liste installée et, hors Windows, le lanceur aux octets dérivés de la liste. Elle SHALL NOT résoudre par coordonnée ni lancer `coursier`. Un classpath différent SHALL refuser le plan avant toute copie, avec la commande de réparation. Hors Windows et sans classpath installé seulement, un lanceur autonome du `PATH` MAY être utilisé.

#### Scenario: Linux sans lanceur autonome
- **GIVEN** un classpath installé conforme et aucun `scip-java` dans le `PATH`
- **WHEN** un plan est demandé pour un projet Maven
- **THEN** la commande est `java -jar <lanceur> index --output <sortie>` et ne contient ni `launch` ni Coursier

#### Scenario: Classpath installé et lanceur dans le PATH
- **GIVEN** sous Linux un classpath installé conforme et un `scip-java` dans le `PATH`
- **WHEN** un plan est demandé
- **THEN** la commande est celle du lanceur du classpath, pas celle du `PATH`

#### Scenario: Jar altéré
- **GIVEN** un classpath installé dont le vérificateur refuse un jar
- **WHEN** un plan est demandé, sous Linux ou sous Windows
- **THEN** la demande échoue en nommant le jar et `minos tools install scip-java`, et aucune copie du projet n'existe

#### Scenario: Lanceur altéré
- **GIVEN** sous Linux un lanceur dont les octets diffèrent de ceux dérivés de la liste
- **WHEN** un plan est demandé
- **THEN** la demande échoue en nommant `scip-java-launcher.jar`

#### Scenario: Classpath absent
- **GIVEN** aucune liste installée (et, sous Linux, aucun lanceur autonome)
- **WHEN** un plan est demandé
- **THEN** la demande échoue avec « managed scip-java classpath is not installed » et l'état du provider est « non installé »

#### Scenario: Le lanceur exécute la classe principale
- **GIVEN** un jar contenant `org.scip_code.scip_java.ScipJava` et le lanceur dérivé de sa liste
- **WHEN** la commande du plan est exécutée par la JVM
- **THEN** la classe principale reçoit `index --output <sortie>`

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [installation-des-providers](../../../docs/architecture/diagrams/capacites/installation-des-providers.md)

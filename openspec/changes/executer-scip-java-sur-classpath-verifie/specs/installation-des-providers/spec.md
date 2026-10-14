## ADDED Requirements

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

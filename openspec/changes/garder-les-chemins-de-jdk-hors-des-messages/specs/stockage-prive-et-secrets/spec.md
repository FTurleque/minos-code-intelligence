## ADDED Requirements

### Requirement: Le gate des messages reconnaît un chemin nommé par le suffixe Home
La règle `message(Path)` de `check-private-io.py` SHALL traiter comme un chemin une `String` déclarée dans le fichier, ou un accesseur sans argument, dont le nom finit par `Home`, au même titre que les suffixes `Path`, `Root`, `Dir`, `Directory` et `File`. Une telle valeur rendue dans un message d'exception ou un journal par niveau SHALL faire échouer le gate, sauf entrée nominative de la liste de tolérance.

#### Scenario: Valeur de JAVA_HOME concaténée dans une exception
- **GIVEN** une source de production qui déclare `String javaHome` et lève `new IllegalStateException("x: " + javaHome)`
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté sur cet arbre
- **THEN** il échoue avec `message(Path)` en nommant le fichier et la ligne

#### Scenario: Rôle nommé ou nom de feuille
- **GIVEN** une source qui déclare `String javaHome` et dont le message écrit seulement `"JAVA_HOME does not contain javac"` ou `leafName(Path.of(javaHome))`
- **WHEN** le gate est exécuté
- **THEN** il réussit

#### Scenario: Mutation du site réel corrigé
- **GIVEN** `ScipJavaProjectJdk.java` après correction, où l'un des deux messages reçoit de nouveau `+ javaHome`
- **WHEN** `python scripts/architecture/test_check_private_io.py` exécute le témoin de ce site
- **THEN** le compte `message(Path)` de la source mutée vaut 1, et 0 pour la source réelle

#### Scenario: Entrée de liste périmée
- **GIVEN** une entrée `message(Path)` de maximum 1 pour un fichier dont le message a été corrigé
- **WHEN** le gate est exécuté
- **THEN** il échoue en demandant d'abaisser ou de retirer l'entrée (cliquet)

### Requirement: Le gate des messages reconnaît un chemin dérivé par un appel chaîné
La règle `message(Path)` SHALL faire échouer le gate quand un message rend, par `+`, un chemin connu suivi d'une chaîne de `getParent()`, `normalize()`, `resolve(…)` ou `resolveSibling(…)`, ou rend `Path.of(…)` ou `Paths.get(…)`. Une chaîne terminée par un appel qui ne rend pas le chemin (`getFileName()`, `relativize(…)`) ou enveloppée par `leafName(...)` SHALL NOT être un refus.

#### Scenario: Parent ou résolution d'un chemin connu
- **GIVEN** une source qui déclare `Path root` et lève `new IOException("x: " + root.resolve("bin").normalize())`, ou `"x: " + root.getParent()`, ou `"x: " + project.rootPath().getParent()`
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté sur cet arbre
- **THEN** il échoue avec `message(Path)` pour chacune

#### Scenario: Chemin construit dans le message
- **GIVEN** une source qui lève `new IOException("x: " + Path.of(home))`
- **WHEN** le gate est exécuté
- **THEN** il échoue avec `message(Path)`

#### Scenario: Appel final qui ne rend pas le chemin
- **GIVEN** une source dont le message écrit `root.resolve("bin").getFileName()`, `leafName(root.resolve("bin"))` ou `Path.of(a).getFileName()`
- **WHEN** le gate est exécuté
- **THEN** il réussit

### Requirement: Le gate des messages lit un diagnostic préparé dans un Optional
La règle `message(Path)` SHALL lire l'argument d'un appel `Optional.of(…)` ou `Optional.ofNullable(…)` comme un message, avec les mêmes formes de rendu que pour une exception. Un tel argument qui rend un chemin SHALL faire échouer le gate, sauf entrée nominative ; un `Optional.of` d'un chemin non rendu en texte SHALL NOT être un refus.

#### Scenario: Diagnostic qui concatène un chemin
- **GIVEN** une source qui déclare `String javaHome` et retourne `Optional.of("JAVA_HOME does not contain javac: " + javaHome)`
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté sur cet arbre
- **THEN** il échoue avec `message(Path)` en nommant la ligne

#### Scenario: Chemin transporté sans être rendu
- **GIVEN** une source qui déclare `Path root` et retourne `Optional.of(root)` ou `Optional.of(root.resolve("bin"))`
- **WHEN** le gate est exécuté
- **THEN** il réussit

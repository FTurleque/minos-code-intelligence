## MODIFIED Requirements

### Requirement: La façade ne rend ni type du moteur ni racine de composition
`MinosApplication` SHALL NOT déclarer, au niveau de son corps, de méthode publique ni de constructeur public dont le type de retour ou un paramètre nomme un type de `minos-engine` ou `MinosApplicationComposer` ; son `Builder` imbriqué est hors de cette règle. Ses ports et sa racine de composition SHALL rester lisibles par les seules classes du package `com.minos.application`. Le garde SHALL faire échouer A11 dès qu'une telle signature apparaît, en nommant la méthode et le type.

#### Scenario: Façade réduite au HEAD
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -public -cp minos-application/target/classes com.minos.application.MinosApplication` est exécuté
- **THEN** la sortie ne contient ni `compositionRoot`, ni `projectRegistry`, ni `snapshotStore`, ni `providerRuntimeManager`, ni aucune méthode publique dont le type de retour ou un paramètre appartient à `com.minos.orchestration`

#### Scenario: Racine de composition réintroduite refusée
- **GIVEN** un arbre de test où `MinosApplication` déclare `public MinosApplicationComposer compositionRoot()`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `compositionRoot` et `MinosApplicationComposer`

#### Scenario: Accesseur typé moteur réintroduit refusé
- **GIVEN** un arbre de test où `MinosApplication` déclare `public ProjectRegistry projectRegistry()` et où la table des accesseurs est vide
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `projectRegistry` et `registry.ProjectRegistry`

#### Scenario: Paramètre typé moteur refusé
- **GIVEN** un arbre de test où `MinosApplication` déclare `public void leak(ProjectRegistry registry)`, puis `public static MinosApplication of(com.minos.registry.ProjectRegistry registry)`, puis `public ProjectIndexingUseCase projectIndexing(UnaryOperator<IndexerExecutor> decorator)`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que, pour chaque variante, le garde échoue en A11 en nommant la méthode et le type du moteur

#### Scenario: Le Builder garde ses paramètres du moteur
- **GIVEN** un arbre de test où seul le `Builder` imbriqué de `MinosApplication` déclare `public Builder projectRegistry(ProjectRegistry value)`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que la règle des paramètres réussit

#### Scenario: Aucune signature typée moteur au HEAD
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

### Requirement: Les tests lisent un port de l'application par un accès de test
Un test d'un autre module qui lit un port d'une application ouverte, ou qui construit l'indexation avec un décorateur d'exécuteur, SHALL passer par une classe d'accès des sources de test de `minos-application`, dans le package `com.minos.application`, publiée par le `test-jar` de ce module. Ce `test-jar` SHALL être déclaré en portée `test` seulement, et le garde SHALL refuser toute autre portée. Aucun jar livré SHALL contenir la classe d'accès.

#### Scenario: Jar livré sans accès de test
- **GIVEN** le reactor construit par `./mvnw -B -ntp verify`
- **WHEN** le test de packaging liste le contenu du jar de `minos-application` et du jar ombré `target/minos-code-intelligence-*-all.jar`
- **THEN** aucun des deux ne contient la classe d'accès de test

#### Scenario: Test d'une surface sur un home protégé
- **GIVEN** un `MINOS_HOME` en lecture seule préparé par la classe d'accès de test sur une application ouverte par `MinosApplication.open`
- **WHEN** `./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpWriteProtectedHomeTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit

#### Scenario: Accès de test hors portée de test refusé
- **GIVEN** un arbre de test où le `pom.xml` d'une surface déclare le `test-jar` de `minos-application` en portée `compile`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en nommant le module, `minos-application`, le type `test-jar` et la portée

#### Scenario: Indexation décorée lue par l'accès de test
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=MinosApplicationTestAccessTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit : `decoratedProjectIndexing` est un relais de `MinosApplicationTestAccess` et n'est pas une méthode publique de `MinosApplication`

## ADDED Requirements

### Requirement: Le test-jar de l'application ne publie que la classe d'accès
`minos-application/pom.xml` SHALL déclarer une seule exécution du but `test-jar`, dont `<includes>` contient exactement `com/minos/application/MinosApplicationTestAccess.class`. Le garde SHALL refuser un `<includes>` absent, élargi ou complété, et une seconde exécution, en nommant le POM et la valeur. Le `test-jar` construit SHALL n'avoir, hors de `META-INF/`, que cette entrée.

#### Scenario: Inclusion élargie refusée par le garde
- **GIVEN** un arbre de test où `minos-application/pom.xml` remplace l'inclusion par `com/minos/application/**`, puis ajoute une seconde inclusion, puis supprime `<includes>`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que, pour chaque variante, le garde échoue en nommant `minos-application/pom.xml` et la valeur trouvée

#### Scenario: Seconde exécution test-jar refusée
- **GIVEN** un arbre de test où `minos-application/pom.xml` déclare une seconde exécution du but `test-jar` sans `<includes>`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en nommant `minos-application/pom.xml`

#### Scenario: Contenu du test-jar construit
- **GIVEN** le reactor construit
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify "-Dit.test=ApplicationTestAccessPackagingIT"` est exécuté
- **THEN** l'IT réussit : les entrées fichiers du `test-jar` hors de `META-INF/` valent exactement `com/minos/application/MinosApplicationTestAccess.class`, et aucune déclaration de service n'y figure

#### Scenario: Configuration du test-jar au HEAD
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

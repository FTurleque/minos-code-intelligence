## ADDED Requirements

### Requirement: La façade ne rend ni type du moteur ni racine de composition
`MinosApplication` SHALL NOT déclarer de méthode publique d'instance dont le type de retour nomme un type de `minos-engine` ou `MinosApplicationComposer`. Ses ports et sa racine de composition SHALL rester lisibles par les seules classes du package `com.minos.application`. Le garde de frontières SHALL faire échouer la règle A11 dès qu'une telle méthode publique apparaît, en la nommant avec le type rendu.

#### Scenario: Façade réduite au HEAD
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -public -cp minos-application/target/classes com.minos.application.MinosApplication` est exécuté
- **THEN** la sortie ne contient ni `compositionRoot`, ni `projectRegistry`, ni `snapshotStore`, ni `providerRuntimeManager`, ni aucune méthode publique dont le type de retour appartient à `com.minos.orchestration`

#### Scenario: Racine de composition réintroduite refusée
- **GIVEN** un arbre de test où `MinosApplication` déclare `public MinosApplicationComposer compositionRoot()`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `compositionRoot` et `MinosApplicationComposer`

#### Scenario: Accesseur typé moteur réintroduit refusé
- **GIVEN** un arbre de test où `MinosApplication` déclare `public ProjectRegistry projectRegistry()` et où la table des accesseurs est vide
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `projectRegistry` et `registry.ProjectRegistry`

### Requirement: Les tests lisent un port de l'application par un accès de test
Un test d'un autre module qui lit un port d'une application ouverte SHALL passer par une classe d'accès des sources de test de `minos-application`, dans le package `com.minos.application`, publiée par le `test-jar` de ce module. Ce `test-jar` SHALL être déclaré en portée `test` seulement, et le garde SHALL refuser toute autre portée. Aucun jar livré SHALL contenir la classe d'accès.

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

### Requirement: Le retrait des accesseurs de la façade ne change aucune sortie publique
Le retrait du public de `compositionRoot()` et des accesseurs typés moteur de `MinosApplication` SHALL laisser identiques à l'octet les sorties caractérisées de la CLI, de l'API Java et du MCP. Aucune signature de `com.minos.api` SHALL changer, et le refus de l'indexation distante SHALL intervenir avant tout effet.

#### Scenario: Goldens inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** la commande `git diff` se termine avec le code 0

#### Scenario: Refus de l'indexation distante conservé
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=LocalRemoteIndexOperationsRefusalTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit : le refus intervient avant toute matérialisation, bail, enregistrement ou épingle

## MODIFIED Requirements

### Requirement: Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet
La table `MINOS_APPLICATION_ENGINE_ACCESSORS` SHALL être vide, et les accesseurs publics d'instance de `MinosApplication` qui rendent un type de `minos-engine` SHALL être exactement ceux de cette table. Un accesseur hors table, un type rendu différent, une entrée sans accesseur ou une surface de production qui atteint la racine de composition SHALL faire échouer A11. `gitIntelligence`, `providerCatalog`, `scipArtifactImporter` et `hostedControlPlaneService` SHALL ne plus être publics.

#### Scenario: Un nouvel accesseur public refusé
- **GIVEN** une copie du dépôt où `MinosApplication` gagne `public GitIntelligence git() { return gitIntelligence; }`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en A11 en nommant `git` et `git.GitIntelligence`

#### Scenario: Une surface qui atteint la racine de composition
- **GIVEN** un arbre de test où une classe de production de `minos-cli` appelle `app.compositionRoot().gitIntelligence()` ou `MinosApplicationComposers.resolve()`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant le fichier et la racine de composition

#### Scenario: Un accesseur retiré sans mettre la table à jour
- **GIVEN** un arbre de test où la table liste un accesseur que `MinosApplication` ne déclare plus publiquement
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Entrée ajoutée à la table vide refusée
- **GIVEN** un arbre de test où la table reçoit l'entrée `projectRegistry` pendant que `MinosApplication` garde cet accesseur privé au package
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Table à jour au HEAD
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit et annonce 0 accesseur public typé moteur, et la CLI ne liste plus `registry.ProjectPathMappings` dans aucune table A11

### Requirement: Un accesseur de MinosApplication appelé par une surface compte comme un usage du type rendu
Le garde SHALL lire dans la source de `MinosApplication` ses méthodes publiques d'instance dont le type de retour nomme un type de `minos-engine`. Dans une surface, un appel de même arité ou une référence `::nom` à l'une d'elles SHALL compter comme un usage du type rendu, dans le compteur de la règle A11. Un appel homonyme d'arité différente SHALL être ignoré.

#### Scenario: Accesseur appelé sans nommer le type
- **GIVEN** un arbre de test où `MinosApplication` déclare un accesseur public `snapshotStore()` qui rend `CodeKnowledgeSnapshotStore`, et où un fichier de `minos-api` l'appelle sans importer ce type, absent de la liste de l'API
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en nommant la surface, le fichier et le type rendu

#### Scenario: Import remplacé par var
- **GIVEN** un arbre de test où `MinosApplication` déclare un accesseur public qui rend `GitIntelligence`, et où un fichier qui importait ce type écrit `var g = app.gitIntelligence()` sans l'import
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que le garde réussit avec le même compteur : le fichier utilise toujours le type

#### Scenario: Méthode homonyme d'arité différente
- **GIVEN** un arbre de test où une surface appelle `builder.gitIntelligence(supplier)`, à un argument
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas de test vérifie que cet appel n'est pas compté comme un usage de `GitIntelligence`

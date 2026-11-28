## MODIFIED Requirements

### Requirement: Le lancement de processus hors du runtime local est tenu par liste fermée
Dans `minos-engine`, `minos-application` et `minos-domain`, une classe qui lance un processus SHALL figurer dans une liste fermée du garde avec son nombre de sites (`new ProcessBuilder`, `ProcessBuilder::new`, `ProcessBuilder.startPipeline`, `exec` sur `Runtime.getRuntime()` ou sur un nom de type `Runtime` ou affecté de `Runtime.getRuntime()`). Sur le dépôt, la liste SHALL être vide. Une classe hors liste, un nombre de sites différent ou une entrée sans lancement SHALL faire échouer le garde.

#### Scenario: Second site de lancement dans le moteur
- **GIVEN** une copie du dépôt où une classe de `minos-engine` hors liste instancie `ProcessBuilder`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe

#### Scenario: Troisième appel dans la classe listée
- **GIVEN** un arbre de test où une classe listée avec 2 sites construit un troisième `ProcessBuilder`
- **WHEN** le garde est exécuté sur cet arbre
- **THEN** il échoue en nommant la classe, le nombre de sites listé et le nombre observé

#### Scenario: Site retiré sans baisser le compteur
- **GIVEN** un arbre de test où une classe listée avec 2 sites n'en a plus qu'un
- **WHEN** le garde est exécuté sur cet arbre
- **THEN** il échoue en donnant le nombre de sites à écrire

#### Scenario: Site existant
- **GIVEN** le dépôt, où aucune classe du domaine, du moteur ni de l'application ne lance de processus
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche 0 classe et 0 site de lancement

#### Scenario: Retour d'icacls dans PrivateLocalStorage
- **GIVEN** une copie du dépôt où `PrivateLocalStorage` construit un `ProcessBuilder` pour `icacls`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la classe

#### Scenario: Runtime tenu par une variable
- **GIVEN** un arbre de test où une classe du moteur hors liste écrit `Runtime r = Runtime.getRuntime(); r.exec("x")`, ou appelle `exec` sur un champ ou un paramètre `Runtime`, ou référence `Runtime.getRuntime()::exec`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py ProcessLauncherRuleTest` est exécuté
- **THEN** le garde échoue en nommant la classe, et accepte la même classe listée avec 1 site

#### Scenario: Requête sur le Runtime sans lancement
- **GIVEN** un arbre de test où une classe du moteur appelle `Runtime.getRuntime().availableProcessors()` et `maxMemory()` sur une variable `Runtime`
- **WHEN** le garde est exécuté sur cet arbre
- **THEN** il réussit et ne compte aucun site

### Requirement: Les familles de l'indexation ne sont plus nommées par les surfaces
Les sources de production de `minos-cli`, `minos-api` et `minos-mcp` SHALL NOT nommer de type de `com.minos.orchestration` ni de `com.minos.remote`, ni appeler `indexStateStore()`, `providerRuntimeManager()`, `indexerDescriptors()` ou `compositionRoot()` de `MinosApplication`. La table `ENGINE_TYPES_NAMED_BY_SURFACE` SHALL compter 15 entrées pour la CLI, 14 pour l'API, 4 pour le MCP et 6 pour NEXUS.

#### Scenario: Aucun nom ni accesseur retiré dans les surfaces
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `com\.minos\.(orchestration|remote)\.` puis `\.(indexStateStore|providerRuntimeManager|indexerDescriptors|compositionRoot)\(\)` dans `minos-cli/src/main`, `minos-api/src/main` et `minos-mcp/src/main`
- **THEN** aucune des deux recherches ne retourne de ligne

#### Scenario: Décomptes du cliquet
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit et affiche `minos-api=14, minos-cli=15, minos-mcp=4, minos-nexus=6`

#### Scenario: Retour d'un type retiré refusé
- **GIVEN** un arbre temporaire de l'auto-test du garde, muni de la liste réelle de `minos-cli`, où une classe de production de la CLI importe `com.minos.remote.RemoteIndexingRuntime`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le cas attend et obtient l'échec d'A11 nommant `minos-cli`, le fichier et le type

### Requirement: Un accesseur de MinosApplication appelé par une surface compte comme un usage du type rendu
Le garde SHALL lire dans la source de `MinosApplication` ses méthodes publiques d'instance dont le type de retour nomme un type de `minos-engine`. Dans une surface, un appel de même arité ou une référence `::nom` à l'une d'elles SHALL compter comme un usage du type rendu, dans le compteur de la règle A11. Un appel homonyme d'arité différente SHALL être ignoré.

#### Scenario: Accesseur appelé sans nommer le type
- **GIVEN** un arbre de test où `MinosApplication` déclare un accesseur public `snapshotStore()` qui rend `CodeKnowledgeSnapshotStore`, et où un fichier de `minos-api` l'appelle sans importer ce type, absent de la liste de l'API
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en nommant la surface, le fichier et le type rendu

#### Scenario: Import remplacé par var
- **GIVEN** un arbre de test où `MinosApplication` déclare un accesseur public qui rend `GitIntelligence`, et où un fichier qui importait ce type écrit `var g = app.gitIntelligence()` sans l'import
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que le garde réussit avec le même compteur : le fichier utilise toujours le type

#### Scenario: Méthode homonyme d'arité différente
- **GIVEN** un arbre de test où une surface appelle `builder.gitIntelligence(supplier)`, à un argument
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que cet appel n'est pas compté comme un usage de `GitIntelligence`

### Requirement: Le contrôle hébergé est consommé par une façade applicative
`minos-api`, `minos-cli` et `minos-mcp` SHALL obtenir le plan de contrôle de l'équipe par `TeamControlPlane` de `minos-application`, que rend `MinosApplication.teamControlPlane()`. Leurs sources de production SHALL NOT nommer ni utiliser un type de `com.minos.hosted`, par import, nom qualifié, chaînage d'accesseur ou en bytecode. Leurs sorties SHALL rester identiques.

#### Scenario: Le moteur réimporté dans l'API
- **GIVEN** une copie du dépôt où `LocalMinosTeamApi` importe de nouveau `com.minos.hosted.HostedRole`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en A11 en nommant `minos-api`, le fichier et le type, absent de `ENGINE_TYPES_NAMED_BY_SURFACE`

#### Scenario: Un accesseur du moteur chaîné par la CLI
- **GIVEN** une copie du dépôt où `MinosCliRunner` appelle de nouveau `opened.hostedControlPlaneService()`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A11 sur `hosted.HostedControlPlaneService` pour `minos-cli`

#### Scenario: Aucune entrée hosted dans les tables
- **GIVEN** le dépôt
- **WHEN** `test_check_module_boundaries_ratchets.py` et `ModuleArchitectureTest` sont exécutés
- **THEN** ils sont verts et aucune surface n'a d'entrée `hosted.*` dans l'une ou l'autre table

#### Scenario: Sorties inchangées
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest` et `RendererKeyOrderTest` sont exécutés
- **THEN** `team.golden`, `retention.golden`, `api.golden` et `mcp-all-tools.golden` sont égaux à l'octet et l'ordre des clés est inchangé

### Requirement: Les résultats de requête sont des modèles de lecture publiés
Les seules entrées `query.*` des surfaces dans les deux tables A11 SHALL être `SymbolResult`, `UsageResult` et `RelationshipResult`, rendus par le port applicatif `ProjectSymbolQuery`. Chacune SHALL porter dans la table la raison écrite de sa présence (ADR 0057, point 6). Un autre type de `com.minos.query` nommé par une surface SHALL faire échouer A11.

#### Scenario: Les entrées de requête du dépôt réel
- **GIVEN** le dépôt
- **WHEN** l'auto-test `test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** l'ensemble des entrées `query.*` de `ENGINE_TYPES_NAMED_BY_SURFACE` est inclus dans les trois modèles de lecture publiés

#### Scenario: Un service de requête du moteur nommé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe `com.minos.query.SymbolQueryService`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-cli`, le fichier et le type

### Requirement: Les sous-packages de com.minos.storage appartiennent à minos-storage
Hors de `minos-storage`, aucune source de production ni de test SHALL déclarer un package sous `com.minos.storage.` ; `minos-engine` garde le seul package racine `com.minos.storage`. Le garde SHALL refuser toute violation en nommant le fichier. Aucun package existant n'est renommé.

#### Scenario: Sous-package du stockage dans le moteur
- **GIVEN** un arbre de test où `minos-engine` (production), `minos-application` (production) ou `minos-bootstrap` (test) déclare `com.minos.storage.cache`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py StoragePackageIsolationRuleTest` est exécuté
- **THEN** le garde échoue en nommant le fichier et « below com.minos.storage outside minos-storage »

#### Scenario: Ports du stockage dans le moteur
- **GIVEN** le dépôt, où `minos-engine` déclare `com.minos.storage` et `minos-storage` déclare `codec`, `home`, `local` et `postgresql`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit

### Requirement: La part de code concret du moteur est mesurée sans cliquet
Le garde de frontières SHALL afficher à chaque exécution, pour `minos-engine`, les fichiers et lignes de production par genre de première déclaration de premier niveau (classe, interface, record, enum, `info`, `unclassified`). Il SHALL NOT échouer sur cette mesure (ADR 0068, décision D1 du 2026-10-28).

#### Scenario: Mesure sur un arbre de test
- **GIVEN** un arbre de test avec deux classes, une interface, un record, un enum, un `package-info` et un fichier sans déclaration dans `minos-engine`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py EngineConcreteCodeMeasureTest` est exécuté
- **THEN** chaque fichier est compté une fois dans son genre avec toutes ses lignes, et les tests et les autres modules sont ignorés

#### Scenario: Mesure du dépôt
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il affiche la ligne `AUD-ARC-05 minos-engine concrete code` avec le nombre de fichiers de classes, leurs lignes et le total, et aucun fichier `unclassified`

### Requirement: Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet
La table `MINOS_APPLICATION_ENGINE_ACCESSORS` SHALL être vide, et les accesseurs publics d'instance de `MinosApplication` qui rendent un type de `minos-engine` SHALL être exactement ceux de cette table. Un accesseur hors table, un type rendu différent, une entrée sans accesseur ou une surface de production qui atteint la racine de composition SHALL faire échouer A11. `gitIntelligence`, `providerCatalog`, `scipArtifactImporter` et `hostedControlPlaneService` SHALL ne plus être publics.

#### Scenario: Un nouvel accesseur public refusé
- **GIVEN** une copie du dépôt où `MinosApplication` gagne `public GitIntelligence git() { return gitIntelligence; }`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en A11 en nommant `git` et `git.GitIntelligence`

#### Scenario: Une surface qui atteint la racine de composition
- **GIVEN** un arbre de test où une classe de production de `minos-cli` appelle `app.compositionRoot().gitIntelligence()` ou `MinosApplicationComposers.resolve()`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant le fichier et la racine de composition

#### Scenario: Un accesseur retiré sans mettre la table à jour
- **GIVEN** un arbre de test où la table liste un accesseur que `MinosApplication` ne déclare plus publiquement
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Entrée ajoutée à la table vide refusée
- **GIVEN** un arbre de test où la table reçoit l'entrée `projectRegistry` pendant que `MinosApplication` garde cet accesseur privé au package
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Table à jour au HEAD
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit et annonce 0 accesseur public typé moteur, et la CLI ne liste plus `registry.ProjectPathMappings` dans aucune table A11

### Requirement: Les API d'infrastructure du JDK nommées par l'application le sont par liste fermée
Le garde de frontières SHALL refuser toute source de production de `minos-application` qui nomme `java.net.HttpURLConnection`, un type de `java.net.http`, de `javax.tools` ou de `com.sun.source`, sauf les fichiers d'une table à cliquet. Un fichier hors table et une entrée qui ne nomme plus aucune de ces API SHALL faire échouer le garde. Commentaires et littéraux SHALL être ignorés.

#### Scenario: Nouveau client HTTP dans l'application
- **GIVEN** un arbre de test où une classe de production de `minos-application` importe `java.net.http.HttpClient`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant le fichier et l'API

#### Scenario: Table du HEAD acceptée
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0 et annonce le nombre de fichiers de la table

#### Scenario: Entrée périmée
- **GIVEN** un arbre de test où un fichier de la table ne nomme plus aucune API surveillée
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Mention en commentaire
- **GIVEN** un arbre de test où une classe hors table cite `javax.tools.ToolProvider` dans une Javadoc seulement
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde réussit

### Requirement: Une classe d'adaptateur embarquée n'est nommée que par sa famille
Le garde de frontières SHALL tenir la liste fermée des classes d'adaptateur que `minos-application` embarque, chacune avec sa justification. Aucune source de production de `minos-application` hors de cette liste SHALL nommer une de ces classes, par import, nom qualifié ou nom simple du même package. Chaque classe de `INFRASTRUCTURE_API_USERS` SHALL figurer dans cette liste par son nom simple. La liste SHALL seulement rétrécir.

#### Scenario: Le service nomme de nouveau un adaptateur
- **GIVEN** un arbre de test où `ProgramGraphService` instancie `FileProgramGraphProvider`
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant le fichier et la classe

#### Scenario: Classes de la liste entre elles
- **GIVEN** le dépôt après le changement, où `JavaProgramGraphEngine` nomme `JavaAstParser` et `SourceStateKeyMemo` nomme `JavaProgramGraphEngine.SourceStateKey`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** le garde réussit

#### Scenario: Entrée sans classe
- **GIVEN** un arbre de test où la liste nomme une classe absente des sources de production de `minos-application`
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Entrée retirée alors que la classe nomme encore une API d'infrastructure
- **GIVEN** un arbre de test où `OllamaEmbeddingProvider` nomme `java.net.HttpURLConnection`, figure dans `INFRASTRUCTURE_API_USERS` et manque à la liste des adaptateurs embarqués
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant `OllamaEmbeddingProvider` et la liste où l'ajouter
- **AND** le même arbre où une autre source nomme `OllamaEmbeddingProvider` échoue aussi

### Requirement: Les classes de production de minos-app sont tenues par liste fermée
Le garde de frontières SHALL tenir la liste fermée des classes de production de `minos-app`, chacune avec son rôle (assemblage, route de lancement, transport, configuration du backend du home). Une classe de production absente de la liste et une entrée sans classe SHALL faire échouer le garde. La liste SHALL NOT contenir de classe dont le rôle est une politique applicative.

#### Scenario: Liste du HEAD acceptée
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0 et annonce le nombre de classes de `minos-app`

#### Scenario: Nouvelle classe dans l'assemblage
- **GIVEN** un arbre de test où `minos-app` gagne une classe de production absente de la liste
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant la classe et en demandant de la placer dans un module applicatif ou une surface

#### Scenario: Entrée périmée
- **GIVEN** un arbre de test où la liste nomme une classe que `minos-app` ne déclare plus
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en demandant de retirer l'entrée

#### Scenario: Rôle absent
- **GIVEN** un arbre de test où une entrée de la liste n'a pas de rôle écrit
- **WHEN** l'auto-test du garde est exécuté
- **THEN** le cas vérifie que le garde échoue en nommant l'entrée

### Requirement: La façade ne rend ni type du moteur ni racine de composition
`MinosApplication` SHALL NOT déclarer, au niveau de son corps, de méthode publique ni de constructeur public dont le type de retour ou un paramètre nomme un type de `minos-engine` ou `MinosApplicationComposer` ; son `Builder` imbriqué est hors de cette règle. Ses ports et sa racine de composition SHALL rester lisibles par les seules classes du package `com.minos.application`. Le garde SHALL faire échouer A11 dès qu'une telle signature apparaît, en nommant la méthode et le type.

#### Scenario: Façade réduite au HEAD
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -public -cp minos-application/target/classes com.minos.application.MinosApplication` est exécuté
- **THEN** la sortie ne contient ni `compositionRoot`, ni `projectRegistry`, ni `snapshotStore`, ni `providerRuntimeManager`, ni aucune méthode publique dont le type de retour ou un paramètre appartient à `com.minos.orchestration`

#### Scenario: Racine de composition réintroduite refusée
- **GIVEN** un arbre de test où `MinosApplication` déclare `public MinosApplicationComposer compositionRoot()`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `compositionRoot` et `MinosApplicationComposer`

#### Scenario: Accesseur typé moteur réintroduit refusé
- **GIVEN** un arbre de test où `MinosApplication` déclare `public ProjectRegistry projectRegistry()` et où la table des accesseurs est vide
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que le garde échoue en A11 en nommant `projectRegistry` et `registry.ProjectRegistry`

#### Scenario: Paramètre typé moteur refusé
- **GIVEN** un arbre de test où `MinosApplication` déclare `public void leak(ProjectRegistry registry)`, puis `public static MinosApplication of(com.minos.registry.ProjectRegistry registry)`, puis `public ProjectIndexingUseCase projectIndexing(UnaryOperator<IndexerExecutor> decorator)`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que, pour chaque variante, le garde échoue en A11 en nommant la méthode et le type du moteur

#### Scenario: Le Builder garde ses paramètres du moteur
- **GIVEN** un arbre de test où seul le `Builder` imbriqué de `MinosApplication` déclare `public Builder projectRegistry(ProjectRegistry value)`
- **WHEN** `python -m unittest scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** le cas de test vérifie que la règle des paramètres réussit

#### Scenario: Aucune signature typée moteur au HEAD
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

### Requirement: Une méthode générique ou un import joker de la façade ne contourne pas A11
A11 SHALL lire les méthodes publiques, statiques comprises, et les constructeurs publics de `MinosApplication`, y compris quand ils déclarent des paramètres de type à bornes imbriquées. Un type de `minos-engine` nommé dans une borne SHALL compter comme rendu et pris par la méthode ; une méthode statique qui rend un tel type SHALL échouer. Un import joker d'un package de `minos-engine` dans `MinosApplication` SHALL faire échouer A11 en nommant le package.

#### Scenario: Paramètre typé moteur d'une méthode générique
- **GIVEN** une copie du dépôt où `MinosApplication` déclare `public <T> void leakGeneric(com.minos.registry.ProjectRegistry r) {}`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté dans la copie
- **THEN** le garde se termine avec le code 1 en A11 et nomme `leakGeneric` et `registry.ProjectRegistry`

#### Scenario: Retour typé moteur d'une méthode générique
- **GIVEN** une copie du dépôt où `MinosApplication` déclare `public <T> com.minos.registry.ProjectRegistry leakRet() { return null; }`
- **WHEN** le garde est exécuté dans la copie
- **THEN** il se termine avec le code 1 en A11 et nomme `leakRet`

#### Scenario: Retour typé moteur d'une méthode statique
- **GIVEN** l'auto-test du garde, avec `public static ProjectRegistry leakStatic()` puis `public static <T> ProjectRegistry leakStaticGeneric()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Type du moteur porté par une borne
- **GIVEN** l'auto-test du garde, avec `public <T extends ProjectRegistry> void leakBound(T r)` puis `public <T extends java.util.Map<String, ProjectRegistry>> T nested()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Import joker d'un package du moteur
- **GIVEN** l'auto-test du garde, avec `import com.minos.registry.*;` dans `MinosApplication`
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate le refus en A11 et le message qui nomme `com.minos.registry`

#### Scenario: Méthode générique sans type du moteur
- **GIVEN** l'auto-test du garde, avec `public <T extends Comparable<T>> java.util.List<T> sorted(java.util.List<T> values)`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle des paramètres et celle des retours réussissent

#### Scenario: Façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: L'écriture d'une déclaration de la façade ne contourne pas A11
A11 SHALL lire une méthode ou un constructeur public de `MinosApplication` quels que soient les annotations placées dans sa déclaration, la casse de son nom et les retours à la ligne de son en-tête, nom qualifié compris. Une déclaration publique suivie d'une parenthèse dont A11 ne lit pas le nom SHALL faire échouer le garde.

#### Scenario: Annotation à l'intérieur de la déclaration
- **GIVEN** l'auto-test du garde, avec `public @Deprecated ProjectRegistry x()`, une annotation à arguments imbriqués, entre deux modificateurs, après les paramètres de type ou dans un nom qualifié
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Nom de méthode qui n'est pas en minuscules
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry Leak()`, `public void Leak(ProjectRegistry r)` et `public ProjectRegistry $leak()`
- **WHEN** le même auto-test est exécuté
- **THEN** chaque variante est refusée en A11 et nomme la méthode

#### Scenario: En-tête sur plusieurs lignes
- **GIVEN** l'auto-test du garde, avec un retour `Map<String,` puis `ProjectRegistry>` à la ligne suivante, puis un nom qualifié coupé avant son dernier segment
- **WHEN** le même auto-test est exécuté
- **THEN** chaque variante est refusée en A11 et nomme `registry.ProjectRegistry`

#### Scenario: Déclarations sans type du moteur
- **GIVEN** l'auto-test du garde, avec des membres annotés, un champ initialisé par un appel et un `record` imbriqué non public
- **WHEN** le même auto-test est exécuté
- **THEN** les règles de la façade réussissent

#### Scenario: Déclaration illisible
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry (`
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate l'échec du garde, message `A11 unreadable MinosApplication member`

#### Scenario: Façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Un import qui passe par un type du moteur ne contourne pas A11
Dans `MinosApplication`, un import joker, statique ou non, dont la cible est un type de `minos-engine` ou un type imbriqué d'un tel type SHALL faire échouer A11 en nommant la cible. Un type importé par `import static` depuis un type de `minos-engine` SHALL compter comme un type du moteur quand une signature publique de la façade le nomme.

#### Scenario: Joker sur les membres d'un type du moteur
- **GIVEN** l'auto-test du garde, avec `import com.minos.orchestration.IndexingRuntimePorts.*;`, puis sa forme `import static`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque forme est refusée en A11, message nommant `com.minos.orchestration.IndexingRuntimePorts.*`

#### Scenario: Import statique d'un type imbriqué
- **GIVEN** l'auto-test du garde, avec `import static com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;` et `public IndexerExecutor x()`
- **WHEN** le même auto-test est exécuté
- **THEN** la méthode est refusée en A11, message nommant `x` et `orchestration.IndexingRuntimePorts`

#### Scenario: Import statique hors du moteur
- **GIVEN** l'auto-test du garde, avec `import static` d'une méthode d'un type de `minos-domain` et un joker sur les membres d'un type du JDK
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

### Requirement: La façade ne rend pas la racine de composition par une méthode statique
Une méthode statique publique de `MinosApplication` dont le type de retour ou une borne de paramètre de type nomme `MinosApplicationComposer` SHALL faire échouer A11 en nommant la méthode, qu'elle soit générique ou non.

#### Scenario: Racine de composition rendue par une méthode statique
- **GIVEN** l'auto-test du garde, avec `public static MinosApplicationComposer root()`, `public static <T> MinosApplicationComposer root()` et `public static <T extends MinosApplicationComposer> T root()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant `root` et la racine de composition

### Requirement: La façade n'a aucun champ public typé moteur
`MinosApplication` SHALL NOT déclarer de champ public, statique ou non, dont le type déclaré nomme un type de `minos-engine` ou `MinosApplicationComposer`. A11 SHALL échouer en nommant le champ et le type.

#### Scenario: Champ typé moteur refusé
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry registry;`, un champ `static final`, `java.util.List<ProjectRegistry>`, puis `public ProjectRegistry[] a, b;`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant le champ et `registry.ProjectRegistry`

#### Scenario: Champ typé racine de composition refusé
- **GIVEN** l'auto-test du garde, avec `public MinosApplicationComposer root;`
- **WHEN** le même auto-test est exécuté
- **THEN** le champ est refusé en A11, message nommant `root` et `MinosApplicationComposer`

#### Scenario: Champ sans type du moteur ou non public
- **GIVEN** l'auto-test du garde, avec `public static final String NAME = "x";` et `private ProjectRegistry registry;`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

#### Scenario: Champs de la façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Le `Builder` est le seul type imbriqué public de la façade
Un type imbriqué public de `MinosApplication` autre que `Builder` (classe, `record`, interface, enum ou annotation) SHALL faire échouer A11 en le nommant, qu'il nomme ou non un type du moteur. Un type imbriqué non public SHALL rester accepté.

#### Scenario: Type imbriqué public refusé
- **GIVEN** l'auto-test du garde, avec tour à tour un `public record` générique ou non, une `public static final class`, une `public interface` dont les méthodes sont implicitement publiques, un `public enum` et une `public @interface`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant le type imbriqué

#### Scenario: Builder et types non publics acceptés
- **GIVEN** l'auto-test du garde, avec `public static final class Builder` portant une méthode typée moteur, un `record` privé au package et un `private record`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

### Requirement: Un import est lu quelle que soit sa position dans la ligne
Les règles de `check-module-boundaries.py` qui lisent les déclarations d'import SHALL lire chacune d'elles où qu'elle soit dans sa ligne : à son début, ou à la suite d'un autre import, de la déclaration de package, d'un point-virgule seul ou d'un commentaire. Un `import` écrit dans un commentaire ou dans un littéral SHALL rester ignoré.

#### Scenario: Second import d'une ligne dans la façade
- **GIVEN** l'auto-test de la façade, avec `MinosApplication` qui écrit sur une même ligne `import java.util.List;` puis un import simple, joker ou statique qui atteint un type de `minos-engine`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque forme est refusée en A11

#### Scenario: Joker de package en second sur sa ligne
- **GIVEN** les auto-tests, avec ce joker dans une surface (A11), un adaptateur (A13), une source qui ne doit nommer aucun adaptateur (A2), un module hors de `minos-runtime-local` (A10), `minos-bootstrap` (R1) et un package du stockage
- **WHEN** les auto-tests des cliquets et des adaptateurs sont exécutés
- **THEN** chaque règle refuse et nomme le package ou le fichier

#### Scenario: Cycle et type référencé par un second import
- **GIVEN** l'auto-test des packages, avec un cycle fermé par un joker en second sur sa ligne, puis un type public nommé par le seul joker de son package, en second sur sa ligne
- **WHEN** `python scripts/architecture/test_check_module_boundaries_packages.py` est exécuté
- **THEN** le cycle est refusé en A8
- **AND** A15 ne déclare pas le type sans appelant

#### Scenario: Autres positions
- **GIVEN** l'auto-test de la façade, avec un import de façade placé après la déclaration de package, après un point-virgule seul ou après un commentaire de bloc fermé sur la ligne d'un autre import
- **WHEN** `python scripts/architecture/test_check_module_boundaries_facade.py` est exécuté
- **THEN** chaque forme est refusée en A11

#### Scenario: Import écrit dans un commentaire ou un littéral
- **GIVEN** l'auto-test de la façade, avec le même texte d'import dans un commentaire de ligne, un commentaire de bloc, une chaîne et un bloc de texte
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

## ADDED Requirements

### Requirement: Les opérations locales reçoivent leurs collaborateurs et seule la façade lit ses ports
`LocalProjectOperations`, `ProviderPlatformService` et `LocalProjectGitQuery` SHALL être construits à partir de leurs collaborateurs, assemblés par `MinosApplication`. Aucune classe de production autre que `MinosApplication` SHALL appeler une méthode non publique de `MinosApplication` qui rend un type de `minos-engine` ou `MinosApplicationComposer`. `ModuleArchitectureTest` SHALL échouer en nommant la classe appelante et la méthode.

#### Scenario: Port relu par une classe du package
- **GIVEN** une copie du dépôt où `LocalProjectGitQuery(MinosApplication)` appelle de nouveau `application.gitIntelligence()`, accesseur rétabli dans `MinosApplication`
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté sur cette copie
- **THEN** le test échoue en nommant `LocalProjectGitQuery` et `MinosApplication.gitIntelligence()`

#### Scenario: Seule la façade lit ses ports au HEAD
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit

#### Scenario: Import exercé sans application
- **GIVEN** un test de `minos-application` qui fournit des doubles des sept collaborateurs de `LocalProjectOperations`
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalProjectOperations*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'import publié applique la rétention dans l'ordre publication, état, rétention, sans qu'aucune `MinosApplication` soit ouverte

#### Scenario: Collaborateur manquant refusé
- **GIVEN** des `Dependencies` de `LocalProjectOperations` dont un collaborateur est `null`
- **WHEN** les opérations sont construites dans un test de `minos-application`
- **THEN** la construction échoue par `NullPointerException` qui nomme le collaborateur, avant toute lecture du registre

### Requirement: Les points d'entrée publics des opérations locales sont conservés
`LocalProjectOperations(Path)`, `LocalProjectOperations(MinosApplication)`, `ProviderPlatformService.defaults(MinosApplication)`, `LocalProjectGitQuery(MinosApplication)` et `LocalProjectSymbolQuery(MinosApplication)` SHALL garder leur signature publique et leur comportement. `defaults` SHALL lire le catalogue des fournisseurs à chaque appel. Les sorties caractérisées de la CLI, de l'API Java et du MCP SHALL rester identiques à l'octet.

#### Scenario: Signatures publiques inchangées
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -public -cp minos-application/target/classes com.minos.application.LocalProjectOperations com.minos.application.ProviderPlatformService com.minos.application.LocalProjectGitQuery com.minos.application.LocalProjectSymbolQuery` est exécuté
- **THEN** la sortie contient les cinq points d'entrée qui prennent `Path` ou `MinosApplication`, et aucun type `Dependencies` public

#### Scenario: Catalogue lu à chaque appel
- **GIVEN** un test de `minos-application` dont l'application a un catalogue qui change de fournisseurs entre deux appels
- **WHEN** `ProviderPlatformService.defaults(application).listStaticProfiles()` est appelé deux fois
- **THEN** le second appel rend les fournisseurs du catalogue modifié

#### Scenario: Goldens inchangés
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** la commande `git diff` se termine avec le code 0

### Requirement: Les ports sans lecteur sont retirés de la façade
`MinosApplication` SHALL NOT déclarer `gitIntelligence()`, `providerCatalog()`, `scipArtifactImporter()`, `indexImportHistoryStore()` ni `hostedControlPlaneService()`. Leurs collaborateurs SHALL rester requis non nuls par la construction de l'application.

#### Scenario: Façade sans les ports retirés
- **GIVEN** le dépôt après le changement, `minos-application` compilé
- **WHEN** `javap -p -cp minos-application/target/classes com.minos.application.MinosApplication` est exécuté
- **THEN** la sortie ne contient ni `gitIntelligence()`, ni `providerCatalog()`, ni `scipArtifactImporter()`, ni `indexImportHistoryStore()`, ni `hostedControlPlaneService()`

#### Scenario: Collaborateur de la composition toujours exigé
- **GIVEN** un test de `minos-application` qui construit une application par le `Builder` avec un importeur SCIP nul et une racine de composition qui n'en fournit pas
- **WHEN** l'application est construite
- **THEN** la construction échoue par `NullPointerException` qui nomme `scipArtifactImporter`

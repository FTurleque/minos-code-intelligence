## ADDED Requirements

### Requirement: Les points d'entrée publics des opérations locales sont éprouvés sur une application
Un test de `minos-application` SHALL faire passer un import SCIP publié par `new LocalProjectOperations(application)` et affirmer la publication, l'état et la rétention de cette application, dans cet ordre. Un test SHALL affirmer par un appel observable que `new LocalProjectSymbolQuery(application)` lit le registre de cette application.

#### Scenario: Import publié par le constructeur sur application
- **GIVEN** une application construite sur les ports de la fixture de `LocalProjectOperationsRetentionTest`
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalProjectOperationsRetentionTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le cas sur application constate les événements `publish`, `state` puis `retention` du snapshot importé

#### Scenario: Rétention de l'assemblage retirée
- **GIVEN** une copie de travail où `localProjectOperationsDependencies()` passe une autre rétention que `stores.retentionService()`
- **WHEN** la même commande est exécutée
- **THEN** le cas sur application échoue

#### Scenario: Requête de symboles sur le registre de l'application
- **GIVEN** une application dont le registre double enregistre ses appels
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalOperationsAssemblyTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le cas constate qu'une recherche de symboles par `LocalProjectSymbolQuery(application)` a appelé ce registre

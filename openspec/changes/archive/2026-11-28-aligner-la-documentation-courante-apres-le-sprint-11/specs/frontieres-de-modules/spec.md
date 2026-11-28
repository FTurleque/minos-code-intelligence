## MODIFIED Requirements

### Requirement: La table de rupture de la façade ne promet aucun remplaçant absent
Dans `docs/user/java-api.md`, chaque remplaçant donné par une table de rupture de `MinosApplication` ou de ses cas d'usage SHALL être une méthode ou un constructeur public qui rend ce service, ou SHALL être écrit « aucun remplaçant public ». `retentionService()` SHALL être documenté sans remplaçant à la demande, la rétention suivant chaque indexation et chaque import qui publie un snapshot.

#### Scenario: Rétention sans remplaçant promis
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE 'retentionService\(\)` \| `new LocalProjectOperations' -- docs/user/java-api.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Promesse réintroduite
- **GIVEN** une copie de travail où la ligne `retentionService()` de la table redonne `new LocalProjectOperations(application)` comme remplaçant
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

#### Scenario: Décoration des exécuteurs sans remplaçant public
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -n 'projectIndexing(executorDecorator)' -- docs/user/java-api.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1
- **AND** la ligne de `ProjectIndexingUseCase.of(MinosApplication, UnaryOperator<IndexerExecutor>)` contient « aucun remplaçant public »

#### Scenario: Méthode privée au package promise de nouveau
- **GIVEN** une copie de travail où cette ligne redonne `application.projectIndexing(executorDecorator)` comme remplaçant
- **WHEN** la commande `git grep` du scénario précédent est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

## ADDED Requirements

### Requirement: L'activité Git est consommée par une requête applicative
La CLI et l'API Java SHALL obtenir l'inspection et l'activité Git d'un projet par `ProjectGitQuery` de `minos-application`, qui rend des records applicatifs. Les sources de production de `minos-cli` et `minos-api` SHALL NOT nommer ni utiliser `git.GitIntelligence`, ni par import, ni par nom qualifié, ni par l'accesseur `gitIntelligence()`, ni en bytecode.

#### Scenario: Le port Git réimporté par la commande
- **GIVEN** une copie du dépôt où `GitActivityCommand` importe de nouveau `com.minos.git.GitIntelligence`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-cli`, le fichier et le type, absent de `ENGINE_TYPES_NAMED_BY_SURFACE`

#### Scenario: L'accesseur appelé par l'API
- **GIVEN** une copie du dépôt où `LocalMinosMultiRepositoryApi` appelle `app.gitIntelligence()` sans import
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-api` et `com.minos.git.GitIntelligence`

#### Scenario: Requête testée sans racine de composition
- **GIVEN** un faux port `GitIntelligence` dans un test de `minos-application`
- **WHEN** `LocalProjectGitQuery` inspecte et analyse un dépôt par lui
- **THEN** chaque champ du rapport du port est rendu à l'identique, une borne invalide est refusée avec le message du port, et l'exception du port est propagée sans enveloppe

#### Scenario: Sortie inchangée
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest`, `GitActivityCommandTest` et `LocalMinosMultiRepositoryApiIntegrationTest` sont exécutés
- **THEN** `cli-git.golden` est inchangé et les valeurs de `inspectGit` et `analyzeGitActivity` sont celles attendues aujourd'hui

### Requirement: La requête de symboles de l'API est construite par l'application
`LocalMinosApi` SHALL construire sa requête de symboles à partir de `MinosApplication` par `LocalProjectSymbolQuery`, sans appeler `projectRegistry()` ni `snapshotStore()`. Les sources de production de `minos-api` SHALL NOT utiliser `registry.ProjectRegistry` ni `store.CodeKnowledgeSnapshotStore`.

#### Scenario: Les accesseurs réintroduits dans l'API
- **GIVEN** une copie du dépôt où `LocalMinosApi` appelle de nouveau `app.projectRegistry()`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-api` et `com.minos.registry.ProjectRegistry`

#### Scenario: Réponses inchangées
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest` est exécuté
- **THEN** `api.golden` est inchangé

### Requirement: Les résultats de requête sont des modèles de lecture publiés
Les seules entrées `query.*` des surfaces dans les deux tables A11 SHALL être `SymbolResult`, `UsageResult` et `RelationshipResult`, rendus par le port applicatif `ProjectSymbolQuery`. Chacune SHALL porter dans la table la raison écrite de sa présence (ADR 0057, point 6). Un autre type de `com.minos.query` nommé par une surface SHALL faire échouer A11.

#### Scenario: Les entrées de requête du dépôt réel
- **GIVEN** le dépôt
- **WHEN** l'auto-test `test_check_module_boundaries.py` est exécuté
- **THEN** l'ensemble des entrées `query.*` de `ENGINE_TYPES_NAMED_BY_SURFACE` est inclus dans les trois modèles de lecture publiés

#### Scenario: Un service de requête du moteur nommé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe `com.minos.query.SymbolQueryService`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant `minos-cli`, le fichier et le type

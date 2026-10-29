## ADDED Requirements

### Requirement: La correspondance des chemins Docker passe par une façade applicative
`DockerRuntimeBootstrap` SHALL configurer la correspondance hôte/conteneur par `ProjectPathMappingService` (`minos-application`), sans nommer ni atteindre un type de `minos-engine`. La façade SHALL créer une correspondance absente, accepter la même et refuser d'en remplacer une autre sans rien écrire. Sorties, messages et codes de sortie SHALL rester identiques.

#### Scenario: Correspondance conflictuelle refusée
- **GIVEN** un `MINOS_HOME` qui contient la correspondance `N:/workspace-dev` ↔ `/workspace/projects`
- **WHEN** `DockerRuntimeBootstrapTest` invoque `configure-project-paths D:/other-projects /workspace/projects`
- **THEN** le code de sortie vaut 1, l'erreur contient « refusing to replace », et le fichier garde `N:/workspace-dev`

#### Scenario: Création puis invocation identique
- **GIVEN** un `MINOS_HOME` vide
- **WHEN** la même correspondance est configurée deux fois, la seconde avec des séparateurs Windows
- **THEN** les deux invocations sortent en 0, la première écrit « mapping created », la seconde « already matches »

#### Scenario: Le port réintroduit dans la CLI
- **GIVEN** une copie du dépôt où une classe de production de `minos-cli` importe `com.minos.registry.ProjectPathMappings`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en A11 en nommant `minos-cli`, le fichier et le type

### Requirement: Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet
Les accesseurs publics d'instance de `MinosApplication` qui rendent un type de `minos-engine` SHALL être exactement ceux de `MINOS_APPLICATION_ENGINE_ACCESSORS`, chacun avec son type rendu. Un accesseur hors table, un type rendu différent, une entrée sans accesseur ou une surface de production qui atteint la racine de composition SHALL faire échouer A11. `gitIntelligence`, `providerCatalog`, `scipArtifactImporter` et `hostedControlPlaneService` SHALL ne plus être publics.

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

#### Scenario: Table à jour au HEAD
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit et annonce 15 accesseurs publics typés moteur, et la CLI ne liste plus `registry.ProjectPathMappings` dans aucune table A11

## MODIFIED Requirements

### Requirement: L'incrémental ne dépend plus de l'orchestration en retour
Dans `minos-engine`, `com.minos.incremental` SHALL ne pas importer `com.minos.orchestration`. Les classes de planification incrémentale qui utilisent l'orchestration (`IncrementalIndexingPlan`, `IncrementalIndexingPlanner`, `ProjectInvalidationService`) SHALL se trouver dans `com.minos.orchestration`. Une classe retirée du produit SHALL NOT être exigée par cette règle.

#### Scenario: Plus d'arête incremental vers orchestration
- **GIVEN** les sources de production de `minos-engine`
- **WHEN** on recherche `import com.minos.orchestration` dans les sources de `com.minos.incremental`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Classe replacée dans l'incrémental
- **GIVEN** une copie du dépôt où `IncrementalIndexingPlanner` est replacé dans `com.minos.incremental`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant le cycle entre `incremental` et `orchestration`

## ADDED Requirements

### Requirement: Les surfaces n'assemblent pas le cycle d'indexation
Aucune surface (`minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`) SHALL NOT construire `IndexingLifecycleService` ni enchaîner elle-même découverte, négociation, planification et exécution. Ces étapes SHALL être assemblées dans `minos-application`, qui SHALL NOT nommer de classe d'adaptateur pour le faire.

#### Scenario: Construction du cycle hors des surfaces
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `new IndexingLifecycleService` dans les sources de production des quatre surfaces
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Cas d'usage sans adaptateur
- **GIVEN** le cas d'usage d'indexation de `minos-application`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** le garde réussit et ne signale aucun import d'adaptateur depuis `minos-application`

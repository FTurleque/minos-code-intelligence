# Spec Delta

## ADDED Requirements

### Requirement: Les classes de la frontière de confiance des providers ont un plancher de branches minimal
Le filtre d'environnement des processus de provider, l'exécuteur à propriété forte, la fabrique du plan de scip-java et le gestionnaire des runtimes polyglottes SHALL chacun porter un plancher de classe JaCoCo, jugé sur chaque système où leur scope est jugé. Ce plancher SHALL valoir au moins 0,50 en branches, et au moins 0,80 pour le filtre d'environnement. Un plancher inférieur ou absent MUST faire échouer l'auto-test du gate.

#### Scenario: Plancher abaissé sous le minimum
- **GIVEN** une copie de la table des scopes où le plancher de branches du filtre d'environnement vaut 0,20
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` est exécuté sur cette copie
- **THEN** l'auto-test échoue en nommant la classe, son plancher et le minimum exigé

#### Scenario: Plancher de classe retiré
- **GIVEN** une copie de la table où l'une des quatre classes n'a plus de plancher de classe
- **WHEN** l'auto-test est exécuté
- **THEN** il échoue en nommant la classe sans plancher

#### Scenario: Érosion de la couverture du filtre d'environnement
- **GIVEN** un rapport JaCoCo où le filtre d'environnement a moins de branches couvertes que son plancher
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** le gate échoue en nommant le scope, la classe, sa mesure et son plancher

#### Scenario: Table réelle
- **GIVEN** la table des scopes du dépôt
- **WHEN** l'auto-test est exécuté
- **THEN** il réussit : les quatre classes portent un plancher de branches au moins égal à leur minimum

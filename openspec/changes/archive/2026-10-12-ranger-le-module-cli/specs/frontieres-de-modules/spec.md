## ADDED Requirements

### Requirement: Le module CLI est rangé en sous-packages sans cycle
`minos-cli` SHALL répartir ses classes entre `command`, `operations` et `support`, sa racine ne gardant que les points d'entrée et le SPI `McpLaunchRoute`, dont les noms qualifiés SHALL rester inchangés. Les dépendances SHALL aller de la racine vers les autres, de `command` vers `operations` et `support`, et d'`operations` vers `support`, sans cycle.

#### Scenario: Aucun cycle entre les sous-packages
- **GIVEN** le module rangé
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit, la table des cycles connus restant vide

#### Scenario: Le point d'entrée garde son nom
- **GIVEN** le jar ombré construit
- **WHEN** `ShadedJarMcpEntryPointIT` est exécuté
- **THEN** il trouve `com.minos.cli.MinosLauncher` et le SPI `com.minos.cli.McpLaunchRoute`

#### Scenario: Une classe de support ne dépend pas d'une commande
- **GIVEN** une classe de `support` qui importerait une classe de `command`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le cycle de packages

## ADDED Requirements

### Requirement: Les codes de sortie de la CLI ont une seule définition
Les codes de sortie de la CLI (succès 0, erreur d'exécution 1, erreur d'usage 2, résultat partiel 3) SHALL être définis par la seule classe `CliExitCodes`. Aucune classe de commande SHALL en déclarer un alias, et aucune source de production ou de test SHALL lire un code de sortie par `FindSymbolCommand`.

#### Scenario: Aucun alias dans une commande
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `FindSymbolCommand\.(SUCCESS|EXECUTION_ERROR|USAGE_ERROR|PARTIAL_RESULT)` dans les sources Java de tous les modules et dans `docs/user/`, `docs/developer/` et `docs/architecture/` hors archives
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Valeurs inchangées
- **GIVEN** le dépôt après le changement
- **WHEN** un test de `minos-cli` lit les quatre constantes de `CliExitCodes`
- **THEN** elles valent 0, 1, 2 et 3

#### Scenario: Sorties caractérisées inchangées
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** la commande `git diff` se termine avec le code 0

### Requirement: Les codes de sortie de la surface d'exploration suivent ceux de la CLI
Chaque constante de code de sortie déclarée par `ExplorerSession` SHALL porter le nom et la valeur d'une constante de `CliExitCodes`. Un test de l'assemblage SHALL échouer quand une valeur diverge ou quand `ExplorerSession` déclare un code que `CliExitCodes` ne connaît pas.

#### Scenario: Parité au HEAD du changement
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ExitCodeContractParityTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit pour `SUCCESS`, `EXECUTION_ERROR` et `USAGE_ERROR`

#### Scenario: Valeur divergente refusée
- **GIVEN** une table de constantes de test où `USAGE_ERROR` vaut 2 d'un côté et 64 de l'autre
- **WHEN** la comparaison du test de parité est appliquée à cette table
- **THEN** elle échoue en nommant `USAGE_ERROR` et les deux valeurs

#### Scenario: Code inconnu de la CLI refusé
- **GIVEN** une table de constantes de test où la surface déclare un code absent de la CLI
- **WHEN** la comparaison du test de parité est appliquée à cette table
- **THEN** elle échoue en nommant la constante sans équivalent

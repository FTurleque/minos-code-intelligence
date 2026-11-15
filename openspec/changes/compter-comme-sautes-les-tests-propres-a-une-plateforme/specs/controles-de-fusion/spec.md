## ADDED Requirements

### Requirement: Aucun test ne passe à vide derrière un retour anticipé de plateforme
Dans les sources de test Java de tous les modules, plugin IntelliJ compris, une instruction `return` sans valeur gardée par une condition sur le système d'exploitation SHALL être refusée par un gate du job des invariants, que la garde tienne sur une ou plusieurs lignes et quelle que soit l'expression qui lit le système. Un test propre à un système SHALL être restreint par `@EnabledOnOs` et SHALL figurer comme sauté, avec sa raison, dans le rapport de l'autre système.

#### Scenario: État du dépôt
- **GIVEN** le dépôt au commit candidat
- **WHEN** `python scripts/quality/check-test-platform-guards.py` est exécuté
- **THEN** il se termine avec le code 0 et affiche le nombre de fichiers de test lus et le nombre d'exceptions déclarées

#### Scenario: Garde sur deux lignes
- **GIVEN** un fichier de test contenant `if (!CommandLocator.isWindows()) {` suivi de `return;` sur la ligne suivante
- **WHEN** `python scripts/quality/check-test-platform-guards.py --self-test` l'évalue
- **THEN** le cas constate le code 1 et un message qui nomme le fichier et la ligne

#### Scenario: Garde par variable locale
- **GIVEN** un fichier de test qui lit `os.name` dans une variable puis contient `if (!os.contains("linux")) return;`
- **WHEN** l'auto-test l'évalue
- **THEN** le cas constate le code 1

#### Scenario: Test Windows sous Linux
- **GIVEN** les tests de `minos-runtime-local` exécutés sous Linux
- **WHEN** le rapport Surefire de `CommandLocatorTest` est lu
- **THEN** `realWindowsBatchInvocationUsesCanonicalSystem32Cmd` et `realWindowsCmdPreservesAdversarialQuotedArguments` y figurent comme sautés

#### Scenario: Aucun fichier lu
- **GIVEN** une racine sans aucune source de test Java
- **WHEN** l'auto-test évalue le gate sur cette racine
- **THEN** le cas constate le code 1 et non un succès sur zéro fichier

### Requirement: Les retours anticipés de plateforme tolérés sont une liste fermée et motivée
Un retour anticipé gardé par le système d'exploitation SHALL n'être toléré que hors d'une méthode de test (extension ou outillage de test) et seulement s'il figure dans la liste d'exceptions du gate, par fichier et par nombre d'occurrences, avec sa raison. Le gate SHALL échouer quand une exception ne correspond plus à aucune occurrence, et SHALL NOT accepter une exception située dans une méthode annotée `@Test`.

#### Scenario: Exception devenue sans objet
- **GIVEN** une liste d'exceptions qui déclare deux occurrences pour un fichier qui n'en contient plus qu'une
- **WHEN** l'auto-test du gate l'évalue
- **THEN** le cas constate le code 1 et un message qui nomme le fichier et les deux nombres

#### Scenario: Exception dans une méthode de test
- **GIVEN** une exception déclarée pour un fichier dont l'occurrence est dans une méthode annotée `@Test`
- **WHEN** l'auto-test l'évalue
- **THEN** le cas constate le code 1

#### Scenario: Auto-test câblé
- **GIVEN** un `pr-ci.yml` dont le job `invariants` ne lance pas `check-test-platform-guards.py --self-test`
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 1 et nomme le script

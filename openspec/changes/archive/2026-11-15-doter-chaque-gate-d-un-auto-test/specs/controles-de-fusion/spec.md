## ADDED Requirements

### Requirement: Tout gate a un auto-test
Tout script `scripts/**/check-*.py` hors `history/` SHALL déclarer l'option `--self-test` ou posséder un fichier `test_*.py` jumeau dans son dossier. `check-ci-wiring.py` SHALL échouer en nommant tout gate qui n'a ni l'un ni l'autre et qui ne figure pas dans la liste fermée des gates en attente.

#### Scenario: État du dépôt
- **GIVEN** le dépôt au commit candidat
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté
- **THEN** il se termine avec le code 0 et son message de succès compte les gates dotés d'un auto-test et les gates en attente

#### Scenario: Gate ajouté sans auto-test
- **GIVEN** un arbre où `scripts/quality/check-exemple.py` existe sans `--self-test`, sans `test_check_exemple.py` et hors de la liste fermée
- **WHEN** `python scripts/quality/test_check_ci_wiring.py` évalue cet arbre
- **THEN** le cas constate le code 1 et un message qui nomme `scripts/quality/check-exemple.py`

#### Scenario: Les dix gates du constat
- **GIVEN** les huit gates `check-*-consistency.py`, `check-current-docs-vertical-extension.py` et `scripts/docs/check-current-docs.py`
- **WHEN** chacun est lancé avec `--self-test`
- **THEN** chacun se termine avec le code 0 et affiche le nombre de mutations refusées, supérieur à zéro

### Requirement: L'auto-test d'un gate littéral refuse une mutation par fait affirmé
L'auto-test d'un gate qui affirme des chaînes ou des motifs lus dans des fichiers SHALL, pour chaque fait affirmé sur l'état du dépôt, appliquer une mutation qui le rend faux et constater que le gate échoue en nommant le fichier. Il SHALL échouer quand une mutation est acceptée, quand un fait n'a produit aucune mutation ou quand aucun fait n'a été relevé. Il SHALL NOT modifier un fichier du dépôt.

#### Scenario: Littéral exigé retiré
- **GIVEN** un fait « le fichier contient ce littéral » d'un gate de cohérence
- **WHEN** `--self-test` retire ce littéral d'une copie en mémoire du fichier et réévalue le gate
- **THEN** le gate échoue avec un message qui nomme le fichier

#### Scenario: Littéral interdit introduit
- **GIVEN** un fait « le fichier ne contient pas ce littéral »
- **WHEN** `--self-test` l'introduit dans la copie et réévalue le gate
- **THEN** le gate échoue

#### Scenario: Règle devenue vacuitaire
- **GIVEN** un gate dont une règle accepte la copie mutée
- **WHEN** `--self-test` est exécuté
- **THEN** il se termine avec le code 1 et nomme la règle, le fichier et le littéral

#### Scenario: Aucun fait relevé
- **GIVEN** un gate dont l'évaluation ne relève aucun fait
- **WHEN** `--self-test` est exécuté
- **THEN** il se termine avec le code 1 et non par un succès sur zéro mutation

#### Scenario: Dépôt intact
- **GIVEN** un arbre de travail propre
- **WHEN** les dix auto-tests sont exécutés
- **THEN** `git status --porcelain` ne liste aucun fichier

### Requirement: Les gates sans auto-test sont une liste fermée qui ne peut que diminuer
La liste des gates tolérés sans auto-test SHALL être déclarée dans `check-ci-wiring.py`, chaque entrée avec sa raison. Le gate SHALL échouer quand une entrée désigne un script absent ou un script qui a désormais un auto-test, de sorte que la liste ne puisse ni s'allonger sans relecture ni garder une entrée périmée.

#### Scenario: Entrée périmée
- **GIVEN** une liste fermée qui nomme un gate déclarant maintenant `--self-test`
- **WHEN** `python scripts/quality/test_check_ci_wiring.py` évalue cet arbre
- **THEN** le cas constate le code 1 et un message qui demande de retirer l'entrée

#### Scenario: Entrée sans script
- **GIVEN** une liste fermée qui nomme un script qui n'existe pas
- **WHEN** l'auto-test évalue cet arbre
- **THEN** le cas constate le code 1 et nomme l'entrée

## ADDED Requirements

### Requirement: Un échappement Unicode ne cache rien aux règles de frontières
Les règles de `check-module-boundaries.py` qui lisent un source Java SHALL le lire échappements Unicode décodés, comme le compilateur, avant d'écarter commentaires et littéraux. Une barre oblique inverse elle-même échappée par une autre SHALL ne pas ouvrir d'échappement.

#### Scenario: Nom ou mot écrit avec un échappement
- **GIVEN** les auto-tests, avec un point ou une lettre écrits en échappement dans un nom que A2 refuse, puis dans un mot que A12, A16 et A18 comptent
- **WHEN** les auto-tests des cliquets et des adaptateurs sont exécutés
- **THEN** chaque règle refuse comme pour l'écriture directe

#### Scenario: Échappement qui termine un commentaire de ligne
- **GIVEN** l'auto-test des adaptateurs, avec un nom d'adaptateur placé dans un commentaire après un échappement de saut de ligne, puis de retour chariot
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** le nom est refusé en A2

#### Scenario: Barre oblique inverse échappée
- **GIVEN** le même auto-test, avec le même échappement précédé d'une seconde barre oblique inverse
- **WHEN** l'auto-test est exécuté
- **THEN** la règle réussit

### Requirement: Une annotation dans un nom qualifié ne le cache pas
Les règles de `check-module-boundaries.py` qui lisent des noms SHALL lire un nom qualifié interrompu par une ou plusieurs annotations de type comme le nom sans elles. Le type de chaque annotation et ses arguments SHALL rester lus.

#### Scenario: Nom annoté
- **GIVEN** les auto-tests, avec une annotation, avec ou sans arguments, puis deux annotations, devant la dernière partie d'un nom qualifié (A2, A11, A13, A8)
- **WHEN** les auto-tests des cliquets, des adaptateurs et des packages sont exécutés
- **THEN** chaque règle refuse ; A2, A11 et A13 nomment le type d'un seul tenant, A8 les deux packages du cycle
- **AND** des arguments d'annotation non fermés font échouer le garde en nommant le fichier

#### Scenario: Type nommé par l'annotation
- **GIVEN** l'auto-test des cliquets, avec un type du moteur nommé par une annotation placée dans un autre nom, comme annotation puis comme argument
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** ce type est refusé en A11

### Requirement: A19 lit le joker sur une API d'infrastructure
Dans `minos-application`, A19 SHALL compter comme nommant une API d'infrastructure un source qui importe par joker `java.net.http`, `javax.tools`, `com.sun.source` ou l'un de ses sous-packages, et un source qui importe `java.net.*` et nomme `HttpURLConnection`.

#### Scenario: Joker sur un package d'infrastructure
- **GIVEN** l'auto-test des adaptateurs, avec chacun de ces jokers dans un source absent de la liste
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** chaque forme est refusée en A19

#### Scenario: Joker de java.net
- **GIVEN** le même auto-test, avec `import java.net.*;` dans un source qui nomme `HttpURLConnection`, puis dans un source qui ne nomme que `URI`
- **WHEN** l'auto-test est exécuté
- **THEN** le premier est refusé en A19
- **AND** le second est accepté

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

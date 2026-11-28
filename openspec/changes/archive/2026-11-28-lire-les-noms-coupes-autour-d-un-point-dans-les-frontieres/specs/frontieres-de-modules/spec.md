## ADDED Requirements

### Requirement: Un nom coupé autour d'un point est lu en entier
Les règles de `check-module-boundaries.py` qui lisent des noms, importés ou qualifiés, SHALL lire un nom dont les points sont entourés de blancs, de retours à la ligne ou de commentaires comme le même nom écrit d'un seul tenant, le `.*` d'un joker compris. Un tel texte dans un commentaire ou un littéral SHALL rester ignoré.

#### Scenario: Import coupé
- **GIVEN** les auto-tests, avec un import coupé autour de ses points dans une surface (A11), un adaptateur (A13), une source qui ne doit nommer aucun adaptateur (A2), un module hors de `minos-runtime-local` (A10), `minos-bootstrap` (R1) et un package du stockage
- **WHEN** les auto-tests des cliquets et des adaptateurs sont exécutés
- **THEN** chaque règle refuse et nomme la cible d'un seul tenant

#### Scenario: Nom qualifié coupé dans le code
- **GIVEN** les mêmes auto-tests, avec le nom qualifié coupé écrit dans le code sans import
- **WHEN** les mêmes auto-tests sont exécutés
- **THEN** chaque règle refuse et nomme le type d'un seul tenant

#### Scenario: Joker coupé
- **GIVEN** les mêmes auto-tests, avec un import joker dont le `.*` est coupé, dans une surface, un adaptateur et la façade `MinosApplication`
- **WHEN** les mêmes auto-tests sont exécutés
- **THEN** chaque forme est refusée comme le joker écrit d'un seul tenant

#### Scenario: Cycle et type référencé
- **GIVEN** l'auto-test des packages, avec un cycle fermé par un nom coupé, puis un type public nommé par un seul nom coupé
- **WHEN** `python scripts/architecture/test_check_module_boundaries_packages.py` est exécuté
- **THEN** le cycle est refusé en A8
- **AND** A15 ne déclare pas le type sans appelant

#### Scenario: Commentaire, littéral et ellipse
- **GIVEN** l'auto-test des cliquets, avec un nom coupé dans un commentaire et dans une chaîne, puis un paramètre à nombre variable `com.minos.hosted . HostedRole ... roles`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** le commentaire et la chaîne n'ajoutent aucun usage
- **AND** le paramètre est refusé en A11 en nommant `com.minos.hosted.HostedRole`

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

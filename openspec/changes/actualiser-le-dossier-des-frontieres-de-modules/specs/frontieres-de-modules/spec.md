## MODIFIED Requirements

### Requirement: La documentation des gates nomme chaque fichier du garde de frontières
Le chapitre « Gardes d'architecture » de `docs/developer/quality-gates.md` SHALL nommer `check-module-boundaries.py`, chaque `module_boundary_*.py` avec ses règles, `gate_runner.py`, `module_boundaries_testing.py`, chaque auto-test `test_check_module_boundaries*.py`, `test_gate_runner.py`, et `module-boundaries.json` comme source unique de la politique, et décrire chaque règle `A<n>` du garde. Un auto-test sur le dépôt réel SHALL échouer sur un fichier ou une règle non nommés.

#### Scenario: Chaque fichier du garde est nommé
- **GIVEN** le dépôt après correction
- **WHEN** pour chaque fichier `f` de `scripts/architecture/module_boundary_*.py`, `scripts/architecture/test_check_module_boundaries*.py`, `test_gate_runner.py`, `gate_runner.py`, `module_boundaries_testing.py` et `module-boundaries.json`, `grep -c "$(basename f)" docs/developer/quality-gates.md` est exécuté
- **THEN** chaque compte est au moins 1
- **AND** `python scripts/architecture/test_check_module_boundaries_documents.py` se termine avec le code 0

#### Scenario: Module de règles ajouté sans documentation
- **GIVEN** une copie du dépôt où un fichier `scripts/architecture/module_boundary_x.py` est ajouté sans modifier `docs/developer/quality-gates.md`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `module_boundary_x.py`

#### Scenario: Auto-test non nommé
- **GIVEN** une copie du dépôt où la mention de `test_gate_runner.py` est retirée de `docs/developer/quality-gates.md`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `test_gate_runner.py`

#### Scenario: Règle du garde sans description
- **GIVEN** une copie du dépôt où la puce de `docs/developer/quality-gates.md` qui décrit A20 est retirée
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `A20`

### Requirement: Aucune découverte de service de production n'omet le chargeur
Tout appel `ServiceLoader.load` des sources de production du reactor SHALL passer un chargeur de classes explicite. Le garde de frontières SHALL échouer sur un appel à un seul argument, en nommant le fichier et la ligne. Ni la spécification ni le dossier de la capacité n'écrivent le nombre de découvertes : il se lit dans la sortie du garde.

#### Scenario: Appel sans chargeur
- **GIVEN** une copie du dépôt où une classe de production appelle `ServiceLoader.load(X.class)`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant le fichier et la ligne

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après le changement
- **WHEN** le garde est exécuté
- **THEN** il réussit et sa ligne `A16 service discovery` annonce que chaque appel `ServiceLoader.load` de production passe un chargeur explicite
- **AND** le compte de cette ligne est celui qu'affirme `test_the_real_tree_passes_a_loader_at_every_site` dans `scripts/architecture/test_check_module_boundaries_adapters.py`

## ADDED Requirements

### Requirement: Le dossier de la capacité nomme chaque règle du garde
Le paragraphe « Règles du garde » de `docs/architecture/diagrams/capacites/frontieres-de-modules.md` SHALL citer exactement les étiquettes `A<n>` portées par les sources du garde (`check-module-boundaries.py`, `module_boundary_*.py`), chacune par ce qu'elle refuse. Le dossier SHALL n'écrire aucun nombre dérivé de la politique (modules, dépendances, découvertes). Un auto-test sur le dépôt réel SHALL échouer, en la nommant, sur une étiquette manquante ou en trop, et sur un paragraphe introuvable.

#### Scenario: Dossier aligné sur le garde
- **GIVEN** le dépôt après correction
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté
- **THEN** l'auto-test se termine avec le code 0
- **AND** `grep -nE "12 autres|A2 à A18|tables Java et Python|en affirme cinq" docs/architecture/diagrams/capacites/frontieres-de-modules.md` ne rend aucune ligne et se termine avec le code 1

#### Scenario: Règle retirée du dossier
- **GIVEN** une copie du dépôt où `A19` est retirée du paragraphe « Règles du garde » du dossier
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `A19`

#### Scenario: Règle nouvelle du garde non documentée
- **GIVEN** une copie du dépôt où un message d'un module de règles porte l'étiquette `A21`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `A21`

#### Scenario: Paragraphe introuvable
- **GIVEN** une copie du dépôt où l'amorce « Règles du garde » du paragraphe est réécrite
- **WHEN** `python scripts/architecture/test_check_module_boundaries_documents.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 au lieu de réussir sur un ensemble vide

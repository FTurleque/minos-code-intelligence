## ADDED Requirements

### Requirement: Les fonctions de scripts recopiées restent identiques
Une fonction PowerShell recopiée entre scripts livrés, d'installation, de release ou de CI SHALL figurer dans une liste fermée de jumelles. Un gate exécuté par le job des invariants SHALL échouer si deux copies listées diffèrent hors fins de ligne et espaces de fin de ligne, si un fichier ou une définition listée manque, si une entrée nomme moins de deux fichiers, ou si une copie identique n'est couverte par aucune entrée.

#### Scenario: Dépôt réel accepté
- **GIVEN** le dépôt, où chaque fonction listée a le même corps dans tous ses fichiers
- **WHEN** `python scripts/quality/check-twin-script-functions.py` est exécuté
- **THEN** le gate se termine avec le code 0 et affiche le nombre d'entrées et de copies comparées

#### Scenario: Correction faite dans une seule copie
- **GIVEN** une copie du dépôt où une ligne de `Read-BoundedUtf8` a été modifiée dans un seul des deux scripts
- **WHEN** le gate est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant la fonction et les deux fichiers

#### Scenario: Entrée morte
- **GIVEN** une copie du dépôt où un fichier listé est absent, ne définit plus la fonction listée, ou reste seul dans son entrée
- **WHEN** le gate est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant l'entrée

#### Scenario: Copie non déclarée
- **GIVEN** une copie du dépôt où une fonction d'un script du périmètre est recopiée à l'identique dans un autre script du périmètre sans entrée qui les couvre
- **WHEN** le gate est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant la fonction et les deux fichiers

#### Scenario: Fins de ligne différentes
- **GIVEN** deux copies listées dont les corps ne diffèrent que par CRLF et LF ou par des espaces de fin de ligne
- **WHEN** le gate est exécuté sur cet arbre
- **THEN** elles sont tenues pour identiques

#### Scenario: Auto-test du gate
- **GIVEN** le dépôt
- **WHEN** `python scripts/quality/test_check_twin_script_functions.py -v` est exécuté
- **THEN** il réussit, avec au moins un cas refusé par règle et le dépôt réel accepté

### Requirement: La vérification du câblage CI des gates n'est écrite qu'une fois
La vérification qu'un gate et son auto-test sont des étapes sans condition du job des invariants SHALL être définie dans un seul module de `scripts/quality`, importé par les gates qui s'en servent ; chaque gate SHALL garder son auto-test et son refus d'une étape absente ou conditionnée. Un gate qui ne peut pas importer ce module SHALL échouer avec un code non nul, sans repli sur une copie locale.

#### Scenario: Définition unique
- **GIVEN** le dépôt après le changement
- **WHEN** `git grep -l "def find_ci_wiring_violations" -- scripts` est exécuté
- **THEN** il ne renvoie qu'un fichier

#### Scenario: Auto-tests conservés
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/quality/test_check_compose_limits.py -v` puis `python scripts/quality/test_check_image_pins.py -v` sont exécutés
- **THEN** les deux réussissent, y compris leurs cas où l'étape du gate manque ou porte une condition `if:`

#### Scenario: Étape conditionnée refusée
- **GIVEN** une copie du dépôt où l'étape du gate d'identité dans le job des invariants porte `if: false`
- **WHEN** `python scripts/quality/check-twin-script-functions.py` est exécuté avec `--root` sur cette copie
- **THEN** il se termine avec un code non nul en nommant l'étape

#### Scenario: Module partagé absent
- **GIVEN** une copie de `scripts/quality` sans le module partagé
- **WHEN** `check-compose-limits.py` ou `check-image-pins.py` de cette copie est exécuté
- **THEN** il se termine avec un code non nul

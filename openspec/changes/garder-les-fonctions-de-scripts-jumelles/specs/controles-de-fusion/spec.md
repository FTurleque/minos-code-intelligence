## ADDED Requirements

### Requirement: Les fonctions de scripts recopiées restent identiques
Les fonctions recopiées entre scripts vivants SHALL figurer dans une liste déclarée (fonction, fichiers). Un test exécuté par le job des invariants SHALL comparer leurs corps, normalisés sur les seules fins de ligne et espaces de fin de ligne, et SHALL échouer si deux corps diffèrent, si une fonction listée est absente d'un fichier ou si un fichier listé est introuvable.

#### Scenario: Copies identiques
- **GIVEN** le dépôt, où chaque fonction listée a le même corps dans tous ses fichiers
- **WHEN** `python scripts/quality/test_twin_script_functions.py` est exécuté
- **THEN** le test réussit et affiche chaque fonction contrôlée avec ses fichiers

#### Scenario: Correction faite dans une seule copie
- **GIVEN** une copie du dépôt où une ligne de `Read-BoundedUtf8` a été modifiée dans un seul des deux scripts
- **WHEN** le test est exécuté sur cette copie
- **THEN** il échoue en nommant la fonction et les deux fichiers

#### Scenario: Fonction ou fichier disparu
- **GIVEN** une copie du dépôt où un fichier listé est absent, ou ne définit plus la fonction listée
- **WHEN** le test est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée de la liste

#### Scenario: Fins de ligne différentes
- **GIVEN** deux copies dont les corps ne diffèrent que par CRLF et LF
- **WHEN** le test est exécuté
- **THEN** elles sont tenues pour identiques

### Requirement: Le câblage CI des gates Python n'est écrit qu'une fois
La fonction qui vérifie le câblage CI d'un gate Python SHALL être définie dans un seul module, importé par les gates qui s'en servent ; chaque gate SHALL garder son auto-test. Un gate qui ne peut pas importer ce module SHALL échouer avec un code non nul, sans repli sur une copie locale.

#### Scenario: Définition unique
- **GIVEN** le dépôt après le changement
- **WHEN** `grep -l "def find_ci_wiring_violations" scripts/quality/*.py` est exécuté
- **THEN** il ne renvoie qu'un fichier

#### Scenario: Auto-tests conservés
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/quality/check-compose-limits.py --self-test` puis `python scripts/quality/check-image-pins.py --self-test` sont exécutés
- **THEN** les deux réussissent

#### Scenario: Module partagé absent
- **GIVEN** une copie du dépôt sans le module partagé
- **WHEN** l'un des deux gates est exécuté sur cette copie
- **THEN** il se termine avec un code non nul

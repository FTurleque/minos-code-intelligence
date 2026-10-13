## ADDED Requirements

### Requirement: La langue des commentaires de chaque module est déclarée
Le guide de contribution SHALL énoncer la langue des commentaires et de la Javadoc du code de production, et la langue de chaque module SHALL être déclarée dans un fichier unique du dépôt lu par le gate de langue. Le gate SHALL échouer si un module du reactor n'y est pas déclaré.

#### Scenario: Module sans langue déclarée
- **GIVEN** une copie du dépôt où un module du reactor est absent de la déclaration
- **WHEN** `python scripts/quality/check-comment-language.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le module

#### Scenario: Dépôt réel
- **GIVEN** le dépôt, où chaque module du reactor a une langue déclarée
- **WHEN** le gate est exécuté
- **THEN** il réussit et affiche la langue de chaque module

### Requirement: Un fichier de production ne mêle pas deux langues hors d'une liste décroissante
Un fichier Java de production dont les commentaires mêlent français et anglais SHALL faire échouer le gate de langue, sauf s'il figure dans la liste des fichiers mixtes tolérés. Le gate SHALL échouer si un fichier listé est introuvable ou n'est plus mixte, de sorte que la liste ne puisse que diminuer.

#### Scenario: Nouveau fichier mixte
- **GIVEN** une copie du dépôt où un fichier de `src/main` hors liste contient un commentaire français et un commentaire anglais
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier et une ligne de chaque langue

#### Scenario: Fichier listé devenu conforme
- **GIVEN** un fichier de la liste dont les commentaires sont désormais dans une seule langue
- **WHEN** le gate est exécuté
- **THEN** il échoue en demandant de retirer l'entrée

#### Scenario: Fichier listé introuvable
- **GIVEN** une liste qui nomme un fichier supprimé ou déplacé
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée

#### Scenario: Lettre accentuée dans une chaîne de code
- **GIVEN** un fichier dont les commentaires sont en anglais et dont un littéral de chaîne contient une lettre accentuée
- **WHEN** le gate est exécuté
- **THEN** le fichier n'est pas tenu pour mixte

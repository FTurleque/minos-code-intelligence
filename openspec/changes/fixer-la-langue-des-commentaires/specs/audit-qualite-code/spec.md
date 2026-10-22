## ADDED Requirements

### Requirement: La langue des commentaires de chaque module est déclarée
Le guide de contribution SHALL énoncer la langue des commentaires et de la Javadoc du code de production. La langue de chaque module SHALL être déclarée dans un fichier unique lu par le gate de langue, avec par module un maximum de fichiers ayant un commentaire dans l'autre langue. Le gate SHALL échouer si un module n'est pas déclaré, si ce nombre dépasse son maximum ou s'il lui est inférieur.

#### Scenario: Module sans langue déclarée
- **GIVEN** une copie du dépôt où un module du reactor est absent de la déclaration
- **WHEN** `python scripts/quality/check-comment-language.py --root <copie>` est exécuté
- **THEN** le gate échoue avec un code non nul en nommant le module

#### Scenario: Nouveau fichier hors langue
- **GIVEN** une copie du dépôt où un fichier de `src/main` ajouté à un module ne porte que des commentaires dans la langue que ce module ne déclare pas
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le module, son décompte et son maximum

#### Scenario: Maximum devenu trop haut
- **GIVEN** une copie du dépôt où un fichier hors langue a été repris dans la langue déclarée sans abaisser le maximum de son module
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en donnant la valeur à inscrire

#### Scenario: Dépôt réel
- **GIVEN** le dépôt, où chaque module du reactor a une langue déclarée et un maximum égal à son décompte
- **WHEN** `python scripts/quality/check-comment-language.py` est exécuté, sous Windows comme sous Linux
- **THEN** il réussit et affiche la langue et le décompte de chaque module

### Requirement: Un fichier de production ne mêle pas deux langues hors d'une liste décroissante
Un fichier Java de production dont les commentaires mêlent français et anglais SHALL faire échouer le gate de langue, sauf s'il figure, par son nom de fichier, dans la liste des fichiers mixtes tolérés. Le gate SHALL échouer si une entrée ne désigne aucun fichier, en désigne plusieurs, ou désigne un fichier qui n'est plus mixte, de sorte que la liste ne puisse que diminuer. Les sources SHALL être lues en UTF-8.

#### Scenario: Nouveau fichier mixte
- **GIVEN** une copie du dépôt où un fichier de `src/main` hors liste contient un commentaire français et un commentaire anglais
- **WHEN** `python scripts/quality/check-comment-language.py --root <copie>` est exécuté
- **THEN** il échoue en nommant le fichier et une ligne de chaque langue

#### Scenario: Fichier listé devenu conforme
- **GIVEN** un fichier de la liste dont les commentaires sont désormais dans une seule langue
- **WHEN** le gate est exécuté
- **THEN** il échoue en demandant de retirer l'entrée

#### Scenario: Entrée morte
- **GIVEN** une liste qui nomme un fichier supprimé
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée

#### Scenario: Fichier listé déplacé
- **GIVEN** un fichier listé déplacé dans un autre package sans changer de nom
- **WHEN** le gate est exécuté
- **THEN** il réussit sans modification de la liste

#### Scenario: Lettre accentuée hors commentaire
- **GIVEN** un fichier dont les commentaires sont en anglais et dont un littéral de chaîne ou un `{@code …}` contient une lettre accentuée
- **WHEN** `python scripts/quality/check-comment-language.py --self-test` est exécuté
- **THEN** le cas est accepté : le fichier n'est pas tenu pour mixte

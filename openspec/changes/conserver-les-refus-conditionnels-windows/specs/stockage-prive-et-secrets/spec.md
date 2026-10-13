## ADDED Requirements

### Requirement: Un refus conditionnel n'est jamais retiré par le durcissement
Sous Windows, le durcissement d'un emplacement privé SHALL NOT retirer une entrée de refus conditionnel, explicite ou héritée : après l'appel, elle SHALL être encore présente dans la DACL, que le durcissement ait réussi ou qu'il ait échoué avant d'écrire. Une autorisation conditionnelle héritée SHALL continuer d'être retirée. Si la DACL complète ne peut pas être lue, le durcissement SHALL échouer sans écrire, par une erreur sans chemin absolu.

#### Scenario: Refus conditionnel explicite
- **GIVEN** sous Windows, un répertoire dont la DACL porte un refus conditionnel posé par un administrateur
- **WHEN** le durcissement d'emplacement privé est appelé sur ce répertoire
- **THEN** le SDDL relu contient toujours l'entrée de refus conditionnel

#### Scenario: Refus conditionnel hérité du parent
- **GIVEN** un fichier qui hérite un refus conditionnel de son répertoire parent
- **WHEN** le durcissement est appelé sur ce fichier
- **THEN** le refus conditionnel s'applique toujours au fichier

#### Scenario: Autorisation conditionnelle héritée
- **GIVEN** un répertoire qui hérite une autorisation conditionnelle de son parent
- **WHEN** le durcissement est appelé
- **THEN** l'autorisation conditionnelle n'est plus dans sa DACL et l'héritage est coupé

#### Scenario: DACL illisible
- **GIVEN** une lecture de la DACL complète qui échoue ou dépasse son délai (échec injecté)
- **WHEN** le durcissement est appelé
- **THEN** il échoue, la DACL n'a pas été réécrite et le message ne contient pas le chemin absolu

### Requirement: Un octroi d'ACL du bac à sable est additif, borné et fail-closed
Le code Java du bac à sable Windows SHALL accorder ou retirer un droit par une primitive unique qui ajoute ou retire l'entrée d'une seule identité désignée par SID, sans réécrire la DACL, avec un délai borné et un répertoire Windows vérifié. Un octroi qui échoue, dépasse son délai ou ne trouve pas le répertoire Windows SHALL être rapporté comme non accordé et SHALL faire échouer l'opération qui en dépend.

#### Scenario: Octroi sur un répertoire porteur d'un refus
- **GIVEN** sous Windows, une racine d'écriture dont la DACL porte un refus posé par un administrateur
- **WHEN** la primitive accorde un droit à une identité
- **THEN** l'entrée de l'identité est ajoutée et toutes les autres entrées, dont le refus, sont inchangées

#### Scenario: Répertoire Windows inconnu
- **GIVEN** un environnement sans `SystemRoot`
- **WHEN** la primitive est appelée
- **THEN** elle rapporte un octroi non accordé sans lancer de processus ni lever d'erreur non contrôlée

#### Scenario: Délai dépassé
- **GIVEN** un lanceur injecté qui ne se termine pas
- **WHEN** la primitive est appelée
- **THEN** le processus est tué au terme du délai et l'octroi est rapporté comme non accordé

## ADDED Requirements

### Requirement: L'e-mail d'un auteur n'est rendu que sur demande explicite
Par défaut, l'activité Git rendue par la CLI et par l'API Java SHALL NOT contenir l'adresse e-mail d'un auteur : le champ `authorEmail` SHALL rester présent avec un masque fixe. L'adresse SHALL n'être rendue que sur demande explicite de l'appelant. Le masquage SHALL NOT modifier le décompte des auteurs distincts. Aucun outil MCP SHALL rendre cette adresse.

#### Scenario: Sortie par défaut de la CLI
- **GIVEN** un dépôt dont un commit a pour auteur `a2@example.invalid`
- **WHEN** `minos git-activity` est exécuté au format JSON sans option
- **THEN** chaque commit porte la clé `authorEmail` et la sortie ne contient pas `a2@example.invalid`

#### Scenario: Demande explicite par la CLI
- **GIVEN** le même dépôt
- **WHEN** la commande est exécutée avec l'option d'affichage des e-mails
- **THEN** `authorEmail` vaut `a2@example.invalid`

#### Scenario: Valeur par défaut de l'API Java
- **GIVEN** le même dépôt
- **WHEN** l'activité Git est demandée par l'API Java sans paramètre d'affichage
- **THEN** `GitCommitDto.authorEmail()` rend le masque et non l'adresse

#### Scenario: Décompte des auteurs inchangé
- **GIVEN** un fichier modifié par deux auteurs de même nom et d'adresses différentes
- **WHEN** l'activité Git est demandée sans affichage des e-mails
- **THEN** le nombre d'auteurs distincts du fichier vaut 2

#### Scenario: Catalogue MCP
- **GIVEN** le même dépôt indexé
- **WHEN** chaque outil MCP du catalogue est appelé
- **THEN** aucune réponse ne contient `a2@example.invalid`

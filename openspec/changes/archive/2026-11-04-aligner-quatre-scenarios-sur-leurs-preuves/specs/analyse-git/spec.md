## MODIFIED Requirements

### Requirement: Aucun filtre déclaré par le dépôt analysé ne s'exécute
L'analyse Git (`inspect`, `analyze`, `git-activity`) SHALL NOT exécuter de commande déclarée par la configuration du dépôt qu'elle ouvre, qu'elle provienne de `.git/config`, d'un fichier inclus, de `.gitattributes` ou de `info/attributes`. Le champ `clean` SHALL rester calculé.

#### Scenario: Filtre clean déclaré par un dépôt parent piégé
- **GIVEN** un dossier parent contenant un `.git` dont la configuration déclare `filter.piege.clean` sur une commande qui crée un fichier témoin, un `.gitattributes` `* filter=piege` et une entrée d'index dont la date force la vérification de contenu
- **WHEN** `GitIntelligenceService.inspect` est appelé sur un sous-dossier de ce dépôt
- **THEN** le fichier témoin n'existe pas après l'appel et la vue du dépôt est renvoyée avec un champ `clean` défini

#### Scenario: Filtre déclaré par un fichier inclus
- **GIVEN** un `.git/config` contenant `include.path` vers un fichier qui déclare le filtre piégé
- **WHEN** `inspect` est appelé
- **THEN** le fichier témoin n'est pas créé

#### Scenario: Dépôt livré avec son propre .git
- **GIVEN** un projet dont la racine contient un `.git` extrait d'une archive et appartenant à l'utilisateur, avec le filtre piégé
- **WHEN** `analyze` est appelé sur la racine du projet
- **THEN** le fichier témoin n'est pas créé

#### Scenario: Filtre du dépôt principal atteint depuis un worktree lié
- **GIVEN** un dépôt principal dont `.git/config` déclare le filtre piégé, et un worktree lié dont le fichier suivi porte `* filter=piege` et une date qui force la vérification de contenu
- **WHEN** `inspect` est appelé sur le worktree lié
- **THEN** le fichier témoin n'est pas créé
- **AND** la vue rendue déclare la limitation `REPOSITORY_FILTERS_NOT_APPLIED`

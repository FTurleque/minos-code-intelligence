## ADDED Requirements

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

### Requirement: Les filtres de l'utilisateur ne sont pas retirés
Seuls les filtres de la configuration du dépôt SHALL être écartés ; les filtres de la configuration de l'utilisateur et du système SHALL rester appliqués par JGit comme avant.

#### Scenario: Filtre de la configuration de l'utilisateur
- **GIVEN** un filtre `clean` déclaré uniquement dans la configuration de l'utilisateur et un dépôt qui l'utilise par `.gitattributes`
- **WHEN** `inspect` est appelé
- **THEN** le comportement est celui d'avant le changement : la configuration de l'utilisateur n'est ni lue autrement ni modifiée

### Requirement: Le propriétaire du dépôt trouvé est contrôlé avant toute lecture de son contenu
Le dépôt trouvé, directement ou en remontant, SHALL être analysé seulement si le propriétaire de son répertoire Git est celui que le système attribue aux fichiers que le processus MINOS crée. Sinon l'appel SHALL échouer par une erreur actionnable qui nomme le propriétaire attendu et celui trouvé, sans ouvrir l'index ni lancer le moindre statut. Si le propriétaire ne peut pas être lu, l'appel SHALL échouer (fail-closed) avec un message distinct.

#### Scenario: Dépôt parent d'un autre propriétaire
- **GIVEN** un dépôt parent dont le propriétaire diffère du propriétaire attendu (propriétaire injecté par le seam de test)
- **WHEN** `inspect` est appelé sur un sous-dossier
- **THEN** l'appel échoue avec un message contenant les deux propriétaires et aucun statut n'est calculé

#### Scenario: Propriétaire illisible
- **GIVEN** un système de fichiers dont la lecture du propriétaire lève une `IOException`
- **WHEN** `inspect` est appelé
- **THEN** l'appel échoue avec un message distinct de celui du propriétaire différent

#### Scenario: Dépôt appartenant à l'utilisateur
- **GIVEN** un dépôt créé par le processus de test à la racine du projet
- **WHEN** `inspect` est appelé
- **THEN** la vue du dépôt est renvoyée comme avant le changement

### Requirement: La remontée vers un dépôt parent légitime reste possible et visible
Un projet situé dans un sous-dossier d'un dépôt appartenant au bon propriétaire SHALL continuer d'être analysé. Quand la racine du dépôt n'est pas la racine du projet, le résultat SHALL porter la limitation `REPOSITORY_ABOVE_PROJECT_ROOT`.

#### Scenario: Projet dans un sous-dossier d'un monorepo
- **GIVEN** un dépôt à `racine/` et un projet enregistré à `racine/service-a`
- **WHEN** `inspect` est appelé sur `racine/service-a`
- **THEN** la vue renvoie le dépôt `racine/` et la liste des limitations contient `REPOSITORY_ABOVE_PROJECT_ROOT`

#### Scenario: Projet à la racine du dépôt
- **WHEN** `inspect` est appelé sur la racine du dépôt
- **THEN** la liste des limitations ne contient pas `REPOSITORY_ABOVE_PROJECT_ROOT`

### Requirement: L'écart de fidélité dû au retrait des filtres est déclaré
Quand au moins un filtre déclaré par la configuration du dépôt a été écarté, le résultat SHALL porter la limitation `REPOSITORY_FILTERS_NOT_APPLIED`. Sans filtre écarté, elle SHALL être absente.

#### Scenario: Dépôt déclarant un filtre
- **GIVEN** un dépôt dont `.git/config` déclare un filtre `clean`
- **WHEN** `inspect` est appelé
- **THEN** la liste des limitations contient `REPOSITORY_FILTERS_NOT_APPLIED`

#### Scenario: Dépôt sans filtre
- **GIVEN** un dépôt dont la configuration ne contient aucune section `filter`
- **WHEN** `inspect` est appelé
- **THEN** la liste des limitations ne contient pas `REPOSITORY_FILTERS_NOT_APPLIED`

### Requirement: L'environnement Git du processus ne redirige toujours pas l'analyse
`GIT_DIR`, `GIT_WORK_TREE` et `GIT_CEILING_DIRECTORIES` SHALL rester ignorés : le dépôt analysé est celui trouvé depuis la racine demandée.

#### Scenario: GIT_DIR pointant vers un autre dépôt
- **WHEN** le test `theGitEnvironmentCannotRedirectTheAnalysedRepository` est exécuté après le changement
- **THEN** il réussit sans modification

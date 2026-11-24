# analyse-git Specification

## Purpose
Définit les conditions dans lesquelles MINOS lit un dépôt Git qu'il n'a pas créé : le dépôt analysé n'est pas de confiance. Son propriétaire est contrôlé avant toute lecture, aucun filtre qu'il déclare n'est exécuté, les filtres de l'utilisateur sont laissés en place, un dépôt trouvé au-dessus de la racine du projet reste accepté mais signalé, et l'écart de fidélité qui en résulte est déclaré dans la sortie.

## Requirements

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

### Requirement: Le remote rendu ne contient ni identifiant ni chemin local
L'activité Git rendue par la CLI et par l'API Java SHALL rendre `originRemote` seulement pour un remote réseau reconnu (URL à schéma et hôte autre que `file`, ou forme SCP), sans identifiants, requête ni fragment. Toute autre valeur SHALL être rendue `null` avec la limitation `ORIGIN_REMOTE_NOT_DISCLOSED`. L'identité du dépôt SHALL rester celle calculée avant ce changement.

#### Scenario: URL réseau à identifiants
- **GIVEN** un dépôt dont `remote.origin.url` vaut `https://user:secret@example.com/org/repo.git?token=x`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` vaut `https://example.com/org/repo`

#### Scenario: URL non analysable à identifiants
- **GIVEN** un remote `https://user:p ss@example.com/org/repo.git`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` est `null`, la limitation `ORIGIN_REMOTE_NOT_DISCLOSED` est présente et la sortie ne contient pas `p ss`

#### Scenario: Remote local
- **GIVEN** un remote `/srv/depots/repo.git`, `C:\depots\repo` ou `file:///srv/repo.git`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` est `null` et la limitation `ORIGIN_REMOTE_NOT_DISCLOSED` est présente

#### Scenario: Forme SCP
- **GIVEN** un remote `git@github.com:org/repo.git`
- **WHEN** l'activité Git est demandée
- **THEN** `originRemote` vaut `github.com:org/repo`

#### Scenario: Identité inchangée
- **GIVEN** un dépôt à remote local connu avant ce changement
- **WHEN** son identité est recalculée
- **THEN** `repositoryId` est identique

### Requirement: La matérialisation d'un dépôt distant n'exécute aucun pilote de filtre
Le clone d'un dépôt distant et l'extraction de son arbre de travail SHALL NOT exécuter de commande `filter.*` (`clean`, `smudge`), ni de filtre intégré choisi par `useJGitBuiltin`, que le pilote soit déclaré par la configuration du dépôt, de l'utilisateur ou du système. Le contenu extrait SHALL être le contenu brut des objets Git. L'analyse d'un dépôt local n'est pas concernée.

#### Scenario: Pilote de la configuration de l'utilisateur choisi par le dépôt distant
- **GIVEN** un dépôt de fixture dont `.gitattributes` contient `* filter=x`, et une configuration d'utilisateur de test qui déclare `filter.x.smudge` et `filter.x.clean` sur une commande créant un fichier témoin
- **WHEN** `./mvnw -B -ntp -pl minos-integration-git -am test "-Dtest=RemoteMaterializationFilterTest" "-Dsurefire.failIfNoSpecifiedTests=false"` matérialise ce dépôt dans une JVM fille dont le répertoire personnel est celui de test
- **THEN** le fichier témoin n'existe pas
- **AND** le fichier extrait a le contenu brut de l'objet Git

#### Scenario: Pilote déclaré avec un filtre intégré
- **GIVEN** la même fixture, et une configuration d'utilisateur de test qui déclare `filter.x.useJGitBuiltin = true`
- **WHEN** le dépôt est matérialisé par le même test
- **THEN** le fichier extrait a le contenu brut de l'objet Git
- **AND** le pilote redirigé porte `useJGitBuiltin = false` dans la configuration en mémoire

#### Scenario: Dépôt distant sans filtre
- **GIVEN** un dépôt de fixture sans attribut `filter`
- **WHEN** il est matérialisé
- **THEN** l'extraction est identique à celle d'avant le changement et la revalidation la déclare propre

### Requirement: La revalidation d'une extraction distante passe par l'ouverture de confiance
La revalidation d'un dépôt matérialisé, après le clone et à chaque réutilisation d'une entrée du cache, SHALL ouvrir le dépôt par l'ouverture de confiance du module : propriétaire du répertoire Git contrôlé avant toute lecture, aucun pilote de filtre exécuté pendant le calcul de l'état. Un contrôle impossible SHALL rendre l'entrée inutilisable, jamais valide.

#### Scenario: Revalidation d'une entrée du cache à filtre piégé
- **GIVEN** une entrée du cache dont un fichier suivi porte un attribut `filter=x`, une date qui force la vérification de contenu, et une configuration d'utilisateur de test dont `filter.x.clean` crée un fichier témoin
- **WHEN** la même requête distante est rejouée et réutilise le cache
- **THEN** le fichier témoin n'existe pas

#### Scenario: Entrée du cache appartenant à un autre compte
- **GIVEN** un matérialiseur construit avec un fournisseur de propriétaire attendu de test, et une entrée du cache dont le répertoire Git appartient à un autre propriétaire
- **WHEN** la requête distante est rejouée
- **THEN** l'entrée n'est pas réutilisée et son contenu n'est pas lu
- **AND** aucun message public ne contient de chemin absolu

#### Scenario: Extraction dont le HEAD ne correspond pas
- **GIVEN** une extraction dont le HEAD diffère du commit attendu
- **WHEN** elle est revalidée
- **THEN** la matérialisation échoue comme avant le changement

### Requirement: Aucun dépôt analysé n'est ouvert hors de l'ouverture de confiance
Le code principal de `minos-integration-git` SHALL NOT ouvrir un dépôt existant par `Git.open` ni construire un `FileRepositoryBuilder` ailleurs que dans le type qui porte l'ouverture de confiance. Un gate statique SHALL parcourir tous les fichiers de `minos-integration-git/src/main` et échouer sur une ouverture directe ; son auto-test SHALL couvrir ce refus.

#### Scenario: Ouverture directe réintroduite
- **GIVEN** un arbre temporaire où un fichier quelconque de `minos-integration-git/src/main`, autre que le type d'ouverture, contient `Git.open(` ou `new FileRepositoryBuilder`
- **WHEN** `python scripts/quality/check-remote-distributed-consistency.py --self-test` rejoue le gate sur cet arbre
- **THEN** le gate se termine avec un code non nul en nommant le fichier

#### Scenario: Type d'ouverture exempté
- **GIVEN** un arbre temporaire où seul le type d'ouverture construit un `FileRepositoryBuilder`
- **WHEN** le même auto-test exécute le gate
- **THEN** le gate ne signale rien

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/quality/check-remote-distributed-consistency.py` est exécuté
- **THEN** il se termine avec le code 0

### Requirement: La mesure du budget d'un clone distant échoue quand sa racine disparaît
La mesure du budget d'un clone distant SHALL échouer quand la racine mesurée disparaît pendant le parcours de son arbre ; elle SHALL NOT rendre une mesure partielle ou nulle comme valide. Seule une entrée située sous la racine qui disparaît pendant le parcours SHALL être ignorée, après avoir été comptée parmi les entrées parcourues. Toute autre erreur d'accès SHALL faire échouer la mesure. Le message d'échec SHALL NOT contenir de chemin absolu.

#### Scenario: Racine absente au moment du parcours
- **GIVEN** un budget de clone dont la racine mesurée n'existe pas
- **WHEN** `./mvnw -B -ntp -pl minos-integration-git -am test "-Dtest=RemoteCloneBudgetTest" "-Dsurefire.failIfNoSpecifiedTests=false"` parcourt cette racine avec le compteur du budget
- **THEN** le parcours échoue par une `IOException`
- **AND** le message de l'exception ne contient pas le chemin absolu de la racine

#### Scenario: Racine qui disparaît à la fin de son listage
- **GIVEN** le compteur d'un budget de clone
- **WHEN** le même test lui signale la fin du listage de la racine mesurée avec une `NoSuchFileException`
- **THEN** le compteur échoue par une `IOException` dont le message ne contient pas de chemin absolu

#### Scenario: Répertoire sous la racine qui disparaît pendant un parcours réel
- **GIVEN** une racine qui contient deux répertoires, et un parcours réel qui supprime le second pendant la visite du premier
- **WHEN** le même test parcourt la racine avec le compteur du budget, sur Windows et sur Linux
- **THEN** le parcours se termine sans exception
- **AND** le répertoire supprimé compte parmi les entrées parcourues, pas parmi les répertoires

#### Scenario: Autre erreur d'accès à une entrée
- **GIVEN** le compteur d'un budget de clone
- **WHEN** le même test lui signale une `AccessDeniedException` sur une entrée sous la racine
- **THEN** le compteur relance cette exception et la mesure échoue

#### Scenario: Arbre stable
- **GIVEN** un arbre de clone qui ne change pas pendant la mesure
- **WHEN** le même test mesure cet arbre
- **THEN** les octets, fichiers et répertoires rendus sont ceux d'avant le changement

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [analyse-git](../../../docs/architecture/diagrams/capacites/analyse-git.md)

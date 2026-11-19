## ADDED Requirements

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

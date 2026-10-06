# Spec Delta

## Purpose

Garantit que la voie des refus du plan de contrôle tenant hébergé (authentification, RBAC, règles
de gouvernance) laisse la chaîne d'audit intègre, bornée et vérifiable, et qu'un refus produit
toujours un refus d'autorisation tracé, quelle que soit l'entrée de l'appelant ou l'état de la
persistance.

## ADDED Requirements

### Requirement: Un refus ne corrompt jamais la chaîne d'audit
Le système SHALL authentifier chaque événement d'audit sur les valeurs exactes qui sont
persistées et exportées. Un refus RBAC, quelle que soit la forme de l'identifiant de ressource
fourni (espaces ou caractères de contrôle en tête ou en fin), MUST laisser la chaîne vérifiable
par la lecture suivante du tenant.

#### Scenario: Refus avec un espace en tête dans l'identifiant de membre
- **WHEN** un membre de rôle VIEWER tente d'accorder un rôle à un principal nommé avec un espace en tête
- **THEN** l'appel est refusé comme refus d'autorisation
- **AND** le propriétaire du tenant peut ensuite lire et muter le tenant sans erreur d'authentification de l'audit

#### Scenario: Variantes par tabulation, espace final, révocation et émission de jeton
- **WHEN** un membre sans droit d'écriture sur les membres ou les jetons présente un identifiant de principal à tabulation en tête, à espace final, ou dans une révocation ou une émission de jeton
- **THEN** chaque appel est refusé comme refus d'autorisation
- **AND** la vérification de la chaîne réussit après chacun de ces refus

#### Scenario: Événement non chaîné vérifiable
- **WHEN** un refus est livré en non chaîné avec un identifiant de ressource non canonique
- **THEN** l'authentification de l'événement exporté réussit dans son domaine propre

#### Scenario: Altération toujours détectée
- **WHEN** un événement persisté est modifié après coup (champ ou condensat)
- **THEN** la lecture du tenant échoue en fail-closed avec une erreur d'authentification de l'audit

### Requirement: Les chaînes d'audit existantes restent vérifiables
Le système SHALL conserver le calcul d'authentification des événements dont les champs sont déjà
canoniques : une chaîne produite avant ce changement et dont tous les champs sont canoniques MUST
rester vérifiable, sans migration ni réécriture du fichier chiffré.

#### Scenario: Chaîne antérieure relue
- **WHEN** un tenant produit avant ce changement, sans événement non canonique, est relu
- **THEN** la vérification de la chaîne réussit et les exports déjà émis restent vérifiables

#### Scenario: Chaîne antérieure déjà corrompue
- **WHEN** un tenant contient un événement dont l'authentification a été calculée sur une valeur non canonique
- **THEN** la lecture reste refusée en fail-closed (le système n'affaiblit pas la vérification pour l'accepter)

### Requirement: Le champ de ressource d'un refus est borné
Le système SHALL ramener l'identifiant de ressource d'un refus à une forme bornée (128 caractères
au plus, jeu de caractères sûr) avant de l'auditer. Un identifiant hors de cette forme MUST être
remplacé par une représentation déterministe qui ne contient pas la valeur brute. La taille d'un
événement de refus ne dépend alors plus de l'entrée de l'appelant.

#### Scenario: Identifiant de 5 000 caractères
- **WHEN** un membre sans droit présente un identifiant de principal de 5 000 caractères
- **THEN** l'événement de refus produit porte un identifiant de ressource d'au plus 128 caractères
- **AND** deux appels avec la même valeur produisent la même représentation

#### Scenario: Caractères interdits dans l'identifiant
- **WHEN** l'identifiant contient un caractère nul, un saut de ligne ou une tabulation interne
- **THEN** le refus est audité avec la représentation bornée, sans la valeur brute

#### Scenario: Identifiant sûr conservé
- **WHEN** l'identifiant est déjà sûr (après suppression des espaces de bord)
- **THEN** il est conservé tel quel dans l'événement de refus

### Requirement: Les refus chaînés sont bornés en octets
Le système SHALL ne chaîner un refus que si la taille encodée du tenant après ajout reste
inférieure à la limite d'octets du magasin, en préservant une réserve pour les mutations
autorisées. Au-delà, le refus MUST rester appliqué, journalisé et livré en non chaîné, sans
modifier l'état persistant du tenant.

#### Scenario: Rafale de refus sous une limite d'octets basse
- **WHEN** un membre VIEWER enchaîne des centaines de refus à identifiants longs sur un magasin à limite d'octets basse
- **THEN** chaque appel lève un refus d'autorisation
- **AND** le propriétaire peut ensuite effectuer une mutation autorisée avec succès

#### Scenario: Refus au-delà du budget d'octets
- **WHEN** la taille encodée du tenant ne laisse plus la réserve réservée aux mutations autorisées
- **THEN** le refus suivant est livré en non chaîné et journalisé
- **AND** la version et la chaîne persistantes du tenant ne changent pas

#### Scenario: Magasin sans limite déclarée
- **WHEN** le magasin ne déclare aucune limite d'octets
- **THEN** seul le budget en nombre de refus s'applique, comme avant ce changement

#### Scenario: Rétention après saturation
- **WHEN** des refus ont consommé tout le budget d'octets de refus
- **THEN** la définition et l'application d'une rétention par un propriétaire restent possibles

### Requirement: Tout refus laisse une trace et reste un refus d'autorisation
Le système SHALL toujours lever un refus d'autorisation pour une mutation refusée et laisser une
trace de ce refus, chaînée ou, à défaut, journalisée et livrée en non chaîné. Une panne de
persistance du refus (conflit de version, erreur d'E/S) ou une entrée invalide MUST NOT supprimer
la trace ni changer la nature de l'erreur.

#### Scenario: Refus avec identifiant invalide
- **WHEN** un membre sans droit présente un identifiant de 5 000 caractères pour une révocation
- **THEN** l'appel est refusé comme refus d'autorisation et non comme requête invalide
- **AND** une trace existe (événement chaîné ou événement non chaîné livré au puits d'audit)

#### Scenario: Conflit de version pendant l'écriture du refus
- **WHEN** un écrivain concurrent avance la version du tenant entre le chargement et la sauvegarde du refus
- **THEN** l'appelant reçoit un refus d'autorisation (et non une erreur d'E/S)
- **AND** le refus est journalisé et livré en non chaîné

#### Scenario: Panne d'E/S pendant l'écriture du refus
- **WHEN** la sauvegarde de l'événement de refus échoue pour une raison d'E/S
- **THEN** l'appelant reçoit un refus d'autorisation et le refus est livré en non chaîné

#### Scenario: Code d'erreur côté API publique
- **WHEN** un refus par identifiant invalide ou par échec de persistance est exposé par l'API publique de l'équipe
- **THEN** le code d'erreur est « accès refusé »

### Requirement: Un identifiant invalide d'un appelant autorisé reste une requête invalide
Le système SHALL continuer de valider l'identifiant fourni par un appelant dont les droits sont
suffisants et de le rejeter comme requête invalide, sans créer d'événement de refus. L'autorisation
MUST précéder cette validation.

#### Scenario: Propriétaire avec identifiant invalide
- **WHEN** un propriétaire accorde un rôle à un identifiant de principal invalide
- **THEN** l'appel est rejeté comme requête invalide
- **AND** aucun événement de refus n'est ajouté à la chaîne

#### Scenario: Appelant sans droit avec le même identifiant
- **WHEN** un membre sans droit présente le même identifiant invalide
- **THEN** l'appel est refusé comme refus d'autorisation et tracé

### Requirement: Un jeton à clé retirée est un échec d'authentification
Le système SHALL traiter une mutation présentée avec un jeton signé par une clé qui n'est plus la
clé active du tenant comme un échec d'authentification, identique au comportement des lectures :
aucun événement chaîné MUST être ajouté, aucune action MUST être attribuée à un principal non
authentifié, et la journalisation de ces échecs MUST rester bornée.

#### Scenario: Jeton antérieur à la rotation en mutation
- **WHEN** après une rotation de clé, un détenteur d'un jeton antérieur tente une mutation
- **THEN** l'appel est refusé avec le message « clé inactive » identique à celui d'une lecture
- **AND** aucun événement n'est ajouté à la chaîne et la version du tenant ne change pas

#### Scenario: Jetons forgés avec la clé retirée pour des noms distincts
- **WHEN** quarante jetons signés avec la clé retirée portent quarante noms de principal distincts
- **THEN** aucun événement chaîné n'est ajouté et aucun de ces noms n'apparaît dans la chaîne

#### Scenario: Journalisation bornée
- **WHEN** des jetons à clé retirée sont présentés en rafale
- **THEN** le nombre d'entrées de journal émises par fenêtre de temps reste borné
- **AND** les entrées ne contiennent ni le jeton ni un nom de principal présenté comme authentifié

#### Scenario: Membre légitime à clé active
- **WHEN** un membre sans droit présente un jeton signé par la clé active
- **THEN** le refus RBAC reste chaîné et audité comme avant ce changement

#### Scenario: Clé retirée toujours disponible pour l'historique
- **WHEN** le tenant a effectué une rotation
- **THEN** les événements d'audit antérieurs signés avec la clé retirée restent vérifiables

# Spec Delta

## ADDED Requirements

### Requirement: Un chargement de vue non mise en cache réserve son poids avant décodage
Avant de lire ou de décoder un snapshot dont la vue n'est pas en cache, chaque store SHALL réserver le poids estimé de cette vue sur un budget de mémoire des vues commun à tout le processus. Si la réservation ne tient pas, le chargement SHALL attendre au plus la durée d'attente bornée, puis échouer par une erreur d'E/S explicite. Un dépassement du budget SHALL NOT se manifester par un `OutOfMemoryError`.

#### Scenario: Second chargement qui attend la fin du premier
- **GIVEN** un budget qui contient une vue du projet A mais pas les vues de A et de B ensemble, et la vue de A en cours de chargement
- **WHEN** la vue active du projet B est demandée et que le chargement de A se termine avant la fin de l'attente bornée
- **THEN** le chargement de B attend, puis réussit
- **AND** la somme des réservations et des vues en cache n'a jamais dépassé le budget

#### Scenario: Attente bornée expirée
- **GIVEN** la vue du projet A en cours de chargement et un budget qui ne contient pas aussi celle du projet B
- **WHEN** la vue de B est demandée et que l'attente bornée expire avant la fin du chargement de A
- **THEN** la lecture de B échoue par une erreur d'E/S dont le message commence par « insufficient memory to load this snapshot »
- **AND** aucun octet du snapshot de B n'a été décodé et le chargement de A se termine normalement

#### Scenario: Deux chargements simultanés dans une JVM au tas réduit
- **GIVEN** une JVM fille lancée avec un `-Xmx` réduit, où deux vues décodées en même temps dépassent le tas mais où une seule tient dans le budget
- **WHEN** les vues actives de deux projets sont demandées en même temps
- **THEN** l'une est chargée et l'autre attend puis réussit, ou échoue par l'erreur explicite de mémoire insuffisante
- **AND** la JVM fille se termine avec le code 0, sans `OutOfMemoryError`

### Requirement: Une vue plus lourde que le budget de mémoire des vues est refusée à l'admission
Un store SHALL refuser sans attendre, et avant toute lecture de la charge utile, le chargement d'une vue dont le poids estimé dépasse le budget de mémoire des vues. Le message SHALL commencer par « insufficient memory to load this snapshot », donner le poids estimé, le budget et le tas maximal en octets, et nommer le réglage à changer, sans chemin.

#### Scenario: Vue plus lourde que le budget
- **GIVEN** un snapshot actif dont le poids estimé de la vue dépasse le budget de mémoire des vues
- **WHEN** sa vue active est demandée, par le store fichier puis par le store PostgreSQL
- **THEN** la lecture échoue aussitôt par une erreur d'E/S dont le message nomme `MINOS_VIEW_HEAP_PERCENT` et le tas maximal
- **AND** aucune réservation n'est retenue, aucun fichier temporaire n'est créé et le message ne contient aucun chemin absolu

### Requirement: Le plafond effectif du cache de vues dérive du tas maximal
Le budget de mémoire des vues SHALL valoir la fraction réglée du tas maximal de la JVM. Le plafond effectif du cache de vues SHALL valoir le plus petit du plafond configuré et de ce budget. Les vues en cache et les chargements en cours SHALL tenir ensemble sous le budget ; une vue en cache SHALL pouvoir être évincée pour admettre un chargement.

#### Scenario: Petit tas
- **GIVEN** une JVM dont la fraction réglée du tas maximal est inférieure au plafond configuré du cache
- **WHEN** un store est ouvert
- **THEN** le plafond effectif rapporté par ses statistiques de cache est égal à cette fraction du tas maximal

#### Scenario: Grand tas
- **GIVEN** une JVM dont la fraction réglée du tas maximal dépasse le plafond configuré du cache
- **WHEN** un store est ouvert
- **THEN** le plafond effectif rapporté est le plafond configuré

#### Scenario: Éviction pour admettre un chargement
- **GIVEN** la vue du projet A en cache et un budget qui ne contient pas aussi la vue du projet B
- **WHEN** la vue de B est demandée et qu'aucun autre chargement n'est en cours
- **THEN** la vue de A est retirée du cache et la vue de B est chargée sans attendre l'expiration du délai

### Requirement: Le budget de mémoire des vues est réglable et une valeur invalide est refusée
La fraction du tas réservée aux vues SHALL se lire dans la propriété système `minos.storage.viewHeapPercent`, puis dans la variable `MINOS_VIEW_HEAP_PERCENT`, et valoir 50 par défaut. Une valeur qui n'est pas un entier de 10 à 80 SHALL être refusée au premier chargement de vue par une erreur qui nomme le réglage et la plage, sans repli silencieux sur la valeur par défaut.

#### Scenario: Valeur réglée
- **GIVEN** la propriété système `minos.storage.viewHeapPercent` fixée à 30
- **WHEN** le budget de mémoire des vues est résolu
- **THEN** il vaut 30 % du tas maximal, arrondi à l'octet inférieur

#### Scenario: Valeur invalide
- **GIVEN** la variable `MINOS_VIEW_HEAP_PERCENT` fixée à `95`, ou à `abc`
- **WHEN** une vue active est demandée
- **THEN** la lecture échoue par une erreur qui nomme `MINOS_VIEW_HEAP_PERCENT` et la plage de 10 à 80
- **AND** aucun snapshot n'est lu

### Requirement: PostgreSQL lit la taille d'un snapshot avant de rapatrier sa charge utile
Le store PostgreSQL SHALL lire la taille en octets et l'en-tête de format de la charge utile active avec ses métadonnées, avant de demander la charge utile. Une charge utile plus grande que le plafond de persistance SHALL être refusée sans être demandée au serveur, par une erreur sans chemin. Le transitoire du pilote pendant le rapatriement SHALL être compté dans la réservation de la vue.

#### Scenario: Ligne au-delà du plafond
- **GIVEN** une ligne active de `knowledge_snapshots` dont la charge utile dépasse le plafond de persistance du store, insérée directement en base
- **WHEN** la vue active du projet est demandée
- **THEN** la lecture échoue par une erreur d'E/S dont le message commence par « snapshot payload exceeds persisted byte limit »
- **AND** aucune requête ne sélectionne la colonne `payload` entière et aucun fichier temporaire n'est créé

#### Scenario: Réservation qui couvre le rapatriement
- **GIVEN** une charge utile dont le transitoire de rapatriement estimé dépasse le budget alors que le poids de la vue le respecte
- **WHEN** la vue active est demandée
- **THEN** la lecture est refusée à l'admission par l'erreur de mémoire insuffisante, avant toute demande de la charge utile

### Requirement: Un refus de mémoire est rendu par la CLI et le MCP avec son message
Une erreur de mémoire insuffisante d'un chargement de vue SHALL être rendue sur la CLI avec son message et le code de sortie d'erreur d'exécution existant, et par un outil MCP avec le même message au lieu de l'erreur générique. Le rendu SHALL rester additif : aucun code de sortie nouveau, aucun champ retiré, goldens inchangés.

#### Scenario: Outil MCP refusé faute de mémoire
- **GIVEN** un backend MCP dont la lecture de vue échoue par l'erreur de mémoire insuffisante
- **WHEN** un outil de requête est appelé
- **THEN** le résultat est une erreur d'outil dont le texte commence par « error: insufficient memory to load this snapshot »

#### Scenario: Commande CLI refusée faute de mémoire
- **GIVEN** une application dont la lecture de vue échoue par l'erreur de mémoire insuffisante
- **WHEN** une commande de requête de la CLI s'exécute
- **THEN** elle écrit le message sur la sortie d'erreur et se termine avec le code 1
- **AND** les goldens de caractérisation restent identiques à l'octet

## MODIFIED Requirements

### Requirement: Le plafond du cache de vues couvre tout snapshot persistable
Le plafond configuré du cache de vues de requête SHALL être au moins égal au plafond de persistance multiplié par l'amplification estimée, de sorte qu'un snapshot persistable ne soit jamais refusé par le cache configuré pour son seul poids estimé. Le plafond effectif, dérivé du tas, SHALL NOT être soumis à cet invariant : sous un petit tas, une vue plus lourde que le budget est refusée à l'admission.

#### Scenario: Invariant respecté
- **GIVEN** le plafond de persistance, l'amplification estimée et le plafond du cache de chacun des deux stores
- **WHEN** le test d'invariant les compare
- **THEN** le plafond du cache est supérieur ou égal au produit du plafond de persistance par l'amplification

#### Scenario: Plafond de cache réduit sous l'invariant
- **GIVEN** un plafond de cache inférieur au produit du plafond de persistance par l'amplification
- **WHEN** le test d'invariant s'exécute
- **THEN** il échoue en nommant les trois valeurs

#### Scenario: Plafond effectif sous l'invariant dans un petit tas
- **GIVEN** une JVM dont le budget de mémoire des vues est inférieur au produit du plafond de persistance par l'amplification
- **WHEN** un store est ouvert
- **THEN** l'ouverture réussit et le plafond effectif rapporté est égal au budget

### Requirement: Un snapshot de plus de 256 Mio est persisté jusqu'au plafond de persistance
Les deux stores de snapshots SHALL publier un snapshot de connaissance dont l'encodage dépasse 256 Mio sans dépasser le plafond de persistance, et SHALL le relire avec la même garantie d'intégrité qu'un petit snapshot dès que le budget de mémoire des vues couvre sa réservation.

#### Scenario: Snapshot de plus de 256 Mio sous le plafond
- **GIVEN** un snapshot de connaissance dont l'encodage dépasse 256 Mio et ne dépasse pas le plafond de persistance
- **WHEN** il est publié par le store fichier, puis par le store PostgreSQL
- **THEN** le snapshot est publié et le pointeur actif le désigne
- **AND** sa relecture est authentifiée par l'empreinte et rend un modèle égal à l'original

#### Scenario: Relecture d'un snapshot de plus de 256 Mio
- **GIVEN** un snapshot de plus de 256 Mio publié sous le plafond de persistance, dans une JVM dont le budget de mémoire des vues couvre sa réservation
- **WHEN** la vue active est lue
- **THEN** la lecture réussit sans erreur de taille

### Requirement: Un snapshot de la taille de ce dépôt est admis au cache de vues
Les deux stores de snapshots SHALL estimer le poids d'une vue de requête d'après une amplification mesurée de la taille persistée. Un snapshot au format courant dont la vue tient sous le plafond effectif du cache SHALL être mis en cache : deux lectures consécutives de la vue active SHALL ne provoquer qu'un seul chargement complet.

#### Scenario: Snapshot de plus de 64 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 64 Mio persistés, dont la vue pèse moins que le plafond effectif du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1

#### Scenario: Snapshot de plus de 300 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 300 Mio persistés, dont le poids estimé est inférieur au plafond effectif du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1
- **AND** la seconde lecture est servie sans relire le fichier

#### Scenario: Vue plus lourde que le plafond
- **GIVEN** un snapshot dont la vue estimée dépasse le plafond effectif du cache sans dépasser le budget de mémoire des vues
- **WHEN** la vue active est lue deux fois de suite
- **THEN** elle n'est pas mise en cache et le compteur de chargements complets vaut 2

## ADDED Requirements

### Requirement: Le nom d'une opération bornée dit son comportement au dépassement
Dans le code de production, un helper d'addition ou de multiplication bornée SHALL porter un nom qui dit s'il sature ou s'il lève une exception au dépassement. Les noms `safeAdd` et `safeMultiply` SHALL NOT être déclarés, et `checkedAdd` SHALL NOT désigner un helper qui ne lève pas. Un gate exécuté par le job des invariants SHALL échouer sur une telle déclaration en nommant le fichier et la ligne.

#### Scenario: Retour d'un nom ambigu
- **GIVEN** une copie temporaire du dépôt où un fichier de `src/main` déclare `private static long safeAdd(long left, long right)`
- **WHEN** `python scripts/quality/check-bounded-helpers.py --self-test` construit cette copie et y exécute le gate
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: checkedAdd qui sature
- **GIVEN** une copie temporaire où une méthode `checkedAdd` rend `Long.MAX_VALUE` au dépassement sans lever d'exception
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier et la ligne

#### Scenario: checkedAdd qui lève
- **GIVEN** une copie temporaire où une méthode `checkedAdd` lève une exception au dépassement
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il réussit

#### Scenario: Dépôt réel après renommage
- **GIVEN** le dépôt après le lot de renommage
- **WHEN** `python scripts/quality/check-bounded-helpers.py` est exécuté
- **THEN** il réussit et affiche le nombre de helpers contrôlés

### Requirement: Un cache LRU pondéré n'est écrit qu'une fois
Tout cache à ordre d'accès borné en poids du code de production SHALL utiliser le cache partagé. Un gate SHALL échouer sur une carte à ordre d'accès construite hors du cache partagé, sauf pour les fichiers d'une liste fermée dont la raison est écrite, et SHALL échouer sur une entrée de liste devenue sans objet. La migration SHALL laisser inchangés les noms et les valeurs des statistiques publiques de chaque cache.

#### Scenario: Cache réécrit à la main
- **GIVEN** une copie temporaire du dépôt où un fichier de `src/main` hors liste construit `new LinkedHashMap<>(16, 0.75f, true)`
- **WHEN** `python scripts/quality/check-bounded-helpers.py --self-test` y exécute le gate
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: Exception déclarée
- **GIVEN** un fichier de la liste fermée, avec sa raison
- **WHEN** le gate est exécuté
- **THEN** ce fichier est accepté et la liste est affichée dans la sortie

#### Scenario: Entrée de liste périmée
- **GIVEN** une liste fermée qui nomme un fichier absent ou sans carte à ordre d'accès
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Statistiques inchangées après migration
- **GIVEN** les tests existants des caches migrés, sans modification de leurs assertions
- **WHEN** `./mvnw -B -ntp -pl minos-application,minos-storage-local,minos-bootstrap -am test` est exécuté
- **THEN** ils réussissent

### Requirement: Le cache partagé tient ses bornes et son poids
Le cache partagé SHALL ne jamais dépasser sa borne en entrées ni son plafond de poids, SHALL évincer d'abord les entrées les plus anciennement lues, SHALL retrancher le poids d'une entrée à chaque retrait, remplacement, invalidation ou éviction, et SHALL rester cohérent sous accès concurrent. Une entrée plus lourde que le plafond SHALL NOT être conservée ni provoquer d'éviction.

#### Scenario: Éviction par le poids
- **GIVEN** un cache dont le plafond de poids est atteint
- **WHEN** une entrée de plus est insérée et que les tests du cache partagé sont exécutés (`./mvnw -B -ntp -pl minos-engine -am test`)
- **THEN** les entrées les plus anciennement lues sont évincées et le poids total est inférieur ou égal au plafond

#### Scenario: Poids après retraits
- **GIVEN** un cache rempli puis vidé entrée par entrée, remplacements et invalidations par prédicat compris
- **WHEN** son poids total est lu
- **THEN** il vaut zéro

#### Scenario: Entrée plus lourde que le plafond
- **GIVEN** une entrée dont le poids dépasse le plafond du cache
- **WHEN** elle est insérée
- **THEN** elle n'est pas conservée, les entrées des autres clés restent et le poids total ne croît pas

#### Scenario: Accès concurrents
- **GIVEN** plusieurs fils synchronisés par une barrière qui lisent et insèrent en même temps
- **WHEN** ils ont terminé
- **THEN** le poids total égale la somme des poids des entrées présentes et aucune borne n'est dépassée

### Requirement: Le cache partagé refuse un poids ou une borne invalide
Le cache partagé SHALL refuser, par une exception d'argument et sans modifier son contenu, l'insertion d'une entrée de poids négatif. Il SHALL refuser à la construction une borne en entrées inférieure à un et un plafond de poids nul, négatif ou supérieur à la moitié de la plus grande valeur d'un entier long, afin que la somme des poids ne puisse pas déborder.

#### Scenario: Poids négatif
- **GIVEN** un cache contenant des entrées
- **WHEN** une entrée de poids négatif est insérée
- **THEN** une exception d'argument est levée, et le contenu et le poids total du cache sont inchangés

#### Scenario: Bornes invalides
- **GIVEN** une borne en entrées nulle, ou un plafond de poids nul, négatif ou supérieur à la moitié de `Long.MAX_VALUE`
- **WHEN** le cache est construit dans les tests de son module (`./mvnw -B -ntp -pl minos-engine -am test`)
- **THEN** la construction échoue par une exception d'argument

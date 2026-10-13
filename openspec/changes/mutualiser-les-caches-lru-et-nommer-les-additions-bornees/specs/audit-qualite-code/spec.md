## ADDED Requirements

### Requirement: Le nom d'une addition bornée dit son comportement au dépassement
Dans le code de production, un helper d'addition bornée SHALL porter un nom qui dit s'il sature ou s'il lève une exception au dépassement. Le nom `safeAdd` SHALL NOT être déclaré, et `checkedAdd` SHALL NOT désigner un helper qui sature. Un gate exécuté par le job des invariants SHALL échouer sur une telle déclaration.

#### Scenario: Retour d'un nom ambigu
- **GIVEN** une copie du dépôt où un fichier de `src/main` déclare `private static long safeAdd(long left, long right)`
- **WHEN** `python scripts/quality/check-bounded-helpers.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: Dépôt réel après renommage
- **GIVEN** le dépôt après le lot de renommage
- **WHEN** le gate est exécuté
- **THEN** il réussit et affiche le nombre de helpers contrôlés

### Requirement: Un cache LRU pondéré n'est écrit qu'une fois
Tout cache à ordre d'accès borné en poids du code de production SHALL utiliser la classe de cache partagée. Un gate SHALL échouer sur une carte à ordre d'accès construite hors de cette classe, sauf pour les fichiers d'une liste fermée dont la raison est écrite, et SHALL échouer sur une entrée de liste devenue sans objet.

#### Scenario: Cache réécrit à la main
- **GIVEN** une copie du dépôt où un fichier de `src/main` hors liste construit `new LinkedHashMap<>(16, 0.75f, true)`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier et la ligne

#### Scenario: Exception déclarée
- **GIVEN** un fichier de la liste fermée, avec sa raison
- **WHEN** le gate est exécuté
- **THEN** ce fichier est accepté et la liste est affichée dans la sortie

#### Scenario: Entrée de liste périmée
- **GIVEN** une liste fermée qui nomme un fichier absent ou sans carte à ordre d'accès
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

### Requirement: Le cache partagé tient ses bornes et son poids
Le cache partagé SHALL ne jamais dépasser sa borne en entrées ni son plafond de poids, SHALL retrancher le poids d'une entrée à chaque retrait, remplacement ou éviction, et SHALL rester cohérent sous accès concurrent. Une entrée plus lourde que le plafond SHALL NOT être mise en cache.

#### Scenario: Éviction par le poids
- **GIVEN** un cache dont le plafond de poids est atteint
- **WHEN** une entrée de plus est insérée
- **THEN** les entrées les plus anciennement lues sont évincées et le poids total est inférieur ou égal au plafond

#### Scenario: Poids après retraits
- **GIVEN** un cache rempli puis vidé entrée par entrée, remplacements compris
- **WHEN** son poids total est lu
- **THEN** il vaut zéro

#### Scenario: Entrée plus lourde que le plafond
- **GIVEN** une entrée dont le poids dépasse le plafond du cache
- **WHEN** elle est insérée
- **THEN** elle n'est pas conservée, les entrées déjà présentes restent et le poids total est inchangé

#### Scenario: Accès concurrents
- **GIVEN** plusieurs fils qui lisent et insèrent en même temps
- **WHEN** ils ont terminé
- **THEN** le poids total égale la somme des poids des entrées présentes et aucune borne n'est dépassée

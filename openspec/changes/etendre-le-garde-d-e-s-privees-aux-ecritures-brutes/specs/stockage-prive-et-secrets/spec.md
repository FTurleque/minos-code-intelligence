## ADDED Requirements

### Requirement: Une écriture brute nouvelle hors des primitives est refusée par le gate
Le gate `check-private-io.py` SHALL refuser, dans le code de production du reactor et hors des primitives d'E/S, tout appel, référence de méthode ou import statique de `Files.newOutputStream`, `newBufferedWriter`, `newByteChannel`, `createFile`, `createDirectory`, `createTempFile`, `createTempDirectory`, `copy` ou `move`, et toute redirection de la sortie d'un processus vers un fichier, sauf occurrence inscrite nominativement dans la liste de tolérance.

#### Scenario: Appel brut ajouté dans un fichier non listé
- **GIVEN** un arbre où un fichier de `src/main/java` hors primitives appelle `Files.newOutputStream`, sans entrée de liste
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté sur cet arbre
- **THEN** le gate échoue et nomme le fichier et l'API

#### Scenario: Chaque API nouvelle est refusée seule
- **GIVEN** l'auto-test du gate, avec un témoin par règle
- **WHEN** `python scripts/architecture/test_check_private_io.py -v` est exécuté
- **THEN** chaque témoin produit exactement une violation qui nomme sa règle, y compris par référence de méthode et par import statique

#### Scenario: Redirection d'un processus vers un fichier
- **GIVEN** un fichier de production qui redirige la sortie d'un processus vers un fichier
- **WHEN** le gate est exécuté
- **THEN** il échoue ; la même source redirigée vers `Redirect.DISCARD` passe

#### Scenario: Appels permis non signalés
- **GIVEN** un fichier de production qui n'appelle que `Files.readAllBytes` et les primitives d'E/S
- **WHEN** le gate est exécuté
- **THEN** il réussit

### Requirement: La liste de tolérance des écritures brutes ne fait que se resserrer
Chaque occurrence existante d'une API refusée SHALL être inscrite par une entrée nominative (fichier, API, maximum, justification écrite). Le gate SHALL échouer quand un fichier listé compte une occurrence de plus que son maximum, quand un maximum dépasse le décompte réel, et quand la liste est illisible ou contient un dossier, un joker ou une justification vide : il SHALL NOT se replier sur une liste vide ou ignorée.

#### Scenario: Une occurrence de plus dans un fichier listé
- **GIVEN** un fichier listé pour deux `Files.move` qui en contient trois
- **WHEN** le gate est exécuté
- **THEN** il échoue et cite le maximum de l'entrée

#### Scenario: Entrée périmée après migration
- **GIVEN** une entrée dont le fichier ne contient plus l'API listée
- **WHEN** le gate est exécuté
- **THEN** il échoue et demande de retirer ou d'abaisser l'entrée

#### Scenario: Liste illisible
- **GIVEN** une liste de tolérance qui n'est pas un tableau JSON d'entrées complètes
- **WHEN** le gate est exécuté
- **THEN** il échoue sans examiner les sources comme si la liste était vide

#### Scenario: Dépôt réel
- **GIVEN** le dépôt après ce changement, sans aucun fichier Java modifié
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et son décompte de règles a augmenté de dix

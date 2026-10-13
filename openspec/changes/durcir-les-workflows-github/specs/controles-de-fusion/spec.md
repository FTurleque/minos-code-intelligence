## ADDED Requirements

### Requirement: Le jeton du dépôt n'est pas conservé après le checkout
Chaque étape `actions/checkout` d'un workflow SHALL déclarer `persist-credentials: false`. Le gate des workflows SHALL échouer sur un checkout qui ne le déclare pas.

#### Scenario: Checkout sans l'option
- **GIVEN** une copie du dépôt où un workflow contient un `actions/checkout` sans `persist-credentials: false`
- **WHEN** `python scripts/quality/check-workflow-pins.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt
- **WHEN** le gate est exécuté
- **THEN** il réussit et compte les checkouts contrôlés

### Requirement: Chaque job a un délai maximal
Tout job qui s'exécute sur un runner SHALL déclarer `timeout-minutes`. Un job qui appelle un workflow réutilisable est la seule exception. Le gate SHALL échouer sur un job à `runs-on` sans délai.

#### Scenario: Job sans délai
- **GIVEN** une copie du dépôt où un job à `runs-on` n'a pas de `timeout-minutes`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le workflow et le job

#### Scenario: Appel de workflow réutilisable
- **GIVEN** un job défini par `uses:` sans `runs-on`
- **WHEN** le gate est exécuté
- **THEN** ce job n'est pas signalé

### Requirement: Le runner et l'outil de build d'un workflow sont épinglés
Aucun job SHALL NOT s'exécuter sur une image de runner flottante (`*-latest`). Un workflow qui construit le plugin IntelliJ SHALL utiliser le wrapper Gradle du dépôt et SHALL NOT installer une version de Gradle par `gradle-version:`.

#### Scenario: Runner flottant
- **GIVEN** une copie du dépôt où un job déclare `runs-on: ubuntu-latest`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le job

#### Scenario: Gradle installé hors wrapper
- **GIVEN** une copie du dépôt où un workflow déclare `gradle-version:`
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier et la ligne

### Requirement: Les identifiants de registre ne restent pas exposés au code d'une pull request sans nécessité
Dans un job qui se connecte à un registre d'images, une étape `run:` postérieure à la connexion SHALL être précédée d'une déconnexion, sauf pour les jobs d'une liste fermée dont la raison est écrite. Le jeton utilisé SHALL être en lecture seule et sa portée SHALL être documentée.

#### Scenario: Script exécuté sans déconnexion
- **GIVEN** une copie du dépôt où un job hors de la liste fermée exécute un script après `docker/login-action` sans `docker logout`
- **WHEN** le gate des workflows est exécuté sur cette copie
- **THEN** il échoue en nommant le job

#### Scenario: Job de la liste fermée
- **GIVEN** un job de la liste fermée, avec sa raison
- **WHEN** le gate est exécuté
- **THEN** ce job est accepté et la liste est affichée dans la sortie

#### Scenario: Entrée de liste sans objet
- **GIVEN** une liste fermée qui nomme un job qui ne se connecte plus à un registre
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

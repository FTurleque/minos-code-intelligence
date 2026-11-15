## ADDED Requirements

### Requirement: Un secret n'accompagne pas la compilation du code d'une pull request
Dans un workflow déclenché par `pull_request`, une étape `run:` dont l'`env:` d'étape référence un secret SHALL NOT exécuter le wrapper Maven ni un script du dépôt, sauf entrée nominative d'une liste fermée du gate, avec sa raison ; une telle étape SHALL NOT lancer de phase du cycle de vie Maven. Un `env:` de job ou de workflow SHALL NOT référencer de secret autre que `DOCKERHUB_USERNAME`. Une entrée de liste périmée SHALL faire échouer le gate.

#### Scenario: Compilation avec le jeton d'analyse
- **GIVEN** une copie du dépôt où une étape de `pr-ci.yml` déclare `SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` et exécute `./mvnw … test-compile`
- **WHEN** `python scripts/quality/check-workflow-pins.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le workflow, le job et l'étape

#### Scenario: Étape hors liste qui reçoit un secret et lance un script du dépôt
- **GIVEN** une copie du dépôt où une étape hors de la liste fermée reçoit un secret et exécute un script de `scripts/`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant l'étape

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt, où la compilation du candidat et l'analyse sont deux étapes et où seule l'analyse reçoit le jeton
- **WHEN** le gate est exécuté
- **THEN** il réussit et affiche la liste fermée avec ses raisons

#### Scenario: Entrée de liste sans objet
- **GIVEN** une liste fermée qui nomme une étape qui ne reçoit plus de secret ou n'existe plus
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Secret remonté au niveau du job
- **GIVEN** une copie du dépôt où `SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` est déclaré dans l'`env:` du job `sonar` ou du workflow `pr-ci.yml`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le workflow, le niveau et la variable

#### Scenario: Identifiant Docker Hub au niveau du job
- **GIVEN** les jobs du dépôt qui déclarent `DOCKERHUB_USERNAME` dans leur `env:` de job
- **WHEN** le gate est exécuté
- **THEN** cette déclaration n'est pas signalée par cette règle

#### Scenario: Workflow sans déclencheur pull_request
- **GIVEN** un workflow lancé seulement à la demande ou par planification, dont une étape reçoit un secret et exécute le wrapper Maven
- **WHEN** le gate est exécuté
- **THEN** cette étape n'est pas signalée par cette règle

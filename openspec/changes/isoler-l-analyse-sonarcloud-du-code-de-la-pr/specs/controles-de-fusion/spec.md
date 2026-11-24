## MODIFIED Requirements

### Requirement: Un secret n'accompagne pas la compilation du code d'une pull request
Dans un workflow déclenché par `pull_request`, un job dont une étape reçoit un secret par son `env:` d'étape ou par le `with:` d'une action autre que `docker/login-action` SHALL NOT contenir d'étape qui exécute le wrapper Maven, un script de `scripts/` ou une action locale, quelle que soit l'étape ; aucune liste d'exception n'est admise. Un `env:` de job ou de workflow SHALL NOT référencer de secret autre que `DOCKERHUB_USERNAME`.

#### Scenario: Compilation avec le jeton d'analyse
- **GIVEN** une copie du dépôt où une étape de `pr-ci.yml` déclare `SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` et exécute `./mvnw … test-compile`
- **WHEN** `python scripts/quality/check-workflow-pins.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le workflow, le job et l'étape

#### Scenario: Étape hors liste qui reçoit un secret et lance un script du dépôt
- **GIVEN** une copie du dépôt où une étape reçoit un secret et exécute un script de `scripts/`, le gate n'ayant plus aucune liste d'exception
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant l'étape

#### Scenario: Entrée de liste sans objet
- **GIVEN** le gate du dépôt, dont la liste d'exception `SECRET_WITH_REPOSITORY_CODE_KEPT` est supprimée
- **WHEN** `python scripts/quality/test_check_workflow_pins.py` est exécuté
- **THEN** il réussit et affirme qu'aucune table d'exception pour un secret et du code du dépôt n'est déclarée par le gate

#### Scenario: Identifiant Docker Hub au niveau du job
- **GIVEN** les jobs du dépôt qui déclarent `DOCKERHUB_USERNAME` dans leur `env:` de job
- **WHEN** le gate est exécuté
- **THEN** cette déclaration n'est pas signalée par cette règle

#### Scenario: Job à secret qui compile dans une autre étape
- **GIVEN** une copie du dépôt où le job `sonar` de `pr-ci.yml` lance `./mvnw … install` dans une étape sans secret, puis donne `SONAR_TOKEN` à une étape suivante qui n'exécute aucun code du dépôt
- **WHEN** `python scripts/quality/check-workflow-pins.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le workflow, le job, l'étape qui reçoit le secret et celle qui exécute le code du dépôt

#### Scenario: Job à secret qui lance un script ou une action locale
- **GIVEN** une copie du dépôt où un job d'un workflow `pull_request` donne un secret à une étape et lance, dans une autre étape, un script de `scripts/` ou une action `uses: ./…`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le job et l'étape fautive

#### Scenario: Secret passé par with
- **GIVEN** une copie du dépôt où un job d'un workflow `pull_request` passe `${{ secrets.SONAR_TOKEN }}` dans le `with:` d'une action et lance `./mvnw` dans une autre étape
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le job

#### Scenario: Connexion à un registre
- **GIVEN** le job `verify` du dépôt, qui passe `secrets.DOCKERHUB_TOKEN` à `docker/login-action`, se déconnecte, puis lance `./mvnw`
- **WHEN** le gate est exécuté
- **THEN** ce job n'est pas signalé par cette règle

#### Scenario: Secret remonté au niveau du job
- **GIVEN** une copie du dépôt où `SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}` est déclaré dans l'`env:` du job `sonar` ou du workflow `pr-ci.yml`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le workflow, le niveau et la variable

#### Scenario: Workflow sans déclencheur pull_request
- **GIVEN** un workflow lancé seulement à la demande ou par planification, dont un job donne un secret à une étape et lance le wrapper Maven
- **WHEN** le gate est exécuté
- **THEN** ce job n'est pas signalé par cette règle

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt, où le job `sonar` ne lance que le scanner sur les classes et rapports transmis par `Verify`
- **WHEN** le gate est exécuté
- **THEN** il réussit sans liste d'exception

## ADDED Requirements

### Requirement: L'analyse d'une pull request ne lit aucune configuration du scanner venue de la pull request
Dans un workflow déclenché par `pull_request`, un job qui donne `SONAR_TOKEN` à une étape SHALL écrire dans le workflow l'hôte, l'organisation et le projet de l'analyse, et SHALL échouer avant l'analyse si l'arbre extrait contient un fichier `sonar-project.properties`, à toute profondeur. Le gate des workflows SHALL refuser un tel job sans ce contrôle ou sans ces paramètres.

#### Scenario: Job d'analyse sans contrôle du fichier de configuration
- **GIVEN** une copie du dépôt où le job `sonar` de `pr-ci.yml` n'a plus d'étape qui refuse un `sonar-project.properties` avant l'analyse
- **WHEN** `python scripts/quality/check-workflow-pins.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le workflow et le job

#### Scenario: Hôte de l'analyse absent du workflow
- **GIVEN** une copie du dépôt où l'étape d'analyse ne fixe plus `sonar.host.url`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le paramètre manquant

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt
- **WHEN** le gate est exécuté
- **THEN** il réussit et le job `sonar` porte le contrôle et les trois paramètres

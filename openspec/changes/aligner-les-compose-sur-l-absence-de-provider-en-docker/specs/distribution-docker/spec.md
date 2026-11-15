## ADDED Requirements

### Requirement: Les fichiers de distribution Docker ne présentent pas le plan d'administration comme lançant des providers
Les fichiers compose et le Dockerfile de release SHALL décrire le service `minos-admin` par ce qu'il exécute : administration, inventaire et vérification des outils, indexation refusée faute de bac à sable OS. Ils SHALL NOT affirmer que ce service lance un provider pour indexer. Le test de contrat Docker SHALL échouer sur une telle affirmation dans le bloc du service `minos-admin`. Le service de sonde des outils n'est pas concerné.

#### Scenario: Commentaire qui annonce le lancement des providers
- **GIVEN** une copie d'un fichier compose où le commentaire de `minos-admin` dit que l'indexation lance les providers
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=M29DockerAdministrationContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté sur cette copie
- **THEN** le test échoue en nommant le fichier et la ligne

#### Scenario: Fichiers réels
- **GIVEN** les deux fichiers compose et le Dockerfile de release du dépôt après le changement
- **WHEN** le même test est exécuté
- **THEN** il réussit
- **AND** le commentaire de `minos-admin` dit que l'indexation y est refusée

#### Scenario: Service de sonde des outils
- **GIVEN** les fichiers compose du dépôt, où le service `minos-provider-probe` est décrit comme sondant `javac`, `mvn`, les indexeurs et les toolchains
- **WHEN** le même test est exécuté
- **THEN** ce commentaire n'est pas signalé

#### Scenario: Le runtime confirme ce que disent les fichiers
- **GIVEN** une installation Docker de qualification
- **WHEN** `scripts/ci/qualify-docker-upgrade.ps1` lance `index` dans le plan d'administration et lit l'état des providers
- **THEN** l'indexation est refusée avant tout processus fils et scip-java est déclaré `UNSUPPORTED_BY_BACKEND`

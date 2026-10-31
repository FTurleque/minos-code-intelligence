## ADDED Requirements

### Requirement: Le build Maven analysé par scip-java s'exécute hors ligne
Sous Linux, le plan scip-java SHALL déclarer `MAVEN_ARGS` avec `--offline`, un dépôt de tête `-Dmaven.repo.local` sous le dossier du run et, si le dépôt de l'utilisateur existe, `-Dmaven.repo.local.tail` vers sa racine réelle, déclarée en lecture seule. La politique réseau du plan MUST rester `DENY`. Un artefact absent fait échouer le build.

#### Scenario: Plan avec dépôt présent
- **GIVEN** sous Linux, un projet Maven et un dépôt local `<tmp>/m2/repository` désigné par la configuration
- **WHEN** le plan scip-java est construit
- **THEN** `MAVEN_ARGS` contient `--offline`, un `maven.repo.local` sous le run et `maven.repo.local.tail` vers ce dépôt, déclaré en lecture seule, et la politique réseau vaut `DENY`

#### Scenario: Plan sans dépôt
- **GIVEN** sous Linux, un projet Maven et un dépôt local désigné qui n'existe pas
- **WHEN** le plan scip-java est construit
- **THEN** `MAVEN_ARGS` contient `--offline` et aucune `maven.repo.local.tail`, et aucune racine n'est déclarée

#### Scenario: Indexation hors ligne de bout en bout (manuelle, Linux)
- **GIVEN** sous Linux avec bac à sable qualifié, `fixtures/java/java-simple` déjà construit une fois hors de MINOS
- **WHEN** `minos index` est lancé
- **THEN** l'index est produit sans sortie réseau et le dépôt de l'utilisateur est inchangé

### Requirement: MINOS ne lit ni ne transmet la configuration Maven de l'utilisateur
Le dépôt de l'utilisateur SHALL être la clé de configuration MINOS de son emplacement, sinon `<user.home>/.m2/repository`. MINOS MUST NOT lire `settings.xml`, ni monter ou accorder `~/.m2`, `settings.xml`, `settings-security.xml` ou `toolchains.xml`, ni transmettre `HOME` ou la clé au provider sous Linux.

#### Scenario: settings.xml jamais exposé
- **GIVEN** sous Linux, `<home>/.m2/settings.xml` contenant un mot de passe et `<home>/.m2/repository`
- **WHEN** le plan scip-java puis le plan du bac à sable sont construits
- **THEN** aucune commande ni variable ne cite `settings.xml`, `<home>/.m2` seul ou le mot de passe, et l'environnement du provider n'a ni `HOME` ni la clé

### Requirement: Sous Linux, scip-java n'est prêt que s'il peut construire hors ligne
Sous Linux, l'état de scip-java SHALL être `BLOCKED`, avec un diagnostic `machine prerequisite (not shipped by MINOS)` actionnable, quand aucun `mvn` de version ≥ 3.9.0 n'est trouvé dans le `PATH` sous une racine montée par le bac à sable, ou quand le dépôt local désigné n'existe pas. La version MUST être lue sur disque, sans exécuter Maven.

#### Scenario: Maven trop ancien
- **GIVEN** sous Linux, un `mvn` dans le `PATH` dont l'installation porte `lib/maven-core-3.8.8.jar`
- **WHEN** `minos doctor` inspecte scip-java
- **THEN** l'état est `BLOCKED` et un diagnostic nomme l'exigence Maven 3.9.0, sans exécuter `mvn`

#### Scenario: Dépôt local absent
- **GIVEN** sous Linux, Maven 3.9.x présent et aucun dépôt local à l'emplacement désigné
- **WHEN** `minos doctor` inspecte scip-java
- **THEN** l'état est `BLOCKED` et le diagnostic dit de construire une fois le projet hors de MINOS et nomme la clé de configuration

#### Scenario: Prérequis réunis
- **GIVEN** sous Linux, classpath installé, JDK du projet, Maven 3.9.x sous `/usr` et dépôt local présent
- **WHEN** `minos doctor` inspecte scip-java
- **THEN** l'état est `READY`

## ADDED Requirements

### Requirement: Le produit se construit et s'exécute sur un JDK LTS maintenu
Le noyau, la CLI, le MCP, le stockage et les providers SHALL être compilés pour Java 25 et SHALL refuser de se construire sur un autre JDK majeur. Les images Docker et le runtime de l'installateur Windows SHALL embarquer un JDK 25. Le plugin IntelliJ SHALL rester en Java 21.

#### Scenario: Construction sur le JDK de la baseline
- **GIVEN** un JDK 25 et le wrapper Maven
- **WHEN** `./mvnw -B -ntp verify` est exécuté sous Linux puis sous Windows
- **THEN** le reactor se construit et tous les tests passent

#### Scenario: Refus d'un autre JDK
- **GIVEN** un JDK 24 ou 26 désigné par `JAVA_HOME`
- **WHEN** `./mvnw -B -ntp validate` est exécuté
- **THEN** Maven Enforcer échoue en nommant la version exigée

#### Scenario: Runtime embarqué
- **GIVEN** l'image Docker de release et l'installation Windows produites par la CI
- **WHEN** `java -version` est exécuté dans l'image et par le runtime de l'installation
- **THEN** la version affichée est une version 25

### Requirement: La version de Java est énoncée une seule fois et contrôlée partout
La propriété `maven.compiler.release` du POM racine SHALL être la source de vérité. Le gate `check-toolchain-baseline.py` SHALL échouer quand la borne de l'enforcer, un `java-version:` de workflow, une image `eclipse-temurin`, `docs/TOOLCHAIN_POLICY.md`, `AGENTS.md` ou `openspec/config.yaml` énonce une autre version. Les étapes du plugin IntelliJ SHALL être reconnues par une liste fermée.

#### Scenario: Workflow resté sur l'ancienne version
- **GIVEN** une copie du dépôt où un workflow du produit déclare `java-version: '24'`
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur cette copie
- **THEN** le gate échoue et nomme le fichier, la ligne et la version attendue

#### Scenario: Image restée sur l'ancienne version
- **GIVEN** une copie du dépôt où un Dockerfile déclare `FROM eclipse-temurin:24.0.2_12-jre`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** le gate échoue et nomme le Dockerfile

#### Scenario: Étape du plugin en Java 21
- **GIVEN** le dépôt réel, où les étapes du plugin IntelliJ déclarent `java-version: '21'`
- **WHEN** le gate est exécuté
- **THEN** il réussit et son message compte les sites comparés

#### Scenario: Version inconnue hors de la liste fermée
- **GIVEN** une copie du dépôt où un workflow hors de la liste du plugin déclare `java-version: '21'`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** le gate échoue

### Requirement: La politique de toolchain nomme la fin de support de chaque baseline
`docs/TOOLCHAIN_POLICY.md` SHALL indiquer, pour le JDK du produit, la date de fin de correctifs connue et le délai avant cette date auquel un changement de baseline est ouvert. Une baseline hors support SHALL figurer au registre des risques avec une échéance tant qu'elle n'est pas remplacée.

#### Scenario: Politique sans date de fin de support
- **GIVEN** une copie du dépôt où la section Java de la politique ne contient aucune date de fin de support
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant la section

#### Scenario: Politique à jour
- **GIVEN** le dépôt réel après la migration
- **WHEN** `python scripts/docs/check-current-docs.py` et le gate de baseline sont exécutés
- **THEN** les deux réussissent

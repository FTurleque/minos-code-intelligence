# socle-d-execution Specification

## Purpose
Définit ce que MINOS garantit sur les runtimes qu'il livre ou sur lesquels il tourne : un runtime Node maintenu et de même majeur sur chaque plateforme, et un inventaire de provenance de l'image Docker qui reflète les binaires réellement installés plutôt que des versions recopiées. La baseline Java du produit et son gate relèvent du changement de migration vers Java 25, encore actif.

## Requirements

### Requirement: Le runtime Node livré est maintenu et de même majeur sur chaque plateforme
Le catalogue `embedded-tools.json` SHALL déclarer, pour chaque plateforme livrée, un runtime Node d'une ligne LTS maintenue et de même version majeure. Les `ARG` du Dockerfile de release SHALL correspondre au catalogue. Une limitation de compatibilité SHALL NOT figurer dans l'inventaire sans avoir été mesurée.

#### Scenario: Majeurs différents entre plateformes
- **GIVEN** une copie du catalogue où `linux-x64` déclare Node 20 et `windows-x64` Node 24
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant les deux versions

#### Scenario: Dockerfile en retard sur le catalogue
- **GIVEN** une copie du dépôt où `NODE_VERSION` du Dockerfile diffère du catalogue
- **WHEN** le gate est exécuté sur cette copie
- **THEN** le gate échoue

#### Scenario: Providers Node qualifiés dans l'image
- **GIVEN** l'image de release construite avec le Node du catalogue
- **WHEN** `scripts/ci/qualify-docker-release.sh` indexe les fixtures TypeScript et Python
- **THEN** scip-typescript et scip-python produisent leur index et la qualification réussit

### Requirement: L'inventaire de provenance de l'image reflète les binaires installés
Les versions de .NET, Go, Rust et Node écrites dans `provider-inventory.json` SHALL être lues sur les binaires de l'image pendant sa construction. Le Dockerfile SHALL NOT contenir de version littérale pour ces champs. La construction SHALL échouer si une version ne peut pas être lue.

#### Scenario: Littéral de version dans l'inventaire
- **GIVEN** une copie du Dockerfile dont le bloc d'inventaire contient `"toolchain":"1.97.1"`
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant la ligne

#### Scenario: Inventaire comparé aux binaires
- **GIVEN** l'image de release construite
- **WHEN** `scripts/ci/qualify-docker-release.sh` compare chaque version de l'inventaire à la sortie du binaire correspondant dans le conteneur
- **THEN** les quatre valeurs sont égales

#### Scenario: Version illisible à la construction
- **GIVEN** une construction où la commande de version d'une toolchain ne rend rien
- **WHEN** l'étape qui écrit l'inventaire s'exécute
- **THEN** la construction échoue et aucun inventaire n'est écrit

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

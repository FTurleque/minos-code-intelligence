## MODIFIED Requirements

### Requirement: Le produit se construit et s'exécute sur un JDK LTS maintenu
Le noyau, la CLI, le MCP, le stockage et les providers SHALL être compilés pour Java 21 et SHALL refuser de se construire sur un autre JDK majeur. Les images Docker et le runtime de l'installateur Windows SHALL embarquer un JDK 21. Le plugin IntelliJ SHALL rester en Java 21.

#### Scenario: Construction sur le JDK de la baseline
- **GIVEN** un JDK 21 et le wrapper Maven
- **WHEN** `./mvnw -B -ntp verify` est exécuté sous Linux puis sous Windows
- **THEN** le reactor se construit et tous les tests passent

#### Scenario: Refus d'un autre JDK
- **GIVEN** un JDK 17 ou 25 désigné par `JAVA_HOME`
- **WHEN** `./mvnw -B -ntp validate` est exécuté
- **THEN** Maven Enforcer échoue en nommant la version exigée

#### Scenario: Exécution sur le runtime Java 21 d'un pair MCP
- **GIVEN** le jar ombré produit par le reactor et un Java 21 réel, avec un `MINOS_HOME` vide et temporaire
- **WHEN** `java -jar <jar> mcp` reçoit `initialize`, `tools/list` puis `minos_index_status` en STDIO
- **THEN** le serveur répond à chaque requête, déclare les outils attendus et sort avec le code 0 à la fermeture de l'entrée

#### Scenario: Runtime embarqué
- **GIVEN** l'image Docker de release et l'installation Windows produites par la CI
- **WHEN** `java -version` est exécuté dans l'image et par le runtime de l'installation
- **THEN** la version affichée est une version 21

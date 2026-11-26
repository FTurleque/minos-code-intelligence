# socle-d-execution Specification

## Purpose
Définit ce que MINOS garantit sur les runtimes qu'il livre ou sur lesquels il tourne : un runtime Node maintenu et de même majeur sur chaque plateforme, et un inventaire de provenance de l'image Docker qui reflète les binaires réellement installés plutôt que des versions recopiées. La baseline Java du produit est une exigence de cette capacité, et son gate relève de l'ADR GEN-0077.

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

### Requirement: La version de Java est énoncée une seule fois et contrôlée partout
La propriété `maven.compiler.release` du POM racine SHALL être la source de vérité. Le gate `check-toolchain-baseline.py` SHALL échouer quand la borne de l'enforcer, un `java-version:` de workflow, une image `eclipse-temurin`, `docs/developer/TOOLCHAIN_POLICY.md`, `AGENTS.md` ou `openspec/config.yaml` énonce une autre version. Les étapes du plugin IntelliJ SHALL être reconnues par une liste fermée.

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
`docs/developer/TOOLCHAIN_POLICY.md` SHALL indiquer, pour le JDK du produit, la date de fin de correctifs connue et le délai avant cette date auquel un changement de baseline est ouvert. Une baseline hors support SHALL figurer au registre des risques avec une échéance tant qu'elle n'est pas remplacée.

#### Scenario: Politique sans date de fin de support
- **GIVEN** une copie du dépôt où la section Java de la politique ne contient aucune date de fin de support
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant la section

#### Scenario: Politique à jour
- **GIVEN** le dépôt réel après la migration
- **WHEN** `python scripts/docs/check-current-docs.py` et le gate de baseline sont exécutés
- **THEN** les deux réussissent

### Requirement: Aucun workflow n'installe une baseline Java précédente
Aucun workflow SHALL NOT installer une version de Java autre que celle du POM racine, hors des étapes du plugin IntelliJ reconnues par leur liste fermée. Le gate `check-toolchain-baseline.py` SHALL NOT comporter d'exception pour une baseline précédente : une étape `java-version:` d'une autre version SHALL le faire échouer, y compris dans un workflow de qualification de mise à niveau.

#### Scenario: Étape Java 25 dans un workflow de qualification
- **GIVEN** une copie du dépôt où `release-promotion-gate.yml` ou `docker-upgrade-qualification.yml` déclare une étape `java-version: '25'`
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier, la ligne et la version attendue

#### Scenario: Dépôt réel
- **GIVEN** les workflows du dépôt après le changement
- **WHEN** le gate est exécuté
- **THEN** il réussit et aucun workflow ne contient d'étape `java-version` autre que la baseline et les étapes du plugin

#### Scenario: Candidat A construit pour une autre version
- **GIVEN** une qualification de mise à niveau lancée à la main depuis un candidat A dont le POM impose une version de Java que le runner n'a pas
- **WHEN** `scripts/ci/qualify-docker-upgrade.ps1` résout le JDK de ce candidat
- **THEN** la qualification échoue avant tout build en nommant la version requise, sans construire le candidat avec un autre JDK

### Requirement: Chaque wrapper de build exige l'empreinte de sa distribution
`.mvn/wrapper/maven-wrapper.properties` et `minos-intellij/gradle/wrapper/gradle-wrapper.properties` SHALL porter chacun exactement une ligne `distributionSha256Sum=` de 64 caractères hexadécimaux minuscules et une `distributionUrl` en `https` sous l'hôte attendu. Le wrapper Maven SHALL rester en `distributionType=only-script`. `check-toolchain-baseline.py` SHALL échouer en nommant le fichier dans tout autre cas, fichier absent compris.

#### Scenario: Somme retirée par une montée de wrapper
- **GIVEN** une copie du dépôt où la ligne `distributionSha256Sum` de `.mvn/wrapper/maven-wrapper.properties` est supprimée
- **WHEN** `python scripts/quality/check-toolchain-baseline.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `.mvn/wrapper/maven-wrapper.properties`
- **AND** la même suppression dans `minos-intellij/gradle/wrapper/gradle-wrapper.properties` échoue de même

#### Scenario: Somme mal formée ou doublée
- **GIVEN** l'auto-test du gate, avec un wrapper dont la somme vaut `00`, puis un wrapper qui porte deux lignes `distributionSha256Sum`
- **WHEN** `python scripts/quality/test_check_toolchain_baseline.py` est exécuté
- **THEN** chaque cas constate le refus et le message qui nomme le fichier

#### Scenario: Distribution servie hors de l'hôte attendu
- **GIVEN** une copie du dépôt où la `distributionUrl` du wrapper Maven commence par `http://`
- **WHEN** `python scripts/quality/check-toolchain-baseline.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme l'URL refusée

#### Scenario: Wrapper Maven qui téléchargerait un jar
- **GIVEN** une copie du dépôt où `distributionType=only-script` est remplacé par `distributionType=bin`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** le gate se termine avec le code 1

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0 et compte les deux wrappers parmi les sites comparés

### Requirement: Chaque archive épinglée de l'image est vérifiée avant usage
Pour chaque `ARG` d'empreinte que `embedded-tools.json` attribue à `docker/Dockerfile.mcp.release`, une seule instruction `RUN` SHALL le consommer. Elle SHALL commencer par `set -e`, contenir une fois `sha256sum -c -` sur cet `ARG`, sans `||`, `set +e` ni `trap`, et n'utiliser le fichier avant ce contrôle que pour le télécharger ou l'effacer. `check-tools-manifest.py` SHALL échouer sinon, en nommant l'`ARG` et la ligne.

#### Scenario: Contrôle neutralisé par un OU logique
- **GIVEN** une copie du dépôt où la ligne de contrôle de `MAVEN_ZIP_SHA256` finit par `sha256sum -c - || true; \`
- **WHEN** `python scripts/quality/check-tools-manifest.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `MAVEN_ZIP_SHA256` et la ligne

#### Scenario: Instruction sans arrêt sur erreur
- **GIVEN** une copie du dépôt où l'instruction qui vérifie `NODE_LINUX_SHA256` commence par `set -ux`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il se termine avec le code 1 et nomme `NODE_LINUX_SHA256`

#### Scenario: Archive utilisée avant son contrôle
- **GIVEN** l'auto-test du gate, avec un Dockerfile où `unzip` de l'archive Maven précède la ligne de contrôle
- **WHEN** `python scripts/quality/test_check_tools_manifest.py` est exécuté
- **THEN** le cas constate le refus et le message qui nomme l'`ARG`

#### Scenario: Contrôle supprimé pour un outil du catalogue
- **GIVEN** une copie du dépôt où la ligne de contrôle de `SCIP_DOTNET_NUPKG_SHA256` est supprimée
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il se termine avec le code 1 et nomme `SCIP_DOTNET_NUPKG_SHA256`

#### Scenario: Dockerfile au HEAD
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0 et les six `ARG` d'empreinte du catalogue sont reconnus vérifiés

### Requirement: Les versions de Maven écrites dans la documentation courante sont celles du wrapper ou de l'image
Dans la liste fermée des documents courants qui décrivent la toolchain (`STATUS.md`, `ROADMAP.md`, `TOOLCHAIN_POLICY.md`, `code-audit.md`, `docker-runtime.md`, `supply-chain.md`), toute version de Maven écrite SHALL être celle de `.mvn/wrapper/maven-wrapper.properties` ou le `MAVEN_VERSION` de `docker/Dockerfile.mcp.release`. `check-toolchain-baseline.py` SHALL échouer sinon, en nommant le fichier, la ligne et les deux références.

#### Scenario: Version de wrapper périmée dans l'état du produit
- **GIVEN** une copie du dépôt où `docs/project/governance/STATUS.md` écrit « Maven Wrapper : Maven 3.10.0 » alors que le wrapper télécharge 3.10.2
- **WHEN** `python scripts/quality/check-toolchain-baseline.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `STATUS.md`, la ligne, 3.10.2 et la version de l'image

#### Scenario: Version de l'image citée par la page utilisateur
- **GIVEN** le dépôt où `docs/user/docker-runtime.md` écrit « Apache Maven 3.9.16 », égal au `MAVEN_VERSION` du Dockerfile
- **WHEN** le gate est exécuté
- **THEN** cette mention est acceptée

#### Scenario: Document de la liste absent
- **GIVEN** l'auto-test du gate, avec un arbre où un document de la liste fermée manque
- **WHEN** `python scripts/quality/test_check_toolchain_baseline.py` est exécuté
- **THEN** le cas constate le refus, qui nomme le document manquant

#### Scenario: Dépôt après réécriture des documents
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [socle-d-execution](../../../docs/architecture/diagrams/capacites/socle-d-execution.md)

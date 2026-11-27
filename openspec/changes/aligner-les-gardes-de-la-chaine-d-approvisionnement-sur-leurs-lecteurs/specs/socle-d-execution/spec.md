## ADDED Requirements

### Requirement: La somme du wrapper Maven est écrite sous la forme que lit mvnw
Dans `.mvn/wrapper/maven-wrapper.properties`, toute ligne non commentaire qui porte la clé `distributionUrl` ou `distributionSha256Sum` SHALL commencer par cette clé suivie aussitôt de `=`. `check-toolchain-baseline.py` SHALL échouer en nommant le fichier et la ligne sur une clé indentée, suivie d'une espace ou séparée par `:`. `gradle-wrapper.properties` SHALL rester lu comme un fichier de propriétés Java.

#### Scenario: Espace avant le signe égal
- **GIVEN** une copie du dépôt où la ligne de somme du wrapper Maven devient `distributionSha256Sum =<somme>`
- **WHEN** `python scripts/quality/check-toolchain-baseline.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `.mvn/wrapper/maven-wrapper.properties` et la ligne

#### Scenario: Deux-points ou clé indentée
- **GIVEN** l'auto-test du gate, avec un wrapper Maven dont la ligne est `distributionSha256Sum:<somme>`, puis `  distributionSha256Sum=<somme>`
- **WHEN** `python scripts/quality/test_check_toolchain_baseline.py` est exécuté
- **THEN** chaque cas constate le refus et le message qui nomme la ligne

#### Scenario: Espaces après le signe égal
- **GIVEN** l'auto-test du gate, avec `distributionSha256Sum= <somme>` dans le wrapper Maven et `distributionUrl=https\://services.gradle.org/…` dans le wrapper Gradle
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate que les deux wrappers sont acceptés

### Requirement: Le contrôle d'une archive de l'image porte sur le fichier téléchargé avant tout usage
Pour chaque `ARG` d'empreinte du catalogue, le fichier du contrôle `sha256sum -c -` SHALL être une cible de `curl -o` du même `RUN`. Aucune commande qui précède le contrôle, hors `curl` et `rm -f`, SHALL nommer ce fichier, ni son nom de base sans `/` qui le précède. `set +o errexit` SHALL compter comme un désarmement. `check-tools-manifest.py` SHALL échouer sinon, en nommant l'`ARG` et la ligne.

#### Scenario: Archive extraite par son nom relatif avant le contrôle
- **GIVEN** une copie du dépôt où la ligne de contrôle de `NODE_LINUX_SHA256` suit `tar -xJf "${node_archive}"`
- **WHEN** `python scripts/quality/check-tools-manifest.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `NODE_LINUX_SHA256` et la ligne du `tar`

#### Scenario: Arrêt sur erreur désactivé par son nom long
- **GIVEN** une copie du dépôt où le `RUN` qui vérifie `MAVEN_ZIP_SHA256` commence par `set -eux; set +o errexit;`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il se termine avec le code 1 et nomme `MAVEN_ZIP_SHA256`

#### Scenario: Contrôle d'un autre fichier
- **GIVEN** l'auto-test du gate, avec un `RUN` qui télécharge `/tmp/cs.gz` et contrôle `/tmp/other.gz`
- **WHEN** `python scripts/quality/test_check_tools_manifest.py` est exécuté
- **THEN** le cas constate le refus et le message qui nomme l'`ARG`

#### Scenario: Dockerfile au HEAD
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0 et les six `ARG` d'empreinte sont reconnus vérifiés

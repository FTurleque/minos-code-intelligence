## ADDED Requirements

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

# Proposal

## Why

**AUD-DEP-04** (moyenne, sprint 5 de l'audit du 10 octobre 2026). Le plugin IntelliJ résout ses dépendances Gradle **sans verrou ni vérification de somme** : `minos-intellij/` ne contient ni `gradle.lockfile` ni `gradle/verification-metadata.xml` (le dossier `gradle/` n'a que `wrapper/`), et `build.gradle.kts` déclare plugins et dépendances en versions directes (`:6`, `:10-11`, `:25-27`).

Deux conséquences :

- **aucun scanner ne voit ces dépendances** : OSV-Scanner ne lit pas un `build.gradle.kts` sans verrou, et Dependency-Check ne couvre pas le plugin (`code-audit.yml:228`, « n'y est pas encore ») ;
- **aucun contrôle d'intégrité** : une dépendance altérée sur un dépôt distant serait acceptée, alors que le zip du plugin est un livrable publié.

Seule la distribution de Gradle elle-même est épinglée par empreinte (wrapper).

## What Changes

- **Verrou des dépendances** du plugin, versionné, pour les configurations qui entrent dans le zip publié et dans son build.
- **Le gate OSV lit ce verrou** (il parcourt déjà le dépôt en récursif) : une vulnérabilité connue dans une dépendance du plugin fait échouer une PR.
- **Vérification de somme des artefacts résolus**, dans la mesure où elle tient avec l'IntelliJ Platform Gradle Plugin sur Linux et Windows (à mesurer ; décision D2).
- **Un gate** refuse la disparition du verrou ou de son activation, et la procédure de montée (Dependabot ou manuelle) est écrite.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `client-intellij` : deux exigences ajoutées (dépendances du plugin verrouillées et analysées ; intégrité des artefacts résolus vérifiée ou limite déclarée).

## Hors périmètre

- Dependency-Check sur le plugin : tâche reportée du changement actif `etendre-audit-outille-a-tout-le-perimetre` (clé NVD), non reprise ici.
- Monter une dépendance ou le plugin Gradle IntelliJ Platform : le verrou fige l'état actuel.
- La baseline Java 21 / Gradle / IntelliJ Platform du plugin.
- Le wrapper dans les workflows de release (AUD-DEP-05, changement `durcir-les-workflows-github`) et la publication du plugin (AUD-DEP-07).
- Le reactor Maven : le plugin en est exclu et le reste.

## Impact

- **Modules du reactor** : aucun. `minos-intellij` (Gradle, hors reactor) : `build.gradle.kts`, nouveau `gradle.lockfile` (et, selon D2, `gradle/verification-metadata.xml`).
- **Surfaces publiques** : aucune ; le plugin reste un client externe du protocole CLI, sans dépendance `com.minos:*`.
- **ADR** : aucun.
- **Workflows** : `intellij-plugin.yml` (les jobs de PR échouent si le verrou est périmé), `.github/dependabot.yml` (écosystème `gradle` de `/minos-intellij`) ; `pr-ci.yml` inchangé si OSV lit le verrou sans argument de plus (à prouver).
- **Gates à rejouer** : `python scripts/ci/plugin-gate.py scope` et `scripts/ci/test_plugin_gate.py`, `python scripts/quality/check-toolchain-baseline.py`, `python scripts/quality/check-ci-wiring.py`, check exigé `IntelliJ plugin (gate)`, `python scripts/architecture/check-module-boundaries.py` (règle A8 du plugin, aucune dépendance `com.minos:*`).
- **Plateformes** : Linux (build, vérification du plugin) **et** Windows (`gradlew.bat test`, job `windows-ownership`) : le verrou et les sommes doivent tenir sur les deux.

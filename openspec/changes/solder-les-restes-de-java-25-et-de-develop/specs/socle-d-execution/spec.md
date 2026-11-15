## ADDED Requirements

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

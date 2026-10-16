# Proposal

## Why

**AUD-TST-05** (moyenne, sprint 6 de l'audit du 10 octobre 2026 ; confirmé, décomptes recalculés). La couverture du plugin IntelliJ n'est **mesurée par aucun contrôle de fusion** :

- `minos-intellij/build.gradle.kts` déclare SpotBugs et PIT (`:10-11`, audit à la demande) et **aucun JaCoCo** ; le workflow `intellij-plugin.yml` exécute `test buildPlugin …` (`:87`) sans rapport de couverture.
- Au HEAD : 25 classes, 3 656 lignes, 16 fichiers de test. **10 classes ne sont référencées par aucun test** (1 382 lignes, 38 %) : `MinosToolWindowPanel` (400), `MinosM21Actions` (229), `MinosEditorActions` (225), `MinosProjectService` (154), `MinosSettingsConfigurable` (123), `MinosSettingsState` (85), `MinosUiController` (77), `MinosNavigation` (43), `MinosRegistryNotice` (25), `MinosToolWindowFactory` (21). Ce sont les dix de l'audit.
- Le plugin est hors du reactor Maven : il n'entre ni dans le rapport JaCoCo agrégé ni, vraisemblablement, dans l'analyse SonarCloud lancée par Maven (non confirmé).

Une régression d'orchestration des actions, de paramétrage ou de résolution du symbole sous le curseur n'est détectée qu'à l'usage, et la CI du plugin ne peut signaler aucune érosion.

## What Changes

- **Mesure** : le build Gradle du plugin produit un rapport JaCoCo des tests unitaires, publié par `intellij-plugin.yml` sur Linux et sur Windows.
- **Plancher** : une couverture sous le plancher fait échouer le build du plugin, donc le check exigé `IntelliJ plugin (gate)` ; valeur fixée après mesure.
- **Logique extraite** : la logique de `MinosProjectService` et des actions qui ne dépend pas de l'IDE passe dans des classes sans type de la plateforme, testées en JUnit simple.
- **Limite déclarée** : l'interface (fenêtre d'outils, panneau de réglages) n'est pas couverte par des tests unitaires ; elle est nommée comme telle.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `client-intellij` : trois exigences ajoutées (couverture des tests du plugin mesurée et publiée ; couverture sous le plancher refusée ; logique du plugin testable sans la plateforme IntelliJ).

## Hors périmètre

- Tests avec fixture de plateforme (`BasePlatformTestCase`), proposés par l'audit : contraires à la règle des tests du dépôt ; voir `design.md` (D4).
- Faire entrer le plugin dans l'analyse SonarCloud ou dans le rapport agrégé du reactor.
- Le verrou des dépendances Gradle (AUD-DEP-04, changement `verrouiller-les-dependances-gradle-du-plugin`) : seul l'ordre entre les deux changements est fixé ici.
- PIT sur le plugin (AUD-TST-11, changement `completer-la-mutation-et-son-document`).
- Le lanceur Job Object du plugin (AUD-QUA-01, changement `garantir-la-parite-du-lanceur-job-object`).
- Tout changement du protocole CLI ou du comportement visible du plugin.

## Impact

- **Modules du reactor** : aucun. `minos-intellij` (Gradle, Java 21, hors reactor) : `build.gradle.kts`, classes extraites et leurs tests.
- **Surfaces publiques** : IntelliJ, sans changement de comportement ; les identifiants d'actions de `minos-intellij/src/main/resources/META-INF/plugin.xml` ne changent pas. CLI, API Java, MCP, NEXUS : aucune.
- **ADR** : aucun. Le plugin reste un client externe, sans dépendance `com.minos:*`.
- **Gates à rejouer** : `python scripts/docs/check-current-docs.py` (`:199-204` exige six chaînes de `intellij-plugin.yml`) ; `python scripts/ci/test_plugin_gate.py` ; `python scripts/quality/check-workflow-pins.py` ; `python scripts/quality/check-ci-wiring.py` (check exigé `IntelliJ plugin (gate)` inchangé) ; `python scripts/quality/check-toolchain-baseline.py` ; le contrôle « aucune dépendance `com.minos:` » de `intellij-plugin.yml:77-83`.
- **Plateformes** : jobs Ubuntu 24.04 et Windows Server 2022 du workflow du plugin ; la couverture du lanceur de processus Windows n'existe que sous Windows.

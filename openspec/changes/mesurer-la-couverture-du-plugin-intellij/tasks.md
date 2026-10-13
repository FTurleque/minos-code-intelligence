# Tasks

Ordre : mesure et décisions, puis par section rouge, correction, preuve. Le plugin se construit par son wrapper (`minos-intellij/gradlew`, Java 21), jamais par Maven. À faire **après** `verrouiller-les-dependances-gradle-du-plugin` (D5) ; sinon le dire dans `SUIVI.md`. Les chaînes de `intellij-plugin.yml` exigées par `check-current-docs.py:199-204` sont conservées.

## 1. Mesure, relevé et décisions (AUD-TST-05)

- [ ] 1.1 [AUD-TST-05] Sur une branche de travail, activer JaCoCo dans `minos-intellij/build.gradle.kts` et mesurer : lignes et branches du plugin entier, de `protocol/`, et par classe, sous Linux et sous Windows ; durée de `test` avant et après ; interférence éventuelle avec l'instrumentation de l'IntelliJ Platform Gradle Plugin. Consigner dans `design.md` (mesure, date, système ; dire quel système n'a été mesuré qu'en CI).
- [ ] 1.2 **(manuelle)** [AUD-TST-05] Le propriétaire relève sur SonarCloud si `minos-intellij` est analysé (sources, couverture). Consigner dans `design.md`.
- [ ] 1.3 [AUD-TST-05] Lire `MinosM21Actions`, `MinosEditorActions`, `MinosSettingsState`, `MinosNavigation` et `MinosProjectService` : lister la logique indépendante de l'IDE à extraire et les classes qui restent sans test unitaire. Relever le comportement de `MinosProjectService.resolveSymbol` quand aucun symbole ne correspond. Consigner dans `design.md`.
- [ ] 1.4 [AUD-TST-05] **Décision à obtenir** : D2 (plancher dans le build du plugin, mesure moins 2 points, recommandé ; ou rapport sans seuil) et D4 (extraction sans fixture de plateforme, recommandé ; ou `BasePlatformTestCase`, qui amende `.claude/rules/tests-java.md`). Consigner dans `design.md`.

## 2. Rapport de couverture (AUD-TST-05)

- [ ] 2.1 [AUD-TST-05] Rouge : dans `minos-intellij`, `./gradlew --no-daemon test jacocoTestReport`. **Preuve du rouge : tâche inconnue au HEAD.**
- [ ] 2.2 [AUD-TST-05] `build.gradle.kts` : greffon `jacoco`, version de l'outil égale à celle du reactor (`pom.xml:66`), rapport XML et HTML limité à `com.minos.intellij` (D1) ; régénérer le verrou Gradle s'il existe (D5). `intellij-plugin.yml` : tâche de rapport ajoutée aux commandes `:87` et `:135`, étape qui échoue si le rapport XML est absent. Preuve : rapport présent localement ; `python scripts/docs/check-current-docs.py`, `python scripts/quality/check-workflow-pins.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-toolchain-baseline.py` et `python scripts/ci/test_plugin_gate.py` verts ; contrôle « aucune dépendance `com.minos:` » toujours vert.

## 3. Logique testable sans la plateforme (AUD-TST-05)

- [ ] 3.1 [AUD-TST-05] Rouge : tests JUnit simples du choix et du score du symbole candidat (fichier et ligne du curseur, égalités, aucun candidat) et du texte de la notice de registre, écrits contre les classes à extraire ; test de garde qui échoue si une classe de logique extraite importe un type `com.intellij`. **Preuve du rouge : les classes n'existent pas ; la logique est privée dans `MinosProjectService` (`:83`, `:135`, `:144`) et `MinosRegistryNotice`.**
- [ ] 3.2 [AUD-TST-05] Extraire cette logique dans des classes sans type de la plateforme ; `MinosProjectService` et `MinosRegistryNotice` les appellent, noms et packages publics inchangés (D6). Preuve : `./gradlew --no-daemon test` vert ; `buildPlugin verifyPluginProjectConfiguration verifyPluginStructure` verts.
- [ ] 3.3 [AUD-TST-05] Même démarche (test rouge, puis extraction) pour la logique relevée en 1.3 dans les actions et les réglages. Preuve : mêmes commandes ; couverture par classe relevée avant et après.

## 4. Plancher (AUD-TST-05)

- [ ] 4.1 [AUD-TST-05] Rouge : règle de vérification déclarée avec un plancher provisoirement supérieur à la mesure. **Preuve du rouge : `./gradlew --no-daemon test jacocoTestCoverageVerification` échoue en nommant la règle, la mesure et le plancher.**
- [ ] 4.2 [AUD-TST-05] Planchers du plugin entier et de `protocol/` fixés d'après la mesure refaite après la section 3 (D2), mesure et date en commentaire ; vérification ajoutée à la commande du job Linux ; job Windows : rapport publié, plancher de classe du lanceur seulement si 1.1 le permet (D3). Preuve : commande verte sous Linux ; suppression locale d'un test de `protocol/` → rouge (non commitée).
- [ ] 4.3 **(manuelle)** [AUD-TST-05] À la PR : `IntelliJ plugin (gate)` vert ; rapports JaCoCo présents dans les artefacts des deux jobs ; noter les run ids.

## 5. Clôture

- [ ] 5.1 `docs/user/intellij-plugin.md` ou `docs/developer/testing.md` : couverture mesurée, plancher, classes d'interface nommées comme non couvertes par des tests unitaires. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 5.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-TST-05 : décomptes recalculés — 25 classes, 3 656 lignes, 1 382 lignes sans test —, mesures par système, décisions D2 et D4, résultat SonarCloud de 1.2, ordre avec AUD-DEP-04, run ids).
- [ ] 5.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/user/intellij-plugin.md`, `docs/developer/quality-gates.md`.

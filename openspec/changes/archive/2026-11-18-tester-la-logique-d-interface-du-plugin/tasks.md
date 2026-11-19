# Tasks

## 1. Logique de présentation extraite et testée (AUD-TST-08)

- [x] 1.1 AUD-TST-08 — Rouge : écrire dans `minos-intellij` les tests JUnit simples de la présentation du panneau — emplacements collectés dans une réponse imbriquée (ordre, chemin d'origine), texte de connexion, résumés d'architecture et de module, champ absent → texte de repli — à partir du comportement relu dans `MinosToolWindowPanel.java:293-318` et `:333-386` et de réponses JSON du protocole. Exigence : client-intellij › La présentation des réponses du CLI par le plugin est testée hors des classes d'interface. Vérifier : les tests ne compilent pas (classe de présentation absente).
  - Fait : `MinosResultPresentationTest` (5 tests) ; rouge : `compileTestJava` en échec (classe absente).
- [x] 1.2 AUD-TST-08 — Correction : extraire cette logique dans une classe sans type `com.intellij` ni `javax.swing` (la collecte rend une liste Java que le panneau verse dans son modèle Swing), faire déléguer `MinosToolWindowPanel`, ajouter la classe à la liste de `PlatformFreeLogicTest` et étendre la garde à `javax.swing`. Vérifier : `./gradlew --no-daemon test` dans `minos-intellij` (job `IntelliJ plugin (gate)` ou WSL) ; garde de dépendances du plugin verte ; `python scripts/quality/check-comment-language.py`.
  - Fait : `MinosResultPresentation` sans type IntelliJ ni Swing, `MinosToolWindowPanel` délègue ; `PlatformFreeLogicTest` garde la classe et refuse `javax.swing`. Suite du plugin : 108 tests, 8 sautés, sous Windows ; job `IntelliJ plugin (gate)` vert sur la PR #774.
- [x] 1.3 AUD-TST-08 — Rouge puis correction : étendre `MinosSettingsRulesTest` à l'analyse d'un réglage saisi (au-delà du maximum → repli, non numérique → repli, valeur valide entourée d'espaces → valeur), puis déplacer l'analyse de la chaîne de `MinosSettingsConfigurable.java:116-122` vers `MinosSettingsRules`, déjà gardée, en réutilisant ses bornes (`MinosSettingsRules.java:20-26`). Le champ requis d'une action avancée n'est pas touché : `MinosAdvancedAnalysisActions.java:219-221` délègue à `MinosJsonFields.required`, couvert par `MinosJsonFieldsTest.java:30-36`. Vérifier : `./gradlew --no-daemon test` vert ; aucun texte affiché modifié (tests de 1.1 inchangés).
  - Fait : `MinosSettingsRules.timeoutSeconds(String)`, `maxGraphNodes(String)`, `parsedOr` ; `MinosSettingsRulesTest` (6 tests) ; aucun texte affiché modifié.

## 2. Planchers et documentation (AUD-TST-08)

- [x] 2.1 AUD-TST-08 — Mesurer sous Linux (`./gradlew --no-daemon clean test jacocoTestReport`, job de la PR ou WSL ; **aucun `pitest` du plugin sur le poste**), écrire décomptes, date et système dans le commentaire de `minos-intellij/build.gradle.kts`, relever les planchers du plugin et de `protocol` à la mesure moins deux points arrondie au centième inférieur ; prouver sur une copie que la suppression des tests de présentation fait échouer `jacocoTestCoverageVerification`. Exigence : client-intellij › Les planchers de couverture du plugin suivent sa mesure. Vérifier : check `IntelliJ plugin (gate)` vert sur la PR ; planchers du plugin > 0,33.
  - Fait : mesure Linux du run 44107036156 (`f0119bf6`, 2026-11-17) : plugin 705/1803 lignes, 386/956 branches ; `protocol/` 468/946, 249/588 ; planchers 0,37 / 0,38 et 0,47 / 0,40. Preuve sur copie du rapport : sans les tests de présentation, `MinosResultPresentation` (50 lignes, 32 branches couvertes, jamais atteinte par d'autres tests) retombe à 0, soit 655/1803 = 0,363 < 0,37 et 354/956 = 0,370 < 0,38 : `jacocoTestCoverageVerification` échouerait.
- [x] 2.2 AUD-TST-08 — Documentation : mettre à jour `docs/user/intellij-plugin.md` (classes de logique gardées, classes d'interface laissées sans test unitaire, planchers et mesure) et `docs/architecture/diagrams/capacites/client-intellij.md`. Vérifier : `python scripts/docs/check-capability-dossiers.py`, `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`.
  - Fait : `docs/user/intellij-plugin.md` (classes gardées, classes laissées sans test, planchers et mesure). Ligne de traçabilité du dossier `client-intellij.md` : à l'archivage.
- [x] 2.3 AUD-TST-08 — **(manuelle)** si le propriétaire retient l'option B de la décision D1 (tests de plateforme), ouvrir un changement distinct qui modifie l'exigence « La logique du plugin est testable sans la plateforme IntelliJ » ; sinon consigner l'option A dans `docs/project/audits/2026-11-15/SUIVI.md`. Vérifier : décision écrite dans le suivi.
  - Option A consignée dans le suivi de l'audit.

## 3. Clôture

- [x] 3.1 AUD-TST-08 — Mettre à jour la matrice de traçabilité du design ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [x] 3.2 AUD-TST-08 — (reste, à l'archivage) Contrôler le dossier de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [x] 3.3 AUD-TST-08 — Valider ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`.
- [x] 3.4 AUD-TST-08 — Lister les documents à mettre à jour avant archivage (`docs/user/intellij-plugin.md`, dossier de capacité, `docs/project/audits/2026-11-15/SUIVI.md`) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver après revue (`openspec archive tester-la-logique-d-interface-du-plugin -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
- Rejouer la mutation du plugin par `code-audit.yml` (`run_intellij_plugin`) après fusion, à la demande du propriétaire ; résultat reporté par `actualiser-le-document-de-mutation`.

# Tasks

Ordre : reproduction et relevé, décisions, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Sous Windows, `mvnw.cmd` se lance depuis PowerShell 7 avec `powershell.exe` 5.1 dans le `PATH` ; citer les arguments `-Dx.y=z`. Les exigences de `specs/` décrivent les options recommandées (D1-A, D2) ; si le propriétaire choisit autrement, les réviser par `/opsx:update` avant d'implémenter.

## 1. Reproduction, relevé et décisions (AUD-TST-18)

- [ ] 1.1 [AUD-TST-18] Reproduire sous Windows, dans un shell sans `JAVA_HOME` (`java` dans le `PATH`) : `./mvnw -B -ntp -pl minos-provider-scip -am test`. **Preuve du rouge : `ManagedScipProviderRuntimeManagerTest` échoue avec « JAVA_HOME is not set ».** Comparer la branche Windows du test à `ScipJavaProcessPlanFactoryToolchainTest:175` (ce qui manque : `-Language KOTLIN`) ; relever si les JVM filles de `ChildJvm` sont instrumentées par JaCoCo et la couverture actuelle de la fabrique sous Windows. Consigner dans `design.md` et requalifier le constat (« reproduit » ou « non reproduit »).
- [ ] 1.2 [AUD-TST-18] Inventorier les tests du module qui atteignent, dans la JVM de test, `ScipJavaProcessPlanFactory.java:299` ou `ManagedScipProviderRuntimeManager.java:241`. Vérifier que surefire 3.6.0 retire une variable de l'environnement des tests (`excludedEnvironmentVariables`) et relever les tests qui rougissent quand `JAVA_HOME` est retirée. Consigner.
- [ ] 1.3 [AUD-TST-18] **Décision à obtenir** : D1 (A, test seul, recommandé ; ou B, injection dans la production avec la question de l'ADR 0045). Consigner dans `design.md`.
- [ ] 1.4 [AUD-TST-18] **Décision à obtenir** : D2 (garde surefire, recommandée sous réserve de 1.2 ; ou preuve locale seule). Consigner dans `design.md`.

## 2. Test hermétique (AUD-TST-18)

- [ ] 2.1 [AUD-TST-18] Rouge : poser la garde D2 dans `minos-provider-scip/pom.xml` **avant** de corriger le test. **Preuve du rouge : le test fautif échoue sous Windows même avec un `JAVA_HOME` sur l'hôte.** Si D2 est refusée, le rouge est celui de 1.1.
- [ ] 2.2 [AUD-TST-18] Scinder `ManagedScipProviderRuntimeManagerTest` (D1-A) : test commun de la commande Coursier ; test `@EnabledOnOs(OS.WINDOWS)` exécuté par `ChildJvm` avec son environnement, ou affirmation ajoutée au test `:175` existant si 1.1 montre le doublon ; suppression du `if (CommandLocator.isWindows())`. Corriger de la même façon les tests relevés en 1.2. Si D1-B est retenue : modifier fabrique et gestionnaire dans cette tâche, avec leurs tests, les appelants et la note de mise en œuvre de l'ADR 0045.
- [ ] 2.3 [AUD-TST-18] Preuve : sous Windows sans `JAVA_HOME`, `./mvnw -B -ntp -pl minos-provider-scip -am verify` vert ; sous Linux, le test Windows figure comme sauté dans le rapport surefire (dire quel système n'a tourné qu'en CI) ; `python scripts/remediation/check-p0-p2.py`, `python scripts/remediation/check-post-mne.py` et `python scripts/quality/check-polyglot-provider-consistency.py` verts ; scope JaCoCo `m24-polyglot-provider-platform` et plancher de classe de `ScipJavaProcessPlanFactory` (0,35 / 0,15) comparés à `develop` sur le même système.
- [ ] 2.4 **(manuelle)** [AUD-TST-18] À la PR : `Verify (ubuntu-24.04)` et `Verify (windows-2022)` verts ; relever dans le rapport Linux le test compté comme sauté ; noter les run ids.

## 3. Clôture

- [ ] 3.1 `docs/developer/testing.md` : un test de processus fils pose son environnement ; garde D2 du module si elle est retenue. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 3.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-TST-18 : reproduction 1.1, décisions D1 et D2, second site de lecture, tests corrigés au titre de 1.2, run ids).
- [ ] 3.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/testing.md`, `docs/developer/polyglot-providers.md` si le prérequis JDK y est décrit.

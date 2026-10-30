# Tasks

Ordre : mesure et décision (1), puis dans chaque section rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Le plugin se construit par son wrapper Gradle (`minos-intellij/gradlew`), hors du reactor.

## 1. Mesure et décision (AUD-PERF-08)

- [x] 1.1 [AUD-PERF-08] Mesurer, sous Windows et sous Linux, la durée moyenne d'un `ProcessHandle.descendants().toList()` et la cadence réelle de la boucle actuelle (appels par seconde) pendant une indexation de ce dépôt ; relever le nombre de processus de l'hôte. Consigner dans `design.md` (« Non mesuré » levé ou maintenu, par système).
- [x] 1.2 [AUD-PERF-08] Relire les tests qui dépendent d'une observation avant la sortie de la racine : `ProcessIndexerExecutorTest.java:179` (déjà relu : fenêtre d'une seconde), `ProcessOwnershipTrackerCleanupFailureTest`, `MinosProcessSupervisorOrphanTest`, `MinosProcessSupervisorTest`. Relever pour chacun la fenêtre d'observation la plus courte. Consigner dans `design.md`. **Relevé : voir « Fenêtres d'observation des tests » dans `design.md`.**
- [x] 1.3 [AUD-PERF-08] **Décision à obtenir** : D2 (adaptatif 10 ms puis 100 ms, recommandé ; 100 ms fixe ; ou sondage en repli seulement). Consigner dans `design.md` la période et la borne du test qui en découle.

## 2. Runtime local (AUD-PERF-08)

- [x] 2.1 [AUD-PERF-08] Rouge : dans `minos-runtime-local`, package `process`, test de cadence avec un `Process` factice dont `descendants()` compte ses appels (D4) : après la phase initiale, au plus 30 appels et au moins un sur 2 s. **Preuve du rouge : plus de 100 appels au HEAD.**
- [x] 2.2 [AUD-PERF-08] `ProcessOwnershipTracker` : période selon D2, fenêtre de 250 ms après la sortie de la racine conservée, Javadoc qui dit la limite (défense en profondeur, descendant éphémère). Preuve : test de cadence vert ; `./mvnw -B -ntp -pl minos-runtime-local -am test` vert ; `normalProviderExitCleansAPreviouslyObservedDetachedChild` vert vingt fois de suite sur le poste ; `python .claude/scripts/run_gates.py --fast` vert. Aucun scope JaCoCo ne cible la classe : relever sa couverture dans le rapport du module (seuil SonarCloud du nouveau code).

## 3. Plugin IntelliJ (AUD-PERF-08)

- [x] 3.1 [AUD-PERF-08] Rouge : dans `minos-intellij`, même test de cadence sur `MinosProcessSupervisor` (constructeur à un `Process`, `:55`), même borne, dans une classe de test **non exclue** de PIT. **Preuve du rouge : plus de 100 appels au HEAD.**
- [x] 3.2 [AUD-PERF-08] `MinosProcessSupervisor` : mêmes constantes et même logique que 2.2, sans import `com.minos` hors du plugin. Preuve : tests du plugin verts par le wrapper Gradle (dire la commande exécutée et le système) ; `python scripts/architecture/check-module-boundaries.py` vert.
- [x] 3.3 **(manuelle)** [AUD-PERF-08] À la PR : `Verify (ubuntu-24.04)`, `Verify (windows-2022)` et `IntelliJ plugin (gate)` verts ; relever que les deux tests de cadence ont été exécutés et non sautés ; noter le run id dans `SUIVI.md`. **Relevé (runs 39078787000 et 39078786757, tête d633c5f0, PR #494) : `ProcessOwnershipTrackerCadenceTest` 1/1 sur `Verify (ubuntu-24.04)` et `Verify (windows-2022)` ; `MinosProcessSupervisorCadenceTest` 1/1 sur `IntelliJ 2026.1 / Java 21` et `Windows process ownership / Java 21` ; `IntelliJ plugin (gate)` vert. Aucun test de cadence sauté.**

## 4. Clôture

- [x] 4.1 [AUD-PERF-08] Refaire la mesure de 1.1 après correction et consigner l'avant/après ; si la mesure n'a pu être faite que sur un système, le dire.
- [x] 4.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-PERF-08 : statut, décision D2, mesures, écarts avec l'action de l'audit — pas de factorisation, sondage conservé).
- [x] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/user/intellij-plugin.md` (phrase sur le sondage, si la cadence y est donnée), `docs/developer/remote-worker-sandbox-disposition.md` si elle cite la période.

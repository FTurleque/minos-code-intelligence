# Tasks

## 1. Superviseur de quota : échantillons observés (AUD-TST-06)

- [ ] 1.1 AUD-TST-06 — Rouge : montrer la vacuité du test actuel (mutation locale non commitée : fil du superviseur non démarré → `aProviderStayingInsideItsBudgetIsNeverKilled` reste vert), puis écrire l'aide d'attente de deux échantillons et le scénario « Superviseur qui n'échantillonne pas » (`assertThrows` sur l'aide, superviseur créé par `startForTest` avec un échantillonneur bloqué). Exigence : confinement-code-non-fiable › L'absence de dépassement de quota est prouvée par des échantillons observés. Vérifier : sous la mutation, l'ancien test passe (défaut prouvé) ; le nouveau ne compile pas faute de `sampleCount()`.
- [ ] 1.2 AUD-TST-06 — Correction : ajouter `sampleCount()` package-private à `ProviderWriteQuotaSupervisor` (incrément après la publication des valeurs observées) ; réécrire `aProviderStayingInsideItsBudgetIsNeverKilled` et `nestedRootsAreAccountedOnceAndSubRootsNeverDoubleCount` sur l'attente de deux échantillons, sans `Thread.sleep(200L)` ; garder les trois noms de méthodes affirmés par le gate. Vérifier : `./mvnw -B -ntp -pl minos-runtime-local -am test "-Dtest=ProviderWriteQuotaSupervisorTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert, rejoué 50 fois (décompte consigné dans la PR) ; sous la mutation de 1.1, le test réécrit est rouge ; `python scripts/remediation/check-minos-01.py` ; `python scripts/quality/check-comment-language.py` ; scope JaCoCo `provider-write-quota-supervisor`.

## 2. Suivi de propriété du runtime : cadence sur temps mesuré (AUD-TST-06)

- [ ] 2.1 AUD-TST-06 — Rouge : écrire dans les tests de `minos-runtime-local` le verdict de cadence (au moins une, au plus 15 par seconde mesurée) et ses cas à valeurs injectées : 45 énumérations en 3,5 s acceptées, 40 en 2 s refusées, 0 en 2 s refusées (`assertThrows`). Exigence : confinement-code-non-fiable › La cadence du suivi de propriété est jugée sur le temps réellement mesuré. Vérifier : le cas « 45 en 3,5 s » échoue contre la borne fixe de 30 du test actuel (défaut prouvé).
- [ ] 2.2 AUD-TST-06 — Correction : réécrire `ProcessOwnershipTrackerCadenceTest` — suivi réel et processus factice conservés ; attente bornée de la première énumération ; phase initiale ; fenêtre mesurée par `System.nanoTime()` (horloge puis compteur à l'ouverture, compteur puis horloge à la fermeture) ; jugement par le verdict. Aucun code de production modifié. Vérifier : `./mvnw -B -ntp -pl minos-runtime-local -am test "-Dtest=ProcessOwnershipTrackerCadenceTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert, rejoué 50 fois ; non-vacuité : période établie abaissée à 10 ms en mutation locale → test rouge ; `ProcessOwnershipTrackerCleanupFailureTest` et `ProcessOwnershipTrackerHostProtectionTest` inchangés et verts ; scope JaCoCo `provider-execution-trust-boundary` inchangé.

## 3. Superviseur du plugin : même preuve (AUD-TST-06)

- [ ] 3.1 AUD-TST-06 — Rouge puis correction, dans `minos-intellij` (Gradle, hors reactor, code de test seul) : verdict de cadence du plugin et ses cas à valeurs injectées, puis réécriture de `MinosProcessSupervisorCadenceTest` sur une fenêtre mesurée, comme 2.2. Exigence : client-intellij › La cadence du superviseur du plugin est jugée sur le temps réellement mesuré. Vérifier : `./gradlew --no-daemon test jacocoTestCoverageVerification` par le job `IntelliJ plugin (gate)` ou sous WSL ; **ne pas lancer `pitest` du plugin sur le poste** ; garde de dépendances du plugin verte (aucun `com.minos:*`).

## 4. Mesure du tas retenu par une vue (AUD-TST-06)

- [ ] 4.1 AUD-TST-06 — Après décision D3 du propriétaire (recommandation : option A) — rouge : cas « Tas jamais stable » (`assertThrows` sur l'aide de mesure, source de lectures injectée instable) ; correction : remplacer `settledHeap` de `QueryViewWeightTest` par des collectes jusqu'à deux lectures égales, limite de tours, sans `Thread.sleep(150L)` ; preuve de non-vacuité : estimation divisée par dix en mutation locale → test rouge avec taille persistée, tas retenu et estimation. Exigence : persistance-snapshots › La mesure du tas retenu par une vue ne dépend d'aucun délai fixe. Vérifier : `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=QueryViewWeightTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert, rejoué 20 fois sous Windows ; la CI le rejoue sous Linux ; scope JaCoCo `persistence-cache-indexes` inchangé.

## 5. Documentation (AUD-TST-06)

- [ ] 5.1 AUD-TST-06 — Ajouter à `docs/developer/testing.md` (§ Tests de concurrence) les deux moyens : échantillons comptés avant une affirmation d'absence, cadence jugée sur la durée mesurée après un premier signal ; reporter les exigences, et la limite « périodes du plugin recopiées sans contrôle d'égalité », dans les dossiers `confinement-code-non-fiable.md`, `client-intellij.md` et `persistance-snapshots.md`. Vérifier : `python scripts/docs/check-capability-dossiers.py`, `python scripts/docs/check-current-docs.py`, liens relatifs valides.

## 6. Clôture

- [ ] 6.1 AUD-TST-06 — Mettre à jour la matrice de traçabilité du design ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 6.2 AUD-TST-06 — Contrôler les dossiers de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 6.3 AUD-TST-06 — Valider ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`.
- [ ] 6.4 AUD-TST-06 — Lister les documents à mettre à jour avant archivage (`docs/developer/testing.md`, dossiers de capacité, `docs/project/audits/2026-11-15/SUIVI.md`) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver après revue (`openspec archive prouver-les-tests-temporises-par-un-signal -y`), puis vérifier que les trois specs de référence gardent leur section `## Documentation liée`.

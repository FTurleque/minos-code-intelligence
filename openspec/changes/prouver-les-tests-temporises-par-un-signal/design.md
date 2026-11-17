# Design

## Contexte

Hygiène de quatre tests. Une seule couture de production est ajoutée (compteur d'échantillons du superviseur de
quota) ; les tests de cadence et de tas se corrigent dans le code de test. Motivation : voir `proposal.md` (Why).
Changement d'outillage de test : seules les sections utiles sont remplies.

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture du code, aucun test exécuté.

| Fait | Preuve |
|---|---|
| Test négatif : 200 ms puis `breach().isEmpty()` | `minos-runtime-local/src/test/java/com/minos/runtime/local/workspace/ProviderWriteQuotaSupervisorTest.java:64-65` |
| Test des racines imbriquées : 200 ms puis lecture de `observedBytes()` | même fichier, `:79-82` |
| Les tests positifs utilisent déjà une attente bornée (`awaitBreach`) | même fichier, `:35`, `:51`, `:105`, `:114-117` |
| Le superviseur publie octets et entrées observés, pas le nombre d'échantillons | `minos-runtime-local/src/main/java/com/minos/runtime/local/workspace/ProviderWriteQuotaSupervisor.java:57-59`, `:132-138`, `:151-156` |
| Couture d'échantillonneur package-private : `startForTest` (`startWithSampler` est privée) | `ProviderWriteQuotaSupervisor.java:90-97`, `:99` |
| Cadence du runtime : 10 ms pendant 1 000 ms, puis 100 ms, décidée dans la boucle ; l'origine du temps est prise dans le fil de suivi | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProcessOwnershipTracker.java:33-36`, `:101-116` |
| Cadence du plugin : mêmes constantes, même boucle recopiée | `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosProcessSupervisor.java:42-44`, `:221-234` |
| Tests de cadence : 1 300 ms après la construction, puis fenêtre de 2 000 ms supposée égale au `Thread.sleep` du test, borne fixe de 30 | `ProcessOwnershipTrackerCadenceTest.java:22-24`, `:30-38` ; `MinosProcessSupervisorCadenceTest.java:23-25`, `:33-36` |
| Les scénarios en vigueur portent sur un suivi réel attaché à un processus factice : au moins une, au plus 30 énumérations en deux secondes ; l'exigence fixe 15 par seconde | `openspec/specs/confinement-code-non-fiable/spec.md:245-251` ; `openspec/specs/client-intellij/spec.md:292-298` |
| Mesure de tas : quatre `System.gc()` + 150 ms | `minos-storage/src/test/java/com/minos/storage/local/store/QueryViewWeightTest.java:97-103` |
| Ce test porte un verdict (estimation ≥ tas retenu), pas seulement une mesure | `QueryViewWeightTest.java:79` |
| Un gate affirme trois noms de méthodes du test du superviseur | `scripts/remediation/check-minos-01.py:44-48` |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit cite les lignes `:79-80` comme « positif après 200 ms » : c'est le test des racines imbriquées, qui lit `observedBytes()` ; même défaut (aucun échantillon garanti), fausse rouge possible | `findings.json`, AUD-TST-06, preuve | `ProviderWriteQuotaSupervisorTest.java:71-84` | Traité par la même attente d'échantillons |
| L'audit propose une « horloge factice » et un compteur d'itérations injecté pour la cadence. Les scénarios en vigueur exigent un suivi **réel** : une borne prouvée sur une règle extraite laisserait verte une boucle qui n'appelle plus cette règle | `findings.json`, AUD-TST-06, action | `openspec/specs/confinement-code-non-fiable/spec.md:248-251` | D2 : fil réel conservé, borne calculée sur le temps mesuré ; aucune couture de production |
| La borne haute ne peut pas être fausse verte (un `sleep` ne rend pas la main en avance) ; le défaut réel est la fausse rouge quand le **test** dort plus de deux secondes, ou quand le fil de suivi démarre en retard et que la phase initiale déborde sur la fenêtre | `findings.json`, AUD-TST-06, impact | `ProcessOwnershipTrackerCadenceTest.java:30-33` ; `ProcessOwnershipTracker.java:103` | D2 : fenêtre ouverte après une première énumération observée, durée mesurée |
| L'audit propose « un test étiqueté mesure » pour le tas : aucun test du dépôt n'est étiqueté, aucun profil Surefire n'exclut un groupe ; et le test porte un verdict, que l'étiquette sortirait de la suite | `findings.json`, AUD-TST-06, action | `QueryViewWeightTest.java:79` ; recherche de `@Tag(` : vide | D3 : garder le verdict, retirer le délai |
| Le critère de sortie parle d'« absence après un délai fixe » : un seul des quatre sites est une affirmation d'absence | `sprints.md`, sprint 6 | constat | Les quatre sont traités |

## Exigences couvertes

- confinement-code-non-fiable › L'absence de dépassement de quota est prouvée par des échantillons observés — D1.
- confinement-code-non-fiable › La cadence du suivi de propriété est jugée sur le temps réellement mesuré — D2.
- client-intellij › La cadence du superviseur du plugin est jugée sur le temps réellement mesuré — D2.
- persistance-snapshots › La mesure du tas retenu par une vue ne dépend d'aucun délai fixe — D3.

Les exigences en vigueur « Le suivi de propriété par sondage est espacé sans rien céder au nettoyage » et « Le
superviseur de processus du plugin sonde à cadence bornée » restent prouvées par les mêmes tests de fil réel :
15 par seconde mesurée vaut 30 pour deux secondes.

## Objectifs et non-objectifs

Non applicable : voir la proposition.

## Responsabilités et frontières des composants

Aucune dépendance nouvelle. `sampleCount()` est package-private, dans `com.minos.runtime.local.workspace` ; aucun
type ajouté à `RUNTIME_LOCAL_EXPORTED_TYPES` (A10). Le code de production du suivi de propriété et du plugin n'est
pas modifié. Le plugin garde son propre verdict de cadence en code de test, sans dépendance `com.minos:*`.

## Modèle de domaine et invariants

- `sampleCount` du superviseur : croissant, incrémenté après la publication des valeurs observées de l'échantillon.
- Verdict de cadence (code de test) : fonction pure `(énumérations, nanosecondes mesurées) → accepté ou erreur
  d'assertion`, avec `1 ≤ énumérations ≤ 15 × secondes mesurées`.

## Traitements et interactions

Test de cadence : attendre, avec échéance, la première énumération (le fil de suivi a démarré) ; laisser passer la
phase initiale ; lire l'horloge monotone **puis** le compteur ; dormir environ deux secondes ; lire le compteur
**puis** l'horloge. Cet ordre de lecture ne peut que surestimer la durée et sous-estimer le nombre : il n'ajoute
aucun faux rouge.

## États et transitions

Non applicable.

## Contrats d'interface

Non applicable : aucune interface publique.

## Persistance et intégrité

Non applicable.

## Transactions, concurrence et idempotence

Le compteur d'échantillons est un `AtomicLong` lu par le fil de test. Tests de fil réel rejoués 50 fois avant d'être
déclarés stables (`docs/developer/testing.md:87`).

## Sécurité et isolation

Aucune garantie de confinement modifiée : périodes, arrêt du job et nettoyage identiques. La couture n'est pas
atteignable hors du package.

## Erreurs et reprise

Non applicable.

## Observabilité

Non applicable.

## Performance et mesure

Non applicable : la durée des tests de cadence reste de l'ordre de trois secondes chacun.

## Compatibilité, migration et retour arrière

Non applicable.

## Diagrammes

Non applicable : aucune structure ni interaction nouvelle à illustrer.

## Décisions

### D1 — Compteur d'échantillons, attendu avec échéance

[Proposition] Ajouter `long sampleCount()` (package-private) au superviseur. Une aide de test attend
`sampleCount() >= 2` (un échantillon peut précéder l'écriture du test ; deux garantissent un échantillon complet
postérieur au démarrage) et lève une erreur d'assertion à l'échéance ; les deux tests l'appellent avant d'affirmer.
Le scénario « n'échantillonne pas » appelle cette aide en `assertThrows`, avec un superviseur créé par la couture
existante `startForTest` et un échantillonneur bloqué. Les trois noms de méthodes affirmés par `check-minos-01.py`
sont conservés.

### D2 — Fil réel conservé, borne calculée sur le temps mesuré

[Proposition] Aucune extraction dans le code de production. Le test garde un suivi réel et un processus factice
compteur ; il ouvre sa fenêtre après une première énumération observée et la phase initiale, mesure la durée par
`System.nanoTime()` et délègue le jugement à un verdict pur du code de test. Les cas « trop élevée », « arrêté » et
« test endormi trop longtemps » sont des `assertThrows` (ou une acceptation) sur ce verdict, à valeurs injectées.
Alternatives écartées : règle de cadence extraite et prouvée sur temps simulé — elle ne prouve plus que la boucle
réelle la respecte, contre les scénarios en vigueur ; passer ces scénarios en `MODIFIED` — inutile, la preuve sur
fil réel reste possible.

Limite déclarée : les constantes de période du plugin sont une copie de celles du runtime
(`ProcessOwnershipTracker.java:33-36`, `MinosProcessSupervisor.java:42-44`) ; aucun contrôle ne compare les deux
fichiers. Les deux tests jugent la même borne de 15 par seconde, pas l'égalité des périodes.

### D3 — Mesure de tas : stabilisation bornée, verdict conservé

[Proposition — décision du propriétaire attendue]

- **A (recommandée)** : remplacer les quatre tours « collecte + 150 ms » par des collectes répétées jusqu'à deux
  lectures consécutives égales, dans une limite de tours ; erreur explicite sinon. La source de lectures est un
  paramètre de l'aide, pour prouver le cas instable par `assertThrows`. Le test reste dans la suite et garde son
  verdict.
- **B (action de l'audit)** : étiqueter le test « mesure » et l'exclure de la suite par défaut. Coût : une
  configuration Surefire nouvelle dans le POM parent, et la perte du verdict en CI.
- **C** : exécuter la mesure dans une JVM fille à ramasse-miettes série, comme `QueryViewAdmissionChildJvmTest`.
  Plus déterministe, plus lourd.

## Qualification des capacités

Aucune capacité requalifiée. Les exigences ajoutées sont **qualifiées** par tests unitaires, Windows et Linux ; le
plugin par le job `IntelliJ plugin (gate)` (Linux) et son job Windows.

## Risques et compromis

- [Risque] Le compteur lu avant la publication des valeurs → incrément après `observedBytes.set` / `observedEntries.set`.
- [Risque] La stabilité du tas jamais atteinte sur un runner bruyant (option A) → limite de tours et message ; si la
  CI le montre, passer à l'option C.
- [Risque] Recouvrement avec `compter-comme-sautes-les-tests-propres-a-une-plateforme` et
  `faire-echouer-les-preuves-de-verrou-muettes` (sprint 1) sur des classes de test voisines → fichiers distincts.

## Vérification

- `ProviderWriteQuotaSupervisorTest`, `ProcessOwnershipTrackerCadenceTest`, `QueryViewWeightTest` ;
  `MinosProcessSupervisorCadenceTest` (`minos-intellij`, `./gradlew --no-daemon test`, en CI ou sous WSL ; jamais de
  `pitest` local du plugin).
- `./mvnw -B -ntp -pl minos-runtime-local -am test`, puis `./mvnw -B -ntp -pl minos-storage -am test` (jamais en
  parallèle).
- Preuves de non-vacuité par mutation locale non commitée : fil du superviseur non démarré ; période établie du
  suivi abaissée à 10 ms ; estimation du poids divisée par dix.
- Gates : `check-minos-01.py`, `check-module-boundaries.py`, `check-comment-language.py`.

## Documentation durable à mettre à jour

`docs/developer/testing.md` (§ Tests de concurrence : deux lignes au tableau des moyens), dossiers de capacité
`confinement-code-non-fiable.md`, `client-intellij.md`, `persistance-snapshots.md`.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| confinement-code-non-fiable › L'absence de dépassement de quota est prouvée par des échantillons observés | Provider conforme | D1 | 1.1, 1.2 | `ProviderWriteQuotaSupervisorTest#aProviderStayingInsideItsBudgetIsNeverKilled` (rouge sous « fil non démarré », 50/50 vert) |
| idem | Superviseur qui n'échantillonne pas | D1 | 1.1 | `ProviderWriteQuotaSupervisorTest#aSupervisorThatNeverSamplesFailsTheSampleWait` |
| idem | Racines imbriquées comptées une fois | D1 | 1.2 | `ProviderWriteQuotaSupervisorTest#nestedRootsAreAccountedOnceAndSubRootsNeverDoubleCount` (rouge sous mutation, 50/50 vert) |
| confinement-code-non-fiable › La cadence du suivi de propriété est jugée sur le temps réellement mesuré | Fenêtre réelle en régime établi | D2 | 2.1, 2.2 | `ProcessOwnershipTrackerCadenceTest#aLiveProcessIsEnumeratedRarelyOnceTheInitialPhaseIsOver` (rouge à 10 ms : 128 en 2 006 ms ; 50/50 vert) |
| idem | Fil de test endormi plus longtemps que prévu | D2 | 2.1 | `ProcessOwnershipTrackerCadenceTest#aWindowThatLastedLongerThanPlannedIsJudgedOnItsMeasuredDuration` |
| idem | Cadence trop élevée | D2 | 2.1 | `ProcessOwnershipTrackerCadenceTest#aCadenceAboveTheBoundIsRefusedWithItsMeasures` |
| idem | Suivi arrêté | D2 | 2.1 | `ProcessOwnershipTrackerCadenceTest#aTrackerThatStoppedEnumeratingIsRefused` |
| client-intellij › La cadence du superviseur du plugin est jugée sur le temps réellement mesuré | Fenêtre réelle dans le plugin | D2 | 3.1 | `MinosProcessSupervisorCadenceTest#aLiveProcessIsEnumeratedRarelyOnceTheInitialPhaseIsOver` (vert en local, Gradle sans `instrumentCode` ; mutation non prouvée sur le poste) |
| idem | Cadence trop élevée dans le plugin | D2 | 3.1 | `MinosProcessSupervisorCadenceTest#aCadenceAboveTheBoundIsRefusedWithItsMeasures` |
| idem | Superviseur qui n'énumère plus | D2 | 3.1 | `MinosProcessSupervisorCadenceTest#aSupervisorThatStoppedEnumeratingIsRefused` |
| persistance-snapshots › La mesure du tas retenu par une vue ne dépend d'aucun délai fixe | Tas stabilisé | D3 | 4.1 | `QueryViewWeightTest#estimatedWeightIsNotBelowTheHeapRetainedByTheView`, `QueryViewWeightTest#aSettledHeapIsTheCommonValueOfTwoConsecutiveReadings` |
| idem | Tas jamais stable | D3 | 4.1 | `QueryViewWeightTest#aHeapThatNeverSettlesFailsTheMeasure` |

## Questions ouvertes

- D3 : option A, B ou C (recommandation : A). Attendue avant la tâche 4.1.

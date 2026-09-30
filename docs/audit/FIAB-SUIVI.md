# Suivi — chantier Fiabilité opérationnelle (lot 1 : R4, R5, R7 — intégrité de la reprise ; lot 2 : P1, Q3, Q4 — un seul régime de verrous, § 8)

> Branche : `fiab/r4-r5-r7-reprise` (depuis `develop`, base `017e339d`, les branches `code/*` des PR #305 à #308 y sont déjà fusionnées), worktree `minos-wt/fiab-lot1`.
> Constats : **R4** (promotion reprise sans égalité des cibles), **R5** (rétention d'un run reprenable, durée de vie du marqueur) et **R7** (réparation « snapshot stable » sans `resumableRunId` ni `supersede`), `AUDIT-2026-09.md` § R4, R5, R7 ; conception de référence : [ADR 0039](../adr/0039-reprise-indexation-apres-interruption.md).
> Agents : `impl-fiab` (implémentation), `verif-fiab` (inspection de chaque commit, en parallèle). Aucun push, aucune PR ouverte par les agents de lot.
> Règles du lot : aucun changement de comportement non voulu ; test rouge avant correctif, sortie rouge jointe au message du commit ; chaque test de concurrence rejoué 50 fois, sans `Thread.sleep` de synchronisation ; une règle de durée de vie écrite **une seule fois**.
> Ce fichier est repris tel quel par les lots suivants (2 à 5) : les sections « constats de verif-fiab » et « à traiter plus tard » s'y accumulent, l'inventaire des verrous du lot 2 viendra s'ajouter à la suite.

## 1. Tableau de bord des cinq lots

| Lot | Contenu | Statut | Commits |
|---|---|---|---|
| 1 — R4, R5, R7 | Promotion reprise bornée aux cibles courantes, rétention d'un run reprenable (parcours tronqué, run concurrent, durée de vie), réparation « snapshot stable » alignée | livré, en attente du verdict final de `verif-fiab` | `cdeac459` … (§ 4) |
| 2 — P1, Q3, Q4 | Un seul régime de verrous ; lecture d'état sans bail exclusif (§ 8) | en cours (branche `fiab/p1-q3-q4-verrous`, depuis la branche du lot 1 `bcdadd23`) | § 8.8 |
| 3 — R2, R3 | Propriété des cgroups indépendante de l'horloge murale | à faire | — |
| 4 — Q5, R6 | Interruption de bout en bout, confinement du chemin d'artefact | à faire | — |
| 5 — Q8, Q9 | Tolérance aux données abîmées (`listProjects`, clé sémantique en double) | à faire | — |

## 2. Inventaire daté (base `017e339d`, 30 septembre 2026)

Les chemins de l'audit sont antérieurs à A2/A3/A4 et au chantier Code ; chaque cible a été relocalisée par recherche dans les sources.

### 2.1 Cibles de l'audit, relocalisées

| Cible de l'audit | Emplacement réel | Constat |
|---|---|---|
| `IndexingRunExecutor` | `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java` (paquet `com.minos.orchestration`, module `minos-engine`) | 835 lignes ; `execute`, `executeResumed`, `persistTerminalFailure`, `persistInterruption` |
| promotion reprise (`promoteOnly`) | `IndexingRunExecutor.executeResumed`, lignes 127-129 : `resume.remaining().isEmpty() && phase == PROMOTION && stagedSnapshotId.isPresent()` | R4 : aucune comparaison avec les cibles du run interrompu |
| plan de reprise | `minos-engine/.../orchestration/IndexingResumePlanner.java` (`decide`, `checkpointFor`, `reusable`) | part des cibles négociées maintenant, jamais des exécutions du run interrompu |
| rétention des runs (`overCount`, `.resumable`) | `minos-runtime-local/src/main/java/com/minos/runtime/local/RunDirectoryRetention.java` (ligne 109 : `overCount = retainedCount > maxEntries \|\| scan.truncated()`, ligne 108 : `expired` mesuré à `resumeTtl` pour un run marqué) | R5 ; appelée depuis `ProcessIndexerExecutor.prepareRunDirectory` (ligne 397), sous le seul `protectedRunRoot` du run courant |
| marqueur `.resumable` | port `minos-engine/.../orchestration/ResumableRunMarkers.java`, adaptateur `minos-runtime-local/.../local/FileResumableRunMarkers.java`, câblage `minos-bootstrap/.../bootstrap/RunDirectoryResumableRunMarkers.java` | posé par `persistInterruption` et `recoverAbandonedRuns`, retiré par `supersede`/`unmarkQuietly` |
| réconciliateur « snapshot stable » | `minos-engine/.../orchestration/AuthoritativeProjectStateReconciler.java`, `reconcileStableSnapshot` (lignes 162-189) | R7 : écrit un `ProjectIndexState` à 6 arguments (donc sans `resumableRunId`), ne supplante rien |
| rétention des métadonnées de run | `minos-storage-local/src/main/java/com/minos/storage/local/orchestration/IndexRunRetentionService.java` | protège déjà `latestRunId` et `resumableRunId` (métadonnées seulement, pas les répertoires) |
| état projet | `minos-engine/.../orchestration/ProjectIndexState.java` | invariant : `resumableRunId` seulement en `STALE`/`FAILED` |

### 2.2 Ce que fait vraiment le code d'origine (lu, pas déduit de l'audit)

- **R4.** `IndexingResumePlanner.decide` bâtit `reused`/`remaining` à partir des cibles **courantes**. Un module supprimé ne figure plus dans le plan : sa cible n'est ni réutilisée ni à réexécuter, donc `remaining` est vide alors que le snapshot préparé (`stagedSnapshotId`) contient encore son index. `promoteOnly` est alors vrai et le snapshot est promu tel quel.
- **R5, parcours tronqué.** `scan.truncated()` est vrai dès que `runs/` contient plus de 4 096 entrées ; la condition `overCount` devient vraie pour chaque entrée, `.resumable` compris (le tri « marqués en dernier » ne protège rien : la boucle les supprime tous).
- **R5, run concurrent.** `prune(runsRoot, protectedRunRoot)` ne protège que le répertoire du run du processus appelant. Le répertoire d'un run **en cours** dans une autre JVM n'a aucun marqueur (posé seulement à l'interruption) : une seconde indexation, si le budget est dépassé, peut l'effacer, artefacts déjà produits compris.
- **R5, durée de vie.** `expired` compare à `resumeTtl` (24 h) pour un run marqué et à `maxAge` (7 j) sinon : marquer un run interrompu divise sa durée de vie par sept. Le test existant `resumableRunOlderThanTheResumeTtlIsReclaimedEvenUnderBudget` fige ce comportement.
- **R7.** `reconcileStableSnapshot` : quand le snapshot autoritaire diffère de celui de l'état persisté et qu'aucun run n'est à récupérer, l'état devient `READY` (invariant : jamais de `resumableRunId` en `READY`). Le run `INTERRUPTED` désigné par l'ancien `resumableRunId` reste `INTERRUPTED` et marqué ; il n'est plus référencé par aucun état. `recoveredProjectState` (l'autre chemin vers `READY`) le supplante et retire son marqueur.
- Tous les appelants de production du réconciliateur passent par `reconcileUnderExclusiveLease` ; la variante sans bail n'est appelée que par des tests.

### 2.3 Décisions « ce run est reprenable » / « ce répertoire est supprimable » — comptes AVANT

Sites qui **décident** qu'un run est reprenable (production) :

| # | Site | Décision |
|---|---|---|
| 1 | `IndexingResumePlanner.decide` | statut `INTERRUPTED`, format courant, au moins une cible réutilisable |
| 2 | `AuthoritativeProjectStateReconciler.offersResume` | format courant et (point de contrôle ou snapshot préparé) |
| 3 | `IndexingRunExecutor.persistTerminalFailure` | `!committed` et (`checkpointCount() > 0` ou snapshot préparé) — copie de la règle 2 sans le format |
| 4 | `IndexingRunExecutor.executeResumed` (`promoteOnly`) | promotion directe du snapshot préparé |
| 5 | `RunDirectoryRetention.resumableSince` | présence d'un marqueur régulier non lien |
| 6 | `IndexRunRetentionService.compact` | `resumableRunId` protégé de la suppression des métadonnées |

**AVANT : 6 sites.**

Sites qui décident qu'un répertoire de run est supprimable, et règles de durée de vie :

| # | Site | Règle |
|---|---|---|
| 1 | `RunDirectoryRetention.prune` | `maxAge` (7 j, `Policy`), `resumeTtl` (24 h, `Policy`), `maxEntries`, `maxBytes`, `scan.truncated()`, `reclaimFirst` |
| 2 | `RunDirectoryRetention.DEFAULT_RESUME_TTL`, `IndexingResumePlanner.DEFAULT_RESUME_TTL`, `SnapshotRetentionService.DEFAULT_ORPHAN_MAX_AGE` | trois constantes de 24 h à aligner à la main (ADR 0039, écart (f)) |

Règles de durée de vie d'un répertoire de run : **AVANT : 2** (7 j ordinaire, 24 h marqué), dans un seul fichier mais en deux branches indépendantes.

### 2.4 Chiffres de référence des gates (base `017e339d`, avant le premier commit de code)

| Gate | Résultat |
|---|---|
| `check-module-boundaries.py` | `modules=14, sources=504, packages=45` |
| `check-current-docs.py` | SUCCESS |
| `product-facts.py --check` | SUCCESS |
| `check-milestone-artifact-references.py` | `scripts checked=95` |
| `check-jacoco.py` (après `clean verify` de la base, Windows) | 26 portées PASS ; **seule rouge : `m24-polyglot-provider-platform`** (`ManagedPolyglotScipRuntimeManager` line 0,228 < 0,28, branch 0,146 < 0,20), préexistante et propre à Windows. `critical-orchestration` line 0,888 / branch 0,759 ; `resume-orchestration` line 0,903 / branch 0,782 (seuils 0,75 / 0,55) |
| `./mvnw clean verify` (base `017e339d`) | **BUILD SUCCESS**, 15 modules, 11 min 21, **1 671 tests, 0 échec, 0 erreur, 46 ignorés** (hypothèses `Assumptions` préexistantes) |

## 3. Décisions

- **R4 — égalité des clés de cible.** `Outcome.Resume.stagedSnapshotCoversThePlan()` (dans `IndexingResumePlanner`, là où la clé de cible est déjà calculée) : aucune cible à réexécuter **et** l'ensemble des clés des exécutions du run interrompu égal à celui des cibles réutilisées. Une exécution sans point de contrôle n'a pas de clé de cible : elle ne peut être prouvée présente dans le plan, donc l'égalité est fausse (fail-closed). Quand elle est fausse, la reprise continue (les cibles valides sont réutilisées, `runId` conservé) mais le snapshot est **re-préparé** ; le message du run l'écrit (« staged snapshot discarded »). Le snapshot préparé orphelin est balayé par la rétention des `.snapshot-*.tmp` existante.
- **R5 — règle de durée de vie, une seule fois.** `RunDirectoryRetention.Policy.lifetime(boolean marked)` est le seul endroit qui dit combien de temps vit un répertoire de run : `maxAge` pour un run ordinaire, `max(maxAge, resumeTtl)` pour un run marqué, mesuré depuis la plus récente de deux dates (mtime du marqueur, mtime du répertoire). Un run marqué ne vit donc **jamais moins** qu'un run ordinaire (avant : 24 h contre 7 j). Borne supérieure explicite : marqueur ou dernière écriture + `max(maxAge, resumeTtl)` (7 j par défaut) ; passé cela, le run est supprimé même marqué, même parcours tronqué, même sous budget. Le marqueur ne peut pas immobiliser `runs/` : le budget (`maxEntries`, `maxBytes`) s'applique aussi aux runs marqués (ils sont supprimés en dernier, pas jamais).
- **R5 — parcours tronqué.** Un parcours tronqué reste une raison de purger les entrées **non** marquées vues (résidu hostile, comportement inchangé) ; il n'est plus une raison de supprimer un run marqué.
- **R5 — run d'une autre JVM.** Le marqueur `.resumable` devient « répertoire retenu » : il est posé dès le début d'un run neuf (avant tout provider) et retiré à la fin du run, quel qu'en soit le résultat, pas seulement à l'interruption. C'est un fichier : il protège d'une seconde indexation, d'un autre processus comme du même. Un processus tué laisse le marqueur ; la récupération (`recoverAbandonedRuns`) le retire quand elle finalise le run en `FAILED`, et la rétention l'expire de toute façon à la borne ci-dessus. Le port `ResumableRunMarkers` et ses noms ne changent pas (les gates affirment des littéraux).
- **R7 — même point pour tous les chemins.** La branche de réparation utilise le point qui écrit `resumableRunId` et appelle `supersede` (le bloc de `recoveredProjectState` extrait en une méthode partagée) : un projet qui devient `READY` supplante son run offert et retire son marqueur, dans les deux chemins.

## 4. Journal par commit

| Commit | Contenu | Gates |
|---|---|---|
| `cdeac459` | docs : ce suivi, inventaire et décisions | 504 / 45 / SUCCESS / SUCCESS / 95 |
| `74b632da` | docs : chiffres de référence des gates (`clean verify` de la base) | idem |
| `188725e4` | R4 : `stagedSnapshotCoversThePlan` (égalité des clés de cible), snapshot re-préparé sinon, clé de cible calculée à un seul endroit | idem |
| `eb0b65cb` | R5 : `Policy.lifetime` (règle de durée de vie unique), parcours tronqué sans effet sur un run marqué, entrée illisible sans effet sur un run marqué, test de concurrence à barrière | idem |
| `fcec3fd3` | R7 : `endResumeOffer`, même point pour la récupération et la réparation « snapshot stable » | idem |
| `284a2f33` | R5 : run retenu dès son démarrage (`holdRunDirectory`), levé à la fin, levé par la récupération ; `IndexingRun.offersResume` (une copie de la règle en moins) | idem |
| `d5e808ec` | `promoteOnly` : la décision vit entièrement dans le planificateur ; Javadoc des ports ; ADR 0039 écart (k) ; ce journal | idem |
| `cb681858` | docs : preuves de fin de lot (premier `clean verify`) | idem |
| `7b63257b` | V-L1-01 (date dans le futur re-datée), V-L1-02 (vrai parcours tronqué dans le test de borne) | idem |
| `6851f615` | V-L1-03 (répertoire vide retiré avec le marqueur) | idem |
| `52a5d8f2` | ADR 0039 (k) : 24 h / 7 j, date future ; constats de verif-fiab | idem |
| (commit suivant) | preuves de fin de lot au commit final | idem |

Gates rejoués à chaque commit : `check-module-boundaries.py` (« modules=14, sources=504, packages=45 »), `check-current-docs.py` (SUCCESS), `product-facts.py --check` (SUCCESS), `check-milestone-artifact-references.py` (« scripts checked=95 »), `check-minos-01.py` et `check-post-mne.py` (SUCCESS : ils affirment des littéraux de `RunDirectoryRetention`). **Mêmes chiffres que la base** : aucune classe de production ajoutée ni retirée.

## 5. Preuves

### 5.1 Rouge → vert

Chaque test a été joué **avant** le correctif sur le code d'origine (quand l'API neuve n'y était qu'une façade de l'ancien comportement, pour que le test compile), puis après. La sortie rouge est jointe au message du commit correspondant.

| Défaut | Test | Rouge (code d'origine) | Vert |
|---|---|---|---|
| R4 module supprimé après interruption en promotion | `IndexingResumeTest.stagedSnapshotIsNeverPromotedWhenAModuleWasRemovedSinceTheInterruption` | `the stale staged snapshot must never be promoted ==> expected: <false> but was: <true>` | 19/19 |
| R4 exécution sans point de contrôle | `IndexingResumeTest.stagedSnapshotOfAnExecutionWithoutCheckpointIsNotProvablyCurrentAndIsPreparedAgain` | `expected: <false> but was: <true>` | 19/19 |
| R5 parcours tronqué | `RunDirectoryRetentionTest.aTruncatedScanNeverReclaimsARunOfferedForResume` | `a marked run survives a truncated scan: marked-0 ==> expected: <true> but was: <false>` | 16/16 |
| R5 durée de vie du marqueur | `RunDirectoryRetentionTest.markingARunNeverShortensItsLifetimeBelowThatOfAnOrdinaryRun` | `a marker must lengthen the lifetime, never shorten it ==> expected: <true> but was: <false>` | 16/16 |
| R5 entrée illisible d'un run retenu | `RunDirectoryRetentionTest.anEntryThatVanishesOrIsUnreadableNeverDisqualifiesARunHeldByAMarker` | `expected: <false> but was: <true>` | 16/16 |
| R5 run en cours d'une autre indexation | `RunDirectoryRetentionTest.anInFlightRunOfAnotherIndexationSurvivesEveryConcurrentPruneWhileItIsBeingWritten` | `NoSuchFileException … artifact-12` (le run en cours d'écriture est supprimé) | 16/16 |
| R5 run retenu avant le premier provider | `RunDirectoryHoldTest.aFreshRunHoldsItsDirectoryBeforeItsFirstProviderAndLiftsTheHoldOnceSucceeded` | `expected: <[true, true, true]> but was: <[false, false, false]>` | 4/4 |
| R5 run échoué | `RunDirectoryHoldTest.aFailedRunLiftsItsHold` | `expected: <true> but was: <false>` | 4/4 |
| R5 récupération (échec, succès) | `InterruptedRunRecoveryTest.abandonedRunWithoutCheckpoint…`, `…authoritativePromotionRecovery…` | `the hold a crashed run left behind is lifted…`, `a recovered success no longer needs its hold` | 13/13 |
| R7 réparation « snapshot stable » | `InterruptedRunRecoveryTest.stableSnapshotRepairSupersedesTheOfferedRunAndLiftsItsMarker` | `the offered run is finalized, not left INTERRUPTED ==> expected: <FAILED> but was: <INTERRUPTED>` | 13/13 |

Tests de borne (verts avant et après, ils gardent l'invariant « le marqueur n'immobilise pas `runs/` ») : `aMarkedRunAlwaysExpiresAtTheExplicitUpperBound` (supprimé à la borne + 1 s, conservé à la borne − 1 s) et `aMarkedRunAlsoExpiresAtTheUpperBoundWhenTheScanIsTruncated` (vrai parcours tronqué), `markedRunsRemainBoundedByTheCountBudget` (20 runs marqués, budget 16 : les 4 plus anciens partent), `theMarkerLengthensTheLifetimeWhenTheResumeTtlExceedsTheMaximumAge` (`maxAge` 2 h, `resumeTtl` 24 h : le run marqué survit, l'ordinaire non), `aTruncatedScanStillReclaimsUnmarkedRunsItObserved` (la purge du résidu hostile observé est inchangée).

Un test existant a été remplacé parce qu'il figeait le défaut : `resumableRunOlderThanTheResumeTtlIsReclaimedEvenUnderBudget` (marqueur de 25 h ⇒ supprimé). Un autre a été précisé : `threadInterruptionWithoutAnyCheckpointStillFails` affirmait qu'aucun marqueur n'était jamais posé ; il affirme maintenant qu'aucun marqueur ne survit au run.

### 5.2 Tests de concurrence (rejoués 50 fois)

Un seul test de concurrence dans ce lot : `RunDirectoryRetentionTest.anInFlightRunOfAnotherIndexationSurvivesEveryConcurrentPruneWhileItIsBeingWritten`. Deux threads (un « run en cours d'une autre indexation » qui écrit 20 artefacts dans son répertoire marqué, une « seconde indexation » qui enchaîne 20 `prune` sous pression de budget et parcours tronqué) sont lancés sur une `CyclicBarrier` et attendus par `Future.get` ; aucun `Thread.sleep`, aucune attente calibrée. Le test boucle lui-même sur **50 tours** (constante `CONCURRENCY_ROUNDS`, répertoire et barrière neufs à chaque tour). Rejeu de l'exécution complète de la classe par 50 lancements Maven successifs (`-Dsurefire.rerunFailingTestsCount=0`, script `replay.sh` du scratchpad, arbre `fiab-base`) : **50 passages sur 50, 0 échec**, soit 2 500 tours de barrière sans échec, sur `eb0b65cb` (première version) puis de nouveau **50 sur 50** sur `6851f615` (après V-L1-01 et V-L1-03, qui touchent la rétention et le marqueur). Les autres tests du lot (R4, R7, `RunDirectoryHoldTest`, bornes de rétention) sont déterministes et mono-thread : ils n'ont aucune synchronisation à prouver.

### 5.3 Comptes AVANT / APRÈS

Sites qui décident qu'un run est reprenable :

| # | Avant | Après |
|---|---|---|
| 1 | `IndexingResumePlanner.decide` | `IndexingResumePlanner` (`decide`, plus `Resume.stagedSnapshotKnown` / `stagedSnapshotCoversThePlan`, qui remplacent `promoteOnly` de l'exécuteur) |
| 2 | `AuthoritativeProjectStateReconciler.offersResume` | `IndexingRun.offersResume()` (une seule règle, appliquée par la récupération **et** par l'interruption) |
| 3 | `IndexingRunExecutor.persistTerminalFailure` (copie de la règle 2) | supprimé |
| 4 | `IndexingRunExecutor.executeResumed` (`promoteOnly`) | supprimé (délégué au planificateur, site 1) |
| 5 | `RunDirectoryRetention.resumableSince` | inchangé |
| 6 | `IndexRunRetentionService.compact` | inchangé |
| **Total** | **6** | **4** |

Sites qui décident qu'un répertoire de run est supprimable : `RunDirectoryRetention.prune` seul, **1 avant, 1 après** (`IndexRunRetentionService.compact` ne supprime que des métadonnées).

Règles de durée de vie d'un répertoire de run : **2 avant** (7 j ordinaire / 24 h marqué, deux branches indépendantes de `prune`), **1 après** (`Policy.lifetime`, appelée par `prune` ; aucune autre durée n'y est comparée). Les trois constantes de 24 h de l'écart (f) de l'ADR restent alignées à la main (un test de chaque côté en affirme la valeur) ; ce n'est pas ce lot.

Calcul d'une clé de cible : `checkpointFor` en construisait deux à la main (cible courante, exécution du run) ; R4 en avait besoin d'une troisième fois. `targetKey` et `executionKey` (dans `IndexingResumePlanner`) sont désormais les seuls appelants de `IndexingRun.targetKey`, partagés par `checkpointFor` et `stagedSnapshotCoversThePlan` : le nombre de copies du calcul n'a pas augmenté.

### 5.4 Fin de lot

`./mvnw clean verify` complet dans le worktree (journal dans le scratchpad, pas dans `target/`) sur `52a5d8f2` (code identique à `6851f615`, seule la documentation diffère) : **BUILD SUCCESS**, 15 modules, 12 min 06, **1 691 tests exécutés, 0 échec, 0 erreur, 46 ignorés** (hypothèses `Assumptions` préexistantes, aucun `@Disabled` ajouté ; 1 671 à la base, +20 : `RunDirectoryHoldTest` 4, `RunDirectoryRetentionTest` +10 (11 ajoutés, 1 remplacé), `FileResumableRunMarkersTest` +2, `IndexingResumeTest` +2, `InterruptedRunRecoveryTest` +2). Un premier `clean verify` sur `d5e808ec` (avant les constats de verif-fiab) avait donné 1 686 tests, 0 échec.

| Gate | Base `017e339d` | Fin de lot |
|---|---|---|
| `check-module-boundaries.py` | `modules=14, sources=504, packages=45` | identique |
| `check-current-docs.py`, `product-facts.py --check` | SUCCESS | SUCCESS |
| `check-milestone-artifact-references.py` | `scripts checked=95` | `scripts checked=95` |
| `check-minos-01.py`, `check-post-mne.py` | SUCCESS | SUCCESS |
| `check-jacoco.py` | 26 PASS, seule rouge `m24-polyglot-provider-platform` (line 0,228 < 0,28, préexistante, Windows) | 26 PASS, **même unique rouge**, mêmes chiffres |
| `critical-orchestration` (line / branch) | 0,888 / 0,759 | 0,891 / 0,773 |
| `resume-orchestration` (line / branch) | 0,903 / 0,782 | 0,906 / 0,787 |

Golden : les 12 de `characterization/` **inchangés** (`git diff 017e339d..HEAD -- minos-app/src/test/resources scripts` vide) ; aucun script de `scripts/` assoupli.

**Linux.** WSL Ubuntu (Java 24), module `minos-engine` (paquet `com.minos.orchestration`, 119 tests) et `minos-runtime-local` (`RunDirectoryRetentionTest` 19 dont 1 ignoré : la jonction NTFS, propre à Windows ; `FileResumableRunMarkersTest` 5), rejoués sur le code final : verts. Le `clean verify` complet et le rejeu des 50 lancements n'ont été faits que sous Windows.

## 6. Constats de verif-fiab

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L1-01 | à corriger | la borne de 7 jours n'est pas robuste à un marqueur daté dans le futur (saut d'horloge) : `ageReference` n'était pas plafonnée | **résolu `7b63257b`**. Plafonner à `now` ne suffisait pas (le run serait neuf à chaque passage donc jamais expiré) : la première passe qui voit la date impossible la re-date à now (répertoire et marqueur, au mieux), la durée de vie compte alors depuis ce moment. Test rouge rejoué sur le code précédent : `a future-dated marker expires at most one lifetime after now ==> expected: <false> but was: <true>` ; `aMarkerDatedInTheFutureNeverPinsARunBeyondOneLifetimeFromWhenItWasSeen` et `aRunDirectoryDatedInTheFutureIsRedatedAndExpiresLikeAnyOther` |
| V-L1-02 | à corriger | le test de la borne « même parcours tronqué » ne tronquait pas (budget de parcours 4096 pour 2 répertoires) | **résolu `7b63257b`**. Aucun défaut de production : test scindé, la borne avec un vrai parcours tronqué (budget d'une entrée par passe) est `aMarkedRunAlsoExpiresAtTheUpperBoundWhenTheScanIsTruncated` ; ce fichier § 5.1 corrigé |
| V-L1-03 | remarque | (a) un `unmark` qui échoue (verrou antivirus) laisse un run terminal marqué jusqu'à 7 j ; (b) `mark` crée `runs/<runId>/` et `unmark` n'en retire que le fichier : un run qui n'écrit rien laisse un répertoire vide compté dans `maxEntries` | (b) **résolu `6851f615`** : `unmark` retire le répertoire s'il est vide (test rouge rejoué : `an empty run directory goes away with its marker ==> expected: <false> but was: <true>`). (a) documenté § 7 : borné par la durée de vie et par le budget, avertissement journalisé par `unmarkQuietly` |
| V-L1-04 | remarque | `resumeTtl` 24 h (planificateur) contre 7 j (répertoire marqué) : jusqu'à 6 jours d'artefacts retenus pour une reprise déjà refusée | **résolu (documenté)** : écrit dans l'ADR 0039 (k) comme conséquence assumée d'un marqueur qui ne raccourcit jamais la vie ; § 7 |
| V-L1-05 | remarque | la re-datation d'une date future est « au mieux » : si `setLastModifiedTime` échoue en permanence (marqueur en lecture seule, verrou), la date future reste traitée comme `now` à chaque passe et le run ne vieillit jamais | **accepté, documenté § 7** : cas conjugué (horloge avancée et fichier non modifiable) ; le budget de nombre et de volume borne toujours la taille ; un avertissement sans chemin est journalisé à chaque échec |

## 7. À traiter plus tard

- **Publication atomique et rétention concurrente sous Windows.** Un premier essai du test de concurrence, où le « fournisseur » publiait par `Files.move(ATOMIC_MOVE)` pendant que `prune` mesurait le même répertoire, a échoué sous Windows avec `FileSystemException … utilisé par un autre processus` : la lecture des attributs par la rétention entre en conflit avec le renommage. Hors périmètre (le test a été ramené à des écritures simples). À vérifier dans `DurableAtomicFile.replace`, qui n'a pas de nouvelle tentative : deux indexations de projets différents sur un même `MINOS_HOME` Windows peuvent-elles s'y gêner ?
- **Trois constantes de 24 h** (`IndexingResumePlanner.DEFAULT_RESUME_TTL`, `RunDirectoryRetention.DEFAULT_RESUME_TTL`, `SnapshotRetentionService.DEFAULT_ORPHAN_MAX_AGE`) : alignées à la main, chacune affirmée par un test local. Les fusionner demande un module commun aux deux côtés du port (ADR 0039, écart (f)).
- **Un snapshot préparé écarté par R4 reste sur disque** jusqu'au balayage des `.snapshot-*.tmp` (24 h). Les snapshots préparés sous leur nom final ne sont balayés par aucune rétention : à examiner avec le lot 2 (Q4).
- **`RunDirectoryRetention.prune` ne tourne qu'au début d'une exécution de provider.** Si plus aucune indexation n'a lieu, les répertoires expirés restent jusqu'à la suivante : comportement d'origine, non modifié.
- **Un `unmark` qui échoue laisse un run terminal marqué** (verrou antivirus Windows, V-L1-03 a) : `unmarkQuietly` journalise un avertissement et la rétention l'expire à `max(maxAge, resumeTtl)` (7 j), après tous les runs non marqués et sous le budget de nombre et de volume. Les runs terminaux ne sont jamais réconciliés : rien ne le lèvera plus tôt. Borné, pas une fuite ; à reconsidérer si les avertissements sont fréquents.
- **Artefacts d'un run marqué entre 24 h et 7 j** : refusés par le planificateur (TTL de 24 h), conservés par la rétention (V-L1-04, ADR 0039 (k)). Réduire la fenêtre demande de décider si les artefacts d'un run non reprenable servent encore au diagnostic.
- **Re-datation impossible d'une date future** (V-L1-05) : si le marqueur est en lecture seule ou verrouillé en permanence *et* que l'horloge a sauté en avant, la borne de durée de vie est perdue pour ce run (il reste borné par le budget de nombre et de volume). Une alternative serait de supprimer le marqueur dont la date est impossible et non modifiable ; non retenue ici : supprimer une protection sur un doute est le mauvais sens pour un run peut-être en cours.

## 8. Lot 2 — P1, Q3, Q4 : un seul régime de verrous

> Branche `fiab/p1-q3-q4-verrous`, créée depuis la branche du lot 1 (`fiab/r4-r5-r7-reprise`, `bcdadd23`), worktree `minos-wt/fiab-lot2`. Constats : **P1** (la lecture d'état prend le bail exclusif puis réécrit l'état), **Q3** (publication, promotion et compaction d'empreintes non exclusives), **Q4** (PLAUSIBLE : la rétention tourne sous un verrou distinct du bail de cycle de vie). Règle du lot : **un ordre de prise unique et documenté ; aucun mécanisme d'exclusion ajouté, l'existant est réutilisé ou fusionné.**

### 8.1 Cibles relocalisées (base `bcdadd23`)

| Cible | Emplacement réel | Constat |
|---|---|---|
| `FileProjectFingerprintSnapshotStore` (`publish`, `promote`, `compact`) | `minos-storage-local/.../storage/local/incremental/FileProjectFingerprintSnapshotStore.java` | `publish` : « existe-t-il un fichier pour cet identifiant ? » puis publication, sans exclusion ; `promote` : lecture du snapshot puis remplacement du pointeur, sans exclusion ; `compact` : seul `synchronized` (moniteur de l'instance, donc invisible d'un autre processus et des deux autres méthodes) |
| bail de cycle de vie exclusif de 10 s | `minos-storage-local/.../orchestration/ProjectIndexLease.java` (fichier `index-state/locks/indexing/<projectId>.lock`), pris par `FileIndexStateStore.acquireProjectLease` (réentrant par thread et par instance de store), exposé par le port `IndexStateStore.acquireProjectLease`, tenu par `IndexingLifecycleService.run()` | l'ADR 0039 cite encore l'ancien chemin `minos-application/.../ProjectIndexLease` et `MINOS_HOME/locks/indexing` |
| lecture d'état qui prend le bail puis réécrit | `minos-engine/.../orchestration/IndexingLifecycleService.projectState(UUID)` (bail, puis `AuthoritativeProjectStateReconciler.reconcileUnderExclusiveLease` qui termine les runs abandonnés et réécrit l'état) ; et, sur le chemin réel de `minos_index_status`, `MinosApplicationMcpBackend.indexStatus` → `ProjectInspectionService.view` → `ProjectIndexStateReconciler.reconcile(projectId)` (persistant) qui prend le bail dès que le snapshot actif n'est pas celui que l'état référence | P1 : deux chemins de lecture qui écrivent |
| rétention et son verrou | `minos-storage-local/.../storage/local/LocalStorageRetentionService.java` : `FileChannel.lock()` brut sur `retention-locks/<projectId>.lock` ; appelle `SnapshotCompactionService.compactWithActiveSnapshot` (bail de mutation), `FileProjectFingerprintSnapshotStore.compact`, `IndexRunRetentionService.compact` | Q4 : ni bail de cycle de vie, ni bail de mutation autour de l'ensemble ; seuls appelants de production : quatre sites de `LocalAutonomousIndexOperations.executeLocked`, tous sous le bail de cycle de vie **par convention** (rien ne l'impose) |
| bail de mutation de snapshot | `minos-storage-local/.../store/SnapshotProjectLease.java` (fichier `.project-mutation-leases/<projectId>.lock` au parent commun des stores frères, donc `MINOS_HOME/.project-mutation-leases/`) | partagé par `FileSymbolSnapshotStore.publishSnapshot`, `SnapshotCompactionService`, `SnapshotRetentionService`, `ProjectMutationSemanticVectorStore` ; **pas** par le store d'empreintes |
| snapshot « préparé » | `minos-provider-scip/.../ScipProjectSnapshotLifecycle` : `stage` écrit dans `MINOS_HOME/staged-snapshots/<runId>/project/` (un `FileSymbolSnapshotStore` privé), `promote` republie dans `symbol-snapshots` | aucun code de rétention ne parcourt `staged-snapshots/` : ni suppression, ni balayage (voir § 8.3) |

### 8.2 Inventaire des verrous et baux sous `MINOS_HOME`

Notation : **portée** = `thread` (verrou JVM réentrant), `JVM` (moniteur/verrou en mémoire), `inter-processus` (verrou fichier du système d'exploitation, `FileLock`). Tous les baux `BoundedFileLease` ont **un délai d'acquisition de 10 s** (un seul `LeaseDeadline` partagé par le verrou JVM strié et le verrou fichier), sont propres au thread propriétaire, ouverts `NOFOLLOW_LINKS`, et échouent en `IOException` au délai.

**Famille « un projet » (celle de ce lot) :**

| # | Nom | Fichier / objet | Pris par | Tenu pendant | Portée | Réentrance / délai |
|---|---|---|---|---|---|---|
| L1 | bail de **cycle de vie** | `index-state/locks/indexing/<projectId>.lock` (`ProjectIndexLease`) | `IndexingLifecycleService` (`run`, `planned`, `projectState`, `withProjectLease`), `LocalAutonomousIndexOperations.execute` (toute l'indexation : préparation, run, empreinte, sémantique, rétention), `LocalProjectOperations.importScip`, `ProjectIndexStateReconciler.reconcile` (si réparation), `IncrementalIndexingCoordinator.refresh` | le cycle complet d'une indexation (heures possibles) | thread + JVM (verrou par chemin, compté) + inter-processus | réentrant **par thread et par instance de `FileIndexStateStore`** (compteur en `ThreadLocal`), 10 s |
| L2 | bail de **mutation de snapshot** | `.project-mutation-leases/<projectId>.lock` (`SnapshotProjectLease`) | `FileSymbolSnapshotStore.publishSnapshot` (publication + promotion du pointeur), `SnapshotCompactionService.compactWithActiveSnapshot`, `SnapshotRetentionService` (`applyPolicy`, `deleteHistoricalSnapshots`, `deleteOrphanPreparedSnapshots`), `ProjectMutationSemanticVectorStore` (`replace`, `replaceConditionally`, `delete`) | une publication, une passe de compaction, un remplacement sémantique (secondes) | thread (64 verrous striés) + inter-processus | **non réentrant** : un second `acquire` du même thread réussit le verrou JVM puis boucle sur `OverlappingFileLockException` jusqu'au délai de 10 s, puis `IOException` ; 10 s |
| L3 | verrou de **rétention** | `retention-locks/<projectId>.lock` (`LocalStorageRetentionService`) | `LocalStorageRetentionService.compact` | une compaction complète (snapshots, empreintes, métadonnées de run) | inter-processus seulement ; **aucune couche JVM** : un second `compact` du même processus lève `OverlappingFileLockException` (non contrôlée) au lieu d'attendre | `FileChannel.lock()` **sans délai**, contrairement à tous les autres baux ; fichier créé sans `NOFOLLOW` ni droits privés |
| L4 | verrou de **synchronisation sémantique** | `semantic-index/.sync-locks/<projectId>.lock` (`FileSemanticVectorStore.replaceConditionally`) | `replaceConditionally`, toujours **sous L2** quand le store passe par `ProjectMutationSemanticVectorStore` (câblage de `LocalStorageBackend`) | la vérification « le snapshot actif n'a pas changé » et le remplacement | thread (table de verrous compté) + inter-processus | 10 s ; redondant avec L2 dans le câblage réel, utile quand le store est employé seul |
| M | moniteur de l'**état d'index** | `synchronized` sur 6 méthodes de `FileIndexStateStore` (`findProjectState`, `findRun`, `listRuns`, `saveProjectState`, `saveRun`, `deleteRun`…) | toute lecture ou écriture de l'état | un appel (I/O locales) | JVM, **par instance** | aucun bail n'est jamais pris depuis l'intérieur : feuille |
| FPM | moniteur de la **compaction d'empreintes** | `public synchronized … compact` de `FileProjectFingerprintSnapshotStore` | `LocalStorageRetentionService` | une compaction | JVM, par instance ; **ne couvre ni `publish` ni `promote` ni les autres processus** | — |

**Feuilles hors famille projet** (aucun n'est jamais pris avec L1 ou L2 tenus, ils ne participent à aucun cycle) :

| Nom | Fichier / objet | Portée |
|---|---|---|
| registre de projets | `registry/.registry.lock` (`InterProcessLocalProjectRegistry`, global, pas par projet) | thread (64) + inter-processus, 10 s |
| observations d'exécution | `runtime-observations/<projet>/.lock` (`FileRuntimeObservationStore`) précédé d'un verrou JVM strié (`SerializedRuntimeObservationStore`) | idem, 10 s |
| plan de contrôle hébergé | `<tenantId>.lock` (`FileHostedControlPlaneStore`, `FileChannel.lock()` brut) | thread + inter-processus, sans délai (hors périmètre, hébergé) |
| indexation distante | `remote-index-leases/<sha>.lock` (`RemoteIndexLease`, cli) ; verrou de matérialisation et baux de cache (`JGitRemoteRepositoryMaterializer`, `SharedCacheLeaseRegistry`, `DistributedArtifactBundleStore`) | par source ou clé de cache, 10 s |
| verrous purement mémoire | `FileSymbolSnapshotStore` (verrous de construction de vue et cache), `ProgramGraphService`, `SemanticSearchService`, `HybridSearchService`, `InMemoryIndexStateStore` | JVM |

### 8.3 Répertoires de travail sous `MINOS_HOME` qui touchent ce lot

| Répertoire | Propriétaire | Durée de vie / nettoyage | Protégé par |
|---|---|---|---|
| `index-state/projects/<id>.properties`, `index-state/runs/<id>/<runId>.properties`, `.by-id/` | `FileIndexStateStore` | métadonnées : `IndexRunRetentionService` (protège `latestRunId` et `resumableRunId`) ; écriture : temporaire `.state-*.tmp` puis `DurableAtomicFile.replace` | M pour les accès d'une instance ; L1 pour l'exclusivité entre indexations ; **aucun verrou pour les lecteurs d'un autre processus** (remplacement atomique) |
| `symbol-snapshots/<id>/` : `snapshot-*.bin`, `active.pointer`, `.snapshot-*.tmp` | `FileSymbolSnapshotStore` | historique borné par `SnapshotRetentionService` (sous L2) ; `.snapshot-*.tmp` balayés après 24 h (sous L2) | L2 |
| `fingerprint-snapshots/<id>/` : `fingerprint-*.bin`, `active.pointer`, `.fingerprint-*.tmp`, `.active-*.tmp` | `FileProjectFingerprintSnapshotStore` | historique borné par `compact` ; les temporaires ne sont supprimés qu'à la fin de l'appel qui les a créés (un processus tué en laisse, jamais balayés) | **rien** avant ce lot (FPM pour `compact` seulement) |
| `staged-snapshots/<runId>/project/` (snapshot **préparé**) | `ScipProjectSnapshotLifecycle` | supprimé par `promote` après la publication, ou par le prochain `stage` du même run ; **aucun balayage** d'un run abandonné | le bail de cycle de vie L1 du projet, par convention ; aucune rétention ne le parcourt |
| `runs/<runId>/…` (artefacts des providers, `.resumable`) | `ProcessIndexerExecutor`, `RunDirectoryRetention` | règle de durée de vie unique du lot 1 (`Policy.lifetime`) ; marqueur « répertoire retenu » | aucun bail : ce répertoire est **global à tous les projets**, donc un bail par projet ne peut pas le protéger ; c'est le marqueur de fichier du lot 1 qui le fait |
| `retention-locks/`, `.project-mutation-leases/`, `index-state/locks/indexing/`, `semantic-index/.sync-locks/` | les baux ci-dessus | les fichiers de verrou ne sont jamais supprimés (le verrou est sur le descripteur, pas sur l'existence du fichier) | — |

### 8.4 Graphe de prise AVANT (base `bcdadd23`)

Arête `A → B` : B est pris alors que A est déjà tenu. Les flux qui prennent plusieurs verrous :

```
indexation CLI (LocalAutonomousIndexOperations.execute)
  L1 ──► M                                  (état)                      // run, reprise, finalisation
  L1 ──► L2                                 (activeStore.publish)       // promotion
  L1 ──► L2 ──► L4                          (ProjectMutationSemanticVectorStore.replaceConditionally)
  L1 ──► L3 ──► L2 ──(relâché)──► FPM ──► M (LocalStorageRetentionService.compact : snapshots, empreintes, runs)
  L1 ──► (publication d'empreintes : aucun verrou)                      // Q3
lecture d'état minos_index_status
  (aucun verrou)  ou, si le snapshot actif n'est pas celui de l'état :  L1 ──► M (écriture)   // P1
IndexingLifecycleService.projectState
  L1 ──► M (écriture)                                                   // P1
rétention appelée sans L1 (l'API le permet, aucun appelant actuel)
  L3 ──► L2 ──► FPM ──► M                                               // Q4 : ne connaît pas L1
```

Pas de cycle dans le graphe d'origine : L1 est toujours pris avant L2, L2 avant L4, M est une feuille ; L3 n'est jamais tenu par un appelant de L1 qui le prendrait à rebours. Mais **l'ordre n'est écrit nulle part**, L3 n'est ordonné par rapport à L1 que par la position des appels dans `executeLocked`, et FPM n'est ordonné par rien. Un appelant futur qui prendrait L2 puis L1 (par exemple une réparation dans un chemin de lecture sous mutation) fermerait un cycle sans qu'aucun test ne l'attrape.

### 8.5 Ce que fait le code d'origine (lu)

- **P1.** `IndexingLifecycleService.projectState` : bail L1 puis `reconcileUnderExclusiveLease`, qui finalise comme abandonné tout run `RUNNING` (en présupposant que le détenteur du bail est mort) et réécrit l'état. Pendant une indexation (bail tenu ailleurs) l'appel attend 10 s puis lève `UncheckedIOException`. Sur le chemin de l'outil MCP, `ProjectIndexStateReconciler.reconcile(projectId)` lit sans verrou quand l'état référence le snapshot actif, mais, dès que le snapshot actif a avancé (promotion faite, état pas encore écrit : la fenêtre dure jusqu'à la fin du run), il prend L1 pour réparer : même attente, même échec.
- **Q3.** `publish` : `filesForIdHash` puis `DurableAtomicFile.publish` sans exclusion ; le nom du fichier contient la somme de contrôle du contenu, donc deux publications de contenus différents pour un même identifiant écrivent deux fichiers distincts et `load` / `promote` répondent ensuite `multiple fingerprint snapshots` **de façon permanente**. `compact` peut supprimer le fichier qu'une `promote` en cours vient de lire et va référencer.
- **Q4.** `LocalStorageRetentionService.compact` calcule son ensemble protégé (snapshot actif de l'état + snapshot de connaissance actif) puis compacte les empreintes. Un snapshot d'empreintes publié entre ces deux instants par un cycle de vie actif, et pas encore promu, n'appartient pas à l'ensemble protégé ; rien n'empêche la rétention de s'exécuter en même temps que le cycle de vie (elle ne prend pas L1). Aucun appelant de production ne le fait aujourd'hui ; c'est une propriété de l'API, pas du câblage. L'énoncé de l'audit (un snapshot « préparé » est celui de `staged-snapshots/`) ne tient pas : aucune rétention n'y touche.

### 8.6 Ordre de prise retenu (décision, écrite avant le code)

Un seul ordre total, à respecter par tout code qui prend plusieurs verrous d'un même projet :

1. **L1** bail de cycle de vie (`IndexStateStore.acquireProjectLease`) ;
2. **L2** bail de mutation de snapshot (`SnapshotProjectLease`) ;
3. **L4** verrou de synchronisation sémantique (toujours sous L2) ;
4. **M** moniteurs en mémoire (feuille) ; puis les feuilles hors famille (registre, observations, distant), qui ne sont jamais tenues avec L1 ou L2.

Règles : (a) on ne prend **jamais** L1 en tenant L2 ou L4 ; (b) **toute mutation du stockage d'index d'un projet, rétention comprise, se fait sous L1** (réentrant pour le thread qui le tient déjà) ; (c) **toute mutation d'un store partagé entre processus se fait sous L2**, y compris le store d'empreintes ; (d) une **lecture ne prend ni L1 ni L2 et n'écrit pas**.

Conséquences prévues, sans nouveau mécanisme : le store d'empreintes réutilise L2 (la classe `SnapshotProjectLease`, dont la documentation prévoit déjà ce rôle pour les stores frères sous `MINOS_HOME`), ce qui retire le moniteur FPM ; la rétention prend L1 au lieu de L3 (`retention-locks`), ce qui retire L3 (et son attente sans délai). Les lectures d'état n'ont plus de chemin vers L1 (P1).

### 8.7 Comptes AVANT

Mécanismes d'exclusion de la famille « un projet » : **6** (L1, L2, L3, L4, M, FPM), dont **3 fichiers indépendants sans ordre écrit** (L1, L2, L3) et **2 moniteurs JVM sans couverture inter-processus** (M, FPM), plus L4 imbriqué sous L2. Chemins de lecture d'état qui prennent L1 : **2** (`IndexingLifecycleService.projectState`, `ProjectIndexStateReconciler.reconcile` persistant, ce dernier via `ProjectInspectionService.view` et donc `minos_index_status`). Appels du store d'empreintes qui mutent sans aucun verrou inter-processus : **3** (`publish`, `promote`, `compact`).

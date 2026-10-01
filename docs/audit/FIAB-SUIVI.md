# Suivi — chantier Fiabilité opérationnelle (lot 1 : R4, R5, R7 — intégrité de la reprise)

> Branche : `fiab/r4-r5-r7-reprise` (depuis `develop`, base `017e339d`, les branches `code/*` des PR #305 à #308 y sont déjà fusionnées), worktree `minos-wt/fiab-lot1`.
> Constats : **R4** (promotion reprise sans égalité des cibles), **R5** (rétention d'un run reprenable, durée de vie du marqueur) et **R7** (réparation « snapshot stable » sans `resumableRunId` ni `supersede`), `AUDIT-2026-09.md` § R4, R5, R7 ; conception de référence : [ADR 0039](../adr/0039-reprise-indexation-apres-interruption.md).
> Agents : `impl-fiab` (implémentation), `verif-fiab` (inspection de chaque commit, en parallèle). Aucun push, aucune PR ouverte par les agents de lot.
> Règles du lot : aucun changement de comportement non voulu ; test rouge avant correctif, sortie rouge jointe au message du commit ; chaque test de concurrence rejoué 50 fois, sans `Thread.sleep` de synchronisation ; une règle de durée de vie écrite **une seule fois**.
> Ce fichier est repris tel quel par les lots suivants (2 à 5) : les sections « constats de verif-fiab » et « à traiter plus tard » s'y accumulent, l'inventaire des verrous du lot 2 viendra s'ajouter à la suite.

## 1. Tableau de bord des cinq lots

| Lot | Contenu | Statut | Commits |
|---|---|---|---|
| 1 — R4, R5, R7 | Promotion reprise bornée aux cibles courantes, rétention d'un run reprenable (parcours tronqué, run concurrent, durée de vie), réparation « snapshot stable » alignée | livré, en attente du verdict final de `verif-fiab` | `cdeac459` … (§ 4) |
| 2 — P1, Q3, Q4 | Un seul régime de verrous ; lecture d'état sans bail exclusif | à faire | — |
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

- **SonarCloud, PR 309 (Quality Gate passé, 4 issues)** : `S1186` (`RunDirectoryHoldTest`, méthode vide) et `S3776` (`RunDirectoryRetention.measure`, complexité 16 pour 15) corrigées dans le même lot. Restent : `S6539` (`IndexingRunExecutor`, 25 dépendances pour 20 autorisées : la classe est découpée par le chantier Architecture, pas ici) et `S9391` (`IndexingResumePlanner`, boucle à remplacer par un flux : purement stylistique). Non traitées pour ne pas élargir le périmètre.

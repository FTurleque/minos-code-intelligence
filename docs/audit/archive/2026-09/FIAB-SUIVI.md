# Suivi — chantier Fiabilité opérationnelle (lot 1 : R4, R5, R7 — intégrité de la reprise ; lot 2 : P1, Q3, Q4 — un seul régime de verrous, § 8 ; lot 3 : R2, R3 — propriété des cgroups, § 9 ; lot 4 : Q5, R6 — interruption et confinement, § 10 ; lot 5 : Q8, Q9 — tolérance aux données abîmées, § 11)

> Branche : `fiab/r4-r5-r7-reprise` (depuis `develop`, base `017e339d`, les branches `code/*` des PR #305 à #308 y sont déjà fusionnées), worktree `minos-wt/fiab-lot1`.
> Constats : **R4** (promotion reprise sans égalité des cibles), **R5** (rétention d'un run reprenable, durée de vie du marqueur) et **R7** (réparation « snapshot stable » sans `resumableRunId` ni `supersede`), `AUDIT-2026-09.md` § R4, R5, R7 ; conception de référence : [ADR 0039](../../../adr/0039-reprise-indexation-apres-interruption.md).
> Agents : `impl-fiab` (implémentation), `verif-fiab` (inspection de chaque commit, en parallèle). Aucun push, aucune PR ouverte par les agents de lot.
> Règles du lot : aucun changement de comportement non voulu ; test rouge avant correctif, sortie rouge jointe au message du commit ; chaque test de concurrence rejoué 50 fois, sans `Thread.sleep` de synchronisation ; une règle de durée de vie écrite **une seule fois**.
> Ce fichier est repris tel quel par les lots suivants (2 à 5) : les sections « constats de verif-fiab » et « à traiter plus tard » s'y accumulent, l'inventaire des verrous du lot 2 viendra s'ajouter à la suite.

## 1. Tableau de bord des cinq lots

| Lot | Contenu | Statut | Commits |
|---|---|---|---|
| 1 — R4, R5, R7 | Promotion reprise bornée aux cibles courantes, rétention d'un run reprenable (parcours tronqué, run concurrent, durée de vie), réparation « snapshot stable » alignée | livré, en attente du verdict final de `verif-fiab` | `cdeac459` … (§ 4) |
| 2 — P1, Q3, Q4 | Un seul régime de verrous ; lecture d'état sans bail exclusif (§ 8) | en cours (branche `fiab/p1-q3-q4-verrous`, depuis la branche du lot 1 `bcdadd23`) | § 8.8 |
| 3 — R2, R3 | Propriété des cgroups : R2 et R3 déjà fermés sur `develop` (§ 9.1) ; le lot referme les trous restants de la décision « ce cgroup appartient à un MINOS mort » (§ 9.4) | livré, en attente du verdict final de `verif-fiab` (branche `fiab/r2-r3-cgroups`, depuis la branche du lot 2, fusion `8670f2e2`) | § 9.7 |
| 4 — Q5, R6 | Interruption de bout en bout (le drapeau était rétabli avant d'écrire l'état), confinement du chemin d'artefact (PLAUSIBLE reproduit puis corrigé) | livré, `VERDICT-FINAL: ok` de `verif-fiab` (branche `fiab/q5-r6-interruption`, depuis la branche du lot 3 `cab6f74e`) | § 10.6 |
| 5 — Q8, Q9 | Tolérance aux données abîmées (`listProjects`, clé sémantique en double) : Q8 ouvert à la base, corrigé (`project list` sort en 3 sur un inventaire partiel) ; Q9 PLAUSIBLE, non reproduit par le chemin de production, aucun correctif (§ 11) | livré, `VERDICT-FINAL: ok` de `verif-fiab` (branche `fiab/q8-q9-tolerance`, depuis la branche du lot 4 `40100dbf`) | § 11.6 |

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

### Lot 2

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L2-01 | à corriger | le raccourci « état en vol rapporté tel quel » de `observe` (91a23b22) s'appliquait aussi au plan du `--dry-run` : après un crash entre la promotion et l'écriture de l'état, le dry-run annonçait `NONE` (état `INDEXING`/ancien snapshot) alors que le run réel, qui répare sous bail, planifie `FULL_REQUIRED [BASELINE_INDEX_MISMATCH]` | **résolu `55d4091d`** : deux points d'entrée en lecture, `observe` (plan : calcule toujours la réparation en mémoire, comportement d'origine) et `observeStatus` (statut : rapporte l'état en vol tel quel, utilisé par `ProjectInspectionService.view`) ; test rouge rejoué sur `44aca82a` (`expected: <true> but was: <false>`), `observeForADryRunPlanStillComputesTheRepairOfAnInProgressStateLeftByADeadRun` |
| V-L2-02 | remarque | l'ordre de prise écrit dans les Javadoc de `IndexStateStore.acquireProjectLease` et de `ProjectIndexLease` omettait le verrou de rétention L3 | **résolu** (commit suivant) : L3 ajouté aux deux Javadoc ; l'ordre L1 < L3 < L2 < L4 < M est désormais le même aux quatre endroits et dans l'ADR 0039 (l) |
| V-L2-03 | remarque | la règle (b) « toute mutation sous L1 » n'est appliquée par aucun test ni garde : `compact` appelé sans L1 n'est pas attrapé (sonde de verif-fiab : `OverlappingFileLockException` de L3 sur les threads de cycle de vie) | **accepté, documenté § 7** : Q4 non reproductible, la rétention ne prend pas L1 ; son unique appelant le tient ; la fusion L3 → L1 est la suite indiquée si un scénario atteignable apparaît |
| V-L2-04 | remarque | `FileIndexStateStore` (constructeur, `migrateLegacyRuns`) et `findRun` (`migrateLegacyRun`) migrent des fichiers de run du format historique sans bail : le processus qui répond au statut peut donc écrire | **documenté § 7** : préexistant, limité au format historique, idempotent (remplacement atomique, `NoSuchFile` toléré) ; hors périmètre du lot |
| V-L2-05 | remarque | la nouvelle tentative de `DurableAtomicFile` retarde d'environ une seconde l'échec d'un refus d'accès réel, sous L1 ou L2 | **accepté** : bornée, Windows seulement, § 7 |

### Lot 3

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L3-01 | à corriger | R2 et R3 sont déjà fermés sur la base : aucun correctif de production sans test rouge sur un scénario réel | **résolu** : § 9.1 consigne `93ed7905` et `d6e5296d` comme fermant R2 et R3 (le scénario R2 était reproductible, rouge historique dans `RESIDUS-SPRINT-1-SUIVI.md`) ; chaque changement de production du lot suit un test rouge joué sur le comportement d'origine (`ecbde9b9`, avant tous les correctifs) |
| V-L3-02 | bloquant | `parseStartTicks` accepte un champ 22 tronqué (987 pour 987654) : un propriétaire vivant est jugé « PID réutilisé » et son cgroup tué | **résolu `4b919467`** (= F2) : champ 22 cru seulement si l'enregistrement se termine par son saut de ligne et qu'un champ le suit ; tests des quatre coupes de la consigne |
| V-L3-03 | à corriger | un propriétaire vivant d'un autre espace de PID est « absent » ou « réutilisé », son cgroup est tué (W6) ; tuerie réelle rejouée (`unshare --pid --mount-proc`) | **résolu `290514e5`** : option (a), la marque porte les inodes des espaces de PID et de temps ; toute marque d'un autre espace, sans estampille ou d'un format ancien est laissée ; tests de décision, de balayage et sur vrai cgroup (WSL) |
| V-L3-04 | à corriger | une suppression ratée est comptée « récupérée » (« already-empty » faux) ; un cgroup imbriqué peuplé d'un propriétaire mort n'est ni tué ni rapporté | **résolu `2d2c2b5f`** : appartenance récursive, `cgroup.kill` qui atteint les sous-cgroupes, suppression du plus profond au plus haut, un cgroup non supprimé est un résidu nommé dans le WARNING agrégé |
| V-L3-05 | remarque | le balayage plafonne en silence à 4 096 entrées | **résolu `2d2c2b5f`** : `StaleSweep.notExamined` et « N entries were not examined » dans le WARNING |
| V-L3-06 | remarque | `ProcessOwnershipTracker` compare `startInstant` : pas R2 (même JVM, base `btime` figée une fois) | **vérifié, hors périmètre** : § 9.2, § 7 |

### Lot 4

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L4-01 | bloquant | le drapeau est rétabli AVANT la persistance du run INTERRUPTED : avec un vrai magasin, FAILED rendu, run RUNNING sur disque, marqueur retiré, projet `INDEXING` (sondes rejouées par verif-fiab sous Windows et WSL) | **résolu `97aba265`** : tests rouges sur le vrai stockage (`5cd64c30`, `expected: <INTERRUPTED> but was: <FAILED>`) ; le drapeau est effacé pour écrire et rejoué en dernier sur toutes les sorties |
| V-L4-02 | à corriger | une seconde interruption pendant l'écriture de l'état INTERRUPTED est avalée : le run rendu dit INTERRUPTED, le disque dit RUNNING | **résolu `fad14230`** (+ `af85eab9`) : écriture refaite drapeau effacé (3 tentatives au plus), statut rendu fidèle à ce que le magasin tient ; rouge `037bf19f` (`expected: <INTERRUPTED> but was: <RUNNING>`) |
| V-L4-03 | à corriger | un répertoire ANCÊTRE lié vers l'INTÉRIEUR du répertoire de run est accepté (la Javadoc promettait « aucun lien sur le chemin ») ; un artefact frais n'est pas reconfiné avant la mise en snapshot | **résolu `66fd1d17`** : tous les composants parcourus sans suivre de lien (Windows : symlink et jonction ; Linux : `openat`), tous les artefacts reconfinés au début de `stageSnapshot`, Javadoc alignées ; rouge `3c71e622` |
| V-L4-04 | remarque | confinement ignoré quand les marqueurs n'ont pas de répertoire de run ; chemin absolu dans le message d'un artefact illisible (préexistant) | **résolu** : la décision est écrite au § 10.4 ; message sans chemin (`66fd1d17`, rouge `3c71e622`) |
| V-L4-05 | remarque | répertoire de run en nom LONG avec un artefact écrit en nom COURT (8.3) : refusé depuis `66fd1d17`, accepté par `41be5596` | **accepté, documenté § 7** : échec fermé, jamais observé (l'artefact et le marqueur dérivent du même `MINOS_HOME`) ; le deviner demanderait de tolérer un alias dont on ne sait pas s'il est un lien |

### Lot 5

| Id | Sévérité | Constat | Résolution |
|---|---|---|---|
| V-L5-01 | à corriger | `DegradedEntry.reason` laisse passer les caractères de contrôle d'un fichier abîmé : `PublicErrorMessages.sanitize` n'aplatit que CR et LF, le message d'une `DateTimeParseException` recopie la valeur brute ; un fichier forgé écrit ESC/BEL dans les lignes de `project list` (confirmé de bout en bout sur un vrai `MINOS_HOME`, `createdAt=<ESC>[2Jevil`) | **résolu `dc38efee`** : `DegradedEntry.printable`, seul endroit, remplace par `_` tout caractère de contrôle ISO, de format (U+202E…) et tout séparateur de ligne ou de paragraphe ; tests rouges `3d2247a1` (`unexpected character U+1b in: registry entry is unreadable (bad …`), verts `DegradedEntryTest` 6/6 et les deux tests d'intégration |
| V-L5-02 | remarque | (a) `ProjectInspectionService.readHistory` ignore en silence un historique qui est un répertoire ou un lien pendant (`Files.isRegularFile`) : la ligne perd `lastSuccessfulIndexAt` et `providerId` sans trace ; (b) le listage strict (`registerProject`, `ProjectResolver` par nom, `NexusExportService`, `findWorkspace`) échoue toujours sur une entrée abîmée et ignore toujours en silence un répertoire ou un lien pendant | **consigné § 7**. (b) est voulu et documenté (mutations et résolutions fermées, `docs/user/cli.md`) ; (a) préexiste, ne concerne pas l'inventaire, et le corriger ferait échouer `inspect` là où il réussit aujourd'hui |
| V-L5-03 | à corriger | la page arc42 des concepts transverses disait encore « 0/1/2 » | **résolu `dc38efee`** (code 3 ajouté à `08-concepts-transverses.md`) |
| V-L5-04 | remarque | le plugin IntelliJ (`MinosCliClient.resolveProject`) n'accepte que le code 0 pour `project list` : avec un seul projet abîmé il reçoit « exit 3 » et ne résout pas le projet sain ouvert | **consigné § 7** : `minos-intellij` est hors réacteur (Gradle), non touché ; pas une régression (le code était 1 avant) ; correctif : accepter `Set.of(0, 3)` pour cette seule commande, la sortie JSON étant valide et complète |
| V-L5-05 | remarque | Q9 : le refus « collision » au staging n'est pas le constat Q9 mais reste un échec global du run | **consigné § 7** (§ 11.8) : autre mécanisme, préexistant, voulu ; à rouvrir seulement si un plan d'indexation peut produire le cas |
| R-L5-A | remarque | littéraux dupliqués trois fois ou plus dans des fichiers de test neufs (`"registry"`, `"NEVER_INDEXED"`, `"projects"`, `".properties"`, `"ui/app"`, `"sym:a"`, `"degraded"`) | **résolu** (commit suivant) : constantes, aucun changement de comportement |
| R-L5-B | remarque | pour un échec de VUE (historique, état, répertoire illisible) la ligne `UNREADABLE` porte `rootAvailable=false` alors que la racine existe (elle est seulement illisible), et la raison ne dit que le nom de classe de l'exception quand le message porte un chemin (`AccessDeniedException`) | **accepté, consigné § 7** : la ligne est volontairement une ligne « sans information » (aucun fait de découverte n'a pu être établi) ; le nom de classe est le repli de `PublicErrorMessages` |
| R-L5-C | remarque | `listWorkspaces()` reste strict : un seul fichier de projet abîmé fait encore échouer `LocalMinosMultiRepositoryApi.listWorkspaces` | **consigné § 7** (listage strict) |
| R-L5-D | remarque | un échec d'E/S transitoire sur l'état d'un projet (verrou Windows pendant une indexation par exemple) dégrade ce projet en `UNREADABLE` pour cet appel et fait sortir `project list` en 3, sans nouvelle tentative | **accepté, consigné § 7** : explicite et visible, un second appel donne le bon résultat ; la nouvelle tentative appartient à `DurableAtomicFile` / au magasin d'état, pas à l'inventaire |

## 7. À traiter plus tard

- **Publication atomique et rétention concurrente sous Windows (traité au lot 2, § 8.9, `7e1b2903`).** Un premier essai du test de concurrence, où le « fournisseur » publiait par `Files.move(ATOMIC_MOVE)` pendant que `prune` mesurait le même répertoire, a échoué sous Windows avec `FileSystemException … utilisé par un autre processus` : la lecture des attributs par la rétention entre en conflit avec le renommage. Hors périmètre (le test a été ramené à des écritures simples). À vérifier dans `DurableAtomicFile.replace`, qui n'a pas de nouvelle tentative : deux indexations de projets différents sur un même `MINOS_HOME` Windows peuvent-elles s'y gêner ?
- **Trois constantes de 24 h** (`IndexingResumePlanner.DEFAULT_RESUME_TTL`, `RunDirectoryRetention.DEFAULT_RESUME_TTL`, `SnapshotRetentionService.DEFAULT_ORPHAN_MAX_AGE`) : alignées à la main, chacune affirmée par un test local. Les fusionner demande un module commun aux deux côtés du port (ADR 0039, écart (f)).
- **Un snapshot préparé écarté par R4 reste sur disque** jusqu'au balayage des `.snapshot-*.tmp` (24 h). Les snapshots préparés sous leur nom final ne sont balayés par aucune rétention : à examiner avec le lot 2 (Q4).
- **`RunDirectoryRetention.prune` ne tourne qu'au début d'une exécution de provider.** Si plus aucune indexation n'a lieu, les répertoires expirés restent jusqu'à la suivante : comportement d'origine, non modifié.
- **Un `unmark` qui échoue laisse un run terminal marqué** (verrou antivirus Windows, V-L1-03 a) : `unmarkQuietly` journalise un avertissement et la rétention l'expire à `max(maxAge, resumeTtl)` (7 j), après tous les runs non marqués et sous le budget de nombre et de volume. Les runs terminaux ne sont jamais réconciliés : rien ne le lèvera plus tôt. Borné, pas une fuite ; à reconsidérer si les avertissements sont fréquents.
- **Artefacts d'un run marqué entre 24 h et 7 j** : refusés par le planificateur (TTL de 24 h), conservés par la rétention (V-L1-04, ADR 0039 (k)). Réduire la fenêtre demande de décider si les artefacts d'un run non reprenable servent encore au diagnostic.
- **Re-datation impossible d'une date future** (V-L1-05) : si le marqueur est en lecture seule ou verrouillé en permanence *et* que l'horloge a sauté en avant, la borne de durée de vie est perdue pour ce run (il reste borné par le budget de nombre et de volume). Une alternative serait de supprimer le marqueur dont la date est impossible et non modifiable ; non retenue ici : supprimer une protection sur un doute est le mauvais sens pour un run peut-être en cours.
- **(lot 2) `staged-snapshots/<runId>/` n'est balayé par aucune rétention.** Un run tué avant `promote` laisse son snapshot préparé (pouvant peser des centaines de Mo) pour toujours ; même chose pour les `.fingerprint-*.tmp` et `.active-*.tmp` d'un processus tué pendant une publication d'empreinte. Ce n'est pas une suppression (Q4), c'est la fuite de disque que la non-reproduction de Q4 laisse en évidence. À traiter sous L1, avec l'âge de la durée de vie unique du lot 1.
- **(lot 2) Verrou de rétention L3.** `retention-locks/<projet>.lock` est un `FileChannel.lock()` sans délai (les autres baux sont bornés à 10 s, et les gates interdisent `channel.lock()` ailleurs), sans couche JVM (un second `compact` du même processus lèverait `OverlappingFileLockException`, non contrôlée, au lieu d'attendre) et ouvert sans `NOFOLLOW` ni droits privés. Sans danger aujourd'hui parce que son unique appelant tient L1, qui sérialise déjà ; le fusionner dans L1 (la rétention prend L1 elle-même) supprimerait un fichier de verrou, et serait la suite naturelle si un scénario atteignable de Q4 apparaissait.
- **(lot 2) L4 (`semantic-index/.sync-locks`) est redondant avec L2** dans le câblage réel (`ProjectMutationSemanticVectorStore` le prend toujours sous L2) ; il ne sert que lorsque `FileSemanticVectorStore` est employé seul. Candidat à la fusion.
- **(lot 2) Lecture sans bail de l'empreinte active.** `loadActive` lit le pointeur puis le snapshot sans bail : si une promotion et une compaction s'intercalent, le snapshot désigné par le pointeur lu peut avoir été supprimé avant sa lecture (échec transitoire « file is missing », à relire). Le store structurel traite ce cas par une boucle de relecture bornée ; le store d'empreintes non.
- **(lot 2) Un état `INDEXING` abandonné reste visible en lecture.** Une lecture ne répare plus : après un crash entre la promotion et l'écriture de l'état, le statut répond `INDEXING` (avec le bon snapshot actif) jusqu'au prochain run, qui récupère sous bail. Avant, la première lecture qui trouvait le bail libre réparait. Acceptable (le snapshot actif rapporté est juste), à reconsidérer si un client a besoin de la fin d'un run abandonné.
- **(lot 2) Bail de mutation strié à l'échelle de la JVM.** `SnapshotProjectLease` partage 64 verrous JVM entre tous les projets et tous les `MINOS_HOME` du processus : deux projets qui tombent sur la même bande s'attendent. Sans conséquence avec un projet actif à la fois ; à revoir pour un processus qui indexerait plusieurs projets en parallèle.
- **(lot 2) Nouvelle tentative de remplacement : bornée à environ une seconde.** Un lecteur qui tiendrait une cible ouverte plus longtemps ferait échouer le remplacement (erreur de l'écrivain, inchangée dans son type). Les lectures de ces fichiers durent quelques millisecondes.
- **(lot 2) Migrations de runs du format historique sans bail.** Le constructeur de `FileIndexStateStore` (`migrateLegacyRuns`, `migrateRunLocators`) et `findRun` (`migrateLegacyRun`) déplacent ou suppriment des `runs/<id>.properties` du format d'origine : tout processus qui ouvre le store, y compris celui qui répond à un statut, peut donc écrire, sans L1. Préexistant, idempotent, limité aux données historiques ; la règle « une lecture ne mute rien » ne le couvre pas. À encadrer (migration à l'ouverture sous L1, ou à l'écriture seulement) dans un lot dédié (V-L2-04).
- **(lot 2) La règle (b) de l'ordre n'est vérifiée par aucun garde.** Un futur appelant de `LocalStorageRetentionService.compact` sans L1 ne serait pas attrapé (V-L2-03) ; un test d'architecture, ou la fusion de L3 dans L1, le ferait.
- **SonarCloud, PR 309 (Quality Gate passé, 4 issues)** : `S1186` (`RunDirectoryHoldTest`, méthode vide) et `S3776` (`RunDirectoryRetention.measure`, complexité 16 pour 15) corrigées dans le même lot. Restent : `S6539` (`IndexingRunExecutor`, 25 dépendances pour 20 autorisées : la classe est découpée par le chantier Architecture, pas ici) et `S9391` (`IndexingResumePlanner`, boucle à remplacer par un flux : purement stylistique). Non traitées pour ne pas élargir le périmètre.
- **SonarCloud, PR 310 (Quality Gate passé, 3 issues propres au lot 2, corrigées)** : `S1192` (littéral `projectId` dupliqué) et `S3776` (`ProjectIndexStateReconciler.reconcile`, complexité 24 ; `FileProjectFingerprintSnapshotStore.compactLocked`, 16) corrigées par extraction de méthodes, sans changement de comportement (`reconcileUnderLease`, `repairedState`, `requireNoPersistedActiveSnapshot`, `activeFileName`, `protectedPrefixes`). `S6539` et `S9391` : voir le lot 1.

- **(lot 3) `AUDIT-2026-09.md` donne encore R2 et R3 pour ouverts.** Ils sont fermés (§ 9.1) ; le fichier porte des modifications non suivies de l'utilisateur dans le dépôt principal, je ne l'ai pas touché. À mettre à jour par l'orchestrateur, avec le statut des trous F1 à F6 fermés ici.
- **(lot 3) Propriétaire d'un autre espace de PID *frère* (conteneurs partageant une racine).** Ses processus sont à 0 dans `cgroup.procs` : la lecture de l'appartenance échoue et la qualification rejette toute la racine (`invalid PID in cgroup.procs: 0`), sans rien tuer. Sûr, mais grossier : le sandbox Linux devient indisponible pour ce MINOS. Une amélioration serait de traiter ce cgroup en `LEAVE` nommé plutôt qu'en échec de racine ; non fait, la marque d'espace de noms rend déjà le cas inatteignable pour un cgroup marqué par ce format.
- **(lot 3) Hôtes durcis avec `hidepid` et propriétaires d'un autre compte.** La règle de § 9.10 (propriétaire = compte du balayeur) est conservatrice : un cgroup d'un autre compte ne sera jamais récupéré sous `hidepid`. Partager une racine déléguée entre comptes est de toute façon hors du modèle de délégation documenté.
- **(lot 3) `LinuxCgroupJob.close()` ne supprime pas les cgroups qu'un provider a créés sous son job** : le `kill()` les atteint, leur appartenance est vérifiée, mais la suppression du cgroup parent échoue (« unable to reclaim empty cgroup », échec fermé). Comportement inchangé ; `removeCgroupTree` pourrait servir à `close()`.
- **(lot 3) Les trois copies de « détruire les descendants du `Process` lancé »** de `minos-provider-scip` (`ManagedPolyglotScipRuntimeManager`, `ManagedScipProviderRuntimeManager`, `ManagedScipPythonRuntimeManager`) et `ProcessOwnershipTracker.resolveSameProcess` (instant de démarrage de `ProcessHandle.Info`, même JVM) : pas exposés à R2, hors du chantier ; à rapprocher de `ProcessTreeTermination` par le chantier Architecture.
- **(lot 3) `relocateSelf`** (S3 : déplacer tout le processus MINOS dans `minos-controller`) : effet de bord global inchangé.
- **(lot 3) Les cgroups marqués par les builds `develop` antérieurs** à ce lot survivant à leur propriétaire restent à supprimer à la main (§ 9.10) ; à clore quand aucun de ces builds n'existe plus.
- **SonarCloud, PR 311 (Quality Gate passé, 18 issues propres au lot 3)** : corrigées, sans effet sur la décision de tuer : `S6353` (`\d` pour `[0-9]`, équivalent en Java sans `UNICODE_CHARACTER_CLASS`), `S1192` (`OWNER_PID`), `S3358` (ternaire imbriqué de `Mark.suffix`), `S1130` et `S5778` (tests). **Non traitées, par prudence** : `S5843` (complexité de la regex `MARKED_NAME`), `S135` ×2 et `S3776` (`CgroupJobOwnership` ~l. 399, `LinuxCgroupJob.reclaimStaleJobs`) : ce sont les boucles et la regex qui décident de tuer ; `verif-fiab` les a relues et rejouées adversairement, les réécrire pour un gain de style sans refaire cette vérification irait contre la règle « en cas de doute, ne pas récupérer ». À traiter dans un lot dédié avec le même rejeu adverse.


- **(lot 4) `AUDIT-2026-09.md` donne encore Q5 pour « CONFIRMÉ / PLAUSIBLE » et R6 pour ouverts.** Q5 : le drapeau était déjà rétabli depuis `90ab9197`, le vrai défaut (l'ordre drapeau / écriture) est corrigé par ce lot, et le confinement est reproduit puis corrigé (§ 10.2, § 10.4) ; R6 est fermé. Le fichier porte des modifications non suivies de l'utilisateur dans le dépôt principal : non touché. À mettre à jour par l'orchestrateur.
- **(lot 4) Le planificateur de reprise ne chaîne pas la cause de ses refus** (`Refusal(String)`) : une interruption pendant le plan (lecture de l'empreinte du scope, confinement d'un artefact) refuse la reprise et laisse le run se poursuivre en run complet, sur un thread dont le drapeau est levé (les providers sont interrompus au premier point bloquant). Sans dommage démontré ; à traiter avec la suite du planificateur.
- **(lot 4) `LinuxCgroupJob` préserve une interruption dans sa chaîne nettoyée avec sa propre reconnaissance** (`cause instanceof InterruptedException`) ; ce n'est pas une décision sur un run. Un reconnaisseur partagé par l'orchestration et le runtime demanderait un module commun (les deux côtés du port) ; non fait.
- **(lot 4) Les autres `catch (InterruptedException)` de `minos-runtime-local`** (`ProcessOwnershipTracker`, `ProviderWriteQuotaSupervisor`, `ProcessTreeTermination`, sandbox Windows) rétablissent le drapeau sans relancer : ils n'atteignent l'orchestrateur qu'en cause chaînée (vérifié pour le chemin d'un provider) et n'ont pas été modifiés.
- **(lot 4) Confinement ignoré sans répertoire de run** (V-L4-04) : `ResumableRunMarkers.none()` ne connaît aucun répertoire de run ; c'est une décision (§ 10.4), valable tant que le câblage de production fournit toujours un répertoire. À reconsidérer si un port inerte apparaissait en production.
- **(lot 4) Un répertoire de run nommé en nom LONG avec un artefact écrit en nom COURT (8.3) est refusé** (V-L4-05) : `ArtifactConfinement` lit le chemin sous le répertoire tel qu'il est écrit, puis sous sa forme résolue ; l'artefact écrit en nom long sous un répertoire en nom court est accepté, l'inverse non. `41be5596` acceptait les deux, `66fd1d17` (qui refuse tout lien à tout niveau) ne le peut plus sans deviner si un alias est un lien. Échec fermé (au pire une reprise refusée, donc un run complet), jamais observé : l'artefact et le marqueur dérivent du même `MINOS_HOME`. Les chemins persistés d'un run écrit avec un `MINOS_HOME` exprimé autrement (court/long) ne seraient plus reprenables.
- **(lot 4) Les tests de liens symboliques se sautent (`assumeTrue`) sur un Windows sans privilège** (R-L4-B) : une CI Windows sans mode développeur ne les exécute pas ; le cas `..` et les tests de `ArtifactConfinementTest` sans lien, eux, s'exécutent toujours. Les liens ont été exécutés ici (Windows avec privilège, WSL) : 0 test sauté.
- **(lot 4) Après une interruption, les appelants de l'exécuteur écrivent drapeau levé** (R-L4-C) : l'exécuteur rejoue le drapeau en sortant, et un appelant qui écrit ensuite (par exemple `LocalAutonomousIndexOperations` : `recoverPromotedRunIfNeeded`, la compaction de la rétention) peut échouer en `ClosedByInterruptException` (non sondé). Comportement d'avant le lot, hors périmètre : le run, lui, est déjà persisté. À traiter si un arrêt réel du service doit aussi nettoyer derrière lui.
- **(lot 4) Rejeu ×50 sous Linux et interruption d'un provider externe réel sous sandbox** : non exécutés (le test simule le provider par un exécuteur) ; le test passe sous WSL en une exécution.

- **(lot 5) `AUDIT-2026-09.md` donne encore Q8 et Q9 pour ouverts.** Q8 est corrigé (§ 11) ; Q9 est **PLAUSIBLE non reproduit** (§ 11.8). Le fichier porte des modifications non suivies de l'utilisateur dans le dépôt principal : non touché. À mettre à jour par l'orchestrateur.
- **(lot 5) Le listage strict reste strict** (`registerProject`, `ProjectResolver` par nom, `NexusExportService`, `findWorkspace`, `listWorkspaces`) : avec un seul fichier de registre abîmé, `minos project add` et `minos inspect <nom>` échouent (code 1) ; `inspect <identifiant>` passe. C'est voulu pour les mutations (elles ne savent pas si l'entrée abîmée est celle qu'elles cherchent). Pour la résolution par nom et les workspaces, la tolérance demanderait de dire « inconnu, mais N entrées sont illisibles » au lieu d'« inconnu » : à traiter dans un lot dédié (V-L5-02 b).
- **(lot 5) Entrées écartées en silence qui subsistent hors de l'inventaire** : `ProjectInspectionService.readHistory` ignore un historique qui est un répertoire ou un lien pendant ; `LocalProjectRegistry.propertyFiles` ignore un workspace qui est un répertoire ou un lien pendant (V-L5-02 a). Préexistant, hors `listProjects` ; les corriger fait échouer là où ça réussit aujourd'hui.
- **(lot 5) Plugin IntelliJ** (`minos-intellij/.../MinosCliClient.resolveProject`) : n'accepte que le code 0 de `project list` ; avec un projet abîmé il échoue (comme avant, avec le code 1) et ne résout pas le projet sain. Accepter `Set.of(0, 3)` pour cette commande, dans le lot qui touche le plugin (V-L5-04).
- **(lot 5) Garde d'interruption du registre sans test** : `LocalProjectRegistry.scanProjects` relance un échec quand le thread est interrompu ; sur ce JDK une lecture de fichier sur un thread déjà interrompu ne lève pas, et une interruption en plein milieu d'une lecture n'est pas reproductible de façon déterministe : le garde de la vue est sous test (rouge sans lui), celui du registre est par symétrie (§ 11.7).
- **(lot 5) Deux fournisseurs qui décrivent le même symbole dans la même portée** font échouer le run au staging (« provider snapshot collision », `ScipProjectSnapshotLifecycle.putUnique`) : vu en prouvant Q9, non cherché plus loin (V-L5-05).
- **(lot 5) Clé sémantique en double : aucune garde défensive n'a été ajoutée.** Si l'invariant « id dérivé de la clé » était un jour cassé (un second producteur de symboles, un format de snapshot qui porte des ids indépendants), la fabrique échouerait bruyamment (`IllegalStateException`), comme aujourd'hui ; une règle de résolution (première gagne, dernière gagne, fusion) serait alors à écrire, avec son compteur, après un test rouge atteignable.
- **(lot 5) PostgreSQL** : `inventory()` y vaut `listProjects()` (colonnes typées, aucune corruption de texte possible) ; si une colonne `root_value` illisible y devenait possible, le défaut de l'interface devrait être remplacé.
- **(lot 5) Ligne `UNREADABLE` d'un échec de vue** (R-L5-B) : `rootAvailable=false` et aucun fait de découverte, même quand la racine existe et n'est qu'illisible ; la raison se réduit au nom de classe quand le message porte un chemin. À enrichir seulement si un client a besoin de distinguer « racine absente » de « racine illisible ».
- **(lot 5) Échec transitoire d'un magasin d'état** (R-L5-D) : un verrou Windows passager pendant une indexation dégrade le projet concerné en `UNREADABLE` pour un appel (code 3), sans nouvelle tentative ; le second appel donne le bon résultat. Une nouvelle tentative bornée, si elle est voulue, se met dans la lecture de l'état, pas dans l'inventaire.
- **(lot 5) `listWorkspaces()` et `findWorkspace()` restent stricts** (R-L5-C) : ils lisent tous les projets pour établir l'appartenance ; un fichier de projet abîmé fait échouer `LocalMinosMultiRepositoryApi.listWorkspaces`.

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
- **Q4.** `LocalStorageRetentionService.compact` calcule son ensemble protégé (snapshot actif de l'état + snapshot de connaissance actif) puis compacte les empreintes ; il ne prend pas L1, donc rien n'empêche, dans l'API, qu'il s'exécute en même temps qu'un cycle de vie. Aucun appelant de production ne le fait (les quatre sites de `LocalAutonomousIndexOperations.executeLocked` sont sous L1). Ce qui a été mesuré est au § 8.10 : l'énoncé de l'audit (un snapshot « préparé » est celui de `staged-snapshots/`) ne tient pas, et l'empreinte publiée avant sa promotion survit à la rétention de production.

### 8.6 Ordre de prise retenu

Un seul ordre total, à respecter par tout code qui prend plusieurs verrous d'un même projet :

1. **L1** bail de cycle de vie (`IndexStateStore.acquireProjectLease`) ;
2. **L3** verrou de rétention (`retention-locks`), pris par la seule rétention, toujours sous L1 ;
3. **L2** bail de mutation de snapshot (`SnapshotProjectLease`) ;
4. **L4** verrou de synchronisation sémantique (toujours sous L2) ;
5. **M** moniteurs en mémoire (feuille) ; puis les feuilles hors famille (registre, observations, distant), jamais tenues avec L1 ou L2.

Règles : (a) on ne prend **jamais** un verrou de rang inférieur en tenant un verrou de rang supérieur (en particulier jamais L1 en tenant L2 ou L4), et L2 n'est jamais repris par son propre thread (il n'est pas réentrant) ; (b) **toute mutation du stockage d'index d'un projet, rétention comprise, se fait sous L1** (réentrant pour le thread qui le tient déjà) : la rétention ne prend pas L1 elle-même, c'est son unique appelant qui le tient (§ 8.10, Q4) ; (c) **toute mutation d'un store partagé entre processus se fait sous L2**, y compris le store d'empreintes ; (d) une **lecture ne prend ni L1 ni L2 et n'écrit pas**.

Décisions après preuve, sans nouveau mécanisme : le store d'empreintes réutilise L2 (la classe `SnapshotProjectLease`, dont la documentation prévoit déjà ce rôle pour les stores frères sous `MINOS_HOME`), ce qui retire le moniteur FPM (Q3) ; les lectures d'état n'ont plus aucun chemin vers L1 ni vers une écriture (P1) ; la rétention **garde** L3 : le scénario de Q4 ne se reproduit pas (§ 8.10), donc elle n'est pas fusionnée dans L1 (un changement de comportement sans scénario démontré). L'ordre est écrit à quatre endroits, là où l'on prend les verrous : Javadoc de `IndexStateStore.acquireProjectLease`, de `ProjectIndexLease`, de `SnapshotProjectLease` et de `LocalStorageRetentionService`, plus l'ADR 0039 (l) ; `LocalStorageRetentionPreparedSnapshotTest` exécute l'ordre sur un thread.

### 8.7 Comptes AVANT

Mécanismes d'exclusion de la famille « un projet » : **6** (L1, L2, L3, L4, M, FPM), dont **3 fichiers indépendants sans ordre écrit** (L1, L2, L3) et **2 moniteurs JVM sans couverture inter-processus** (M, FPM), plus L4 imbriqué sous L2. Chemins de lecture d'état qui prennent L1 : **2** (`IndexingLifecycleService.projectState`, `ProjectIndexStateReconciler.reconcile` persistant, ce dernier via `ProjectInspectionService.view` et donc `minos_index_status`). Appels du store d'empreintes qui mutent sans aucun verrou inter-processus : **3** (`publish`, `promote`, `compact`).

### 8.8 Journal par commit (lot 2)

| Commit | Contenu | Gates |
|---|---|---|
| `ec49871b` | docs : inventaire des verrous, baux et répertoires de travail, graphe de prise, ordre retenu (§ 8.1 à 8.7) | 504 / 45 / SUCCESS / SUCCESS / 95 |
| `64c704b6` | Q3 : publication, promotion et compaction d'empreintes sous le bail de mutation du projet ; `SnapshotProjectLease` publique ; le `synchronized` de `compact` disparaît | idem |
| `7e1b2903` | `DurableAtomicFile.replace` retenté sous Windows face à un lecteur qui tient la cible ouverte (conséquence de P1, § 8.9) ; test de lecture sous écriture concurrente | idem |
| `91a23b22` | P1 : le statut lit par `observe` (aucun bail, aucune écriture), un état en vol est rapporté tel quel ; `projectState` devient `recoverProjectState` | idem |
| `49e3fdfb` | Q4 : test de non-reproduction (empreinte préparée, snapshot structurel préparé, ordre de prise) ; aucun correctif | idem |
| `44aca82a` | documentation : ordre de prise dans les Javadoc et l'ADR 0039 (l), ce journal, preuves (avec correction de l'emplacement périmé du bail dans l'ADR) | idem |
| `0003d80e` | preuves de fin de lot (rejeux, `clean verify`, Linux) | idem |
| `55d4091d` | V-L2-01 : `observeStatus` (lecture de statut) distinct de `observe` (plan du dry-run) | idem |

### 8.9 Preuves

**Rouge → vert.** Chaque test a été joué sur le code d'origine avant le correctif correspondant (sortie rouge dans le message du commit).

| Constat | Test | Rouge (code d'origine) | Vert |
|---|---|---|---|
| Q3 publications de contenus différents | `FileProjectFingerprintSnapshotStoreConcurrencyTest.twoConcurrentPublicationsWithDifferentContentLeaveExactlyOneSnapshotForTheIdentifier` | `one content wins, the other is refused ==> expected: <1> but was: <2>` (tour 0) | 3/3 |
| Q3 publications du même contenu | `…twoConcurrentPublicationsOfTheSameContentBothSucceedAndShareOneFile` | `a republication of identical content is idempotent: [done, refused: … .fingerprint-*.tmp -> …]` | 3/3 |
| Q3 compaction pendant une promotion | `…aCompactionNeverDeletesTheSnapshotAPromotionIsReadingOrLeavesADanglingPointer` | `a refused promotion is refused cleanly: refused: fingerprint snapshot must be a regular non-symlink file` | 3/3 |
| P1 statut pendant une indexation | `ProjectStatusReadIsLeaseFreeTest.statusAnswersInsteadOfFailingWhileAnotherProcessHoldsTheLifecycleLease` | `IOException: failed to acquire project lifecycle lease for metadata reconciliation … timed out … after PT10S` | 3/3 |
| P1 une lecture n'écrit pas | `…aReadNeverRepairsTheStateItReportsEvenWhenNoRunHoldsTheLease` | `array lengths differ, expected: <295> but was: <347>` (l'état a été réécrit) | 3/3 |
| P1 état en vol rapporté tel quel | `ProjectIndexStateReconcilerTest.observeStatusReportsAnInFlightRunUnchangedEvenWhenItsSnapshotIsAlreadyPromoted` | (l'ancien `observe` répondait READY réparé en mémoire : `repaired` vrai) | 10/10 |
| V-L2-01 le dry-run planifie comme le run réel | `ProjectIndexStateReconcilerTest.observeForADryRunPlanStillComputesTheRepairOfAnInProgressStateLeftByADeadRun` | sur `44aca82a` : `the plan anticipates the recovery the real run performs under the lease ==> expected: <true> but was: <false>` | 10/10 |
| Windows : remplacement face à un lecteur | `ProjectStateReadUnderConcurrentWriteTest` | `UncheckedIOException: cannot write MINOS index state … Caused by: AccessDeniedException: .state-<n>.tmp -> <id>.properties` (premier essai) | 1/1 |
| Windows : politique de nouvelle tentative | `DurableAtomicFileTest` (4 nouveaux, déterministes : le déplacement, le système et la pause sont injectés) | (la politique n'existait pas) | 6/6 |

Garde-fous verts avant et après (ils ne devaient pas bouger) : `statusBeforeThePromotionIsLockFreeToo`, `reconcileStillRepairsAnAbandonedInProgressStateUnderTheLeaseItOwns`, tout `IndexingLifecycle*`/`InterruptedRunRecoveryTest`/`Abandoned*` du moteur (43/43, renommage inclus), `LocalStorageRetentionServiceTest`, `FileProjectFingerprintSnapshotStoreTest`.

**Changement observable, en clair.** Pendant une indexation, `minos_index_status`, `index-status`, `inspect`, `project list` et `minos_project_structure` répondent tout de suite avec le dernier état que le run a publié (`INDEXING` tant qu'il n'a pas publié sa fin) ; avant, dès que le snapshot était promu et l'état pas encore écrit, ils attendaient 10 s puis échouaient (`failed to acquire project lifecycle lease`). Une lecture ne répare plus l'état : si le snapshot actif a de l'avance sur l'état publié, elle répond ce que la réparation publierait (calculé en mémoire), sauf pour un état `INDEXING`/`REFRESHING` qu'elle rapporte tel quel (`ProjectIndexStateReconciler.observeStatus`) ; c'est le prochain run qui répare. Le plan d'un `--dry-run` (`observe`) ne change pas : il calcule toujours la réparation en mémoire, pour annoncer le plan que le run réel fera (V-L2-01). Aucun code de sortie ni golden ne change (`git diff bcdadd23..HEAD -- minos-app/src/test/resources scripts` est vide).

**Le lot a révélé un défaut Windows que la correction de P1 rend ordinaire.** Rendre la lecture concurrente de l'écriture fait échouer `DurableAtomicFile.replace` côté écrivain : `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` lève `AccessDeniedException` (ou une violation de partage) tant qu'un lecteur tient la cible ouverte. C'est l'échec « rare » relevé au § 7 du lot 1 ; il se produisait au premier essai dès qu'un lecteur sans bail existait. Sans ce correctif, P1 aurait fait échouer l'indexeur parce qu'un client lit le statut. Correction dans la primitive commune (aucun mécanisme ajouté) : au plus 20 tentatives, pauses croissantes de 5 ms (environ une seconde), seulement sous Windows, seulement pour un remplacement, seulement pour `AccessDeniedException` ou une `FileSystemException` nue.

**Tests de concurrence rejoués 50 fois** (une invocation Maven par rejeu, `-Dsurefire.rerunFailingTestsCount=0`, arbre de rejeu dédié, aucun `Thread.sleep` : barrières `CyclicBarrier`, drapeau d'arrêt du lecteur) :

| Classe | Synchronisation | Rejeux sur le commit | Résultat |
|---|---|---|---|
| `FileProjectFingerprintSnapshotStoreConcurrencyTest` (3 tests, 10 tours chacun par exécution) | `CyclicBarrier(2)` par tour, `Future.get` | `7e1b2903` (code de production final de Q3) | **50 passages sur 50**, 0 échec (30 tours par exécution : 1 500 tours) |
| `ProjectStateReadUnderConcurrentWriteTest` (200 publications par exécution) | `CyclicBarrier(2)`, drapeau `writerDone`, relecture finale après l'arrêt de l'écrivain | `49e3fdfb` puis `55d4091d` (code de production final de P1, après V-L2-01) | **50 sur 50** sur chacun, 0 échec (200 publications lues en boucle par exécution : 10 000 publications, l'échec Windows de l'écrivain n'apparaît plus) |
| `ProjectStatusReadIsLeaseFreeTest` (le bail est tenu par le thread de test, la lecture sur un autre thread) | bail tenu pendant l'appel, `Future.get` | `49e3fdfb` puis `55d4091d` | **50 sur 50** sur chacun (rejoués avec le précédent, et avec `ProjectIndexStateReconcilerTest` au second) |

### 8.10 Q4 : non reproductible, non corrigé

Preuve : `LocalStorageRetentionPreparedSnapshotTest` (commit `49e3fdfb`), 3 tests, verts sur le code d'origine `bcdadd23` (arbre séparé) comme sur le code courant.

1. **Le snapshot « préparé » de l'audit n'est à la portée d'aucune rétention.** Le snapshot structurel qu'un run prépare est écrit par `ScipProjectSnapshotLifecycle.stage` dans un store privé `staged-snapshots/<runId>/project/`. Les trois rétentions (`SnapshotRetentionService` sur `symbol-snapshots/`, `FileProjectFingerprintSnapshotStore.compact` sur `fingerprint-snapshots/`, `RunDirectoryRetention` sur `runs/`) ne parcourent jamais `staged-snapshots/`. Le test crée un snapshot préparé, passe la rétention avec la politique `(0, 0, 0)` (qui ne garde rien) et un balayage des temporaires avec une horloge avancée de trois jours : aucun fichier de `staged-snapshots/` ne bouge. Le pendant est une fuite, pas une suppression (§ 7).
2. **L'empreinte publiée avant sa promotion survit.** C'est le seul snapshot « publié, pas encore promu » qu'une rétention parcourt. Le test place le passage de rétention à l'endroit nuisible d'un cycle de vie, entre `publish` et `promote`, avec l'ensemble protégé d'une rétention qui aurait calculé avant (l'état et le snapshot structurel nomment encore le précédent), sous la politique de production. `compact` garde le plus récent des `maxHistoricalSnapshots = 2` fichiers historiques : l'empreinte préparée est le fichier le plus récent de son répertoire (publiée secondes après les autres), elle est donc conservée quel que soit l'ensemble protégé. Le passage n'est pas vacueux (l'ancien historique est réclamé) et la promotion réussit ensuite.
3. **Borne mesurée, puis annulée.** Avec une politique qui garde **zéro** snapshot historique (`new PersistentRetentionPolicy(0, …)`), la même séquence supprime l'empreinte préparée (`expected: <true> but was: <false>`). Aucun code de production ne la construit : `PersistentRetentionPolicy.DEFAULT` est le seul argument passé par `LocalAutonomousIndexOperations`, dont les quatre appels sont sous le bail de cycle de vie. Un correctif (la rétention prend L1, ou ne supprime pas ce qui est plus récent que le pointeur actif) serait défensif : il ajouterait un changement de comportement (une attente bornée de 10 s de la rétention) sans scénario atteignable, et ne fermerait rien.

Ce qui reste vrai et est consigné au § 7 : la rétention ne prend pas L1 elle-même ; son verrou L3 est un `FileChannel.lock()` sans délai ni couche JVM.

### 8.11 Comptes AVANT / APRÈS

| Mesure | Avant (`bcdadd23`) | Après |
|---|---|---|
| Mécanismes d'exclusion de la famille « un projet » | **6** : L1, L2, L3, L4, M, FPM | **5** : L1, L2, L3, L4, M (FPM, le `synchronized` de `compact`, est retiré) |
| Fichiers de verrou distincts pour un projet | 4 (`index-state/locks/indexing`, `.project-mutation-leases`, `retention-locks`, `semantic-index/.sync-locks`) | 4 (inchangé ; aucun ajouté), **ordonnés** et documentés |
| Appels du store d'empreintes qui mutent sans verrou inter-processus | 3 (`publish`, `promote`, `compact`) | **0** |
| Chemins de lecture d'état qui prennent L1 | 2 (`IndexingLifecycleService.projectState`, `ProjectIndexStateReconciler.reconcile` via le statut) | **0** (`recoverProjectState` est nommé comme la mutation qu'il est) |
| Chemins de lecture d'état qui écrivent | 2 | **0** |
| Sites `SnapshotProjectLease.acquire` | 8 | 11 (les trois opérations d'empreintes, même bail : des sites d'un mécanisme existant, pas un mécanisme) |
| Ordre de prise écrit | nulle part | Javadoc ×4, ADR 0039 (l), § 8.6, un test d'exécution de l'ordre |
| Graphe de prise | sans cycle, non écrit | sans cycle, écrit (§ 8.4 avant, ci-dessous après) |

Graphe APRÈS (arête `A ──► B` : B pris sous A) :

```
indexation CLI (execute)
  L1 ──► M                                   (état, run, reprise)
  L1 ──► L2                                  (promotion du snapshot structurel)
  L1 ──► L2                                  (publication et promotion des empreintes)   // Q3 : avant, sans verrou
  L1 ──► L2 ──► L4                           (synchronisation sémantique)
  L1 ──► L3 ──► L2 (snapshots) , L2 (empreintes) , M (runs)    (rétention, étapes séquentielles, L2 relâché entre elles)
lecture d'état (statut, inspect, project list) : aucun verrou, aucune écriture          // P1
recoverProjectState : L1 ──► M                (mutation, appelée sous L1 par le coordinateur incrémental)
```

Aucun cycle : l'ordre L1 < L3 < L2 < L4 < M est total et respecté par chaque arête ci-dessus ; aucun chemin ne prend un verrou de rang inférieur en tenant un de rang supérieur.

### 8.12 Fin de lot

`./mvnw clean verify` complet dans un arbre dédié (journal dans le scratchpad, pas dans `target/`) : sur `44aca82a` puis, après V-L2-01, sur `55d4091d` (les commits suivants ne changent que de la Javadoc et ce fichier) : **BUILD SUCCESS** les deux fois, 15 modules, 12 min 57 pour le second, **1 708 tests exécutés, 0 échec, 0 erreur, 46 ignorés** (les mêmes hypothèses `Assumptions` qu'à la base ; aucun `@Disabled` ajouté) ; 1 691 à la base du lot, +17 : `FileProjectFingerprintSnapshotStoreConcurrencyTest` 3, `DurableAtomicFileTest` +4, `ProjectStateReadUnderConcurrentWriteTest` 1, `ProjectStatusReadIsLeaseFreeTest` 3, `ProjectIndexStateReconcilerTest` +3, `LocalStorageRetentionPreparedSnapshotTest` 3.

| Gate | Base `bcdadd23` | Fin de lot |
|---|---|---|
| `check-module-boundaries.py` | `modules=14, sources=504, packages=45` | identique |
| `check-current-docs.py`, `product-facts.py --check` | SUCCESS | SUCCESS |
| `check-milestone-artifact-references.py` | `scripts checked=95` | `scripts checked=95` |
| `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py` (littéraux de `SnapshotProjectLease.acquire`, `compactWithActiveSnapshot`, `CompactionResult::activeSnapshotId`) | SUCCESS | SUCCESS |
| `check-jacoco.py` | 26 PASS, seule rouge `m24-polyglot-provider-platform` (line 0,228 < 0,28, préexistante, Windows) | 26 PASS, **même unique rouge**, mêmes chiffres |
| `critical-orchestration` (line / branch) | 0,891 / 0,773 | 0,891 / 0,773 |
| `resume-orchestration` (line / branch) | 0,906 / 0,787 | 0,906 / 0,787 |

Golden : les 12 de `characterization/` **inchangés**, aucun script de `scripts/` modifié (`git diff bcdadd23..HEAD -- minos-app/src/test/resources scripts` vide).

**Linux.** WSL Ubuntu (Java 24), tests du reactor `minos-engine` (455 tests, 7 ignorés), `minos-runtime-local` (226, 30 ignorés : Windows seulement) et `minos-storage-local` (177, 0 ignoré), sur `44aca82a` : **verts, 0 échec**, dont `FileProjectFingerprintSnapshotStoreConcurrencyTest` (3/3), `LocalStorageRetentionPreparedSnapshotTest` (3/3) et `DurableAtomicFileTest` (6/6, où la politique de nouvelle tentative est injectée et ne dépend pas du système). **Vérifié seulement sous Windows** : `minos-bootstrap` (les trois tests de P1, le test de lecture sous écriture concurrente, le rejeu ×50) — le module dépend de `minos-provider-scip`, dont une dépendance n'est pas dans le dépôt Maven local de WSL hors ligne ; le défaut Windows de `replace` (rename sur une cible ouverte) n'existe pas sous Linux, où la lecture concurrente est sans effet sur l'écrivain ; le rejeu ×50 n'a été fait que sous Windows.

## 9. Lot 3 — R2, R3 : la propriété des cgroups

> Branche `fiab/r2-r3-cgroups` (depuis la branche du lot 2, fusion `8670f2e2`), worktree `minos-wt/fiab-lot3`. Code de référence : `minos-runtime-local/src/main/java/com/minos/runtime/local/`. Inventaire daté du 30 septembre 2026.

### 9.1 Constat préalable : R2 et R3 sont déjà fermés sur `develop`

L'audit (`AUDIT-2026-09.md` § R2, R3) décrit un état antérieur au chantier « résidus du sprint 1 ». En relisant le code du worktree avant d'écrire un test, les deux correctifs demandés par l'énoncé du lot y sont déjà :

| Constat | Ce que l'énoncé demande | Ce que le code fait déjà | Commits (ancêtres de `HEAD`) |
|---|---|---|---|
| R2 | décision indépendante de l'horloge murale, ticks de `/proc/<pid>/stat` champ 22 | marque `<job>.own-<pid>-t<startTicks>-<jeton>` ; `CgroupJobOwnership.decide` ne compare que des ticks de démarrage (horloge de boot) ; une marque ancienne (instant mural) ne récupère que si le PID propriétaire est mort, jamais sur un écart d'instant | `93ed7905` (marque en ticks), `0ee22e06` (note de mise à jour) |
| R2 | test qui injecte l'horloge, sans toucher à celle de la machine | `CgroupJobOwnershipTest.aWallClockStepNeverMakesALiveOwnerLookReused` (pas de ±1 h et ±2001 ms) ; `LinuxCgroupStaleRecoveryTest.aWallClockStepNeverMakesALiveOwnerLookReused` (cgroups factices) ; `LinuxCgroupJobOwnershipIsolationTest.aWallClockStepDoesNotKillTheJobsOfALiveMinosInstance` (cgroup réel) | idem |
| R3 | résultat du balayage exploité, résidu visible à niveau WARNING avec compteur | `qualifyRoot` appelle `reclaimAndReportStaleJobs`, qui journalise UN WARNING agrégé (« MINOS left N cgroup(s) intact … ») avec le nom et la raison de chaque résidu, borné à 32 noms | `d6e5296d` |
| R3 | aucun chemin absolu dans les messages | `describeFailure`, `redactCause`, `RedactedCause`, `displayRoot` ; `LinuxCgroupJobDiagnosticsTest.assertNoAbsolutePath` sur chaque journal | `d6e5296d`, `82ea6c39` |

Les rouges historiques (code d'origine) sont dans `RESIDUS-SPRINT-1-SUIVI.md` § 1 (R2 : test WSL où un `sleep` vivant était réellement tué ; R3 : 5 rouges Windows et 1 WSL). Je ne les refais pas : **R2 était reproductible et est fermé** ; ce lot ne réécrit donc ni la marque ni le WARNING.

Conséquence sur la méthode : l'énoncé du lot pose la règle « en cas de doute, ne pas récupérer ». J'ai donc relu CHAQUE branche qui tue ou supprime (§ 9.2) avec la question « quelle est la preuve POSITIVE que le propriétaire est mort ? », et cherché ce que les correctifs précédents ne couvrent pas. Il y en a six (§ 9.4), dont un qui tue un processus vivant sur le code actuel, avec une preuve réelle sous Linux.

`AUDIT-2026-09.md` (R2 et R3 encore listés ouverts) est à mettre à jour par l'orchestrateur : il porte des modifications non suivies de l'utilisateur dans le dépôt principal, je n'y touche pas.

### 9.2 Cibles relocalisées et branches qui tuent ou suppriment

| Cible de l'énoncé | Emplacement réel (`com.minos.runtime.local`) | Rôle |
|---|---|---|
| `CgroupJobOwnership` | `CgroupJobOwnership.java` (256 lignes) | marque + `decide` (SEUL endroit qui décide qu'un cgroup appartient à un MINOS mort), `OwnerLookup.SYSTEM`, `parseStartTicks`, `startTicks` |
| `LinuxCgroupJob` (`qualifyRoot`, `reclaimStaleJobs`, `StaleSweep`) | `LinuxCgroupJob.java` (673 lignes) : `qualifyRoot` l. 129, `reclaimStaleJobs` l. 194, `reclaimAndReportStaleJobs` l. 221, `reclaimStaleJob` l. 249 | applique le verdict : `cgroup.kill` puis suppression |
| `ProcessTreeTermination` | `ProcessTreeTermination.java` | exclusion de l'hôte (`destroyIfNotHost`), arrêt d'un arbre atteignable depuis un `Process` que MINOS a lancé ; ne décide rien par PID |
| `StrongProcessOwnershipIndexerExecutor` | `StrongProcessOwnershipIndexerExecutor.java` | câblage : `createOwnershipOnly`, `kill()` de SON job ; aucune décision d'appartenance |
| `WorkerSandboxBackends` | `WorkerSandboxBackends.java` | sélection de backend et journal des refus (codes seulement) ; aucune décision d'appartenance |
| (découvert) | `ProcessOwnershipTracker.java` l. 196-206 | ré-acquisition d'un descendant mémorisé : PID + instant de démarrage (`ProcessHandle.Info`), sinon le handle d'origine ; même JVM, donc même base `btime` (le JDK la calcule une fois) : pas exposé à R2 |

Branches qui suppriment ou tuent, et leur preuve actuelle (code lu, pas déduit de l'audit) :

| # | Branche (`CgroupJobOwnership.decide` puis `LinuxCgroupJob.reclaimStaleJob`) | Preuve d'appartenance à un mort |
|---|---|---|
| a | cgroup non marqué, 0 processus : supprimé | lecture de `cgroup.procs` vide ; **puis `aliveProcesses() > 0` relit et `kill()` tue** (F3) |
| b | marque lisible, PID propriétaire « absent » : tué et supprimé | `ProcessHandle.of(pid)` vide, pris pour « mort » (F1) |
| c | marque en ticks, PID présent, ticks différents : tué et supprimé | deux lectures de `/proc/<pid>/stat` champ 22 ; une lecture tronquée n'est pas détectée (F2) |
| d | marque ancienne (instant mural), PID absent : tué et supprimé | idem b (F1) |
| e | tout le reste : laissé, compté en résidu | aucune preuve requise |

### 9.3 La règle par défaut (écrite dans le code et reprise dans la PR)

> **En cas de doute, ne pas récupérer.** Un résidu laissé derrière coûte de la mémoire et des `pids` de la racine déléguée, et il est journalisé en WARNING avec sa raison. Un processus vivant tué est S3 qui revient. La conclusion par défaut, quand la preuve d'appartenance à un propriétaire MORT est indisponible, partielle ou ambiguë, est : **LEAVE** (le cgroup reste intact, il est compté et signalé).

Sont des « doutes » qui concluent à LEAVE, chacun avec un test : horloge murale décalée (l'instant n'entre pas dans la décision) ; `/proc` illisible, partiel ou qui cache d'autres comptes ; `/proc/<pid>/stat` illisible, tronqué, mal formé, sans champ 22 ; PID présent dont la réponse est ambiguë ; marque sans ticks ; marque d'un format ancien avec un PID vivant ; cgroup non marqué et peuplé ; propriétaire d'un autre espace de PID (membres de `cgroup.procs` à 0 : lecture refusée, rien n'est tué). Toute branche qui récupère exige une preuve POSITIVE : (i) **PID absent d'une table des processus prouvée lisible** (`/proc/self/stat` lu, répertoire `/proc/<pid>` réellement absent, aucun masquage `hidepid` d'un autre compte), ou (ii) **ticks complets et différents** pour un PID présent, ou (iii) cgroup **vide** (suppression seule, jamais de `cgroup.kill`).

### 9.4 Les trous restants (chacun à prouver AVANT de corriger)

| Id | Constat | Preuve |
|---|---|---|
| F1 | `OwnerLookup.SYSTEM` conclut « propriétaire mort » quand `ProcessHandle.of(pid)` est vide. Or le JDK renvoie vide dès que sa propre lecture de `/proc/<pid>/stat` échoue : descripteurs de fichiers épuisés (EMFILE), `hidepid`, `/proc` partiel. Un MINOS voisin **vivant** est alors « mort » : son cgroup est tué (S3) | expérience réelle sous WSL (`FdExhaust.java`, noyau 6.18, Java 24.0.1, `ulimit -n 128`) : avant épuisement `ProcessHandle.of(self)` présent ; **pendant l'épuisement `ProcessHandle.of(self)` et `ProcessHandle.of(1)` sont vides** alors que les deux processus sont vivants ; après libération, présent |
| F2 | `parseStartTicks` accepte une lecture tronquée dont le champ 22 est le dernier jeton : `…18 12` (coupé de `123456`) donne 12 ; le PID est jugé « réutilisé » et le cgroup est tué. Même défaut côté écriture de la marque (`Mark.of`) | test sur le comportement d'origine (§ 9.8) |
| F3 | TOCTOU dans `reclaimStaleJob` : verdict « cgroup non marqué sans processus » puis relecture `aliveProcesses()` ; un MINOS plus ancien qui démarre un job dans l'intervalle voit son processus tué sans aucune preuve d'appartenance | test de décision : le verdict d'un non marqué vide autorise aujourd'hui `kill` |
| F4 | `Files.list(root).limit(4096)` : au-delà, les entrées ne sont ni examinées ni signalées (R3 dit « un résidu non récupérable remonte ») | test à borne réduite |
| F5 | une suppression qui échoue est comptée `reclaimed` (le cgroup existe encore) et n'est journalisée qu'en avertissement par entrée, hors du rapport agrégé | le test existant `emptyStaleCgroupDeletionFailureDoesNotDisableContainment` fige « reclaimed » pour un cgroup resté en place |
| F6 | un `cgroup.kill` de récupération n'est journalisé qu'en DEBUG : l'opérateur ne voit jamais qu'un balayage a tué des processus | test de capture du journal |

Ajoutés en cours de lot sur constat de `verif-fiab` (V-L3-03, W6 du sprint 1) : l'inscription des espaces de noms PID et temps dans la marque (§ 9.10). Hors périmètre, consigné § 7 : `relocateSelf`.

### 9.5 Comptes AVANT (base `8670f2e2`)

Endroits qui décident « ce processus (ou ce cgroup) nous appartient et peut être tué », dans `minos-runtime-local` : **4** — `CgroupJobOwnership.decide` (cgroup d'un MINOS mort), `ProcessOwnershipTracker.resolveSameProcess` (descendant mémorisé), `ProcessTreeTermination.terminateTree` (arbre atteignable depuis un `Process` lancé par MINOS, avec son exclusion de l'hôte `destroyIfNotHost`), et `LinuxCgroupJob.kill`/`close` (SON job, par construction). Un seul d'entre eux décide d'après l'identité d'un processus tiers : `CgroupJobOwnership.decide`. Hors module : 3 copies de « détruire les descendants du `Process` lancé » dans `minos-provider-scip` (`ManagedPolyglotScipRuntimeManager`, `ManagedScipProviderRuntimeManager`, `ManagedScipPythonRuntimeManager`), non touchées (chantier Architecture). Sources d'instants de démarrage dérivés de l'horloge murale qui entrent dans une décision de tuer : **1** (`ProcessOwnershipTracker`, une seule JVM, base `btime` mémorisée par le JDK) ; dans une décision inter-processus : **0** (R2 fermé).

Chiffres de référence des gates (base `8670f2e2`, avant le premier commit de code) : `check-module-boundaries.py` `modules=14, sources=504, packages=45` ; `check-current-docs.py`, `product-facts.py --check` SUCCESS ; `check-milestone-artifact-references.py` `scripts checked=95`. JaCoCo et `clean verify` : ceux de la fin du lot 2 (§ 8.12), le code de ce lot est celui de la base ; `provider-sandbox-linux` (qui couvre `LinuxCgroupJob`, pas `CgroupJobOwnership`) est SKIPPED sous Windows et rejoué sous WSL.

Tests de la zone avant le lot : 62 (`CgroupJobOwnershipTest` 18, `LinuxCgroupStaleRecoveryTest` 8, `LinuxCgroupJobDiagnosticsTest` 5, `LinuxCgroupJobFailClosedTest` 9, `LinuxCgroupJobOwnershipIsolationTest` 7, `LinuxCgroupJobContainmentTest` 8, `ProcessTreeTerminationTest` 6, `ProcessOwnershipTrackerHostProtectionTest` 1) : **Windows 62 exécutés, 15 ignorés, 0 échec** ; **WSL avec une racine cgroup v2 réellement déléguée : 62 exécutés, 2 ignorés, 0 échec**.

### 9.6 Ce qui s'exécute où

Outillage de ce lot (scratchpad, non versionné) : un script lancé en root sous WSL crée une racine `/sys/fs/cgroup/minos-lot3` (contrôleurs `+memory +pids +cpu`, enfant `minos-controller`, propriété du compte 1000), y place son shell puis lance Maven sous le compte `fturleque` avec `MINOS_SANDBOX_CGROUP_ROOT`. Les tests qui tuent réellement un `sleep` dans un vrai cgroup **s'exécutent donc** sous WSL, contrairement à ce que laissait craindre « uid 1000 non root ». Ils ne s'exécutent nulle part ailleurs en local (Windows les ignore) ; la CI Linux de GitHub a une racine déléguée par `scripts/ci/delegate-linux-cgroup.sh`. La répartition exacte de fin de lot est au § 9.9.

### 9.7 Journal par commit (lot 3)

| Commit | Contenu | Gates |
|---|---|---|
| `57c6eb56` | docs : R2 et R3 déjà fermés, inventaire des branches qui tuent, F1 à F6 (§ 9.1 à 9.6) | 504 / 45 / SUCCESS / SUCCESS / 95 |
| `ecbde9b9` | tests rouges, avec des façades du comportement d'origine pour les types neufs (`OwnerStatus`, `ProcessTable`, `Namespaces`, `SweepContext`, `CgroupRemoval`, `Decision.REMOVE_EMPTY`, `StaleSweep.notExamined`) ; les tests qui s'appuyaient sur la table des processus de l'hôte passent par un contexte de balayage injecté | idem |
| `4b919467` | F2 / V-L3-02 : un `stat` tronqué n'est jamais des ticks | idem |
| `eeee841c` | F1, F3 : « propriétaire mort » exige une table prouvée lisible (`ProcessTable`, `hidepid`, espace de PID) ; un cgroup vide n'est jamais tué (`REMOVE_EMPTY`) | idem |
| `290514e5` | V-L3-03 / W6 : la marque porte les espaces de noms PID et temps ; Javadoc de la règle par défaut | idem |
| `2d2c2b5f` | F4, F5, F6, V-L3-04 : balayage qui compte, cgroup imbriqué, suppression ratée, INFO | idem |
| (commit suivant) | documentation utilisateur de la marque, preuves de fin de lot, constats | idem |

Gates rejoués à chaque commit : `check-module-boundaries.py` (`modules=14, sources=504, packages=45`), `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py` (`scripts checked=95`), `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py` : **identiques à la base** ; aucune classe de production ajoutée (les types neufs sont imbriqués).

### 9.8 Preuves

**Rouge → vert.** Chaque test a été joué sur le comportement d'origine (les façades du commit `ecbde9b9` le reproduisent ; sortie rouge dans le message de ce commit) puis après le correctif.

| Défaut | Test | Rouge | Vert |
|---|---|---|---|
| F2 stat tronqué | `CgroupJobOwnershipTest.aTruncatedProcStatNeverYieldsStartTicks`, `…OfALiveOwnerLeavesItsCgroupIntact` ; `ProcessTableTest.aTruncatedStatOfAPresentProcess…` | `expected: <OptionalLong.empty> but was: <OptionalLong[987]>` ; `LEAVE` attendu, `RECLAIM` obtenu | `CgroupJobOwnershipTest` 28/28 |
| F1 table illisible prise pour une mort | `OwnerLookupDescriptorExhaustionTest` (JVM fille, `ulimit -n 160`, vrai noyau) | `LIVE_SELF=GONE`, `LIVE_INIT=GONE` (WSL) | non `GONE` |
| F1 (décision) | `anOwnerTheProcessTableCannotVerifyIsNeverReclaimed`, `ProcessTableTest` (16) : table illisible, d'un autre espace de PID, `stat` illisible, entrée sans `stat`, `hidepid` | `GONE` / `RECLAIM` au lieu de `UNVERIFIABLE` / `LEAVE` | 16/16 Windows (14 exécutés, 2 faute de liens symboliques), 16/16 WSL |
| F3 cgroup vide tué | `anEmptyUnmarkedCgroupIsNeverKilled`, `anUnmarkedCgroupIsRemovedWhenEmptyAndLeftWhenItHoldsAnyProcess` | `expected: <false> but was: <true>` ; `RECLAIM` au lieu de `REMOVE_EMPTY` | vert |
| V-L3-03 espaces de noms | `aMarkOfAnotherPidNamespaceIsNeverReclaimed`, `…TimeNamespace…`, `aMarkWithoutNamespaceStampIsNeverReclaimed`, `aSweeperWhoseOwnNamespacesCannotBeReadReclaimsNothing`, `aLegacyMarkIsNeverReclaimed…` ; `LinuxCgroupStaleRecoveryTest.aJobOfAnotherPidNamespaceIsNeverKilled` ; WSL réel : `aJobStampedWithAnotherPidNamespaceIsNeverKilled`, `…TimeNamespace…` | `LEAVE` attendu, `RECLAIM` obtenu ; WSL : un vrai `sleep` est tué (`expected: <true> but was: <false>`) | vert ; le `sleep` survit |
| V-L3-04 imbriqué | `aDeadOwnersProcessesInANestedCgroupAreKilled`, `anUnmarkedCgroupWithProcessesInANestedCgroupIsLeftIntact` ; WSL réel `aDeadOwnersNestedCgroupIsKilledAndRemovedWithIt` | aucun kill ; WSL : le `sleep` imbriqué survit, « reclaimed » annoncé | vert ; tué, cgroup et enfant supprimés |
| F5 suppression ratée | `aRemovalThatFailsLeavesAResidueNotAReclaim`, `aDeletionFailureIsReportedAsAResidue…` | `reclaimed=[minos-empty-with-residue]` pour un cgroup resté en place | vert ; un seul WARNING, sans chemin |
| F4 parcours borné | `aSweepBoundedBelowTheEntryCountSaysWhatItDidNotExamine`, `theWarningCountsTheEntriesTheBoundedSweepDidNotExamine` | `expected: <2> but was: <0>` | vert |
| F6 journal | `whatASweepReclaimsIsJournaledAtInfoWithoutAnAbsolutePath` | aucun INFO | vert |

**F1, la preuve par niveaux.** L'expérience `FdExhaust.java` (WSL, noyau 6.18, Java 24.0.1) montre le JDK répondant « aucun processus » pour l'appelant et pour `init` pendant l'épuisement des descripteurs ; le test de JVM fille la rejoue dans la suite ; `decide` concluait `RECLAIM` sur « absent » (test existant) ; et la récupération d'un propriétaire mort tue réellement un `sleep` dans un vrai cgroup (tests d'isolation WSL). Le maillon manquant, une tuerie réelle de bout en bout sous EMFILE, n'est pas déterministe : le même épuisement fait aussi échouer la lecture de `cgroup.procs` qui suit (le balayage échoue alors fermé, sans tuer). Il faut un épuisement intermittent, ce que la production produit et qu'un test ne doit pas simuler. Je ne le présente donc pas comme démontré de bout en bout : c'est un défaut de la décision (« absent » conclu d'un échec de lecture), prouvé comme tel.

**R2 sous injection de l'horloge.** Déjà couvert et conservé : pas de ±1 h, ±2001 ms sur l'instant des marques anciennes, jamais `RECLAIM` ; une marque ancienne n'est désormais plus jamais récupérée. L'instant mural n'est une entrée d'aucune décision inter-processus.

**Rejeux ×50.** Il n'y a pas de test de concurrence dans ce lot (aucun thread, aucune barrière). Les tests qui touchent le noyau ou lancent une JVM fille ont quand même été rejoués 50 fois sous WSL (racine déléguée réelle, une invocation Maven par rejeu, `-Dsurefire.rerunFailingTestsCount=0`) : `LinuxCgroupJobOwnershipIsolationTest` (10), `OwnerLookupDescriptorExhaustionTest` (1), `ProcessTableTest` (16) sur le code final `2d2c2b5f` : **50 passages sur 50, 0 échec**. Aucun `Thread.sleep` de synchronisation ajouté ; le seul sommeil de production est l'attente bornée de `cgroup.kill`, préexistante.

**Comptes AVANT / APRÈS.**

| Mesure | Avant (`8670f2e2`) | Après |
|---|---|---|
| Endroits qui décident « ce processus (ou ce cgroup) nous appartient et peut être tué » (`minos-runtime-local`) | 4 | **4** : `CgroupJobOwnership.decide`, `ProcessOwnershipTracker.resolveSameProcess`, `ProcessTreeTermination.terminateTree`, `LinuxCgroupJob.kill`/`close` |
| … dont ceux qui décident d'après l'identité d'un processus tiers | 1 (`decide`) | **1** (`decide`) ; `ProcessTable` est une source de preuve, pas un second décideur |
| Lecteurs de `/proc/<pid>/stat` | 2 (`startTicks`, `ProcessHandle.of` dans `SYSTEM`) | 2 (`startTicks`, `ProcessTable.find`, qui passe par `parseStartTicks` : un seul analyseur) ; `ProcessHandle.of` n'entre plus dans aucune décision de tuer |
| Branches de `decide` qui tuent sans preuve positive de mort | 4 (F1, F2, F3, espaces de noms) | **0** |
| Sources d'instants murales dans une décision inter-processus | 0 | 0 |

**Gates de fin de lot** (Windows, `clean verify` de `2d2c2b5f`) : `BUILD SUCCESS`, 15 modules, 19 min 44, **1 750 tests, 0 échec, 0 erreur, 53 ignorés** (hypothèses `Assumptions`, dont 22 de la zone cgroup qui ne s'exécutent que sous Linux) ; boundaries `modules=14, sources=504, packages=45`, current-docs, product-facts SUCCESS, `scripts checked=95` ; `check-jacoco.py` : 26 portées PASS, seule rouge `m24-polyglot-provider-platform` (line 0,619 / branch 0,375 agrégés, préexistante sous Windows, mêmes chiffres qu'à la base), `provider-sandbox-linux` SKIPPED sous Windows ; `critical-orchestration` 0,891 / 0,773 et `resume-orchestration` 0,906 / 0,787, inchangés. Golden : les 12 de `characterization/` inchangés (`git diff 8670f2e2..HEAD -- minos-app/src/test/resources scripts` vide). Journal : `lot3-verify-windows-d.log` du scratchpad.

### 9.9 Répartition Windows / Linux / non exécuté

- **Windows seulement (vérifié là, sans Linux)** : toute la logique de décision (`CgroupJobOwnershipTest` 28, `ProcessTableTest` 14 sur 16, `LinuxCgroupStaleRecoveryTest` 18, `LinuxCgroupJobDiagnosticsTest` 7, `LinuxCgroupJobFailClosedTest` 9), par injection de la table des processus, de la suppression, des bornes et de la marque du balayeur : aucun test ne dépend de ce que l'hôte sait faire. Les deux tests de lecture des liens `/proc/<pid>/ns/*` ignorent faute de liens symboliques.
- **Linux, vrai noyau, racine cgroup v2 déléguée par un script root (WSL, noyau 6.18, compte 1000)** : les mêmes tests plus `LinuxCgroupJobOwnershipIsolationTest` (10, des `sleep` réellement tués ou épargnés), `ProcessTableTest` 16/16 (liens d'espaces de noms réels, vraie table `/proc`), `OwnerLookupDescriptorExhaustionTest` (JVM fille, descripteurs épuisés) ; module `minos-runtime-local` complet : 268 tests, 0 échec, 16 ignorés (Windows seulement) ; `minos-engine` 455, 7 ignorés.
- **Sous Linux sans racine déléguée** (un développeur ordinaire, une CI sans `delegate-linux-cgroup.sh`) : les tests d'isolation s'ignorent (`assumeTrue`) ; le test de JVM fille, lui, s'exécute.
- **Non exécuté nulle part** : une vraie table `/proc` montée avec `hidepid=2` (il faut remonter `/proc` ; la règle est testée par injection du contenu de `mountinfo`) ; un vrai propriétaire d'un autre espace de PID *frère* (conteneurs distincts partageant une racine) : ses processus apparaissent à 0 dans `cgroup.procs`, cas testé par injection (`membersOfAnotherPidNamespaceAreNeverKilled`) ; un noyau sans espace de temps (avant 5.6) : `time` vaut 0 des deux côtés, testé par injection ; `jacoco` de `provider-sandbox-linux` (non rejoué sous WSL pour ce lot).

### 9.10 Décisions

- **Pas de 2ᵉ mécanisme de marque, pas de 2ᵉ lecteur de `/proc`** : la marque de `93ed7905` est étendue (estampille d'espaces de noms), `parseStartTicks` reste le seul analyseur.
- **Format de la marque.** `…-t<ticks>-n<ns pid>_<ns temps>-<jeton>`. L'ancien analyseur de la release et celui du build « ticks sans espaces » lisent le nouveau nom comme non marqué : ils ne tuent jamais un cgroup peuplé (tests sur les deux expressions reprises verbatim). Un build antérieur peut supprimer un cgroup encore vide au nouveau format (fenêtre de démarrage d'un job, déjà décrite au sprint 1, W5) : il ne tue rien.
- **Coût assumé de la règle par défaut.** Les cgroups marqués par un build `develop` antérieur (instant mural, ou ticks sans espaces) ne sont plus jamais récupérés automatiquement, même à propriétaire mort : rien n'y prouve l'espace de noms. Ils sont signalés (WARNING agrégé, nom et raison) et la documentation utilisateur dit comment les supprimer. Aucune version publiée n'écrit ces marques.
- **`hidepid`.** Une table qui masque les processus d'autres comptes ne prouve l'absence d'un propriétaire que si ce propriétaire est le compte du balayeur (propriétaire du répertoire du cgroup = propriétaire de `/proc/self`). Sinon, `UNVERIFIABLE` : résidu signalé, jamais tué.
- **Appartenance lue récursivement** (le cgroup et ceux en dessous, bornes 16 niveaux et 1 024 cgroups, dépassement = échec fermé comme une lecture impossible) : la frontière d'un job inclut les cgroups qu'il a créés. Conséquence sur un job vivant : `aliveProcesses()` et la vérification de `kill()` couvrent aussi ses sous-cgroupes ; `close()` ne les supprime pas (inchangé, voir § 7).
- **Un cgroup « récupéré » qu'on n'a pas pu supprimer est un résidu**, pas une récupération ; il n'y a plus de journal par cgroup hors du rapport agrégé.

## 10. Lot 4 — Q5, R6 : une interruption reste une interruption, un artefact reste dans son répertoire

> Branche `fiab/q5-r6-interruption`, créée depuis la branche du lot 3 (`fiab/r2-r3-cgroups`, `cab6f74e`), worktree `minos-wt/fiab-lot4`. Constats : **Q5** (`InterruptedException` avalée ; chemin d'artefact non confiné, PLAUSIBLE pour la seconde partie) et **R6** (une interruption pendant l'attente de l'artefact dégrade le run en FAILED). Règles du lot : test rouge avant correctif ; le confinement passe par une primitive de confinement unique, pas par une comparaison de chaînes ; l'interruption se reconnaît d'**une seule** façon.

### 10.1 Cibles relocalisées (base `cab6f74e`)

| Cible de l'audit | Emplacement réel |
|---|---|
| `IndexingRunExecutor.execute` (`catch (Exception)`) | `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java` : `execute` puis `persistTerminalFailure` (l. ~532) |
| détection d'interruption en aval | `IndexingRunExecutor.isInterruption` (chaîne des causes, `InterruptedException` seulement) |
| `validateArtifact` et l'attente de lisibilité | `IndexingRunExecutor.validateArtifact`, `awaitReadable` (l. ~706-728) |
| `ResumeAborted` | classe privée de `IndexingRunExecutor` (constructeur sans cause), levée par `reverifyReusedArtifacts`, `canonicalRunDirectory`, `resumeStagedPromotion` |
| confinement de l'artefact à `runs/<runId>/` | `IndexingResumePlanner.reusable` (`toRealPath().startsWith(runDirectory)`) et `IndexingRunExecutor.reverifyReusedArtifacts` ; **aucun** à `validateArtifact` |
| exécuteur de production dont l'artefact n'est PAS dans `runs/<runId>/` | `minos-runtime-local/.../DistributedIndexerExecutor` (rend `distributed-artifacts/<clé>/artifact`, hors du répertoire de run) |

### 10.2 Ce qui reste vrai à la base (lu, puis prouvé par un test rouge)

- **Q5, drapeau d'interruption : déjà fermé, depuis `90ab9197` (R1 lot 3, « R1-11 »).** `persistTerminalFailure` rétablit le drapeau quand `isInterruption` est vrai ; `InterruptedRunRecoveryTest.threadInterruptionDuringAProviderLeavesAnInterruptedRunAndRestoresTheFlag` et `RunDirectoryHoldTest.anInterruptedRunKeepsItsMarkerBecauseItIsOfferedForResume` le gardent. Le constat tel que l'audit le formule (« sans rétablir le drapeau ») ne se recorrige pas.
- **Mais le correctif d'origine a un défaut que l'audit ne voit pas, et que les tests d'origine ne peuvent pas voir : le drapeau est rétabli AVANT d'écrire l'état.** Avec le drapeau levé, toute entrée-sortie sur un canal de fichier interruptible échoue (`ClosedByInterruptException`) : `markers.mark` de `persistInterruption` échoue, et `persistInterruption` bascule sur `persistFailure`. Les tests d'origine utilisent des stockages en mémoire, qui n'écrivent aucun fichier. Preuve sur le vrai stockage : `InterruptionDuringIndexingTest.interruptionRaisedByTheProviderKeepsTheRunResumableWithItsCheckpoints` (provider qui lève `InterruptedException` après deux cibles terminées) rend `FAILED` au lieu de `INTERRUPTED`, marqueur retiré, `resumableRunId` vide : **les deux points de contrôle sont jetés**. C'est le vrai défaut de Q5/R6 : un arrêt du service pendant une indexation ne laisse pas de run reprenable.
- **R6 : ouvert.** `awaitReadable` rétablit le drapeau mais rend `false` ; `validateArtifact` lève `IllegalStateException("final index artifact is missing or unreadable")` sans cause ; `isInterruption` ne voit donc rien. Rouge : `IndexingInterruptionTest.anInterruptionPendingWhenTheArtifactWaitBeginsLeavesAnInterruptedResumableRun` (`expected: <INTERRUPTED> but was: <FAILED>`, message `IllegalStateException`) et, sur le vrai stockage, `InterruptionDuringIndexingTest.interruptionWhileWaitingForTheArtifactKeepsTheRunResumableWithItsCheckpoints`.
- **R6, même famille : `ResumeAborted` sans cause.** Une interruption pendant la promotion d'une reprise (`resumeStagedPromotion` enveloppe toute exception) devient un `ResumeAborted` sans cause : le run est FAILED **et l'appelant enchaîne sur un run complet neuf**, qui relance tous les providers alors que le service s'arrête. Rouge : `IndexingInterruptionTest.anInterruptionDuringTheResumedPromotionKeepsTheRunInterruptedInsteadOfFallingBackToAFullRun` (le run rendu est un run neuf, pas le run interrompu).
- **R6, même famille : un canal fermé par l'interruption n'est pas reconnu.** `ClosedByInterruptException` (exception d'un canal de fichier interrompu, par exemple dans la lecture de mise en snapshot) n'est pas un `InterruptedException` : le run devient FAILED. Rouge : `IndexingInterruptionTest.aChannelClosedByTheInterruptionDuringStagingKeepsTheRunInterrupted`.
- **Q5, confinement : REPRODUIT (le PLAUSIBLE tombe), au niveau du port.** Un exécuteur qui rend un artefact hors de `runs/<runId>/` est accepté, le run SUCCEEDED et le fichier extérieur est mis en snapshot : par des segments `..` (le chemin est normalisé en `home/outside/index.scip`, donc accepté), par un lien symbolique final, par un répertoire ancêtre lié. Rouge : `IndexingArtifactConfinementTest` (3 tests, `expected: <FAILED> but was: <SUCCEEDED>` ; les liens symboliques sont disponibles sur cette machine Windows, aucun test sauté). **Portée réelle, dite honnêtement** : aucun exécuteur de production ne laisse le *fournisseur* choisir le chemin rendu (`ProcessIndexerExecutor` le construit lui-même et vérifie `regularFileNoFollow`, `SymlinkReplacingProviderMain` en est le test) ; le défaut est l'absence de garde-fou dans l'orchestrateur (défense en profondeur), pas une faille atteignable aujourd'hui. Le lot le ferme quand même, parce que le port n'impose rien à un futur exécuteur.
- **Piège découvert : `DistributedIndexerExecutor` rend un artefact hors de `runs/<runId>/`** (`distributed-artifacts/<clé>/artifact`, dans le cache de bundles vérifié). Un confinement naïf au répertoire de run casserait l'indexation distante (elle est aujourd'hui fermée en échec par A1, mais le code est prévu pour fonctionner). Décision au § 10.4.

### 10.3 Chiffres de référence des gates (base `cab6f74e`)

| Gate | Résultat |
|---|---|
| `check-module-boundaries.py` | `modules=14, sources=504, packages=45` |
| `check-current-docs.py`, `product-facts.py --check` | SUCCESS |
| `check-milestone-artifact-references.py` | `scripts checked=95` |
| `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py` | SUCCESS |
| `check-jacoco.py` | référence : fin du lot 3 (`dfc9027a`), 26 portées PASS, seule rouge `m24-polyglot-provider-platform` (préexistante, Windows) ; `critical-orchestration` line 0,891 / branch 0,773 ; `resume-orchestration` 0,906 / 0,787 |

### 10.4 Décisions

- **Q5, drapeau d'interruption : pas recorrigé, déjà fermé.** Ce que le lot corrige est l'**ordre** : `persistTerminalFailure` efface le drapeau pour écrire l'état (`RunContext.clearInterruptFlagForWrite`) et le **rejoue en tout dernier**, sur toutes les sorties (INTERRUPTED persisté, repli FAILED, interruption levée à l'entrée, interruption reçue pendant l'écriture). Le drapeau rétabli avant l'écriture était la cause des points de contrôle jetés (§ 10.2).
- **Une seconde interruption pendant l'écriture (V-L4-02).** Chaque écriture terminale efface le drapeau avant d'écrire et se refait (au plus 3 tentatives, constante `PERSISTENCE_ATTEMPTS_UNDER_INTERRUPTION`) quand elle a échoué parce que le thread a été interrompu de nouveau ; il en va de même du marqueur de rétention. Si une écriture échoue malgré tout, **le run rendu le dit** (« the interrupted state could not be written, the next indexing reconciles it ») au lieu d'annoncer une reprise offerte que le magasin ne tient pas.
- **Une seule façon de reconnaître une interruption : `IndexingRunExecutor.isInterruption`.** Elle cherche, dans toute la chaîne des causes, un `InterruptedException` ou un `ClosedByInterruptException` (l'exception d'un canal de fichier interrompu). `InterruptedIOException` est volontairement exclue : `SocketTimeoutException` en hérite, un délai réseau n'est pas un arrêt du service. Conséquence de la règle : **toute exception qui perd la cause d'une interruption est un défaut à l'endroit où elle la perd** ; les trois endroits trouvés sont corrigés (`awaitReadable` propage au lieu de rendre `false` ; `ResumeAborted` chaîne sa cause ; la pause de remplacement de `DurableAtomicFile` garde l'`InterruptedException` comme cause).
- **Une interruption pendant une reprise n'abandonne pas la reprise.** `resumeStagedPromotion` ne transforme plus une interruption en `ResumeAborted` et `executeResumed` ne rend pas la main à un run complet neuf quand l'abandon était une interruption : le run rouvert reste INTERRUPTED et offert.
- **Q5, confinement : la primitive est `ConfinedFileOpener`** (S5 : chaque répertoire descendu sans suivre de lien, dernier composant ouvert sans suivre de lien, lien, jonction, objet spécial et non-régulier refusés). `PrivateLocalStorage` est la politique de droits à la création (0700/0600, ACL), pas une primitive de confinement : elle n'est pas le bon outil ici. Aucune comparaison de chaînes : `Path.startsWith` sur des chemins normalisés ou résolus, par composants (un répertoire frère `run-evil` n'est pas « sous » `run`, test dédié).
- **Une seule décision de confinement : `ArtifactConfinement.requireInside`**, appelée par `validateArtifact` (à la sortie du provider), par `stageSnapshot` (tous les artefacts, frais ou réutilisés, juste avant la mise en snapshot : V-L4-03), par `IndexingResumePlanner.reusable` et par `reverifyReusedArtifacts` (les deux copies de la règle d'avant disparaissent). Un lien est refusé **à tout niveau** du chemin, même quand sa cible retomberait dans le répertoire de run (refuser tout lien est plus simple à démontrer que trier les liens inoffensifs).
- **L'exécuteur distribué n'est pas confiné à `runs/<runId>/`.** Il rend un artefact du cache de bundles vérifié (`distributed-artifacts/<clé>/`). Le port `IndexerExecutor` gagne `artifactsLiveInRunDirectory()` (défaut `true` : un nouvel exécuteur est confiné tant qu'il ne déclare pas le contraire) ; `DistributedIndexerExecutor` répond `false`, `LocalRemoteIndexingRuntime` le transmet. Le confiner aurait cassé l'indexation distante le jour où A1 la rouvre.
- **Sans répertoire de run connu, rien n'est confiné (V-L4-04).** Le port `ResumableRunMarkers.none()` (constructeur de cycle de vie à quatre arguments, stores en mémoire) ne connaît aucun répertoire de run ; aucune reprise n'est possible non plus (le planificateur refuse). En production le câblage (`RunDirectoryResumableRunMarkers`) rend toujours un répertoire. C'est une décision, pas un oubli : un répertoire inconnu ne se confine pas.
- **Messages.** Aucun message neuf ne porte de chemin. Le message d'un run dont l'artefact ne devient jamais lisible n'en porte plus non plus (il portait le chemin absolu, V-L4-04). Les refus de reprise `artifact became a symbolic link` / `left the run directory` deviennent `artifact is, or lies under, a symbolic link or a special file` / `lies outside the run directory` (le texte ne dit plus « became », il décrit l'état ; aucun test ni script n'affirmait l'ancien).
- **Jouée au niveau du port, pas de la production.** Le chemin de l'artefact n'est jamais choisi par le provider (§ 10.2) : le lot est une défense en profondeur qui ne change rien aujourd'hui en production. Ce qui change pour l'utilisateur, c'est l'interruption (§ 10.7).

### 10.5 Comptes AVANT (les comptes APRÈS sont au § 10.7)

| Décision | Sites avant |
|---|---|
| « ceci est une interruption » (production) | **1** : `IndexingRunExecutor.isInterruption` (cause `InterruptedException` seulement) ; `LinuxCgroupJob` *préserve* une interruption dans une chaîne nettoyée mais ne décide rien du run |
| « ce chemin est confiné au répertoire de run » | **2** : `IndexingResumePlanner.reusable`, `IndexingRunExecutor.reverifyReusedArtifacts` (deux copies de la même règle `isSymbolicLink` + `toRealPath().startsWith`) ; **0** à `validateArtifact` |

### 10.6 Journal par commit

| Commit | Contenu | Gates |
|---|---|---|
| `b9a74864` | docs : état réel de Q5 et R6 à la base, inventaire des décisions | 504 / 45 / SUCCESS / SUCCESS / 95 |
| `5cd64c30` | tests rouges : interruption sur le vrai stockage (2), en mémoire (3), confinement (3) | idem |
| `1cbb1432` | test rouge : la pause de remplacement garde sa cause | idem |
| `97aba265` | Q5/R6 : drapeau effacé pour écrire puis rejoué, `awaitReadable` propage, `ResumeAborted` chaîne sa cause, pas de repli sur un run complet après interruption | idem |
| `41be5596` | Q5 : `ArtifactConfinement`, une décision pour trois sites, exemption de l'exécuteur distribué | 505 / 45 / SUCCESS / SUCCESS / 95 |
| `037bf19f` | test rouge : V-L4-02, seconde interruption pendant l'écriture | idem |
| `fad14230` | V-L4-02 : écriture refaite drapeau effacé, statut rendu fidèle | idem |
| `af85eab9` | refactor : un seul endroit efface le drapeau pour écrire | idem |
| `3c71e622` | tests rouges : V-L4-03 (ancêtre lié vers l'intérieur, artefact frais échangé), V-L4-04 (message sans chemin) | idem |
| `66fd1d17` | V-L4-03/04 : aucun lien à aucun niveau, chaque artefact reconfiné avant la mise en snapshot, message sans chemin | idem |
| `8bec5c6e` | décisions, journal, preuves de fin de lot, constats | idem |
| (commit suivant) | remarques non bloquantes du verdict final : constantes des tests, § 7 | idem |

Gates rejoués après chaque commit : `check-module-boundaries.py` (`modules=14`, `sources=505` depuis `41be5596` : +1 classe de production, `ArtifactConfinement`, attendue ; `packages=45`), `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py` (`scripts checked=95`), `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py`, `check-remote-distributed-consistency.py` (le lot touche `DistributedIndexerExecutor`) : SUCCESS. Aucun script de `scripts/` ne nomme les méthodes touchées (grep fait avant chaque changement de nom).

### 10.7 Preuves

**Ce que voit l'utilisateur.**

- **Un arrêt du service pendant une indexation** (le thread d'indexation est interrompu : arrêt de l'hôte MCP, fermeture d'un pool) : avant, le run était rendu **FAILED** (ou restait RUNNING sur disque), le marqueur de rétention était retiré et les points de contrôle déjà acquis devenaient inutilisables ; `minos_index_status` répondait `INDEXING` jusqu'à la prochaine exécution. Après, le run est **INTERRUPTED**, le projet est `STALE` (ou `FAILED` s'il n'avait jamais été indexé) avec `resumableRunId`, le répertoire de run reste retenu, et `minos index` **reprend** : les providers terminés ne sont pas relancés. Le drapeau d'interruption est rendu à l'appelant.
- **Une interruption pendant la promotion d'une reprise** ne relance plus un run complet (tous les providers) pendant que le service s'arrête.
- Le message d'un run dont l'artefact est introuvable ne contient plus de chemin absolu.
- **Rien d'autre ne change en production** : un artefact qui sort de `runs/<runId>/` n'est produit par aucun exécuteur (§ 10.2) ; s'il l'était, le run serait FAILED avec « executor returned an artifact that lies outside the run directory » (ou « … is, or lies under, a symbolic link or a special file »), sans rien mettre en snapshot.

**Rouge → vert.** Chaque test a été joué sur le code d'origine avant le correctif (Windows, puis WSL Ubuntu pour ceux qui touchent aux liens), sortie rouge dans le message du commit.

| Défaut | Test | Rouge (code d'origine) | Vert |
|---|---|---|---|
| Q5/R6 interruption levée par le provider, vrai stockage | `InterruptionDuringIndexingTest.interruptionRaisedByTheProviderKeepsTheRunResumableWithItsCheckpoints` | `Optional[InterruptedException: service is stopping] ==> expected: <INTERRUPTED> but was: <FAILED>` | 2/2 ; **2 points de contrôle relus sur disque, run INTERRUPTED, marqueur, `resumableRunId`, puis la reprise en réutilise 2 et ne relance que le dernier provider** |
| R6 interruption PENDANT l'attente de l'artefact, vrai stockage | `…interruptionWhileWaitingForTheArtifactKeepsTheRunResumableWithItsCheckpoints` | `IllegalStateException: final index artifact is missing or unreadable … ==> expected: <INTERRUPTED> but was: <FAILED>` | idem |
| R6 attente déjà interrompue, en mémoire | `IndexingInterruptionTest.anInterruptionPendingWhenTheArtifactWaitBeginsLeavesAnInterruptedResumableRun` | `expected: <INTERRUPTED> but was: <FAILED>` | 5/5 |
| R6 `ResumeAborted` sans cause, repli sur un run neuf | `…anInterruptionDuringTheResumedPromotionKeepsTheRunInterruptedInsteadOfFallingBackToAFullRun` | le run rendu est un run **neuf** | vert |
| R6 canal fermé par l'interruption | `…aChannelClosedByTheInterruptionDuringStagingKeepsTheRunInterrupted` | `the interrupt flag must be replayed to the caller ==> expected: <true> but was: <false>` | vert |
| R6 pause de remplacement sans cause | `DurableAtomicFileTest.anInterruptionDuringTheReplacementPauseKeepsItsCauseAndReplaysTheFlag` | `Unexpected null value, expected: <java.lang.InterruptedException> but was: <null>` | 7/7 |
| V-L4-02 seconde interruption pendant l'écriture de l'état | `…aSecondInterruptionWhileTheInterruptedStateIsBeingWrittenStillReachesTheStore` | `the store holds what the caller was told ==> expected: <INTERRUPTED> but was: <RUNNING>` | vert |
| V-L4-02 seconde interruption pendant la pose du marqueur | `…aSecondInterruptionWhileTheMarkerIsBeingHeldStillOffersTheRunForResume` | `expected: <INTERRUPTED> but was: <FAILED>` | vert |
| Q5 confinement : `..`, lien final, ancêtre lié | `IndexingArtifactConfinementTest` (3) | `an artifact outside the run directory is refused ==> expected: <FAILED> but was: <SUCCEEDED>` (le fichier extérieur est mis en snapshot) | 7/7 |
| V-L4-03 ancêtre lié vers l'intérieur | `ArtifactConfinementTest.aDirectoryOnTheWayThatIsALinkIsRefusedEvenWhenItLeadsInsideTheRunDirectory` | `Expected ArtifactConfinement.Escape to be thrown, but nothing was thrown` | 8/8 |
| V-L4-03 artefact frais échangé pendant un autre provider | `…anArtifactReplacedByALinkWhileLaterProvidersRanIsRefusedBeforeStaging` | `expected: <FAILED> but was: <SUCCEEDED>` | vert |
| V-L4-04 message sans chemin | `…anArtifactThatNeverBecomesReadableFailsTheRunWithoutItsPath` | message avec `C:\Users\…\never-written.scip` | vert |

**Le constat PLAUSIBLE (confinement) n'est pas tombé : il est reproduit**, au niveau du port (§ 10.2). Sa portée réelle est documentée : défense en profondeur, pas une faille atteignable aujourd'hui.

**Test de concurrence, rejoué 50 fois.** `InterruptionDuringIndexingTest` (vrai stockage, deux threads : celui qui indexe et celui qui l'interrompt) se synchronise sans aucun `Thread.sleep` : le fournisseur de la troisième cible signale sa sortie par un `CountDownLatch`, le thread d'interruption attend ce signal, puis l'entrée du thread d'indexation dans son **unique** attente chronométrée (état `TIMED_WAITING` : l'attente de lisibilité de l'artefact), et seulement alors l'interrompt. Les bornes de 60 s ne sont que des garde-fous, jamais ce qui ordonne les événements. Rejeu par 50 lancements Maven successifs (`-Dsurefire.rerunFailingTestsCount=0`, script `loop50.sh` du scratchpad, arbre séparé du worktree) : **50 passages sur 50, 0 échec** sur `97aba265` (premier correctif) et **50 passages sur 50, 0 échec (et 50 sur 50 de `verif-fiab`, indépendamment, sur les cinq classes du lot)** sur `66fd1d17` (code final). Les autres tests du lot (en mémoire) sont déterministes et mono-thread : les deux « secondes interruptions » sont livrées par l'appel d'écriture lui-même.

**Comptes AVANT / APRÈS** (grep du code de production, hors commentaires ; `minos-intellij` exclu).

| Décision | Avant | Après |
|---|---|---|
| « ceci est une interruption » | **1** (`isInterruption`, `InterruptedException` seulement) | **1** (`isInterruption`, `InterruptedException` ou `ClosedByInterruptException`) ; ses appelants passent de 1 à 5 (reprise, promotion, écriture, marqueur), la règle reste écrite une fois |
| interruptions avalées qui perdaient la cause (`catch InterruptedException` qui rend `false`, `ResumeAborted` sans cause, `IOException` sans cause) | 3 (`awaitReadable`, `ResumeAborted`, pause de `DurableAtomicFile`) | **0** |
| endroits qui effacent le drapeau pour écrire | 0 | **1** (`RunContext.clearInterruptFlagForWrite`, appelé par les quatre écritures terminales) |
| « ce chemin est confiné au répertoire de run » | **2** (`IndexingResumePlanner.reusable`, `reverifyReusedArtifacts`) + **0** à `validateArtifact` | **1** (`ArtifactConfinement.requireInside`), 4 appelants |
| `catch (InterruptedException)` de production | 33 | 32 |
| `Thread.interrupted()` / `isInterrupted()` de production | 1 | 2 (la pause de `DurableAtomicFile`, existante, et `RunContext.clearInterruptFlagForWrite`) |

**Gates de fin de lot.** `clean verify` sur `66fd1d17` (code final ; les commits suivants ne changent que ce fichier) : voir ci-dessus. `check-module-boundaries.py` `modules=14, sources=505, packages=45` (base 504 : +1, `ArtifactConfinement`) ; `check-current-docs.py`, `product-facts.py --check` SUCCESS ; `check-milestone-artifact-references.py` `scripts checked=95` ; `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py`, `check-remote-distributed-consistency.py` SUCCESS ; `check-jacoco.py` : 26 portées PASS, **seule rouge `m24-polyglot-provider-platform`** (line 0,228 < 0,28, préexistante, Windows, mêmes chiffres qu'à la base) ; `critical-orchestration` line / branch 0,891 / 0,773 → 0,908 / 0,766 ; `resume-orchestration` 0,906 / 0,787 → 0,920 / 0,782 (seuils 0,75 / 0,55 ; la branche baisse de 0,007 et 0,005 : des chemins neufs, dont les réécritures sous interruption, sont des branches de plus)

**Golden.** Les 12 de `characterization/` sont **inchangés** (`git diff cab6f74e..HEAD -- minos-app/src/test/resources scripts` vide) ; aucun script de `scripts/` assoupli.

### 10.8 Répartition Windows / WSL

- **Windows** (machine locale, liens symboliques disponibles : **aucun test sauté**) : toute la suite du lot ; `clean verify` complet (`BUILD SUCCESS`, 15 modules, 11 min 19, **1 773 tests exécutés, 0 échec, 0 erreur, 53 ignorés** (les mêmes 53 `Assumptions` qu'au lot 3 ; les tests de liens symboliques du lot se sont exécutés, aucun sauté ; 1 750 à la base, +23 : `ArtifactConfinementTest` 8, `IndexingArtifactConfinementTest` 7, `IndexingInterruptionTest` 5, `InterruptionDuringIndexingTest` 2, `DurableAtomicFileTest` +1)) ; le test de concurrence rejoué 50 fois ; les stratégies de confinement du repli par chemin de `ConfinedFileOpener` (Windows : revalidation de la chaîne d'ancêtres pendant que le canal est tenu ouvert).
- **WSL Ubuntu, Java 24, compte non privilégié, arbre copié sur le système de fichiers Linux** : `ArtifactConfinementTest` 8, `IndexingArtifactConfinementTest` 7, `IndexingInterruptionTest` 5, `IndexingResumeTest` 19, `IndexingResumePlannerTest` 7, `InterruptedRunRecoveryTest` 13, `RunDirectoryHoldTest` 4, `DurableAtomicFileTest` 7, `InterruptionDuringIndexingTest` 2 : **tous verts, 0 sauté** (stratégie `SecureDirectoryStream` de `ConfinedFileOpener`, `openat` sans suivre de lien) ; le même ensemble est **rouge** sur le code d'origine (9 tests rouges : 3 de confinement, 3 d'interruption en mémoire, 1 de pause de remplacement, 2 sur le vrai stockage).
- **Non exécuté nulle part** : le rejeu ×50 n'a été fait que sous Windows (le test n'a pas de dépendance à la plateforme autre que le système de fichiers, et passe sous Linux en une exécution) ; une interruption réelle d'un provider externe sous sandbox (le test simule le provider par un exécuteur).

### 10.9 Constats de verif-fiab (lot 4)

V-L4-01 à V-L4-05 et leur résolution : § 6, « Lot 4 ». Aucun constat ouvert à la fin du lot : `VERDICT-FINAL: ok` de `verif-fiab` sur `8bec5c6e` (`clean verify` rejoué de son côté : 1 773 tests, 0 échec, 9 min 57 ; rejeux ×50 indépendants sur les cinq classes du lot). Trois remarques non bloquantes, consignées : R-L4-A (littéraux dupliqués dans `IndexingArtifactConfinementTest`, factorisés en constantes), R-L4-B et R-L4-C (§ 7).

## 11. Lot 5 — Q8, Q9 : une entrée abîmée dégrade cette entrée, pas l'inventaire

> Branche `fiab/q8-q9-tolerance`, créée depuis la branche du lot 4 (`fiab/q5-r6-interruption`, `40100dbf`), worktree `minos-wt/fiab-lot5`. Constats : **Q8** (un seul fichier corrompu ou un répertoire illisible fait échouer tout `listProjects`) et **Q9** (PLAUSIBLE : une clé sémantique en double fait échouer toute l'indexation sémantique). Règles du lot : test rouge avant correctif ; la tolérance ne devient jamais du silence (chaque entrée dégradée est **comptée et affichée**, sans chemin absolu) ; Q9 se prouve atteignable par le chemin de production **avant** toute correction.

### 11.1 Cibles relocalisées (base `40100dbf`)

| Cible de l'audit | Emplacement réel |
|---|---|
| registre de projets, lecture d'une entrée | `minos-storage-local/src/main/java/com/minos/storage/local/registry/LocalProjectRegistry.java` : `listProjects` (boucle sur `projects/*.properties`), `readProject` (`UUID.fromString`, `Instant.parse` deux fois, racines, `required`), `idFromPropertiesFile` ; enveloppé par `InterProcessLocalProjectRegistry` (même contrat, sous bail de fichier) |
| port | `minos-engine/src/main/java/com/minos/registry/ProjectRegistry.java` (`listProjects() throws IOException`) |
| historique (`cli-index-history/<projectId>.properties`) | `minos-application/src/main/java/com/minos/application/ProjectInspectionService.java` : `readHistory` (`BoundedProperties.load`, `required`, `Instant.parse`), appelée par `view` |
| `visitFileFailed` (répertoire illisible) | `minos-engine/src/main/java/com/minos/discovery/ProjectDiscoveryService.java` (l. 151 : `throw exception`), appelée par `ProjectInspectionService.view` pour la racine de chaque projet |
| `listProjects` de la commande | `minos-cli/.../ProjectCommand.runList` → `ProjectOperations.listProjects` → `LocalProjectOperations.listProjects` → `ProjectInspectionService.listProjects` (une boucle `view(project)` sans garde) ; aussi `LocalMinosApi.listProjects` (API Java) |
| fabrique sémantique | `minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java` (l. 89-95, `IllegalStateException("duplicate semantic stable key")`), appelée par `SemanticIndexService` |

### 11.2 Q8 : ce qui reste vrai à la base (lu dans les sources)

Aucun commit postérieur à l'audit ne touche ces chemins. **Q8 est ouvert.** Les modes de défaillance d'un seul fichier abîmé, tous propagés hors de la boucle :

- un `createdAt`/`updatedAt` invalide : `DateTimeParseException` (non contrôlée) ;
- un `id` de contenu qui n'est pas un UUID : `IllegalArgumentException` ; un `id` de contenu différent du nom de fichier : `IOException` (« identity mismatch ») ;
- une propriété requise absente ou vide : `IllegalStateException` ; les deux racines à la fois, une racine portable sans mapping, un fichier vide, tronqué, binaire, trop gros : `IOException` (`BoundedProperties`) ;
- un nom de fichier `*.properties` qui n'est pas un UUID : `IOException` ;
- un répertoire illisible sous la racine d'un projet (`visitFileFailed`) : `IOException`, qui fait échouer `ProjectInspectionService.listProjects` **pour tous les projets** ;
- un historique dont `completedAt` est invalide : `DateTimeParseException`, même effet.

Ce que la lecture découvre en plus : `propertyFiles` écarte **en silence** toute entrée `*.properties` qui n'est pas un fichier régulier (répertoire, lien pendant), donc un projet dont l'entrée a été remplacée disparaît sans trace.

### 11.3 Comptes AVANT : qui lit ou valide une entrée de registre ou d'historique

| # | Site | Tolérance |
|---|---|---|
| 1 | `LocalProjectRegistry.readProject` (entrée de projet) | aucune |
| 2 | `LocalProjectRegistry.readWorkspaceMetadata` (entrée de workspace) | aucune |
| 3 | `LocalProjectRegistry.idFromPropertiesFile` (nom de fichier) | aucune |
| 4 | `ProjectInspectionService.readHistory` (historique) | aucune |
| 5 | `PostgresProjectRegistry.readProject` (ligne SQL typée : `uuid`, `timestamptz`) | sans objet : aucune corruption de texte possible |

**AVANT : 5 sites, 0 chemin tolérant.** L'objectif est **un** vocabulaire de dégradation (`DegradedEntry`) produit à deux endroits qui ne peuvent pas être confondus (une entrée de registre illisible ; un projet dont la vue ne peut être assemblée), pas une sixième variante de lecture : la lecture d'une entrée reste dans `readProject`.

### 11.4 Q9 : ce que dit la lecture avant tout test

Une clé en double n'existe que si deux symboles non externes d'un même instantané portent le même `symbolKey` (la clé d'un document SYMBOL est `symbol:<symbolKey>` ; celle d'un CHUNK `chunk:<symbolKey>:<début>:<fin>` ; celle d'un FILE `file:<fileId>`, une par entrée d'une table à clé unique). Or : (1) le **seul** producteur de symboles en production est `ScipSymbolNormalizer`, qui dérive `id = "sym:" + sha256(projectId + symbolKey)` ; deux symboles de même `symbolKey` ont donc le **même `id`** ; (2) `CodeKnowledgeSnapshot` refuse deux symboles de même `id` (`requireUniqueIds`), `FileSymbolSnapshotStore.publish` aussi (`rejectDuplicateIds`), et la mise en snapshot d'un run fusionne les symboles par `id` (`ScipProjectSnapshotLifecycle.putUnique`) ; un même symbole défini deux fois dans un index est dédupliqué par `id` à l'import (`ScipSymbolSnapshotImporter.CapturingStore`). Le scénario demandé (« deux documents de même clé ») demande donc un instantané que la production ne peut pas construire. **À prouver par des tests** : chaîne normaliseur → import → instantané → fabrique, sans appel direct de la fabrique avec des symboles fabriqués à la main.

### 11.5 Chiffres de référence des gates (base `40100dbf`, avant le premier commit de code)

| Gate | Résultat |
|---|---|
| `check-module-boundaries.py` | `modules=14, sources=505, packages=45` |
| `check-current-docs.py`, `product-facts.py --check` | SUCCESS |
| `check-milestone-artifact-references.py` | `scripts checked=95` |
| `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py` | SUCCESS |
| `check-jacoco.py` | référence : fin du lot 4 (`66fd1d17`, code identique), 26 portées PASS, seule rouge `m24-polyglot-provider-platform` (préexistante, Windows) ; `semantic-hybrid-retrieval` line 0,910 / branch 0,722 (la seule portée qui nomme une classe de ce lot, `SemanticDocumentFactory`) |
| `./mvnw clean verify` (fin du lot 4) | BUILD SUCCESS, 1 773 tests, 0 échec, 53 ignorés |

### 11.6 Journal par commit (lot 5)

| Commit | Contenu | Gates |
|---|---|---|
| `7e3ac753` | docs : état réel de Q8 et Q9 à la base, inventaire, comptes AVANT | 505 / 45 / SUCCESS / SUCCESS / 95 |
| `821c7e67` | tests rouges Q8 (registre, vue, CLI, vrai `MINOS_HOME`) ; points d'entrée neufs en façades de l'ancien comportement | 506 / 45 / SUCCESS / SUCCESS / 95 |
| `97021726` | Q8 : `ProjectRegistry.inventory()`, `scanProjects` (une lecture, deux vues), `DegradedEntry` | idem |
| `a097708a` | Q8 : `ProjectInspectionService.inventory()`, ligne `UNREADABLE`, interruption relancée, API sans run reprenable pour une ligne abîmée | idem |
| `83474d98` | Q8 : `project list` sort en 3, compteur et raisons en texte et en JSON ; `docs/user/cli.md`, `troubleshooting.md` | idem |
| `f6d0606b` | Q9 : le constat tombe, trois chaînes de tests (aucun correctif) | idem |
| `3d2247a1` | tests rouges V-L5-01 (caractères de contrôle dans la raison) | idem |
| `dc38efee` | V-L5-01 : `DegradedEntry.printable` ; V-L5-03 : code 3 dans arc42 | idem |
| (commit suivant) | décisions, journal, preuves de fin de lot, constats | idem |

Gates rejoués après chaque commit : `check-module-boundaries.py` (`modules=14`, `sources=506` depuis `821c7e67` : +1 classe de production, `DegradedEntry`, attendue ; `packages=45`), `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py` (`scripts checked=95`), `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py` : SUCCESS. Aucun script de `scripts/` ne nomme les méthodes touchées (`listProjects` n'y apparaît que dans des sondes historiques sous `scripts/history/` et `scripts/m15`, qui compilent contre l'interface inchangée).

### 11.7 Décisions

- **Q8 : une entrée abîmée dégrade cette entrée, elle ne disparaît pas.** Le projet abîmé reste une **ligne** de l'inventaire, à l'état explicite `UNREADABLE` (`ProjectSummary.UNREADABLE_STATE` : un état de vue, pas une disponibilité d'index), et il est **compté** dans un `DegradedEntry(entry, reason)`. `entry` est l'identifiant du projet (ou le nom du fichier de registre réduit à `[A-Za-z0-9._-]`, 64 caractères au plus), `reason` est un message public passé par `PublicErrorMessages.sanitize` (un message qui porte un chemin tombe sur le nom de la classe de l'exception) : **aucun chemin absolu**. Quand c'est le registre lui-même qui est illisible, nom et racine de la ligne valent `-` ; quand c'est la vue qui n'a pas pu être assemblée (historique, état, répertoire), ils restent ceux du registre.
- **Deux frontières d'isolement, un vocabulaire.** (1) `LocalProjectRegistry.scanProjects` : une entrée de registre ; (2) `ProjectInspectionService.inventory` : la vue d'un projet (historique d'indexation, état, découverte, donc `visitFileFailed`). Les deux produisent le même `DegradedEntry` par les deux mêmes fabriques, et un seul rendu (`ProjectCommand.renderProjects`, `ProjectJson.degraded`). La lecture d'une entrée reste dans `readProject` : pas de sixième variante de lecture.
- **Le listage strict reste strict.** `ProjectRegistry.listProjects()` échoue toujours sur la première entrée abîmée, avec l'exception d'origine : c'est ce que `registerProject` (qui cherche une racine déjà enregistrée), la résolution d'un projet par nom, l'export NEXUS et les workspaces utilisent ; une mutation ou une résolution qui ne sait pas si l'entrée abîmée est celle qu'elle cherche doit échouer fermée. `inspectProject` (un seul projet) reste strict aussi. Seul l'**inventaire** (`inventory()`) est tolérant. Un registre dont le répertoire ne peut pas être listé du tout échoue en bloc (code 1) : il n'y a rien à rapporter entrée par entrée.
- **Une interruption n'est pas une entrée abîmée.** Les deux frontières relancent l'échec quand le thread est interrompu, au lieu de dégrader le projet en cours (mutation témoin : sans le garde, le test d'interruption de la vue est rouge). Le garde du registre n'a pas de test : sur ce JDK, une lecture de fichier sur un thread interrompu ne lève pas, et l'interruption en plein milieu d'une lecture n'est pas reproductible de façon déterministe ; le test du registre affirme ce qu'il peut (une interruption en attente ne dégrade aucune entrée et reste en attente).
- **Le code de sortie : 3, et seule `project list` le rend.** Les codes existants : 0 succès, 1 erreur d'exécution ou diagnostic action requise (`doctor`), 2 usage. Un résultat partiel existe déjà ailleurs comme **avertissement** (`import-scip` : `commitStatus` et `warning:` sur stderr, code 0), mais le constat demande explicitement un code qui distingue « tout va bien » de « inventaire partiel ». 1 le confondrait avec un échec (un script ne lirait plus stdout, pourtant valide) ; 3 dit « la sortie est valide et complète pour ce qui a pu être lu ». Écrit dans `docs/user/cli.md` (Codes de sortie) et `troubleshooting.md`. **Aucune autre commande ne change de code** (tests : `add`, `inspect`, `index-status`, registre non listable = 1, usage = 2, registre vide = 0).
- **La tolérance est comptée et affichée, dans les deux formats.** Texte : les lignes, puis `degraded: <N>` et `  <entrée>: <raison>` par entrée ; JSON : `degradedCount` et `degraded` après `count` et `projects` ; stderr : `warning: project inventory is partial: <N> of <total> entries are degraded`. `count` compte toutes les lignes, dégradées comprises. **Sans entrée dégradée rien ne change** (octet pour octet : pas une clé, pas une ligne, rien sur stderr ; les golden `characterization/` sont inchangés).
- **L'API Java** (`MinosApi.listProjects`) rend la même liste : la ligne abîmée a l'état `UNREADABLE` (visible de l'appelant) ; elle ne cherche pas de run reprenable pour cette ligne (son magasin d'état peut être la partie abîmée). L'API n'a pas de compteur : l'état est la trace.
- **PostgreSQL** : `PostgresProjectRegistry.readProject` lit des colonnes typées (`uuid`, `timestamptz`) : aucune corruption de texte n'y est possible ; il garde le défaut de l'interface (`inventory()` = `listProjects()`, sans entrée dégradée).
- **Q9 : le constat tombe, aucun correctif.** Voir § 11.8 : deux symboles de même clé ne peuvent pas arriver à la fabrique par le chemin de production.

### 11.8 Q9 : pourquoi le constat ne tient pas (preuve, pas de correctif)

**Verdict : NON REPRODUIT.** Une clé sémantique en double exige deux symboles non externes de même `symbolKey` dans l'instantané actif (les clés `chunk:` et `file:` en découlent : `chunk:<symbolKey>:<début>:<fin>` se décode sans ambiguïté, les deux derniers champs étant des entiers ; `file:<fileId>` est une clé de table). L'invariant qui l'empêche est à trois étages, **chacun sous test** :

1. **Le producteur dérive l'identifiant de la clé.** `ScipSymbolNormalizer.normalize` est le seul producteur de symboles hors codec de relecture : `id = "sym:" + sha256(projectId + symbolKey)`. Deux symboles du même projet ont la même clé **si et seulement s'ils ont le même identifiant** (`ScipSymbolNormalizerTest.theIdOfASymbolIsAFunctionOfItsKeyAndTheKeyOfItsIdentity`, six variantes : fournisseur, run, genre, externe, projet).
2. **L'instantané refuse un identifiant en double** (`CodeKnowledgeSnapshot.requireUniqueIds`, `FileSymbolSnapshotStore.publish` : `rejectDuplicateIds`) : `CodeKnowledgeSnapshotIdentityTest.twoSymbolsWithTheSameIdAreRefusedByTheSnapshot` (message exact). Le même test dit aussi, honnêtement, ce que l'instantané **ne** vérifie **pas** : l'unicité des clés (`theSnapshotDoesNotCheckKeysItself`) ; elle découle du point 1, pas d'un contrôle.
3. **Chaque étape en amont fusionne ou refuse** : un même symbole défini plusieurs fois dans un index est dédupliqué par identifiant à l'import (`ScipSymbolSnapshotImporter.CapturingStore`) ; la mise en snapshot d'un run fusionne les symboles identiques et refuse les autres (`ScipProjectSnapshotLifecycle.putUnique` : « provider snapshot collision »).

**Le test de bout en bout** (`SemanticDocumentKeysReachabilityTest`, module `minos-bootstrap`, qui voit à la fois `minos-provider-scip` et `minos-application`) suit le chemin de production, sans appeler la fabrique avec des symboles fabriqués : index SCIP écrit en protobuf → `ScipProjectSnapshotLifecycle.stage` → `promote` → instantané actif **relu du disque** → `SemanticDocumentFactory.build`. Trois scénarios, tous **verts sur le code d'origine** : (a) même nom qualifié dans deux portées + un symbole défini deux fois dans un index : deux symboles, toutes les clés uniques, aucune exception ; (b) un symbole défini trois fois dans un index : fusionné, un symbole ; (c) deux fournisseurs qui décrivent le même symbole dans la même portée : refusé **au staging** (le run échoue à cet endroit, avec « collision »), jamais transformé en doublon. Le seul moyen d'atteindre l'`IllegalStateException` est un fichier d'instantané forgé ou altéré (identifiant qui n'est pas la fonction de la clé) : hors du périmètre « indexation normale » (un fichier altéré après sa publication est déjà refusé à la relecture par la somme de contrôle, `SnapshotIntegrityService.verifyChecksum` ; un fichier forgé avec une somme recalculée n'est pas une indexation).

**Règle de résolution non écrite** : puisqu'aucun doublon n'est atteignable, ni règle (première gagne, dernière gagne, fusion) ni compteur ni journalisation n'ont été ajoutés : ce serait du code défensif sans scénario démontré (règle 3 du chantier). La garde `IllegalStateException` de la fabrique reste ce qu'elle est : une assertion d'invariant qui échouerait bruyamment si l'invariant 1 était un jour cassé.

**Observation hors périmètre (pas un constat, non reproduite ici)** : le scénario (c) montre qu'un run dont deux fournisseurs décrivent le même symbole dans la même portée échoue au staging (`IllegalStateException`, « provider snapshot collision ») : je n'ai pas cherché si un plan d'indexation peut produire ce cas (deux fournisseurs, même portée), c'est hors de ce lot. Noté en § 7.

### 11.9 Preuves

**Ce que voit l'utilisateur.**

- **`minos project list` avec une entrée abîmée.** Avant : `error: project list failed: Text 'not-an-instant' could not be parsed at index 0`, code 1, **aucun** projet listé, pour un seul fichier de registre abîmé (ou un seul historique, ou un seul répertoire illisible sous la racine d'un projet). Après : tous les projets sont listés, le projet abîmé est une ligne à l'état `UNREADABLE`, le pied `degraded: N` et une ligne `  <entrée>: <raison>` par entrée suivent (JSON : `degradedCount` et `degraded`), un avertissement `warning: project inventory is partial: N of T entries are degraded` est écrit sur stderr, et la commande **sort avec le code 3**.
- **Sans entrée abîmée, rien ne change** : code 0, sortie octet pour octet identique, aucune clé ni ligne en plus, rien sur stderr. Les golden `characterization/` sont inchangés.
- **Aucune autre commande ne change de code** : `project add`, `inspect <nom>` échouent toujours (code 1) tant qu'une entrée est illisible (résolution par nom et mutation fermées), `inspect <identifiant sain>` passe (0), registre non listable = 1, usage = 2, registre vide = 0.
- **API Java** : `MinosApi.listProjects` ne lève plus ; la ligne abîmée a l'état `UNREADABLE`.
- Une entrée du registre qui est un **répertoire ou un lien pendant** (avant : le projet disparaissait sans trace) est maintenant comptée.
- Les raisons affichées n'ont **ni chemin absolu ni caractère de contrôle** (V-L5-01).

**Rouge → vert.** Chaque test a été joué sur le code d'origine avant le correctif (Windows ; le vrai `visitFileFailed` sous WSL), sortie rouge dans le message du commit `821c7e67` (et `3d2247a1` pour V-L5-01). Les points d'entrée neufs n'y sont que des façades de l'ancien comportement strict, pour que les tests compilent.

| Défaut | Test | Rouge (code d'origine) | Vert |
|---|---|---|---|
| entrée de registre abîmée (`Instant`, UUID du contenu, identité ≠ nom de fichier, fichier vide / trop gros / propriété manquante, nom non UUID) fait échouer tout l'inventaire | `LocalProjectRegistryInventoryTest` (12 tests) | `DateTimeParse Text 'not-an-instant' could not be parsed at index 0`, `IllegalArgument Invalid UUID string`, `IO project identity mismatch`, … | 19/19 |
| entrée remplacée par un répertoire ou un lien pendant : disparaît sans trace | `…aDirectoryWhereAnEntryShouldBeIsCountedInsteadOfVanishing`, `…aDanglingLink…` | `the damaged entry is counted, not dropped ==> expected: <1> but was: <0>` ; `expected: <[id]> but was: <[]>` | vert |
| plusieurs entrées abîmées, toutes abîmées | `…severalDamagedEntries…`, `…everyEntryDamaged…` | `IllegalArgument Invalid UUID string: not-a-uuid`, `DateTimeParse Text 'x'…` | vert |
| historique abîmé, registre abîmé dans la vue, plusieurs dégâts de natures différentes | `ProjectInventoryToleranceTest` (5 rouges) | `DateTimeParse Text 'not-an-instant' could not be parsed at index 0` | 10/10 (+1 sauté sous Windows) |
| répertoire illisible sous la racine d'un projet (`visitFileFailed`), par injection | `…anUnreadableDirectoryUnderOneProjectRootDegradesThatProjectOnly` | `AccessDenied project-1` | vert |
| **vrai `visitFileFailed`** (`chmod 000`), WSL non privilégié | `…aReallyUnreadableSubdirectoryIsVisitFileFailed…` | `AccessDenied …/project-0/locked` (code d'origine sous WSL : 6 rouges sur 9) | vert sous WSL |
| inventaire partiel rendu avec le code 0 et sans compteur | `ProjectListPartialInventoryTest` (2 rouges) | texte et JSON sans `degraded`, code 0 | 7/7 |
| un fichier abîmé fait échouer `project list` sur un vrai `MINOS_HOME` | `ProjectListDamagedRegistryIntegrationTest` (1 rouge) | `error: project list failed: Text 'not-an-instant' could not be parsed at index 0` (code 1) | 4/4 |
| V-L5-01 caractères de contrôle d'un fichier dans la raison | `DegradedEntryTest`, `…aValueCarryingTerminalControlsNeverReachesTheReason`, `…aHostileValueInADamagedFileNeverReachesTheTerminal` | `unexpected character U+1b in: registry entry is unreadable (bad …` | 6/6, 19/19, 4/4 |

Gardes verts avant et après (ils figent ce qui ne doit pas changer) : un registre sain ne rapporte rien de dégradé et rend les mêmes projets que `listProjects()` ; `listProjects()` strict échoue toujours sur une entrée abîmée ; un registre dont le répertoire ne peut pas être listé échoue en bloc ; `inspectProject` d'un projet abîmé échoue toujours ; une interruption pendant l'assemblage d'une vue est relancée (mutation témoin : sans le garde, rouge) ; les lignes restent triées par identifiant.

**Q9 : non reproduit, aucun correctif** (§ 11.8). Trois tests verts sur le code d'origine, aucune ligne de production touchée pour ce constat (`git diff 40100dbf..HEAD -- minos-application/src/main/java/com/minos/application/semantic` vide).

**Test de concurrence** : aucun dans ce lot. Les deux interruptions sont livrées par le test lui-même (`Thread.interrupt()` avant l'appel, ou par le détecteur injecté), sans `Thread.sleep` ni attente calibrée ; il n'y a pas de course à rejouer 50 fois.

### 11.10 Comptes AVANT / APRÈS (grep du code de production, hors commentaires)

| Décision | Avant | Après |
|---|---|---|
| sites qui lisent ou valident une entrée de registre ou d'historique | 5 (`readProject`, `readWorkspaceMetadata`, `idFromPropertiesFile`, `readHistory`, ligne SQL) | **5** : aucune variante de lecture de plus |
| chemins qui isolent l'échec d'une entrée | 0 | **2**, par nature : une entrée de registre (`LocalProjectRegistry.scanProjects`) et la vue d'un projet (`ProjectInspectionService.inventory`) ; un seul vocabulaire (`DegradedEntry`, deux fabriques, appelées à 3 endroits), un seul rendu (`ProjectCommand.renderProjects`), une seule projection JSON (`ProjectJson.degraded`) |
| endroits où les entrées du registre de projets sont lues toutes ensemble | 1 (`listProjects`) | **1** (`scanProjects`, dont `listProjects` strict et `inventory` tolérant sont deux lectures) |
| entrées de projet écartées en silence | 1 (`propertyFiles` : tout `*.properties` qui n'est pas un fichier régulier) | **0** pour les projets ; restent 1 pour les workspaces (`propertyFiles`) et 1 pour l'historique (`readHistory`, V-L5-02), § 7 |
| appelants de production du listage strict `registry.listProjects()` | 4 (inspection, résolution par nom, NEXUS, enregistrement) | **3** (résolution par nom, NEXUS, enregistrement : les mutations et les résolutions restent fermées) |
| façons de dire « cette ligne est illisible » | 0 | **1** (`ProjectSummary.UNREADABLE_STATE`, construite par `ProjectInspectionService.unreadableView`) |
| records « inventaire » voisins | 0 | 3 (`ProjectRegistry.Inventory`, `ProjectInspectionService.Inventory`, `ProjectOperations.ProjectInventory`) : même forme à chaque couche, comme les deux `ProjectView` (`CODE-SUIVI` § 21.3), sans règle de décision dedans |
| classes de production | 505 | 506 (`DegradedEntry`) |

### 11.11 Gates de fin de lot

| Gate | Base `40100dbf` | Fin de lot |
|---|---|---|
| `check-module-boundaries.py` | `modules=14, sources=505, packages=45` | `modules=14, sources=506, packages=45` (+1 : `DegradedEntry`, attendu) |
| `check-current-docs.py`, `product-facts.py --check` | SUCCESS | SUCCESS |
| `check-milestone-artifact-references.py` | `scripts checked=95` | `scripts checked=95` |
| `check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py`, `check-audit-remediation-v2.py`, `check-p0-p2.py`, `check-post228-hardening.py`, `check-semantic-retrieval-consistency.py` | SUCCESS | SUCCESS |
| `check-jacoco.py` | 26 portées PASS, seule rouge `m24-polyglot-provider-platform` (line 0,228 < 0,28, préexistante, Windows) | **identique** : 26 PASS, même unique rouge, mêmes chiffres |
| `public-api` (line / branch, portée qui nomme `LocalMinosApi`) | 0,823 / 0,593 | 0,824 / 0,599 |
| `semantic-hybrid-retrieval` (la portée qui nomme `SemanticDocumentFactory`) | 0,910 / 0,722 | 0,910 / 0,722 (inchangée : aucune ligne de production) |
| `./mvnw clean verify` | 1 773 tests, 0 échec, 53 ignorés | **1 826 tests, 0 échec, 0 erreur, 54 ignorés** (le seul ignoré de plus : le test POSIX du vrai `visitFileFailed`, exécuté sous WSL), BUILD SUCCESS, 15 modules, 10 min 06 |

Golden : les 12 de `characterization/` sont **inchangés** (`git diff 40100dbf..HEAD -- minos-app/src/test/resources scripts` vide) ; aucun script de `scripts/` assoupli ; aucun test désactivé ni retenté.

### 11.12 Répartition Windows / WSL

- **Windows** (machine locale, liens symboliques disponibles : le test du lien pendant s'est exécuté) : toute la suite du lot ; `clean verify` complet ; toutes les sondes de rouge ; le test POSIX du vrai `visitFileFailed` est sauté (`@EnabledOnOs`).
- **WSL Ubuntu, Java 24, compte non privilégié, arbre copié sur le système de fichiers Linux** : `LocalProjectRegistryInventoryTest` 19/19 (lien pendant compris), `ProjectInventoryToleranceTest` 10/10 **dont le vrai `visitFileFailed`** (répertoire `chmod 000`), `SemanticDocumentKeysReachabilityTest` 3/3, `ProjectListPartialInventoryTest` 7/7, `ProjectListDamagedRegistryIntegrationTest` 4/4, `DegradedEntryTest` 6/6, `LocalMinosApiDamagedRegistryTest` 1/1, `ScipSymbolNormalizerTest` 6/6, `CodeKnowledgeSnapshotIdentityTest` 2/2 ; le vrai `visitFileFailed` est **rouge** sur le code d'origine sous WSL (`AccessDenied …/locked`, 6 tests rouges sur 9).
- **Non exécuté nulle part** : `clean verify` complet sous Linux ; `minos-intellij` (Gradle, hors réacteur, V-L5-04) ; une interruption en plein milieu d'une lecture de fichier du registre (non déterministe, § 7).

### 11.13 Constats de verif-fiab (lot 5)

V-L5-01 à V-L5-05 et R-L5-A à R-L5-D (remarques du verdict final) et leur résolution : § 6, « Lot 5 ». `VERDICT-FINAL: ok` de `verif-fiab` sur `b9441160` (`clean verify` rejoué de son côté : 1 826 tests, 0 échec, 54 ignorés, 9 min 57 ; test POSIX du vrai `visitFileFailed` rejoué sous WSL ; ×10 de trois classes du lot).

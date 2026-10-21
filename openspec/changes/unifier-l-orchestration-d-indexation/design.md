# Design

## Context

Sprint 9 de l'audit du 10 octobre 2026 : **AUD-ARC-02** et la part « coordinateur » d'**AUD-QUA-02**. HEAD analysé : `949e1569` (`develop`). `COORD` = `minos-engine/src/main/java/com/minos/orchestration/IncrementalIndexingCoordinator.java` ; `CLI` = `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java`.

## État vérifié au HEAD

### AUD-ARC-02 — confirmé

- `COORD:24` : classe publique, 220 lignes, dans `orchestration` (l'audit la citait dans `incremental`, d'où elle a été déplacée). Aucune référence depuis un `src/main` ; 4 classes de test, 7 tests : `IncrementalIndexingCoordinatorTest` (3) et `IncrementalIndexingDiagnosticRedactionTest` (1) dans `minos-bootstrap`, `IncrementalIndexingRealFixtureTest` (1) dans `minos-app`, `IncrementalIndexingDiscoveryOrderTest` (2) dans `minos-engine`. `IncrementalIndexingResult` n'est nommé que par le coordinateur et ces tests.
- Flux réel : `CLI:144-225` (`executeLocked`), préparation `CLI:311-353`. Correctif D06 en double : `COORD:86-90` et `CLI:314-316`.
- **Ce que fait la CLI et pas le coordinateur** : résolution du projet (`:129`, `:313`), `forceFull`, `IndexingResumePolicy` (`:133`, `:152`), surcharge de provider (`:330-334`) ; refus d'une négociation incomplète (`:336`) et d'un runtime non `READY` (`:146`) ; exécuteurs choisis par run et décorés par `executorDecorator` (`:161-165`) ; `recoverPromotedRunIfNeeded` (`:234`) ; synchronisation sémantique (`:276`) ; rétention sur les quatre chemins terminaux (`:156`, `:194`, `:211`, `:222`) ; dry-run sans bail (`:117`) ; `ProjectIndexStateReconciler` (`:355-358`).
- **Ce que fait le coordinateur et pas la CLI** : un échec de publication de l'empreinte devient un diagnostic (`COORD:154-165`) alors que la CLI relance l'`IOException` (`CLI:215`) ; le diagnostic « active fingerprint baseline unreadable » est conservé (`COORD:169-178`) alors que la CLI l'avale (`CLI:324-329`) ; le bail passe par `lifecycleService.withProjectLease` (`COORD:76`) ; le résultat est typé.
- Exigences qui nomment les deux flux ou le coordinateur : `openspec/specs/decouverte-et-negociation/spec.md:75-76` ; `openspec/specs/frontieres-de-modules/spec.md:139-140`. Documents : `docs/architecture/arc42/05-vue-blocs.md:147`, `docs/architecture/diagrams/c4-component-application.md:16`.
- Gates littéraux sur le fichier de la CLI : `scripts/remediation/check-post-mne.py:269-276` (deux chaînes exigées, et au moins 4 occurrences cumulées de `application.retentionService().compact(prepared.project().id())` et de `compactAfterSuccess(prepared.project().id())`) ; `scripts/quality/check-remote-distributed-consistency.py:130` (`executorDecorator`, `UnaryOperator<IndexerExecutor>`) ; `scripts/quality/check-polyglot-provider-consistency.py:104` (chemin) et `:243-244` (deux motifs sur `IndexingRequirements`).

**Non vérifié** : aucun build, test ni gate n'a été exécuté ; l'ADR 0057 n'a été relu que dans ses titres et son § 2 ; l'ADR 0039 n'a pas été relu en entier.

### Écarts avec l'action proposée par l'audit

1. **L'audit laisse le choix ; le coordinateur ne peut pas devenir le cas d'usage là où il est.** Le réconciliateur d'état, la synchronisation sémantique, la rétention et `ProjectResolver` vivent dans `minos-application` ; `minos-engine` ne peut pas les nommer (ADR 0022). Le cas d'usage unique se place donc dans `minos-application`, et c'est le comportement de la CLI qui fait référence.
2. **Supprimer le coordinateur sans reporter ses tests perdrait des preuves.** Ses 7 tests couvrent l'ordre empreinte / découverte, la fixture réelle et la rédaction des diagnostics : ils sont réécrits contre le cas d'usage.
3. **Deux comportements du coordinateur sont meilleurs que ceux de la CLI** (diagnostics) : les reprendre change une sortie de `minos index`, donc D3 revient au propriétaire.
4. **Le déplacement casse trois gates littéraux** qui lisent le fichier de la CLI par son chemin : ils se mettent à jour dans la même tâche.
5. **Cinq changements actifs citent des lignes de ce fichier** : `ne-plus-hacher-les-sources-avant-le-cache-du-graphe`, `synchroniser-l-index-semantique-par-differentiel`, `lire-chaque-source-une-fois-par-fichier`, `lire-l-identite-du-snapshot-actif-sans-le-charger`, `figer-la-dette-de-frontieres-par-des-cliquets`. Leurs références dériveront ; celui qui s'implémente en second relit les siennes.
6. **« Ajouter le coordinateur à la cartographie SH-01 »** n'a plus d'objet s'il est supprimé : la cartographie note sa suppression.

## Goals / Non-Goals

**Goals** : un seul chemin d'indexation, celui que les tests valident ; le flux invocable sans CLI ; les garanties actuelles conservées (portée des baux, ordre des effets, commit incertain, `NO_CHANGES`, interruption) ; sorties inchangées hors D3.

**Non-Goals** : réduire tous les imports du moteur par la CLI ; changer la reprise (ADR 0039) ; exposer l'indexation par le MCP.

## Decisions

### D1. Où vit l'orchestration unique — **décidé : option A (propriétaire, 2026-10-21)**

Recommandation (option A) : un cas d'usage dans `minos-application`, qui reprend **le comportement de la CLI** ; le coordinateur et `IncrementalIndexingResult` sont supprimés. C'est SH-05 et l'ADR 0057 § 2. La CLI délègue ; `LocalRemoteIndexOperations` continue d'appeler la même entrée.

Option B : garder le coordinateur dans `minos-engine` comme noyau et l'envelopper dans `minos-application`. Deux couches pour un seul flux, et le coordinateur devrait d'abord gagner tout ce qu'il ne fait pas (écart 1).

Option C : supprimer le coordinateur sans rien déplacer. Ferme AUD-ARC-02 mais laisse l'orchestration dans une surface et ne débloque pas AUD-ARC-01.

### D2. Le cas d'usage rend un résultat typé ; la CLI garde ses vues

Le cas d'usage rend un résultat de domaine (plan, run, snapshot actif, empreinte promue ou non, diagnostics) ; la CLI le projette sur `IndexPlanView` et `IndexExecutionView`, qui restent dans `minos-cli`. Le décorateur d'exécuteur reste un paramètre du cas d'usage (indexation distante). Le package d'accueil est fixé en tâche 1.1 (un package, un module : ADR 0044 ; aucun cycle : règle A8).

### D3. Reprendre les deux diagnostics du coordinateur — **décidé : (a) repris, (b) l'échec de publication reste une erreur (propriétaire, 2026-10-21)**

Recommandation : (a) reprendre le diagnostic « baseline d'empreinte illisible » (additif, il n'y a aujourd'hui aucun message) ; (b) **ne pas** transformer l'échec de publication de l'empreinte en diagnostic sans décision : aujourd'hui `minos index` échoue (`CLI:215`), demain il réussirait avec `fingerprintPromoted=false`. Le snapshot est déjà promu à ce moment : le rendre comme succès partiel est plus exact, mais c'est un changement de sortie et de code de sortie.

Alternative : garder strictement le comportement de la CLI (aucune sortie ne change) et consigner les deux écarts comme dette.

### D4. Les tests du coordinateur sont reportés avant sa suppression

Chaque test est réécrit contre le cas d'usage et doit être vert **avant** la suppression de la classe ; un test sans équivalent possible est nommé avec sa raison. Les tests reportés vivent dans le module du code qu'ils couvrent ou dans `minos-bootstrap` quand ils exigent le câblage réel.

### D5. Les gates littéraux suivent le code

`check-post-mne.py`, `check-remote-distributed-consistency.py` et `check-polyglot-provider-consistency.py` lisent le fichier où vit désormais chaque littéral ; l'invariant vérifié (rétention sur chaque chemin terminal, décorateur d'exécuteur, exigences de négociation) reste le même.

## Décisions du propriétaire et relevé d'implémentation (2026-10-21)

- **D1 = A** : cas d'usage `ProjectIndexingUseCase` dans `minos-application`, package `com.minos.application.indexing` (nouveau, propre à ce module : ADR 0044 ; feuille sans cycle, règle A8 verte). Il reprend le comportement de la CLI ; `IncrementalIndexingCoordinator` et `IncrementalIndexingResult` sont supprimés.
- **D3 (a)** : une baseline illisible donne `Preparation.baselineDiagnostic` et, en tête du diagnostic d'exécution, le texte fixe `active fingerprint baseline unreadable; a full indexing was required` (ni chemin ni cause). Le dry-run (`IndexPlanView`) n'a pas de champ de diagnostic : il n'affiche que le plan `FULL` ; la vue n'a pas été élargie (surface additive non demandée).
- **D3 (b)** : l'échec de publication de l'empreinte reste une `IOException` de la commande, après rétention (comportement de la CLI inchangé).
- **D2** : le cas d'usage reçoit ses ports par `ProjectIndexingUseCase.Dependencies` (testable sans racine de composition) ; `ProjectIndexingUseCase.of(MinosApplication, UnaryOperator<IndexerExecutor>)` est le câblage de production. La CLI garde `IndexPlanView`, `IndexExecutionView`, `ResumeView`, `providers()`, `installProvider()` et `resumableRun()`.
- **Scope JaCoCo (tâche 2.2)** : aucun scope ajouté à `check-jacoco.py` ; le nouveau code est jugé par SonarCloud dans `minos-application` et couvert par les tests unitaires du module (`ProjectIndexingUseCaseTest`, `IndexingBaselineAndLeaseTest`, `PromotedSnapshotIdentityTest`).

### Report des 7 tests du coordinateur (D4)

| Test d'origine | Comportement prouvé | Équivalent sur le cas d'usage |
|---|---|---|
| `IncrementalIndexingCoordinatorTest.qualifiedProviderReceivesIncrementalScopeAndAdvancesBaseline` | FULL puis INCREMENTAL (fichier changé transmis), puis NONE, baseline avancée | `ProjectIndexingUseCaseTest.aCompleteThenAnUnchangedThenAnIncrementalIndexingFollowTheProductPath` |
| `IncrementalIndexingCoordinatorTest.projectLeaseRemainsHeldThroughFingerprintPromotion` | bail tenu jusqu'à la promotion de l'empreinte | `IndexingBaselineAndLeaseTest.theProjectLeaseIsHeldThroughTheFingerprintPromotion` (rejoué 50 fois, vert) |
| `IncrementalIndexingCoordinatorTest.workspaceMutationDuringRunLeavesBaselineBehindAndForcesHealingFullRefresh` | mutation pendant le run : baseline non promue, run suivant FULL `MISSING_FINGERPRINT_BASELINE` | `ProjectIndexingUseCaseTest.aWorkspaceMutationDuringTheRunLeavesTheBaselineBehindAndTheNextIndexingHealsIt` |
| `IncrementalIndexingDiagnosticRedactionTest.publicResultRedactsAbsolutePathFromFingerprintFailure` | diagnostic de baseline illisible sans chemin | `IndexingBaselineAndLeaseTest.anUnreadableBaselineRequiresAFullIndexingAndIsDeclaredWithoutItsPath` (texte fixe au lieu de « internal diagnostic redacted ») |
| `IncrementalIndexingDiscoveryOrderTest` (2 tests) | empreinte capturée avant la découverte (D06) ; arbre stable promu | `IndexingBaselineAndLeaseTest.aStructureCreatedDuringDiscoveryPreventsThePromotionOfTheBaseline`, `aStableTreePromotesTheBaseline` |
| `IncrementalIndexingRealFixtureTest` (`minos-app`) | fixture TypeScript réelle : NONE, repli FULL faute de capacité incrémentale | réécrit en place contre `ProjectIndexingUseCase.of(application, …)` ; il compte les **runs** (identifiants distincts) et non les exécutions, le chemin du produit exécutant le provider une fois par portée découverte (2 portées dans la fixture) |

Aucun test n'est resté sans équivalent. Tests du flux de la CLI déjà présents et conservés : reprise et crash (`minos-bootstrap` `orchestration/*`, `minos-cli` `IndexResume*`), `IndexFailurePathsTest` (échec provider incrémental), `IndexRunFailureMessageTest` (ligne publique, désormais `ProjectIndexingUseCase.failureMessage`). Les deux tests de `IndexFailurePathsTest` qui appelaient des méthodes internes de la CLI (`promoteFingerprint`, `compactAfterFailure`) et `PromotedSnapshotIdentityTest` sont passés dans `minos-application`.

## Direction des dépendances

`domain → engine → runtime/storage → adapters → application → surfaces`. Le cas d'usage est dans `minos-application` et ne nomme que des ports du moteur et des services d'application : `application ↛ adaptateurs` est respecté (aucune classe de `minos-storage-*`, `minos-runtime-local` ni `minos-provider-scip`). `minos-cli` perd des imports du moteur ; aucune arête nouvelle. Le changement réduit la liste A11 de `minos-cli` quand elle existera.

## Qualification des capacités

- Indexation autonome (complète, incrémentale, reprise) : qualifiée, inchangée.
- Rafraîchissement par `IncrementalIndexingCoordinator` : n'a jamais été une capacité du produit ; retiré.
- Diagnostic de baseline illisible : nouveau, qualifié par test si D3 (a) est retenu.

## Windows et Linux

Aucune différence de comportement attendue ; les tests reportés tournent sur les deux systèmes (la fixture réelle et les baux de fichier diffèrent sous Windows : à observer en CI).

## Risks / Trade-offs

- **SonarCloud** : le flux déplacé de `minos-cli` vers `minos-application` est du code neuf jugé à 80 % **dans `minos-application`** ; les tests qui le couvrent aujourd'hui depuis `minos-cli` ou `minos-app` ne comptent pas. Des tests unitaires du cas d'usage sont à écrire dans son module.
- Régression d'ordre des effets (bail, rétention, sémantique) : les goldens et les tests de reprise et de crash existants sont la preuve ; aucun n'est modifié hors D3.
- Les scopes JaCoCo à préfixe (`critical-orchestration`, `resume-orchestration`) ne citent ni le coordinateur ni la CLI ; un scope pour le cas d'usage est à décider en tâche 2.2.

# Tasks

Ordre : relevé et décisions, puis dans chaque section rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les exigences de `specs/` décrivent D1-A et D3 (a) ; si le propriétaire choisit autrement, les réviser par `/opsx:update` avant d'implémenter.

## 1. Relevé et décisions (AUD-ARC-02)

- [x] 1.1 [AUD-ARC-02] Relever, pour chacun des 7 tests du coordinateur, le comportement prouvé et son équivalent côté cas d'usage ; relever les tests existants du flux de la CLI (reprise, crash, verrous, `NO_CHANGES`) ; fixer le package d'accueil dans `minos-application` (ADR 0044, règle A8). Consigner dans `design.md`.
- [x] 1.2 [AUD-ARC-02] **Décision à obtenir** : D1 (A recommandé : cas d'usage dans `minos-application` et suppression du coordinateur ; B ; C). Consigner dans `design.md`.
- [x] 1.3 [AUD-ARC-02] **Décision à obtenir** : D3 (diagnostic de baseline illisible repris ; échec de publication de l'empreinte gardé en erreur ou rendu en diagnostic). Consigner dans `design.md`.

## 2. Cas d'usage d'indexation (AUD-ARC-02)

- [x] 2.1 [AUD-ARC-02] Rouge : dans `minos-application`, tests du cas d'usage sans classe de `minos-cli` — indexation complète, incrémentale, `NO_CHANGES`, négociation incomplète refusée, runtime non `READY` refusé, run interrompu, rétention appelée sur les quatre chemins terminaux. **Preuve du rouge : le cas d'usage n'existe pas au HEAD (échec de compilation du test), et `new IndexingLifecycleService` n'existe que dans `minos-cli`.**
- [x] 2.2 [AUD-ARC-02] Extraire `executeLocked`, `prepare`, `recoverPromotedRunIfNeeded`, la synchronisation sémantique et la rétention de `LocalAutonomousIndexOperations` vers le cas d'usage (D2), sans changer l'ordre des effets ni la portée du bail ; la CLI délègue et projette le résultat sur ses vues. Mettre à jour **dans cette tâche** les littéraux et chemins de `scripts/remediation/check-post-mne.py` (`:108`, `:269-276`), `scripts/quality/check-remote-distributed-consistency.py` (`:61`, `:130`) et `scripts/quality/check-polyglot-provider-consistency.py` (`:104`, `:243-244`) ; décider d'un scope JaCoCo pour le cas d'usage dans `scripts/quality/check-jacoco.py`. Preuve : tests de 2.1 verts ; les trois gates verts ; `python scripts/architecture/check-module-boundaries.py` et `python scripts/quality/check-single-execution.py` verts ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens identiques à l'octet).

## 3. Diagnostic de baseline illisible (AUD-ARC-02)

- [x] 3.1 [AUD-ARC-02] Rouge : test du cas d'usage avec un magasin d'empreintes dont `loadActive` lève une `IOException` portant un chemin absolu — plan complet, raison `FINGERPRINT_BASELINE_UNREADABLE`, diagnostic présent et sans chemin. **Preuve du rouge : aucun diagnostic au HEAD (`LocalAutonomousIndexOperations.java:324-329`).**
- [x] 3.2 [AUD-ARC-02] Rendre le diagnostic (D3 a) ; appliquer D3 (b) selon la décision. Si une sortie change : goldens mis à jour par ajout seulement, `docs/user/cli.md` et `docs/user/troubleshooting.md` dans la même tâche. Preuve : test vert ; `./mvnw -B -ntp -pl minos-app -am verify` ; `python scripts/docs/check-current-docs.py`.

## 4. Report des tests et suppression du coordinateur (AUD-ARC-02, AUD-QUA-02)

- [x] 4.1 [AUD-ARC-02] Réécrire contre le cas d'usage `IncrementalIndexingCoordinatorTest`, `IncrementalIndexingDiagnosticRedactionTest`, `IncrementalIndexingRealFixtureTest` et `IncrementalIndexingDiscoveryOrderTest` (D4) ; nommer tout test sans équivalent, avec sa raison. Preuve : tests reportés verts **avant** toute suppression.
- [x] 4.2 [AUD-ARC-02, AUD-QUA-02] Rouge : `git grep -lw IncrementalIndexingCoordinator -- '*.java'` retourne 5 fichiers. Supprimer `IncrementalIndexingCoordinator` et `IncrementalIndexingResult`, et les anciens tests. Preuve : la recherche ne retourne rien ; `./mvnw -B -ntp -pl minos-bootstrap -am verify` ; `python scripts/architecture/check-module-boundaries.py` ; `python scripts/quality/check-jacoco.py` sur les scopes `critical-orchestration` et `resume-orchestration` (sur poste Windows, comparer à `develop` pour le scope `m24-polyglot-provider-platform`).

## 5. Documentation d'architecture (AUD-ARC-02)

- [x] 5.1 [AUD-ARC-02] `docs/architecture/arc42/05-vue-blocs.md` (`:147`) et `docs/architecture/diagrams/c4-component-application.md` (`:16`) : remplacer le coordinateur par le cas d'usage ; `docs/developer/architecture.md` si le flux y est décrit. Preuve : `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` verts.
- [x] 5.2 [AUD-ARC-02] Note de « Mise en œuvre » de l'ADR 0057 (§ 2 réalisé) ; cocher SH-05 dans `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md` et noter la suppression du coordinateur dans la cartographie SH-01.

## 6. Clôture

- [x] 6.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-ARC-02 : statut, décisions D1 et D3, tests reportés ; AUD-QUA-02 : coordinateur retiré ; AUD-ARC-01 et AUD-PERF-04 signalés comme débloqués).
- [x] 6.2 Signaler aux changements actifs qui citent des lignes de `LocalAutonomousIndexOperations` (écart 5 de `design.md`) que leurs références ont dérivé.
- [ ] 6.3 `python .claude/scripts/run_gates.py` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/project/governance/ROADMAP.md`, ADR 0057, `docs/architecture/arc42/05-vue-blocs.md`.

## Preuves d'implémentation (2026-10-21)

- 2.1 : rouge par construction (`ProjectIndexingUseCase` absent au HEAD `1492113e`) ; `ProjectIndexingUseCaseTest` (12 tests) vert.
- 3.1 : rouge exécuté, ligne D3 (a) neutralisée : `expected: <Optional[active fingerprint baseline unreadable; a full indexing was required]> but was: <Optional.empty>` ; vert après correction.
- 4.1 : test de bail rejoué 50 fois (`@RepeatedTest(50)` temporaire), 50/50 vert ; `IncrementalIndexingRealFixtureTest` réécrit, vert avant la suppression.
- 4.2 : rouge, la recherche `IncrementalIndexingCoordinator` dans les sources Java rendait 4 fichiers (le test de `minos-app` était déjà réécrit) ; 0 après suppression.
- Un test réflexif de la CLI (`LocalAutonomousIndexOperationsRunFormatTest`, `recoverPromotedRunIfNeeded`) a été repointé sur le cas d'usage.
- Gates verts : `check-post-mne.py`, `check-remote-distributed-consistency.py`, `check-polyglot-provider-consistency.py`, `check-module-boundaries.py`, `check-single-execution.py`, `check-current-docs.py`, `product-facts.py --check`, `run_gates.py --fast` (21/21), `openspec validate --all --strict`, `archgate check`.
- Fusion (4.2) : la branche parallèle `refactor/retirer-les-services-sans-appelant-de-production` ajoute la règle A15 (`UNREFERENCED_PRODUCTION_TYPES`, liste à cliquet) qui inscrit `IncrementalIndexingCoordinator` ; à la fusion après elle, cette entrée devra être retirée de la liste.
- 6.3 non coché : `run_gates.py` complet et la mise à jour de STATUS/ROADMAP relèvent de l'archivage, non demandé ici.

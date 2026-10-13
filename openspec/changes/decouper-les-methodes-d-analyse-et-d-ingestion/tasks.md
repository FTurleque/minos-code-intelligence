# Tasks

Remaniement à comportement constant : dans chaque section, la caractérisation tient lieu de rouge (design D1), puis le découpage, puis la preuve. Tout `-pl` s'accompagne de `-am`. Une PR par classe ; la section 2 passe **après** la fusion de `indexer-les-aretes-d-impact-avec-la-vue`.

## 1. Mesure et relevé (AUD-QUA-07)

- [ ] 1.1 [AUD-QUA-07] Écrire la méthode de mesure (ce qui compte comme une méthode, comme une ligne) et relever les méthodes de 80 lignes ou plus de `*/src/main/**` ; dire lequel des deux décomptes connus (40 sur 4 876, 24 sur 4 399) elle confirme, ou aucun. Consigner dans `design.md`. Aucun seuil n'est ajouté à un gate.
- [ ] 1.2 [AUD-QUA-07] Pour `analyze`, `ingest` et `execute` : lister les tests qui les traversent et, par le rapport JaCoCo, les branches qu'aucun test ne traverse. Rechercher chaque nom de méthode et de classe dans `scripts/`. Consigner dans `design.md`.

## 2. `ImpactAnalysisService.analyze` (AUD-QUA-07)

- [ ] 2.1 [AUD-QUA-07] Caractérisation : pour chaque branche non traversée relevée en 1.2, un test dans `minos-application`, vert au HEAD. **Preuve du rouge : sans objet (comportement constant) ; preuve de caractérisation : tests verts avant le découpage, sortie de `./mvnw -B -ntp -pl minos-application -am test` conservée.**
- [ ] 2.2 [AUD-QUA-07] Découper `analyze` en méthodes privées nommées de la même classe (D3), sans classe nouvelle ni signature publique modifiée. Preuve : mêmes tests verts ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens de caractérisation inchangés) ; `python .claude/scripts/run_gates.py --fast` ; longueur avant/après notée.

## 3. `ScipIngestionAdapter.ingest` (AUD-QUA-07)

- [ ] 3.1 [AUD-QUA-07] Caractérisation : tests des branches non traversées dans `minos-provider-scip`, verts au HEAD. **Preuve du rouge : sans objet ; preuve de caractérisation : tests verts avant le découpage.**
- [ ] 3.2 [AUD-QUA-07] Découper `ingest` en méthodes privées nommées de la même classe (D3), ordre des effets et des exceptions inchangé. Preuve : `./mvnw -B -ntp -pl minos-provider-scip -am verify` ; scope JaCoCo `m24-polyglot-provider-platform` (rouge hors régression sur poste Windows : comparer à `develop`) ; `./mvnw -B -ntp -pl minos-app -am verify` ; baseline SpotBugs de `minos-provider-scip` rejouée.

## 4. `IndexingRunExecutor.execute` (AUD-QUA-07)

- [ ] 4.1 [AUD-QUA-07] Caractérisation : relever les tests d'`IndexingLifecycleService` qui passent par `execute`, dont le refus d'une politique de reprise nulle et l'ordre des validations ; compléter si une validation n'est traversée par aucun test. **Preuve du rouge : sans objet ; preuve de caractérisation : tests verts avant.** Arrêter la forme de l'objet requête (D2) et la consigner dans `design.md`.
- [ ] 4.2 [AUD-QUA-07] Introduire l'objet requête de visibilité package (D2) et adapter l'appelant unique (`IndexingLifecycleService`) ; aucun message ni ordre de validation modifié. Preuve : `./mvnw -B -ntp -pl minos-engine -am verify` ; scopes JaCoCo `critical-orchestration` et `resume-orchestration` (`scripts/quality/check-jacoco.py`) ; `python .claude/scripts/run_gates.py --fast` ; baseline SpotBugs de `minos-engine` rejouée.

## 5. Clôture

- [ ] 5.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-QUA-07 : méthode et résultat de la mesure 1.1, longueurs avant/après, méthodes laissées hors lot avec leur raison — `LocalIsolatedIndexWorker.execute`, `CodeSearchService.search`, `sandboxPlan`, `MinosApplicationAssembler.build`).
- [ ] 5.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : aucune page utilisateur ; `docs/developer/indexing-and-storage.md` seulement si elle décrit la signature d'`execute`.

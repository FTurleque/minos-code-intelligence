# Tasks

Changement indépendant des autres changements actifs ; aucun ordre de fusion imposé.

## 1. Symboles lus par la vue (AUD-PERF-14)

- [x] 1.1 AUD-PERF-14 — Rouge : dans `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java`, étendre `anEvictedViewIsNoLongerReachableFromTheService` (`:691-702`) : une `WeakReference` vers un `Symbol` du snapshot de la vue évincée doit être collectée sans nouvel appel au service. Ajouter un test de page pour un fichier à plusieurs symboles dont la plus petite `symbolKey` n'est pas le premier par position (langage et module attendus : ceux de ce symbole), et un cas de symbole sans emplacement (fichier pris dans `fileId()`). Exigence : exploration-semantique › La table de symboles de l'explorateur ne survit pas à sa vue. Vérifier : `./mvnw -B -ntp -pl minos-application -am test` ; le test d'éviction échoue au HEAD sur le symbole, les tests de page passent (caractérisation).
  - Preuve : au HEAD `acd79cb86`, `./mvnw -B -ntp -q -pl minos-application -am test "-Dtest=SemanticCorpusQueryServiceTest"` : 32 tests, 1 échec, `anEvictedViewIsNoLongerReachableFromTheService:704 the service keeps no symbol of the evicted view, without another call ==> expected: <null> but was: <Symbol[id=sym-alpha, ...]>` ; nouveaux tests `aFileIsDescribedByItsSymbolOfSmallestKeyWhateverItsPosition` (dont un symbole dont l'emplacement nomme un autre fichier que son `fileId`) et `aSymbolWithoutLocationDescribesTheFileOfItsFileId` verts (caractérisation). À l'éviction, le magasin de test remplace le snapshot actif par un snapshot égal fait de nouveaux objets `Symbol` (store qui relit depuis le disque).
- [x] 1.2 AUD-PERF-14 — Correction (design, D1 option C, sauf autre choix en 2.1) : dans `SemanticCorpusQueryService`, supprimer `lookups` (`:75-79`) et `SymbolLookup` (`:707-762`) ; décrire chaque document par `view.reader().findSymbolById` et, pour le fichier, la plus petite `symbolKey` parmi `findFileSymbols` ; vérifier que la clé par fichier de `InMemoryCodeKnowledgeStore` (`symbolIndexes.byFileId`) dérive le fichier comme `SymbolLookup.of` (`:732`), sinon garder cette dérivation. Le chemin « snapshot actif différent » reste sans chargement de vue. Vérifier : tous les tests de `SemanticCorpusQueryServiceTest` verts, dont ceux de 1.1 ; `python scripts/architecture/check-module-boundaries.py` vert.
  - Preuve : `symbolIndexes.byFileId` range un symbole sous `fileId()` **et** sous `location().fileId()` quand ils diffèrent (`minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java:672-677`), alors que `SymbolLookup.of` ne prenait que `location().fileId()` (ou `fileId()` sans emplacement) : la dérivation est gardée par un filtre sur les symboles rendus par `findFileSymbols`. `lookups` supprimée ; `SymbolLookup` ne tient plus que le lecteur de la vue, le temps d'une requête. Même commande : 32 tests, 0 échec ; `python scripts/architecture/check-module-boundaries.py` : `M21 MODULE BOUNDARY CONSISTENCY SUCCESS`.
- [ ] 1.3 AUD-PERF-14 — Preuve : `./mvnw -B -ntp -pl minos-application -am verify` ; scope JaCoCo `explorer-surface` (`scripts/quality/check-jacoco.py:60-66`) vert sur les rapports Linux et Windows de la CI (le scope est mesuré sur le rapport agrégé). Ne pas lancer deux builds Maven en parallèle sur le poste.
  - Local (poste Windows, JDK 21) : `./mvnw -B -ntp -pl minos-application -am verify` : `BUILD SUCCESS` (domaine 149 tests, moteur 858 dont 14 sautés, application 575, 0 échec) ; rapport JaCoCo du module : `SemanticCorpusQueryService` et ses classes internes à 350/353 lignes (0,992) et 204/230 branches (0,887). Non coché : le scope `explorer-surface` se juge sur le rapport agrégé des CI Linux et Windows, pas encore produit.
- [ ] 1.4 AUD-PERF-14 — Mesure : temps d'une page de documents sur la fixture du test avant et après (même machine, trois exécutions) ; consigner le résultat dans la PR et dans « Performance et mesure » du design, en disant ce qui n'est pas mesuré (tas retenu sur un vrai snapshot).
  - Non faite : la fixture du test ne porte que quelques symboles, un temps de page n'y mesure pas la différence de coût entre la table et les lectures indexées ; reste ouverte (design, « Performance et mesure »).

## 2. Décision et ADR (AUD-PERF-14)

- [ ] 2.1 AUD-PERF-14 — **(manuelle, propriétaire)** confirmer D1 du design (C recommandée ; B si le propriétaire préfère garder une table attachée à la vue). Vérifier : choix consigné dans la PR.
  - Recommandation du design appliquée (option C), confirmation du propriétaire demandée dans la PR.
- [x] 2.2 AUD-PERF-14 — Réécrire la phrase du point 19 de DATA-0085 (`Proposed`, `docs/architecture/adr/DATA-0085-port-de-connaissance-et-persistance-des-snapshots-vues-comptees.md:56`) au présent : l'explorateur lit les symboles par la vue et ne garde aucune table hors d'elle. Vérifier : `archgate check` et `python .claude/scripts/run_gates.py --fast` verts.
  - Preuve : point 19 réécrit au présent ; `archgate check` : `pass: true`, 15/15 ; `python .claude/scripts/run_gates.py` vert (voir 3.3).
- [x] 2.3 AUD-PERF-14 — Réécrire la phrase de `docs/architecture/diagrams/capacites/exploration-semantique.md:260` sur la table de symboles. Vérifier : `python scripts/docs/check-capability-dossiers.py` vert.
  - Preuve : phrase réécrite, et la même dans `docs/developer/indexing-and-storage.md:319`, qui décrivait encore la `WeakHashMap` ; `python scripts/docs/check-capability-dossiers.py` : `Capability dossiers consistent: 22 dossier(s) for 22 capability spec(s).`

## 3. Clôture

- [x] 3.1 AUD-PERF-14 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
  - Preuve : matrice du design complétée ; les preuves manquantes sont nommées (CI Linux et Windows, confirmation du propriétaire pour D1, mesure 1.4).
- [ ] 3.2 AUD-PERF-14 — Après archivage, mettre à jour la ligne de l'exigence dans `docs/architecture/diagrams/capacites/exploration-semantique.md:298` (4 scénarios, tests de 1.1) ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [x] 3.3 AUD-PERF-14 — Valider les spécifications ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py`.
  - Preuve : `openspec validate --all --strict` : `Totals: 23 passed, 0 failed (23 items)` ; `python .claude/scripts/run_gates.py` : `83/83 gates passent.`
- [x] 3.4 AUD-PERF-14 — Lister les documents à mettre à jour avant archivage (DATA-0085, dossier de capacité, ligne AUD-PERF-14 de `docs/project/audits/2026-11-15/SUIVI.md` ; STATUS et ROADMAP ne citent pas le constat) ; vérifier que chacun est fait ou nommé comme reste.
  - Faits : DATA-0085 point 19, phrase du dossier de capacité (`:260`), `docs/developer/indexing-and-storage.md:319`. Restes nommés : ligne de l'exigence du dossier (`:298`, tâche 3.2, après archivage) et ligne AUD-PERF-14 de `docs/project/audits/2026-11-15/SUIVI.md` (après fusion).

## Workflow follow-up

- Archiver le changement après revue (`openspec archive liberer-la-table-de-symboles-avec-sa-vue -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.

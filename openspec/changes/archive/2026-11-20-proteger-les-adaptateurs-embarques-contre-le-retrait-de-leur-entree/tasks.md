# Tasks

Changement indépendant des autres changements actifs ; aucun ordre de fusion imposé.

## 1. Lien entre les deux listes de la règle A19 (AUD-ARC-11)

- [x] 1.1 AUD-ARC-11 — Rouge : ajouter à `EmbeddedAdapterRuleTest` (`scripts/architecture/test_check_module_boundaries.py:1519`) deux cas : un arbre où `OllamaEmbeddingProvider` nomme `java.net.HttpURLConnection`, figure dans les utilisateurs d'API et manque aux adaptateurs (refus attendu, message qui nomme la classe) ; le même arbre avec une autre source qui nomme `OllamaEmbeddingProvider` (refus attendu). Ajouter un cas vert : les deux listes cohérentes. Exigence : frontieres-de-modules › Une classe d'adaptateur embarquée n'est nommée que par sa famille. Vérifier : `python scripts/architecture/test_check_module_boundaries.py` échoue sur les deux cas rouges, pour l'absence de refus.
  - Preuve : `python scripts/architecture/test_check_module_boundaries.py EmbeddedAdapterRuleTest` avant correction : 12 tests, FAILED (failures=2), les deux cas rouges en « RuntimeError not raised » ; cas vert `test_every_api_user_listed_as_an_adapter_is_accepted_and_an_adapter_may_name_no_api` vert.
- [x] 1.2 AUD-ARC-11 — Correction : dans `check_embedded_adapters` (`scripts/architecture/check-module-boundaries.py:1174-1210`), refuser toute entrée de `INFRASTRUCTURE_API_USERS` dont le nom simple manque à `EMBEDDED_ADAPTER_CLASSES` (design, D1) ; ajuster la docstring et le commentaire des tables (`:1164-1171`). Vérifier : cas de 1.1 verts ; `python scripts/architecture/check-module-boundaries.py` vert sur le dépôt.
  - Preuve : `python scripts/architecture/test_check_module_boundaries.py` : Ran 307 tests, OK ; `python scripts/architecture/check-module-boundaries.py` : M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules=14, sources=678), code 0.
- [x] 1.3 AUD-ARC-11 — Preuve : rejouer la mutation de l'état vérifié (retrait une à une des 17 entrées, appel de `check_embedded_adapters(adapters=…)` depuis un script du scratchpad) ; vérifier que `OllamaEmbeddingProvider`, `JavaAstSupport` et `JavaProgramModel` sont refusés et que seul `FileProgramGraphProvider` reste silencieux, comme D2 l'écrit. Consigner le résultat dans la PR.
  - Preuve : mutation des 17 entrées (`check_embedded_adapters(adapters=…)`, script du scratchpad) : garde d'origin/main refused=13 silent=4 (FileProgramGraphProvider, JavaAstSupport, JavaProgramModel, OllamaEmbeddingProvider) ; garde corrigé refused=16 silent=1 (FileProgramGraphProvider), comme D2 l'écrit ; consigné dans la PR.
- [x] 1.4 AUD-ARC-11 — Câblage : `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts (l'auto-test est déjà exécuté par le job `invariants`) ; `python .claude/scripts/run_gates.py` vert.
  - Preuve : `check-ci-wiring.py` : CI WIRING GATE SUCCESS ; `check-single-execution.py` : SINGLE EXECUTION GATE SUCCESS ; `python .claude/scripts/run_gates.py` : 83/83 gates passent.

## 2. ADR et décisions (AUD-ARC-11)

- [x] 2.1 AUD-ARC-11 — **(manuelle, propriétaire)** trancher D2 du design (résidu `FileProgramGraphProvider` : a recommandée, b). Vérifier : choix consigné dans la PR.
  - Décision du propriétaire du 2026-11-20 : recommandation du design retenue (option a, résidu `FileProgramGraphProvider` accepté et écrit dans ARCH-0087).
- [x] 2.2 AUD-ARC-11 — Amender le point 3 d'ARCH-0087 (`Proposed`, `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md:37`) : la première liste est incluse dans la seconde ; résidu de D2 selon l'option retenue ; compléter « Compliance and Enforcement » avec le nouveau cas d'auto-test. Rédiger au présent. Vérifier : `archgate check` et `python .claude/scripts/run_gates.py --fast` verts.
  - Preuve : point 3 et « Compliance and Enforcement » d'ARCH-0087 amendés au présent ; `archgate check` : pass, 18/18 ; `run_gates.py` : 83/83 gates passent.

## 3. Clôture

- [x] 3.1 AUD-ARC-11 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
  - Preuve : matrice du design relue : la preuve attendue (cas rouge puis vert, garde vert, mutation rejouée) est exécutée (1.1-1.3) ; aucune ligne n'affirme une couverture sans preuve ; reste non prouvé : la confirmation de D2 par le propriétaire.
- [x] 3.2 AUD-ARC-11 — Après archivage, mettre à jour la ligne de l'exigence dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (4 scénarios, nouveau test) ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
  - Fait à l'archivage (trois cas ajoutés) ; `python scripts/docs/check-capability-dossiers.py` : 22 dossiers cohérents.
- [x] 3.3 AUD-ARC-11 — Valider les spécifications ; vérifier par `openspec validate --all --strict`.
  - Preuve : `openspec validate --all --strict` : 23 passed, 0 failed.
- [x] 3.4 AUD-ARC-11 — Lister les documents à mettre à jour avant archivage (ARCH-0087, dossier de capacité, ligne AUD-ARC-11 de `docs/project/audits/2026-11-15/SUIVI.md` ; STATUS et ROADMAP ne citent pas le constat) ; vérifier que chacun est fait ou nommé comme reste.
  - Preuve : ARCH-0087 : fait ; dossier de capacité `frontieres-de-modules.md` : reste (3.2, après archivage) ; ligne AUD-ARC-11 de `SUIVI.md` : reste, après fusion ; STATUS et ROADMAP ne citent pas le constat.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive proteger-les-adaptateurs-embarques-contre-le-retrait-de-leur-entree -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.

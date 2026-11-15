# Tasks

## 1. Règle « tout gate a un auto-test » (AUD-TST-09)

- [x] 1.1 AUD-TST-09 — Rouge : ajouter à `scripts/quality/test_check_ci_wiring.py` les cas « gate sans auto-test hors liste », « entrée de liste dont le script a un auto-test », « entrée de liste sans script », « gate avec `test_*.py` jumeau accepté », « `history/` exclu » ; vérifier que `python scripts/quality/test_check_ci_wiring.py` échoue (`controles-de-fusion` › Tout gate a un auto-test ; Les gates sans auto-test sont une liste fermée qui ne peut que diminuer)
- [x] 1.2 AUD-TST-09 — Correction : règle et table `GATES_WITHOUT_SELF_TEST` dans `scripts/quality/check-ci-wiring.py` (les huit gates de la décision D4 avec leur raison, plus provisoirement les dix du constat : dix-huit entrées) ; message de succès complété ; vérifier que 1.1 passe et que `python scripts/quality/check-ci-wiring.py` est vert

## 2. Module commun et auto-tests des dix gates (AUD-TST-09)

- [x] 2.1 AUD-TST-09 — Rouge : écrire `scripts/quality/test_literal_gate.py` sur un gate factice (littéral exigé retiré, littéral interdit inséré, motif, fichier exigé retiré, règle vacuitaire acceptée, fait sans mutation, zéro fait, aucune écriture sur disque) ; vérifier qu'il échoue faute de module (`controles-de-fusion` › L'auto-test d'un gate littéral refuse une mutation par fait affirmé)
- [x] 2.2 AUD-TST-09 — Correction : écrire `scripts/quality/literal_gate.py` (surcouche de lecture en mémoire, affirmations qui enregistrent les faits, pilote de `--self-test`, commentaires en anglais) ; vérifier que 2.1 passe
- [x] 2.3 AUD-TST-09 — Relever la sortie et le code de sortie des dix gates sur le dépôt (référence), puis porter les huit `scripts/quality/check-*-consistency.py` sur le module commun avec `--self-test` ; vérifier pour chacun : sortie et code identiques à la référence, `--self-test` vert avec un nombre de mutations supérieur à zéro ; déclarer l'option sous la forme littérale `add_argument("--self-test"` ; rejouer `python scripts/remediation/check-post-mne.py`
- [x] 2.4 AUD-TST-09 — Porter `scripts/docs/check-current-docs.py` : faits littéraux par le module commun, table de mutations écrites pour les règles structurées (dont la présence de `-Dminos.sandbox.tests.required` sur chaque commande Maven de `pr-ci.yml`) ; vérifier qu'une copie de `pr-ci.yml` privée de cette propriété sur une seule commande est refusée par `--self-test`
- [x] 2.5 AUD-TST-09 — Séparer dans `scripts/docs/check-current-docs.py` l'évaluation de l'analyse des arguments, puis porter `scripts/quality/check-current-docs-vertical-extension.py` : il appelle l'évaluation du gate courant (plus `current.main()`, `:33-35`) et ajoute ses faits propres ; vérifier `--self-test` vert pour les deux, sortie identique à la référence, et qu'un `--self-test` passé à l'extension ne déclenche pas celui du gate chargé
- [x] 2.6 AUD-TST-09 — Toute mutation acceptée révélée par 2.3 à 2.5 est une règle vacuitaire : la consigner (fichier, littéral) et la corriger si l'attente est retrouvable dans l'historique, sinon la soumettre au propriétaire ; ne supprimer aucune règle sans accord

## 3. Câblage, preuve et documentation (AUD-TST-09)

- [x] 3.1 AUD-TST-09 — Retirer les dix gates de `GATES_WITHOUT_SELF_TEST` ; vérifier que `python scripts/quality/check-ci-wiring.py` échoue alors en nommant les onze auto-tests non câblés (dix gates et `test_literal_gate.py`)
- [x] 3.2 AUD-TST-09 — Correction : étapes du job `invariants` de `.github/workflows/pr-ci.yml` pour les dix `--self-test` et `test_literal_gate.py` ; rejouer `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-workflow-pins.py`
- [x] 3.3 AUD-TST-09 — Preuve : `python .claude/scripts/run_gates.py` vert, durée des dix auto-tests relevée, `git status --porcelain` inchangé après l'exécution ; sur la PR, durée du job `invariants` lue et comparée à son délai de 15 minutes
  - Fait : job `Static invariants (single run)` de la PR #738 (run 43726978252, tête 913c7e95) vert en 4 min 8 s, sous son délai de 15 minutes.
- [x] 3.4 AUD-TST-09 — Documentation : `docs/developer/quality-gates.md` (règle, module commun, liste fermée et son sens), `.claude/rules/gates-python.md` si la règle des auto-tests y figure, `docs/architecture/diagrams/capacites/controles-de-fusion.md` ; vérifier par `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`

## 4. Clôture

- [x] 4.1 Mettre à jour la matrice de traçabilité du design ; nommer les règles vacuitaires trouvées en 2.6 et leur suite ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [x] 4.2 Vérifier le dossier durable `controles-de-fusion` par `python scripts/docs/check-capability-dossiers.py`
- [x] 4.3 Valider les spécifications par `openspec validate --all --strict`
- [x] 4.4 Lister les documents à mettre à jour avant archivage (`docs/developer/quality-gates.md`, `SUIVI.md` de l'audit du 2026-11-15 avec la liste fermée restante) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive doter-chaque-gate-d-un-auto-test -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
- Suite : doter ou retirer les huit gates de la liste fermée (décision D4).

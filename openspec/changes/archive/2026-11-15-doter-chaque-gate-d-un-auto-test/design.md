# Design

Changement d'outillage (scripts de gate et un workflow) : les sections Contexte, État vérifié au HEAD, Écarts, Décisions, Vérification et Traçabilité sont remplies. Les autres sections du modèle sont non applicables : aucun code du produit ne change.

## Contexte

Motivation : `proposal.md`. Les gates de cohérence sont des scripts impératifs : une fonction `read(chemin)` lit un fichier sous une constante `ROOT`, puis des appels `require(chemin, texte, littéral)`, `forbid(...)`, `require_pattern(...)` lèvent une erreur au premier fait manquant. `check-post-mne.py` les lance en sous-processus ; `check-current-docs-vertical-extension.py` charge `check-current-docs.py` comme module puis ajoute ses propres faits. `check-ci-wiring.py` (règle 2) exige qu'un auto-test existant soit lancé par le job `invariants`, mais n'exige pas qu'il existe.

## État vérifié au HEAD

HEAD `89be3fb4`.

| Fait | Preuve |
|---|---|
| Dix gates cités, sans `--self-test` ni `test_*.py` : `check-advanced-provider-consistency.py` (152 l.), `check-hosted-control-plane-consistency.py` (207), `check-java-ast-provider-consistency.py` (183), `check-polyglot-provider-consistency.py` (393), `check-remote-distributed-consistency.py` (260), `check-runtime-dynamic-consistency.py` (189), `check-semantic-retrieval-consistency.py` (281), `check-vertical-decomposition-consistency.py` (225), `check-current-docs-vertical-extension.py` (57), `scripts/docs/check-current-docs.py` (271) | décompte et recherche de `self-test` / `self_test` dans chaque fichier : 0 |
| Huit autres gates hors `history/` sans auto-test : `scripts/remediation/check-audit-remediation-v2.py`, `check-minos-01.py`, `check-mnd.py`, `check-mne.py`, `check-p0-p2.py`, `check-post-mne.py`, `check-post228-hardening.py`, `scripts/release/check-supply-chain.py` | pour chaque `git ls-files 'scripts/**/check-*.py'` : ni `add_argument("--self-test"` ni `test_*.py` jumeau. `check-audit-remediation-v2.py` contient la chaîne `--self-test` (`:108-111`) seulement pour lire `pr-ci.yml` : il ne déclare pas l'option |
| Ces huit gates sont lancés par la CI (sept) ou par une release (un) | `.github/workflows/pr-ci.yml:238-256` (dont `check-audit-remediation-v2.py`, `:249-250`) |
| Trois scripts de `scripts/history/` sont dans le même cas et sont exclus par la règle existante | `scripts/quality/check-ci-wiring.py:11-12` |
| La règle 2 ne vise que les auto-tests existants | `scripts/quality/check-ci-wiring.py:268-298` ; option reconnue par `SELF_TEST_OPTION`, `:55` |
| Les gates lisent le dépôt par une constante `ROOT` et une fonction `read` recopiée | `scripts/quality/check-java-ast-provider-consistency.py:10-17` ; `scripts/docs/check-current-docs.py:10`, `:38` |
| Les faits sont des appels à `require`, `forbid`, `require_pattern`, `require_all`, `require_regex`, `require_absent` | `check-java-ast-provider-consistency.py:26-38` ; `scripts/docs/check-current-docs.py:52-72` |
| L'extension charge le gate courant comme module | `scripts/quality/check-current-docs-vertical-extension.py:14-21`, `:33-37` |
| Un module commun aux gates existe déjà pour le parcours et le compte rendu | `scripts/quality/gate_cli.py` (`repository_files`, `parse_root`, `run`) |
| Les copies de `read` / `require` de ces gates ne sont déclarées dans aucune table de `check-twin-script-functions.py` : ce gate n'est pas concerné | recherche de `consistency` et `check-current-docs` dans `scripts/quality/check-twin-script-functions.py` : 0 ligne |

Statut : **confirmé** pour les dix gates ; l'affirmation du titre « chaque autre gate en a un » est **inexacte** (huit autres n'en ont pas ; dix-huit gates sans auto-test en tout).

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1 — « chaque autre gate en a un rejoué en CI » : huit gates de remédiation et de release n'en ont pas | titre de AUD-TST-09 | recherche au HEAD | les nommer dans une liste fermée décroissante (D3), décider de leur sort (D4) |
| E2 — « l'enregistrer dans check-ci-wiring.py » : il n'y a pas de registre ; un script qui déclare `--self-test` est exigé au câblage automatiquement, mais un gate **sans** auto-test n'est vu par personne | action de AUD-TST-09 | `check-ci-wiring.py:268-298` | ajouter la règle manquante : tout gate a un auto-test (D3) |
| E3 — « une mutation refusée par règle » écrite à la main dans dix scripts ferait dix copies du même pilote et autant de mutations oubliées | action de AUD-TST-09 | structure commune des dix gates | pilote de mutation commun, qui dérive les mutations des faits réellement évalués (D1, D2) |
| E4 — Les gates lèvent au premier échec : un auto-test par copie d'arbre lancerait un sous-processus par fait | structure des gates | durée du job `invariants` (délai de 15 minutes, `pr-ci.yml:52`) | copie **en mémoire** des fichiers lus, réévaluation dans le même processus (D2) ; durée mesurée en tâche |
| E5 — `check-current-docs.py` appelle aussi des fonctions structurées (`maven_verify_lines`, `require_sandbox_qualification_on_every_verify`) qui ne passent pas par `require` | action de AUD-TST-09 | `scripts/docs/check-current-docs.py:166-182` | mutations écrites à la main pour ces règles, déclarées dans le script (D2) ; c'est la règle qui garde `-Dminos.sandbox.tests.required` |

## Décisions

### D1 — Un module commun pour les gates littéraux
[Décision] `scripts/quality/literal_gate.py` (bibliothèque standard) porte une fois : la lecture d'un fichier à travers une surcouche en mémoire, les fonctions d'affirmation (`require`, `forbid`, `require_pattern`, variantes existantes) qui enregistrent chaque fait évalué (règle, fichier, littéral ou motif), et le pilote d'auto-test. Chaque gate sépare son **évaluation** (une fonction qui relève les faits et ne lit pas la ligne de commande) de son `main` (analyse des arguments, dont `add_argument("--self-test"`, forme littérale que `check-ci-wiring.py` reconnaît) : `check-current-docs-vertical-extension.py:33-35` appelle aujourd'hui `current.main()` et appellera l'évaluation, pour ne pas hériter des arguments du gate qu'il charge. Les dix gates importent le module au lieu de recopier ces fonctions ; leurs littéraux, leurs messages et leur code de sortie sont inchangés. `scripts/docs/check-current-docs.py` l'importe par chemin, comme `check-ci-wiring.py` importe `gate_cli`. Écarté : dix `--self-test` écrits séparément (écart E3).

### D2 — Mutations dérivées des faits, sans toucher au dépôt
[Décision] `--self-test` : (1) évalue le gate sur le dépôt et relève les faits ; échec si le gate échoue ou si aucun fait n'est relevé ; (2) pour chaque fait, construit une surcouche où le fichier lu est muté — littéral exigé retiré, littéral interdit inséré, portion reconnue par un motif remplacée, fichier « absent exigé » créé, fichier exigé retiré — et réévalue : le gate doit échouer en nommant ce fichier ; (3) affiche le nombre de mutations refusées et échoue en nommant toute mutation acceptée. Un littéral présent plusieurs fois est retiré partout. Les règles structurées (écart E5) déclarent leurs mutations dans une table du script, et le pilote échoue si une telle règle n'en a pas. Aucune écriture sur disque.

### D3 — Règle « tout gate a un auto-test » et liste fermée
[Décision] `check-ci-wiring.py` reçoit une règle : pour tout `scripts/**/check-*.py` hors `history/`, `--self-test` déclaré (expression `SELF_TEST_OPTION` existante) ou `test_<nom>.py` jumeau ; sinon le script doit figurer dans `GATES_WITHOUT_SELF_TEST`, table `chemin → raison`. Une entrée dont le script a un auto-test, ou n'existe plus, fait échouer le gate (cliquet). `test_check_ci_wiring.py` reçoit les cas correspondants.

### D4 — Sort des huit gates restants
[Proposition, recommandée] Les inscrire dans la liste fermée avec leur raison (« gate de remédiation d'un audit clos, à doter ou à retirer »), et ouvrir un constat de suivi. Les traiter ici doublerait le changement sans rapport avec le risque décrit par le constat (ils portent sur des audits clos). Écarté : les ignorer (la règle D3 serait fausse dès son écriture) ; les doter tous maintenant.

### D5 — Câblage dans `invariants`
[Décision] Une étape par auto-test ou une étape multi-lignes : `check-ci-wiring.py` cherche une ligne qui contient le chemin du script et `--self-test`. `check-single-execution.py` ne compte pas une ligne `--self-test` comme une exécution (`controles-de-fusion › Un auto-test n'est pas compté comme une seconde exécution d'un gate`).

## Vérification

- Rouge : après l'ajout de la règle D3, `python scripts/quality/check-ci-wiring.py` échoue en nommant les dix-huit gates ; après l'ajout de la liste fermée, il nomme encore les dix du constat tant qu'ils n'ont pas leur `--self-test`.
- Rouge du pilote : `test_literal_gate.py` (auto-test du module commun, câblé) prouve sur un gate factice qu'une règle vacuitaire, un fait sans mutation et zéro fait font échouer `--self-test`.
- Non-régression des gates : sortie et code de chacun des dix gates identiques avant et après le passage au module commun, sur le dépôt réel ; `python scripts/remediation/check-post-mne.py` vert.
- `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python .claude/scripts/run_gates.py`.
- Durée des dix auto-tests mesurée sur le poste et lue dans le journal du job `invariants` ; `git status --porcelain` vide après exécution.
- Windows et Linux : mêmes scripts ; fins de ligne des fichiers lus différentes sur un poste `core.autocrlf=true` — les mutations opèrent sur le texte lu, sans hypothèse de fin de ligne.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Tout gate a un auto-test | État du dépôt ; Gate ajouté sans auto-test | D3 | 1.1, 1.2, 3.3 | `scripts/quality/test_check_ci_wiring.py#test_the_repository_satisfies_the_rule_with_its_closed_list`, `#test_a_gate_without_a_self_test_outside_the_list_is_refused_by_name`, `#test_a_gate_with_a_twin_test_is_accepted`, `#test_archived_gates_are_excluded` ; `python scripts/quality/check-ci-wiring.py` (gates with a self-test=31, waiting=8) |
| idem | Les dix gates du constat | D1, D2 | 2.3–2.5 | `--self-test` des dix gates, tous verts, de 40 à 287 mutations refusées chacun (étape « Literal gate self-tests » de `pr-ci.yml`) |
| controles-de-fusion › L'auto-test d'un gate littéral refuse une mutation par fait affirmé | Littéral exigé retiré ; Littéral interdit introduit ; Règle devenue vacuitaire ; Aucun fait relevé | D2 | 2.1, 2.2 | `scripts/quality/test_literal_gate.py` (`SoundGateTest`, `VacuousGateTest#test_a_rule_that_accepts_the_mutated_copy_is_named_with_its_file_and_literal`, `#test_a_gate_without_any_fact_fails`, `#test_a_forbidden_pattern_without_witness_has_no_mutation`) |
| idem | Dépôt intact | D2 | 3.3 | `test_literal_gate.py` compare l'arbre avant et après chaque pilote ; `git status --porcelain` vide après les dix auto-tests sur le poste |
| controles-de-fusion › Les gates sans auto-test sont une liste fermée qui ne peut que diminuer | Entrée périmée ; Entrée sans script | D3, D4 | 1.1, 1.2 | `scripts/quality/test_check_ci_wiring.py#test_a_listed_gate_that_now_has_a_self_test_is_refused`, `#test_a_listed_gate_with_a_twin_test_is_refused_too`, `#test_an_entry_without_a_script_is_refused` |

## Questions ouvertes

- [Question, propriétaire] D4 : inscrire les huit gates restants dans la liste fermée et les traiter dans un changement ultérieur (recommandé), ou les doter ici.

## Résultat de l'implémentation

- Règles vacuitaires (tâche 2.6) : aucune. Les dix auto-tests refusent toutes leurs mutations au commit de l'implémentation.
- Redondance relevée, non vacuitaire : l'existence de `WorkerSandboxQualification.java` vérifiée en fin de `check-vertical-decomposition-consistency.py` est déjà imposée par la lecture du même fichier plus haut ; le pilote accepte qu'un fait de fichier soit refusé par une autre lecture qui nomme le même chemin.
- Messages d'échec : identiques, sauf deux cas qui ne se produisent qu'en échec et donnent un message plus précis : un composant manquant de `check-vertical-decomposition-consistency.py` (lu par le module : « missing required file: … » au lieu de l'erreur d'E/S brute) et `require_absent` de `check-current-docs.py`, qui vise un fichier (un dossier homonyme n'est plus refusé).
- Durée des onze auto-tests sur le poste Windows : environ 24 s au total (dont 4 s chacun pour `check-current-docs.py` et son extension). La durée dans le job `invariants` reste à lire sur la PR.

# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : seuls l'auto-test du garde de frontières, un auto-test nouveau du lanceur partagé, trois docstrings et une étape du job `invariants` changent. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `535a50176` (PR #804), dans le worktree `.claude/worktrees/sprint10`.

| Fait | Preuve |
|---|---|
| La docstring de l'auto-test annonce : `main()` « is checked to run every rule (failing spies) » | `scripts/architecture/test_check_module_boundaries.py:5-6` |
| La docstring de `MainWiringTest` dit encore « main() must run both rules », héritée du jalon où il n'y avait que deux règles | `test_check_module_boundaries.py:416` ; `docs/project/audits/archive/2026-09/ARCHI-SUIVI.md:407` (V-A3-01) |
| `MainWiringTest` espionne 13 fonctions : `check_reactor_modules`, `check_package_ownership`, `check_runtime_local_exports`, `check_engine_types_named_by_surfaces`, `check_minos_application_accessors`, `check_process_launchers`, `check_adapter_types_named_by_adapters`, `check_package_roots`, `check_architecture_test_policy`, `check_snapshot_read_port`, `check_postgresql_backend_isolation`, `check_storage_package_isolation`, `check_unreferenced_production_types` | `test_check_module_boundaries.py:442-484` |
| `run_rules` appelle, dans l'ordre, 22 fonctions `check_*`, puis `runtime_service_edges` ; `check_or_write_dependency_document` vient en dernier | `scripts/architecture/check-module-boundaries.py:291-317` ; analyse AST de `run_rules` (appels `ast.Name`) |
| Appels directs sans espion (9) : `check_pom_rules`, `check_java_layout`, `check_source_boundaries`, `check_assembly_classes` (A20), `check_embedded_adapters` (A19), `check_service_loader_class_loaders` (A16), `check_package_cycles` (A8), `check_architecture_documentation` (A9), `check_or_write_dependency_document` ; plus `runtime_service_edges`, qui n'est pas une `check_*` mais peut échouer | `check-module-boundaries.py:294-316` |
| `check_pom_rules` appelle `check_pom_layout`, `check_no_hidden_internal_dependencies` et `check_dependency_policy`, qui appelle `check_hexagonal_boundaries` (A2) : aucune n'est espionnée à travers `main()` | `check-module-boundaries.py:235-279` |
| `dir()` du garde et des cinq modules de règles (`BOUNDARY_POLICY`, `ADAPTERS`, `RATCHETS`, `PACKAGES`, `DOCUMENTS`) donne exactement 26 fonctions `check_*` : les 22 appels directs et les 4 règles de POM. Aucune autre | énumération exécutée sur le dépôt (script du scratchpad) |
| Toutes les 26 sont des noms globaux du module du garde (définies là ou importées par `from … import`) : un `setattr(_MODULE, nom, espion)` intercepte chaque appel, y compris celui de `check_hexagonal_boundaries` par `check_dependency_policy` | `check-module-boundaries.py:24-42` ; `scripts/architecture/module_boundaries_testing.py:22-32` |
| `EveryRuleHasATest` exige seulement la chaîne `_MODULE.<nom>` ou `<nom> = _MODULE` dans l'un des cinq fichiers de test | `test_check_module_boundaries.py:401-412` |
| Chaque espion de `MainWiringTest` laisse s'exécuter réellement les règles qui le précèdent : un sous-test sur une règle tardive coûte presque une exécution complète du garde (environ 20 s sur le poste) | `test_check_module_boundaries.py:418-431` ; `time python scripts/architecture/check-module-boundaries.py` : 22 s |
| `gate_runner.run_gate` imprime le rapport et rend 0, ou rapporte `<label> FAILED: <message>` sur stderr et rend 1 ; il n'attrape que `Exception` | `scripts/architecture/gate_runner.py:17-29` |
| La docstring de `gate_runner.py` affirme que les auto-tests de `check-module-boundaries.py` et de `check-private-io.py` l'exercent ; celui de `check-private-io.py` n'appelle ni `main()` ni `run_gate` ; `MainWiringTest` n'exerce que la branche d'échec, sans vérifier le préfixe `<label> FAILED:` ni le code 0 | `gate_runner.py:3-5` ; `scripts/architecture/test_check_private_io.py` (aucune occurrence) ; `test_check_module_boundaries.py:445-447` |
| `check-ci-wiring.py`, règle 2 : tout `scripts/**/test_*.py` hors `history` doit être lancé par une étape du job `invariants` ; la règle 7 ne vise que les `check-*.py`, donc ni `gate_runner.py` ni `module_boundaries_testing.py` | `scripts/quality/check-ci-wiring.py:306-336`, `:513-536` |
| Étapes du job `invariants` : le garde, puis l'auto-test « Module-boundary checker self-test (A2 POM rules, policy source, main wiring) », puis les quatre autres auto-tests | `.github/workflows/pr-ci.yml:161-177` |
| Gates littéraux qui lisent ces fichiers : `check-p0-p2.py` lit le garde (`ALLOWED_DEPENDENCIES`, `internal Maven dependency cycle`, `dependencyPolicy=explicit-v1`), aucune chaîne de l'auto-test ni du lanceur ; aucun autre gate ne cite `test_check_module_boundaries`, `MainWiringTest`, `run_main_with` ou `gate_runner` | `scripts/remediation/check-p0-p2.py:163`, `:254-256` ; recherche dans `scripts/` |
| `check-single-execution.py` ne compte que les contrôles lourds (Maven `verify`, `product-facts.py`, `check-jacoco.py`, installation de la chaîne Linux) ; `check-audit-remediation-v2.py` refuse dans `invariants` un `mvnw verify` ou `check-jacoco.py` : une étape Python ajoutée ne touche ni l'un ni l'autre | `scripts/quality/check-single-execution.py:9-24` ; `scripts/remediation/check-audit-remediation-v2.py:101-113` |

### Mutations rejouées

Le 2026-11-24, une copie de `scripts/architecture/` est faite dans le scratchpad de la session, avec le `ROOT` de la copie pointé vers le worktree (lecture seule). Une mutation est appliquée à la fois ; on relève le code de sortie du garde sur le dépôt, puis celui de `test_check_module_boundaries.py`. Ligne de base : le garde et les cinq auto-tests rendent 0. Les quatre autres fichiers de test (`_packages`, `_adapters`, `_ratchets`, `_documents`) n'appellent jamais `main()`, `run_rules`, `run_checks` ni le script (aucune occurrence) : une mutation du point d'entrée ne peut pas les faire rougir.

| Mutation (copie) | Garde | `test_check_module_boundaries.py` |
|---|---|---|
| Témoin : `package_roots = 0` (règle espionnée) | 0 | **1** (`MainWiringTest`) |
| `service_loaders = 0` (A16) | 0 | 0 |
| `api_users, embedded_adapters = 0, 0` (A19) | 0 | 0 |
| `assembly_classes = 0` (A20) | 0 | 0 |
| `check_source_boundaries()` supprimé (A2 sources) | 0 | 0 |
| `total, counts = 0, {…: 0}` (disposition Java) | 0 | 0 |
| `cycles, cycle_packages = 0, 0` (A8) | 0 | 0 |
| `documented = 0` (A9) | 0 | 0 |
| `check_or_write_dependency_document(…)` supprimé (document généré) | 0 | 0 |
| `check_pom_layout(module, pom)` supprimé dans `check_pom_rules` | 0 | 0 |
| `check_no_hidden_internal_dependencies(…)` supprimé dans `check_pom_rules` | 0 | 0 |
| `check_dependency_policy(scoped)` supprimé dans `check_pom_rules` | 0 | **1** (3 cas de `PomRulesOnRealPomsTest`) |
| `check_hexagonal_boundaries(scoped)` supprimé dans `check_dependency_policy` | 0 | **1** (`DependencyPolicyTest`, `PomRulesOnRealPomsTest`) |
| `runtime_edges = []` | **1** (document généré périmé) | 0 |
| `check_package_cycles()` entouré d'un `try` / `except Exception` qui rend `0, 0` | 0 | 0 |
| `check_demo` ajoutée à `module_boundary_ratchets.py`, citée par un test, appelée par personne | 0 | 0 |

Bilan : dix règles peuvent perdre leur appel sans aucun rouge, huit appels directs et deux règles de POM. Une erreur avalée dans `run_rules` reste silencieuse, et une règle nouvelle non câblée aussi. A2 (politique et hexagonale) ne tient que par `PomRulesOnRealPomsTest`, qui appelle `check_pom_rules` directement. `runtime_service_edges` ne tient que par la fraîcheur du document généré, contrôlée par le garde lui-même.

Non mesuré : la durée de `MainWiringTest` en CI (Linux et Windows) ; aucun test de la forme proposée n'a été écrit.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit compte « huit fonctions » manquantes, dont `check_pom_rules` (`check_hexagonal_boundaries`, `check_dependency_policy`). La mesure trouve treize fonctions sans espion : les neuf appels directs, dont `check_or_write_dependency_document` que l'audit omet, et les quatre règles de POM. Dix sont réellement silencieuses ; A2 tient par `PomRulesOnRealPomsTest`, contrairement à ce que dit l'audit. L'audit omet aussi l'erreur avalée et la règle nouvelle non câblée | `findings.json`, AUD-TST-13, preuve | « Mutations rejouées » ci-dessus | D1 couvre les 26 `check_*` sans liste recopiée ; D2 l'erreur avalée |
| L'action de l'audit (« étendre `run_main_with` aux huit fonctions » ou « dériver la liste des appels de `main()` ») ne protège pas contre le défaut qu'elle vise : une liste dérivée des appels de `run_rules` rétrécit avec l'appel retiré, et le test reste vert ; une liste recopiée oublie la règle suivante | `findings.json`, AUD-TST-13, action | AST de `run_rules` : retirer un appel retire le nom de la liste | D1 : la liste attendue vient des `check_*` définies, indépendamment de `run_rules` |
| Un espion qui enregistre l'appel puis appelle la vraie règle ne voit pas une erreur avalée (`try` / `except`) dans `run_rules` | — | `run_gate` n'attrape qu'au niveau de `main()` | D2 garde un espion qui lève une exception par règle |
| Étendre le schéma actuel à 26 espions ferait tourner presque tout le garde 26 fois | `test_check_module_boundaries.py:418-431` | garde : 22 s par exécution sur le poste | D2 : rejeu des résultats enregistrés |
| La docstring de `gate_runner.py` dit son code exercé par deux auto-tests ; un seul l'exerce, et seulement sur la branche d'échec | `gate_runner.py:3-5` | `test_check_private_io.py` ; `test_check_module_boundaries.py:445-447` | D3 |
| L'exigence de couverture nomme le seul `test_check_module_boundaries.py` et le seul script, alors que les règles et leurs tests vivent dans six modules et cinq fichiers depuis les PR #802 à #804 | `openspec/specs/frontieres-de-modules/spec.md:8-9` | `test_check_module_boundaries.py:403-412` (glob `test_check_module_boundaries*.py`, six modules) | exigence modifiée |
| L'étape CI annonce « main wiring » ; la docstring « every rule » : plus affirmatives que les tests | `.github/workflows/pr-ci.yml:164` ; `test_check_module_boundaries.py:5-6` | `MainWiringTest` (13 espions) | deviennent exactes une fois D1 livré ; libellé de l'étape inchangé |

## Exigences couvertes

- frontieres-de-modules › Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté — exigence modifiée (cinq auto-tests, modules de règles).
- frontieres-de-modules › Le point d'entrée du garde exécute chacune de ses règles — D1, D2.
- frontieres-de-modules › Le lanceur partagé des gardes d'architecture a son propre auto-test — D3.

## Décisions


Décision du propriétaire du 2026-11-24 : les recommandations de ce design sont retenues.
### D1 — La liste attendue est l'ensemble des `check_*` des six modules, pas les appels de `run_rules` [Proposition, recommandation]

`MainWiringTest` calcule l'ensemble des fonctions `check_*` appelables de `_MODULE`, `BOUNDARY_POLICY`, `ADAPTERS`, `RATCHETS`, `PACKAGES` et `DOCUMENTS`, avec le même calcul qu'`EveryRuleHasATest` (`test_check_module_boundaries.py:407-409`), partagé plutôt que recopié. Il ouvre un sous-test par nom. Chaque nom doit être un global de `_MODULE` : sinon `main()` ne peut pas l'appeler, et le sous-test échoue en nommant la fonction. Une constante `RULES_NOT_RUN_BY_MAIN` (dictionnaire nom → raison) accueille une fonction `check_*` qui ne serait pas une règle de `main()`. Elle est vide à la livraison ; une entrée qui désigne une fonction absente, ou exécutée par `main()`, fait échouer le test (cliquet).

Conséquences : une fonction `check_*` ajoutée à un module de règles sans appel dans `run_rules` rougit ; un appel retiré rougit ; une règle atteinte par une autre (A2 par `check_pom_rules`) est couverte sans traitement particulier.

Options écartées :

- (b) étendre la liste recopiée aux fonctions manquantes (action de l'audit, première branche) : la règle suivante sera oubliée de la même façon ;
- (c) dériver la liste des appels de `run_rules` par AST (action de l'audit, seconde branche) : un appel retiré sort aussi de la liste, et le test reste vert. L'AST peut servir de contrôle secondaire, pour que tout appel de `run_rules` soit une `check_*` ou figure dans une courte liste nommée (`runtime_service_edges`), mais il ne fixe pas l'attendu.

`runtime_service_edges` n'est pas une `check_*`. Elle peut échouer (implémentation de service hors du module qui l'enregistre) et reçoit son sous-test nommé dans la même boucle, par une liste fermée des fonctions du point d'entrée qui ne sont pas des `check_*`.

### D2 — Un espion qui échoue par règle, les autres règles rejouées [Proposition, recommandation]

Pour garder la preuve stricte (l'erreur de la règle atteint `main()`) sans exécuter 26 fois le garde :

1. Une exécution réelle de `main()` enveloppe chaque règle d'un espion qui appelle la vraie fonction et enregistre son résultat. Le code doit être 0, et toutes les règles attendues doivent avoir été appelées.
2. Pour chaque règle, `main()` est relancé avec cette règle remplacée par un espion qui lève une exception. Les autres règles rendent leur résultat enregistré, sans relire le dépôt. Le code doit être 1, le message de l'espion doit figurer sur stderr, et le rapport du garde ne doit pas affirmer de succès.
3. Une règle atteinte par une autre (`check_pom_layout`, `check_no_hidden_internal_dependencies`, `check_dependency_policy`, `check_hexagonal_boundaries`) est testée en laissant ses appelants réels. Lire les POM coûte peu.

Le coût reste celui d'une exécution complète plus des appels sans E/S. L'option (b) étend les espions actuels : sur le poste, une vingtaine de secondes par règle tardive, soit plusieurs minutes de plus dans le job `invariants`. L'option (c) enregistre seulement les appels : moins chère, mais aveugle à une erreur avalée (écart 3).

### D3 — Auto-test dédié `scripts/architecture/test_gate_runner.py` [Proposition, recommandation]

Le lanceur est partagé par deux gardes : son contrat se teste une fois, à côté de lui, et non par les effets de bord d'un garde. Les cas : rapport rendu (code 0, stdout = rapport, stderr vide) ; `RuntimeError` et `ValueError` (code 1, stderr = `<label> FAILED: <message>`, stdout vide) ; `SystemExit` et `KeyboardInterrupt` propagés, jamais rendus comme un code 0. Une étape « Architecture gate runner self-test » du job `invariants` le lance, comme `check-ci-wiring.py` (règle 2) l'exige dès que le fichier existe. La docstring de `gate_runner.py` nomme cet auto-test et ne prétend plus que `test_check_private_io.py` l'exerce.

Option écartée : des cas ajoutés à `test_check_module_boundaries.py`, sans étape nouvelle. Le contrat du lanceur resterait attaché à un seul de ses deux utilisateurs, et la docstring continuerait de citer un auto-test qui ne l'exerce pas.

## Vérification

- Rouge : au HEAD, toutes les règles sont câblées. Le rouge se prouve donc sur copie : les mutations de l'état vérifié (appel retiré, règle de POM retirée, règle nouvelle non appelée, erreur avalée) laissent l'auto-test actuel vert et font échouer le nouveau `MainWiringTest` en nommant la fonction.
- Rouge du lanceur : `test_gate_runner.py`, exécuté sur une copie dont `return 1` est remplacé par `return 0`, échoue. Retirer son étape de `pr-ci.yml` sur une copie fait échouer `check-ci-wiring.py --root <copie>`.
- Correction : garde vert sur le dépôt ; cinq auto-tests, `test_gate_runner.py` et `test_check_private_io.py` verts ; durée de `test_check_module_boundaries.py` mesurée avant et après.
- Câblage : `check-ci-wiring.py`, `check-single-execution.py`, `check-audit-remediation-v2.py`, `check-p0-p2.py`, `python .claude/scripts/run_gates.py`.
- Windows et Linux : scripts de la bibliothèque standard, chemins par `pathlib`, aucune branche de plateforme ; les deux jobs `Verify` ne sont pas concernés, le job `invariants` tourne sous Ubuntu.

## Documentation durable à mettre à jour

- `docs/architecture/diagrams/capacites/frontieres-de-modules.md` : ligne de traçabilité de l'exigence modifiée (preuves dans les cinq fichiers) et lignes des deux exigences ajoutées, à l'archivage.
- Aucune page de `docs/developer/` : la liste des fichiers du garde, `gate_runner.py` compris, est du ressort d'AUD-ARC-12 (`decrire-le-garde-de-frontieres-decoupe`). Si ce changement est fusionné avant, `docs/developer/quality-gates.md` gagne la mention de `test_gate_runner.py` dans la même PR.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve attendue |
|---|---|---|---|---|
| AUD-TST-13 | frontieres-de-modules › Le point d'entrée du garde exécute chacune de ses règles | D1, D2 | 1.1-1.5 | sous-tests rouges au HEAD puis verts ; mutations de l'état vérifié rouges ; durée mesurée |
| AUD-TST-13 | frontieres-de-modules › Le lanceur partagé des gardes d'architecture a son propre auto-test | D3 | 2.1-2.4 | auto-test vert, mutation `return 0` rouge, `check-ci-wiring.py` rouge sans l'étape |
| AUD-TST-13 | frontieres-de-modules › Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté | — | 3.2 | `EveryRuleHasATest` vert ; dossier de capacité à jour |

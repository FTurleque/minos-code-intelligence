# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : seuls l'auto-test du garde de frontières et deux docstrings changent. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `75e10c681` (PR #819), le 2026-11-25.

| Fait | Preuve |
|---|---|
| `rule_functions()` calcule l'ensemble des `check_*` appelables de `(_MODULE, BOUNDARY_POLICY, ADAPTERS, RATCHETS, PACKAGES, DOCUMENTS)` | `scripts/architecture/test_check_module_boundaries.py:465-469` |
| `EveryRuleHasATest` et `MainWiringTest.setUpClass` partagent ce calcul ; `MainWiringTest` exige en plus que chaque nom soit un global du script d'entrée (« is not a global name of check-module-boundaries.py: main() cannot run it ») | `test_check_module_boundaries.py:478`, `:514-518`, `:559-564` |
| Les docstrings affirment « derives every check_* function of the gate and of its rule modules, rather than copying a list » et « The expected rules are every check_* function (rule_functions) » | `test_check_module_boundaries.py:5-9`, `:485-492` |
| Les cinq noms de modules viennent de `sys.modules` après le chargement du garde : ce sont des cibles de patch ; la docstring dit « the modules that hold its rules are the very modules the gate imports » | `scripts/architecture/module_boundaries_testing.py:1-9`, `:22-33` |
| Le garde importe ses règles par `from module_boundary_<x> import check_…` : au HEAD, `dir(_MODULE)` contient donc déjà les 26 `check_*`, et les cinq autres modules du tuple n'en ajoutent aucune | `scripts/architecture/check-module-boundaries.py:24-42` ; `grep -n "^def check_" scripts/architecture/*.py` |
| Fichiers `module_boundary_*.py` : `adapters`, `documents`, `packages`, `policy`, `ratchets`, `sources`. `sources` (lecture des sources Java) n'est pas dans le tuple ; `policy` y est ; aucun des deux ne définit de `check_*` | `ls scripts/architecture/` ; `grep -n "^def check_"` |
| Les autres fichiers du dossier ne correspondent pas au motif `module_boundary_*.py` : `module_boundaries_testing.py` (pluriel), `gate_runner.py`, `check-private-io.py`, `private-io-allowlist.json`, `module-boundaries.json`, les `test_*.py` | noms de fichiers |
| `check-ci-wiring.py` contrôle l'exécution des `test_*.py` par le job `invariants`, pas le contenu de `rule_functions` | `scripts/quality/check-ci-wiring.py` (règle 2) |
| Aucun gate littéral ne cite `rule_functions`, `RULES_NOT_RUN_BY_MAIN` ni le tuple | `grep -rn "rule_functions\|RULES_NOT_RUN_BY_MAIN" scripts --include=check-*.py` : aucune occurrence |

### Mutations rejouées

Le 2026-11-25, `git archive HEAD` est extrait dans le scratchpad de la session (`garde/repo`). Chaque mutation porte sur une copie entière du dépôt, pour que le `ROOT` du garde (`module_boundary_policy.py:18`, deux niveaux au-dessus du script) désigne la copie. Ligne de base : garde rc=0, `test_check_module_boundaries.py` rc=0 (69 tests).

Toutes les mutations ajoutent `scripts/architecture/module_boundary_extra.py`, qui définit `check_extra_rule()` (lève `SystemExit`). Personne ne l'appelle.

| Mutation (copie) | Garde | Auto-test au HEAD | Auto-test avec dérivation par glob (prototype) |
|---|---|---|---|
| Module ajouté, non importé par le garde | 0 | **0** | 1 (3 échecs, dont « check_extra_rule is not a global name of check-module-boundaries.py ») |
| Module importé comme module (`import module_boundary_extra`), sans appel | 0 | **0** | 1 (mêmes 3 échecs) |
| Règle importée par `from module_boundary_extra import check_extra_rule`, sans appel | 0 | 1 (3 échecs : `EveryRuleHasATest`, `test_the_error_of_every_rule_turns_main_red`, `test_the_real_run_passes_and_calls_every_rule`) | 1 |
| Aucune mutation | 0 | 0 | 0 (69 tests) |

Prototype : la ligne `modules = (...)` est remplacée par un glob trié de `module_boundary_*.py` dans le dossier du test, suivi de `importlib.import_module(path.stem)` sur chaque fichier, en plus de `_MODULE`. Scripts : `scratchpad/garde/mutate_tst14.py`, `scratchpad/garde/derive_tst14.py`.

Non mesuré : la durée de l'auto-test en CI ; le contrôle fail-closed de D2 n'a pas été prototypé.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit décrit un développeur qui « ajoute un septième module de règles, l'importe et appelle sa règle dans run_rules » ; si l'appel disparaît plus tard, aucun contrôle ne rougirait. C'est vrai pour une importation du module (`import module_boundary_extra` puis `module_boundary_extra.check_…`). C'est faux pour la forme que le garde utilise partout (`from … import check_…`) : `dir(_MODULE)` voit alors la fonction, et l'auto-test rougit (mutation 3). Le trou réel est donc plus étroit : module non importé, ou importé comme module | `findings.json`, AUD-TST-14, impact | « Mutations rejouées », lignes 1 à 3 | D1 couvre les deux cas silencieux |
| L'action de l'audit propose, en seconde branche, « un test qui exige que chaque `module_boundary_*.py` figure dans le tuple ». Cela garde une liste écrite à la main, qu'il faut éditer à chaque module, et un helper sans règle (`module_boundary_sources.py`) devrait y entrer ou y être exempté | `findings.json`, AUD-TST-14, action | `module_boundary_sources.py` hors tuple au HEAD | D1 option a |
| Le glob attrape aussi les helpers `module_boundary_sources.py` et `module_boundary_policy.py`. C'est sans effet : ils ne définissent aucune `check_*`. Si un helper en définissait une un jour, elle devrait être câblée, ou exemptée par `RULES_NOT_RUN_BY_MAIN` avec sa raison (cliquet existant) | `grep -n "^def check_"` | `test_check_module_boundaries.py:460-462` | comportement voulu, écrit dans la docstring |
| L'exigence courante a un scénario « Règle nouvelle non câblée » limité à une fonction ajoutée à un module existant | `openspec/specs/frontieres-de-modules/spec.md`, exigence « Le point d'entrée du garde exécute chacune de ses règles » | mutation 1 | exigence modifiée, scénario « Module de règles nouveau non câblé » ajouté |

## Exigences couvertes

- frontieres-de-modules › Le point d'entrée du garde exécute chacune de ses règles — exigence modifiée (D1, D2).

## Décisions

### D1 — Découvrir les modules de règles sur le disque [Proposition, recommandation : a]

- **a (recommandée)** : `rule_functions()` calcule un glob trié de `module_boundary_*.py` dans le dossier de l'auto-test (`Path(__file__).resolve().parent`, non récursif). Elle importe chaque fichier par `importlib.import_module(path.stem)` : le dossier est déjà sur `sys.path`, le module déjà chargé par le garde est rendu tel quel, et un module que le garde n'importe pas est chargé. L'ensemble des `check_*` est pris sur `_MODULE` et sur ces modules. Une `check_*` qui n'est pas un global du script d'entrée échoue alors dans `MainWiringTest`, qu'elle soit dans un module non importé ou atteinte par attribut de module. Le garde garde ainsi une seule forme de câblage, `from … import check_…`, déjà celle des 26 règles.
- **b** : un test qui compare la liste des fichiers `module_boundary_*.py` au tuple (seconde branche de l'audit). Le tuple reste écrit à la main, et chaque module, helper compris, impose une édition.
- **c** : dériver les modules des imports du garde (AST de `check-module-boundaries.py`). Un module que le garde n'importe pas échappe encore : c'est la mutation 1.

Coût de a : importer un module non importé par le garde exécute son code au niveau module pendant l'auto-test. Ce code est celui du dépôt, déjà exécuté par le garde pour les six modules actuels.

### D2 — Échouer plutôt que se rabattre sur le seul script d'entrée [Proposition, recommandation]

Si le glob ne trouve aucun fichier, par exemple après un renommage du préfixe ou un auto-test déplacé, `rule_functions()` doit échouer. Elle doit aussi échouer si un module `module_boundary_*` présent dans `sys.modules` après le chargement du garde n'est pas dans le glob. Sinon, l'ensemble se réduirait aux noms de `_MODULE` sans signal. Au HEAD, ce repli passerait inaperçu, puisque `_MODULE` voit déjà les 26 règles. Le contrôle s'écrit dans un cas à part (`test_the_rule_modules_are_found_on_disk`), testé par une mutation sur copie : dossier sans `module_boundary_*.py` accessible par le glob, glob dirigé vers un dossier vide.

## Windows et Linux

Bibliothèque standard ; `pathlib.Path.glob` trié, pour un ordre identique quelle que soit la casse ou l'ordre du système de fichiers ; aucune branche de plateforme. Le job `invariants` tourne sous Ubuntu ; l'auto-test se rejoue aussi sur le poste Windows (69 tests, environ 20 s).

## Vérification

- Rouge : mutations 1 et 2 sur copie, auto-test au HEAD vert (trou reproduit). Puis, avec la nouvelle `rule_functions()`, les mêmes copies rendent rc=1 en nommant `check_extra_rule`.
- Rouge du repli (D2) : copie où le glob ne trouve rien → rc=1.
- Correction : auto-test vert sur le dépôt, même nombre de sous-tests de câblage (26 `check_*` et `runtime_service_edges`), garde inchangé.
- Gates : voir `proposal.md`, Impact.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve attendue | Preuve manquante à ce stade |
|---|---|---|---|---|---|
| AUD-TST-14 | frontieres-de-modules › Le point d'entrée du garde exécute chacune de ses règles | D1, D2 | 1.1-1.4 | 2026-11-25, copie `git archive` : au HEAD, variantes « non importé » et « importé comme module » garde 0 / auto-test 0 (69 tests), variante `from … import` auto-test 1 ; avec la nouvelle `rule_functions()`, les trois variantes rendent 1 en nommant `check_extra_rule`, glob vide (`RULE_MODULE_GLOB` muté) rend 1 (« cannot be derived ») ; dépôt vert (70 tests, 27 `check_*` dérivées avant et après) ; garde, quatre autres auto-tests, `test_gate_runner.py`, `check-ci-wiring.py`, `check-p0-p2.py`, `check-comment-language.py`, `run_gates.py` verts ; durée de l'auto-test sur le poste : environ 17 s avant et après | durée en CI |

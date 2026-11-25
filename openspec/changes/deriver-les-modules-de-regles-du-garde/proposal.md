# Proposal

## Why

Constat **AUD-TST-14** de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouvert le 2026-11-25). Le sprint 10 (AUD-TST-13, changement archivé `prouver-le-cablage-de-toutes-les-regles-du-garde`) a fait dériver la liste des règles attendues par `MainWiringTest` des fonctions `check_*`, au lieu de la recopier. Mais l'ensemble des modules où chercher ces fonctions reste un tuple écrit à la main : `(_MODULE, BOUNDARY_POLICY, ADAPTERS, RATCHETS, PACKAGES, DOCUMENTS)`, dans `scripts/architecture/test_check_module_boundaries.py:467`. Or la docstring du fichier et celle de `MainWiringTest` affirment que chaque `check_*` « of its rule modules » est dérivée.

Sur une copie, un septième module `module_boundary_extra.py` définit `check_extra_rule()`, que personne n'appelle. S'il n'est pas importé par le garde, ou s'il l'est comme module (`import module_boundary_extra`), le garde et ses 69 auto-tests restent verts. La garantie d'AUD-TST-13 ne vaut donc que pour les six modules déjà connus.

## What Changes

- `rule_functions()` (`scripts/architecture/test_check_module_boundaries.py`) dérive les modules de règles des fichiers `scripts/architecture/module_boundary_*.py` présents sur le disque. Elle les importe sous leur nom, donc les mêmes objets que ceux du garde, en plus du script d'entrée. Il n'y a plus de tuple écrit à la main.
- Un module de règles nouveau entre d'office dans `EveryRuleHasATest` et dans `MainWiringTest`. Une `check_*` que `main()` ne peut pas atteindre fait échouer l'auto-test en la nommant, comme pour les six modules actuels.
- Fail-closed : si le glob ne trouve aucun fichier, ou ne retrouve pas un module que le garde importe, l'auto-test échoue au lieu de se contenter du script d'entrée.
- Les docstrings de `test_check_module_boundaries.py`, de `MainWiringTest` et de `module_boundaries_testing.py` disent comment les modules sont trouvés.
- L'exigence « Le point d'entrée du garde exécute chacune de ses règles » est modifiée : elle exige la découverte des modules de règles sur le disque et gagne un scénario de module nouveau non câblé.

Aucune règle du garde, aucune sortie du garde, aucun code Java ni aucune surface publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : une exigence modifiée, « Le point d'entrée du garde exécute chacune de ses règles ».

## Hors périmètre

- Les règles du garde et `check-module-boundaries.py` : A11 et le `test-jar` relèvent du changement n°1, `tenir-le-contrat-de-la-facade-par-le-garde`.
- La documentation du garde (`docs/developer/quality-gates.md`, dossier de capacité) : changement n°4, `actualiser-le-dossier-des-frontieres-de-modules`. Ce changement-ci n'ajoute aucun fichier : la liste des fichiers nommés ne bouge pas.
- Les noms que les tests utilisent pour patcher une table (`BOUNDARY_POLICY`, `ADAPTERS`, `RATCHETS`, `PACKAGES`, `DOCUMENTS` dans `module_boundaries_testing.py:29-33`) : ce sont des cibles de patch, pas l'ensemble des modules contrôlés. Ils restent tels quels.
- Le garde `check-private-io.py`, qui partage `gate_runner.py` mais n'a pas de modules de règles.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Tuple de modules écrit à la main dans `rule_functions()` | [Existant vérifié] | `scripts/architecture/test_check_module_boundaries.py:465-469` |
| Les docstrings affirment la dérivation de « every check_* function of the gate and of its rule modules » | [Existant vérifié] | `test_check_module_boundaries.py:5-9`, `:485-492` |
| Fichiers du dossier : `module_boundary_{adapters,documents,packages,policy,ratchets,sources}.py`, `module_boundaries_testing.py`, `gate_runner.py`, `check-module-boundaries.py`, `check-private-io.py`, auto-tests | [Existant vérifié] | `ls scripts/architecture/` |
| `module_boundary_sources.py` n'est pas dans le tuple et ne définit aucune `check_*` ; `module_boundary_policy.py` est dans le tuple et n'en définit aucune | [Existant vérifié] | `grep -n "^def check_" scripts/architecture/module_boundary_*.py` |
| Le glob `module_boundary_*.py` n'attrape ni `module_boundaries_testing.py` (pluriel), ni `gate_runner.py`, ni les auto-tests | [Existant vérifié] | noms de fichiers |
| Mutation reproduite : module non importé, ou importé comme module → garde rc=0, auto-test rc=0 (69 tests) ; importé par `from … import check_extra_rule` sans appel → auto-test rc=1 (3 échecs) | [Existant vérifié] | design, « Mutations rejouées » |
| Prototype de dérivation par glob sur copie : vert sur la copie saine, rouge sur les deux mutations silencieuses | [Existant vérifié] | design, « Mutations rejouées » |
| Dérivation par glob et `importlib.import_module`, avec contrôle fail-closed | [Proposition] | design, D1, D2 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé.
- **Workflow** : aucun changement (l'auto-test est déjà lancé par le job `invariants`, `.github/workflows/pr-ci.yml:164-165`).
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, les cinq `test_check_module_boundaries*.py`, `scripts/architecture/test_gate_runner.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-comment-language.py` (docstrings en anglais), `scripts/remediation/check-p0-p2.py` (lit le garde), `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : Windows et Linux ; bibliothèque standard, `pathlib`, glob trié pour un ordre identique sur les deux systèmes.
- **Documentation durable** : dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, ligne de traçabilité de l'exigence modifiée (preuve du nouveau scénario), à l'archivage.

## Questions ouvertes

Aucune bloquante : D1 et D2 ont une recommandation.

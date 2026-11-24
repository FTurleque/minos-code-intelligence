# Proposal

## Why

Constat **AUD-TST-13** de l'audit `docs/project/audits/2026-11-15/` (sprint 10, sévérité faible, ouvert le 2026-11-20 au commit `535a5017`). La docstring de l'auto-test du garde de frontières annonce que `main()` « run every rule (failing spies) ». En réalité, `MainWiringTest` n'espionne que 13 des 22 fonctions `check_*` appelées directement par `run_rules`, et aucune des quatre règles de POM que `check_pom_rules` appelle. Sur une copie, dix règles perdent leur appel sans qu'aucun contrôle rougisse : huit appels directs, dont A8, A9, A16, A20 et A19, une règle que le sprint 9 vient de durcir (AUD-ARC-11), et deux règles de POM. Une erreur de règle avalée dans `run_rules`, ou une fonction `check_*` nouvelle jamais appelée, passent tout aussi inaperçues. `EveryRuleHasATest` exige seulement qu'un test référence chaque `check_*`, pas que `main()` l'exécute. Enfin, le lanceur partagé `gate_runner.py`, extrait par la PR #804 et utilisé par deux gardes, n'a pas d'auto-test propre. Sa docstring affirme que l'auto-test de `check-private-io.py` l'exerce : c'est faux.

## What Changes

- `MainWiringTest` (`scripts/architecture/test_check_module_boundaries.py`) dérive la liste des règles de toutes les fonctions `check_*` du garde et de ses modules de règles, au lieu de la recopier. Pour chacune, il prouve qu'un espion qui échoue rend `main()` rouge. Une règle nouvelle entre d'office dans la vérification ; une règle que `main()` n'exécute pas fait échouer l'auto-test en la nommant. Une liste d'exemptions fermée et justifiée est prévue ; elle est vide à la livraison.
- Les sous-tests rejouent les résultats réels des autres règles, enregistrés lors d'une seule exécution complète, pour que la durée de l'auto-test ne croisse pas avec le nombre de règles (design, D2).
- Un auto-test `scripts/architecture/test_gate_runner.py` prouve les trois comportements de `run_gate` (rapport et code 0, `<label> FAILED: <message>` et code 1, interruption non convertie en succès). Il est exécuté par le job `invariants` de `pr-ci.yml`, comme `check-ci-wiring.py` l'exige de tout `test_*.py`.
- Les docstrings de `test_check_module_boundaries.py`, de `MainWiringTest` et de `gate_runner.py` disent ce que les tests prouvent réellement.
- L'exigence « Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté » nomme les cinq auto-tests et les modules de règles du garde découpé (PR #802 à #804).

Aucun code Java, aucune règle du garde, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : une exigence modifiée (couverture des règles par les cinq auto-tests) et deux exigences ajoutées : le point d'entrée du garde exécute chacune de ses règles ; le lanceur partagé a son propre auto-test.

## Hors périmètre

- Les règles elles-mêmes (`module_boundary_*.py`, `check-module-boundaries.py`) et leurs messages : aucun comportement du garde ne change.
- La prose d'architecture qui décrit le garde découpé (dossier de capacité, ARCH-0058, ARCH-0068, `docs/developer/quality-gates.md`) : constat AUD-ARC-12, changement `decrire-le-garde-de-frontieres-decoupe`. Ce dernier laisse expressément à AUD-TST-13 l'exigence modifiée ici.
- Le câblage de `main()` de `check-private-io.py` au-delà du lanceur partagé (`run_checks`, option `--inventory`) : aucun constat ne le vise. Le design le cite comme bénéficiaire.
- Les écarts E1 à E9 du dossier de capacité, et sa question ouverte sur le libellé de l'étape CI.
- Les autres constats du sprint 10 (AUD-ARC-12, AUD-SEC-11, AUD-PERF-15).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| La docstring annonce que `main()` exécute chaque règle (espions qui échouent) | [Existant vérifié] | `scripts/architecture/test_check_module_boundaries.py:5-6` |
| `MainWiringTest` espionne 13 fonctions | [Existant vérifié] | `test_check_module_boundaries.py:442-484` |
| `run_rules` appelle 22 fonctions `check_*`, plus `runtime_service_edges` ; `check_pom_rules` appelle 4 autres `check_*` ; 26 `check_*` au total | [Existant vérifié] | `scripts/architecture/check-module-boundaries.py:271-279`, `:291-317` ; énumération par AST et `dir()` (design) |
| Huit appels directs et deux règles de POM (`check_pom_layout`, `check_no_hidden_internal_dependencies`) peuvent être retirés sans qu'aucun contrôle rougisse ; A2 tient par `PomRulesOnRealPomsTest` | [Existant vérifié] | mutations rejouées sur une copie le 2026-11-24 (design, « Mutations rejouées ») |
| Les quatre autres fichiers de test n'appellent jamais `main()` ni `run_rules` | [Existant vérifié] | recherche de `.main()`, `run_rules`, `run_checks`, `subprocess` dans `test_check_module_boundaries_*.py` : aucune occurrence |
| `EveryRuleHasATest` ne vérifie que la présence d'une référence textuelle | [Existant vérifié] | `test_check_module_boundaries.py:401-412` |
| `gate_runner.py` n'a pas d'auto-test ; l'auto-test de `check-private-io.py` n'appelle ni `main()` ni `run_gate` | [Existant vérifié] | `scripts/architecture/gate_runner.py:3-5` ; `scripts/architecture/test_check_private_io.py` (aucune occurrence de `main(` ni `run_gate`) |
| `check-ci-wiring.py` exige que tout `scripts/**/test_*.py` soit lancé par le job `invariants` | [Existant vérifié] | `scripts/quality/check-ci-wiring.py:306-336` |
| Liste des règles dérivée de `dir()` des six modules, avec exemptions fermées | [Proposition] | design, D1 |
| Rejeu des résultats enregistrés pour les règles qui ne sont pas sous test | [Proposition] | design, D2 |
| Auto-test dédié `test_gate_runner.py` | [Proposition] | design, D3 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé. ARCH-0068, § « Compliance and Enforcement », reste exact (« ses auto-tests […] exécutés par le même job »).
- **Workflow** : une étape ajoutée au job `invariants` de `.github/workflows/pr-ci.yml` (`python scripts/architecture/test_gate_runner.py -v`).
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, les cinq `test_check_module_boundaries*.py`, `test_gate_runner.py`, `test_check_private_io.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-single-execution.py`, `scripts/remediation/check-audit-remediation-v2.py`, `scripts/remediation/check-p0-p2.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : Windows et Linux. Scripts Python de la bibliothèque standard, sans branche de plateforme.
- **Documentation durable** : `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (traçabilité des deux exigences ajoutées et de l'exigence modifiée, à l'archivage).

## Questions ouvertes

Aucune bloquante : D1 à D3 ont une recommandation.

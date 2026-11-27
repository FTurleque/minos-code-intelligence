# Proposal

## Why

Constat **AUD-ARC-17** de l'audit `docs/project/audits/2026-11-15/` (sprint 12, sévérité faible, ouvert le 2026-11-27 au commit `4f4cac81`). La règle A11 du garde de frontières refuse une méthode publique de `MinosApplication` qui rend ou prend un type de `minos-engine` (cliquet à zéro, AUD-ARC-01, -02, -13). Elle repère les méthodes par l'expression `PUBLIC_METHOD` (`scripts/architecture/module_boundary_ratchets.py:129-130`), qui exige que le type de retour suive directement les modificateurs. Une méthode générique (`public <T> …`) n'est donc lue ni pour son retour ni pour ses paramètres. Rejoué le 2026-11-27 sur des copies du HEAD : `public <T> void leakGeneric(com.minos.registry.ProjectRegistry r)`, `public <T> com.minos.registry.ProjectRegistry leakRet()` et `public <T extends com.minos.registry.ProjectRegistry> void leakBound(T r)` laissent `check-module-boundaries.py` à 0 ; la même fuite sans paramètre de type le fait échouer.

## What Changes

- `module_boundary_ratchets.py` : `PUBLIC_METHOD` (ou une lecture équivalente) reconnaît une section de paramètres de type, imbrications comprises, entre les modificateurs et le type de retour, pour les méthodes et les constructeurs publics. Les types du moteur nommés dans les bornes de cette section comptent comme rendus et pris par la méthode (design, D1).
- A11 refuse aussi une méthode statique publique qui rend un type du moteur : au HEAD, `public static com.minos.registry.ProjectRegistry leak()` passe (design, écart 4 et D3).
- A11 refuse aussi un import joker (`import com.minos.<package>.*;`) d'un package de `minos-engine` dans `MinosApplication` : un type ainsi importé n'est pas résolu et passe la règle (design, écart 2 et D2).
- L'auto-test `scripts/architecture/test_check_module_boundaries_ratchets.py` reçoit les variantes génériques (retour, paramètre, borne, borne imbriquée, statique, constructeur) et l'import joker, refusées, et un cas générique sans type du moteur, accepté.
- La docstring de la règle, `docs/developer/quality-gates.md` (puce « A11, accesseurs de `MinosApplication` ») et le dossier `frontieres-de-modules.md` nomment ces formes.

Aucun code Java, aucun workflow, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change. `MinosApplication` au HEAD ne déclare aucune méthode générique publique ni aucun import joker : la règle reste verte sur le dépôt.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : une exigence ajoutée (une méthode générique ou un import joker de la façade ne contourne pas A11).

## Hors périmètre

- Le `Builder` imbriqué et les services publics (`ProjectInspectionService.statusView(RegisteredProject)`, `view(RegisteredProject)`) : hors règle par décision du sprint 11 (réserve d'AUD-ARC-13).
- Une règle ArchUnit sur les signatures effacées du bytecode : option écartée (design, D1, option b).
- Le rapport du garde, qui n'imprime qu'un compteur A11 (réserve d'AUD-ARC-13, point 3) et les fonctions `print_*_report` non contrôlées (réserve d'AUD-TST-13).
- Les autres consommateurs de `PUBLIC_METHOD` hors A11 : relevés en tâche 1.1, non élargis sans constat.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `PUBLIC_METHOD` exige le type de retour juste après les modificateurs | [Existant vérifié] | `scripts/architecture/module_boundary_ratchets.py:129-130` |
| Retours (`public_instance_methods`) et paramètres (`public_signatures`) reposent sur cette expression | [Existant vérifié] | `module_boundary_ratchets.py:134-157`, `:240-247`, `:251-277` |
| Les imports joker sont exclus de la table `imported` ; un nom simple importé ainsi n'est pas résolu | [Existant vérifié] | `module_boundary_ratchets.py:235-236`, `:261-262` |
| Trois variantes génériques donnent rc=0, la variante simple rc=1 | [Existant vérifié] | mutations rejouées le 2026-11-27 (design) |
| Import joker + paramètre de ce package : rc=0 | [Existant vérifié par l'audit, non rejoué] | second vérificateur de la fiche AUD-ARC-17 |
| Aucune variante générique dans l'auto-test | [Existant vérifié] | `test_check_module_boundaries_ratchets.py:376-396` |
| Retour statique non générique typé moteur : rc=0 ; statique générique : rc=1 par lecture accidentelle | [Existant vérifié] | contre-vérification du 2026-11-27 sur copie |
| Bornes lues comme types de la méthode ; import joker refusé | [Proposition] | design, D1 et D2 |

## Impact

- **Modules du reactor** : aucun code de production.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé. ARCH-0087 (« Compliance and Enforcement ») attend déjà une mise à jour au titre du sprint 11 (tâche 4.2, décision D5) ; ce changement lui ajoute une ligne à proposer dans la même tâche manuelle.
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, ses cinq auto-tests `scripts/architecture/test_check_module_boundaries*.py`, `scripts/architecture/test_gate_runner.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-comment-language.py`, `scripts/docs/check-capability-dossiers.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : le garde tourne sous Ubuntu (job `invariants`) et sur le poste Windows (bibliothèque standard) ; lecture de texte sans dépendance au système.
- **Documentation durable** : `docs/developer/quality-gates.md`, `docs/architecture/diagrams/capacites/frontieres-de-modules.md`.

## Questions ouvertes

Aucune bloquante : D1 à D3 ont une recommandation.

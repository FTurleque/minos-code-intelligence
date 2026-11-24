# Proposal

## Why

Constat **AUD-ARC-12** de l'audit du 2026-11-15 (sprint 10, faible) : la prose d'architecture décrit encore le garde de frontières tel qu'il était avant la source unique de sa politique (PR #791) et avant son découpage (PR #802 à #804).

- Le dossier de la capacité `frontieres-de-modules` situe `ALLOWED_DEPENDENCIES` aux lignes 59-85 de `check-module-boundaries.py`, qui compte 414 lignes, et annonce « une copie identique » dans `ModuleArchitectureTest`, que la règle A17 refuse.
- ARCH-0068 et ARCH-0058 placent les tables du garde (liste `MODULES`, `ALLOWED_DEPENDENCIES`, `PACKAGE_ROOTS_BY_MODULE`) « dans le script » et décrivent A17 comme une égalité entre deux tables.
- Aucun document ne dit que le garde est un script d'entrée, six modules de règles et le lanceur partagé `gate_runner.py`, ni où vit chaque règle.

Un lecteur cherche une table à une ligne qui n'existe plus, croit à une double copie supprimée, et ne sait pas dans quel fichier modifier une règle. Les gates restent verts : `check-current-docs.py` ne lit pas ces passages.

## What Changes

- Le chapitre « Gardes d'architecture » de `docs/developer/quality-gates.md` nomme chaque fichier du garde et les règles qu'il porte, le lanceur `gate_runner.py`, les fixtures `module_boundaries_testing.py` et les cinq auto-tests ; il situe la politique dans `scripts/architecture/module-boundaries.json`.
- Le dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` ne cite plus de ligne du script : il renvoie à la section `allowedDependencies` de la politique et à la règle A17 (aucune copie), et ses écarts E1, E4 et E9 citent le module qui porte la règle ; E9, levé par la source unique, est clos.
- ARCH-0058 et ARCH-0068 (`Accepted`) : les puces de `Compliance and Enforcement` décrivent les contrôles tels qu'ils s'exécutent ; une note datée de `Mise en œuvre` situe les tables ; la décision n'est pas réécrite. ARCH-0087 (`Proposed`) situe ses deux listes fermées dans la politique.
- Le skill `minos-literal-gates` signale que les chaînes du garde vivent aussi dans ses modules et dans la politique JSON.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : deux exigences ajoutées (aucune ligne du script citée par la documentation courante ; fichiers du garde nommés par la documentation des gates).

## Hors périmètre

- Le code du garde, de ses auto-tests et des règles Archgate, y compris les commentaires « du script » de `ARCH-0068-…rules.ts:42` et `:49`, exacts depuis que la constante `SCRIPT` désigne `module_boundary_ratchets.py`.
- L'exigence « Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté » (`openspec/specs/frontieres-de-modules/spec.md:8-9`), qui nomme le seul `test_check_module_boundaries.py` : elle est le terrain d'AUD-TST-13 (design, D3).
- Les mentions qui désignent `check-module-boundaries.py` comme point d'entrée du gate (environ quarante, ADR, specs, règles, skills) : elles restent exactes.
- Les documents figés (`docs/project/history/`, `docs/project/audits/`, `openspec/changes/archive/`, `docs/project/roadmap/`) et `docs/architecture/SYNTHESE.md`, déclaré historique.
- Un gate documentaire contre la récidive (design, D2 : proposé, non retenu par défaut).
- Les autres constats du sprint 10 (AUD-TST-13, AUD-SEC-11, AUD-PERF-15).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le dossier cite `check-module-boundaries.py:59-85` et une copie identique dans `ModuleArchitectureTest` | [Existant vérifié] | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:71-72` |
| Le script compte 414 lignes ; `ALLOWED_DEPENDENCIES` est dérivé de la politique JSON | [Existant vérifié] | `wc -l scripts/architecture/check-module-boundaries.py` ; `scripts/architecture/module_boundary_policy.py:23`, `:219` |
| A17 refuse toute table littérale dans `ModuleArchitectureTest` | [Existant vérifié] | `scripts/architecture/module_boundary_documents.py:195-202` ; `docs/developer/quality-gates.md:79` |
| ARCH-0068 décrit « la liste `MODULES` du script » et une table « lue dans le script » | [Existant vérifié] | `docs/architecture/adr/ARCH-0068-modules-dependances-et-propriete-des-packages.md:168`, `:183`, `:185` ; `ARCH-0068-…rules.ts:3-10` |
| Aucun document ne décrit les modules du garde | [Existant vérifié] | `git grep -n "module_boundary_\|gate_runner" -- docs openspec/specs` : seule `ARCH-0068-…rules.ts` répond |
| Corriger `Compliance and Enforcement` sans réécrire la décision | [Proposition] | design D3 |

## Impact

- **Modules du reactor** : aucun. Aucun code, test, workflow ni gate modifié.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé. ARCH-0058 et ARCH-0068 (`Accepted`) reçoivent une note de mise en œuvre et des puces de contrôle exactes ; ARCH-0087 (`Proposed`) une précision ; tâches planifiées, décisions inchangées.
- **Gates à rejouer** : `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`, `scripts/docs/check-capability-dossiers.py`, `scripts/quality/check-comment-language.py`, `archgate check` (registre d'ADR, règles ARCH-0058 et ARCH-0068), `python .claude/scripts/run_gates.py --fast`, `openspec validate --all --strict`. Aucun scope JaCoCo.
- **Plateformes** : sans objet (documentation).
- **Documentation durable** : `docs/developer/quality-gates.md`, `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, trois ADR, `.claude/skills/minos-literal-gates/SKILL.md`.

## Questions ouvertes

Aucune bloquante. D2 (gate contre la récidive), D3 (forme de la correction des ADR acceptés) et D4 (ARCH-0087) portent une recommandation dans le design.

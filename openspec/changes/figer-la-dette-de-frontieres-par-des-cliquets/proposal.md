# Proposal

## Why

Le sprint 10 de l'audit du 10 octobre 2026 (« Frontières d'architecture ») regroupe des chantiers longs déjà décidés par les ADR 0055 à 0058 mais **non commencés**, et dont les prérequis (sprints 4, 8 et 9) ne sont pas implémentés. En attendant, rien n'empêche la dette de **croître** : les gardes raisonnent au grain du module, et l'arête est autorisée.

Relevé au HEAD `949e1569` :

- **AUD-ARC-01** : les surfaces importent 102 fois le moteur dans 30 fichiers (`minos-cli` 76 imports et 52 types distincts, `minos-api` 16, `minos-nexus` 6, `minos-mcp` 4). L'ADR 0058 § 3 dit qu'un nouvel usage « demande justification » ; aucun garde ne le vérifie : un 53e type du moteur importé par la CLI passe.
- **AUD-ARC-05** : `minos-engine` lance un processus (`PrivateLocalStorage.java:634-635`, `icacls.exe`) alors que l'ADR 0022 réserve l'exécution de processus au runtime local. C'est le seul site, et rien n'en interdit un second.
- **AUD-ARC-08** : `minos-storage-postgresql` nomme quatre types de `minos-storage-local` (5 imports dans 3 fichiers, plus deux usages en nom qualifié que l'audit n'a pas comptés) et `minos-provider-scip` instancie `FileSymbolSnapshotStore` quatre fois. Rien n'interdit un type de plus.
- **AUD-ARC-12** : 23 racines de packages `com.minos.*` ne disent pas leur module ; une nouvelle racine peut apparaître dans n'importe quel module.
- **AUD-ARC-11** : `minos-cli` dépend de `minos-app` à l'exécution (service `McpLaunchRoute`), arête absente du document de dépendances généré.

Ce changement **fige l'existant** par des listes fermées à cliquet, sur le modèle des règles A8 (cycles) et A10 (types exportés de `minos-runtime-local`) : sans déplacer une seule classe, donc sans coût de couverture ni de gates littéraux.

## What Changes

- **Règle A11** : liste fermée, par surface, des types de `minos-engine` qu'elle nomme ; elle ne peut que rétrécir.
- **Règle A12** : liste fermée des classes de `minos-engine`, `minos-application` et `minos-domain` qui lancent un processus (aujourd'hui : `PrivateLocalStorage`).
- **Règle A13** : liste fermée des types d'un adaptateur nommés par un autre adaptateur.
- **Règle A14** : table des racines de packages autorisées par module, figée sur l'existant.
- **Arêtes d'exécution** : le document de dépendances généré montre les arêtes par service (`minos-cli` → `minos-app`) et son contrôle échoue si l'une manque.
- Chaque règle a un cas refusé et un cas accepté dans l'auto-test du garde.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : cinq exigences ajoutées (une par règle, plus les arêtes d'exécution documentées).

## Hors périmètre

- **Les chantiers eux-mêmes** : cas d'usage d'indexation hors de la CLI (SH-05, après AUD-ARC-02), extraction du mécanisme d'ACL et sort de `hosted` (SH-08, après AUD-SEC-06), port de lecture des snapshots et ADR 0047 (SH-04, après AUD-PERF-03), codec commun et staging injecté (SH-02, SH-03, SH-06). Ils déplacent des classes et seront spécifiés quand leurs prérequis seront livrés ; voir `trancher-les-decisions-d-architecture-du-sprint-10`.
- **AUD-ARC-07** : aucun cliquet utile (deux sites de reconstruction, liés à une décision d'ADR).
- Renommer un package, déplacer une classe, réduire une liste : ce changement ne modifie aucun fichier de `src/main`.
- Une règle ArchUnit équivalente : écartée, voir `design.md` (D1).

## Impact

- **Modules du reactor** : aucun code de production. `scripts/architecture/check-module-boundaries.py`, `scripts/architecture/test_check_module_boundaries.py`, document généré `docs/architecture/diagrams/module-dependencies.md` (par `--write-doc`), `docs/architecture/arc42/05` si la vue cite les arêtes.
- **Surfaces publiques** : aucune.
- **ADR** : aucun nouvel ADR. Donne un garde à l'ADR 0058 § 3 et à l'ADR 0022 (note de mise en œuvre) ; applique l'ADR 0057 § 7 (gardes au grain classe).
- **Gates à rejouer** : `python scripts/architecture/check-module-boundaries.py`, `python scripts/architecture/test_check_module_boundaries.py` (règle « chaque règle a un test »), `python scripts/quality/check-ci-wiring.py`, `python scripts/docs/check-current-docs.py`, `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : garde Python du job `invariants` (Ubuntu), exécutable sur le poste Windows.
- **Constats bénéficiaires** : AUD-ARC-01, -05, -08, -11, -12 (dette figée, non résorbée).

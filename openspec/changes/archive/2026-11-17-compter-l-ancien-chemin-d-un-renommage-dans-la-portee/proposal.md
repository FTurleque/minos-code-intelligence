# Proposal

## Why

Constat **AUD-DEP-08** (Faible, sprint 5 de l'audit du 2026-11-15, relevé le 2026-11-17) : la portée calculée de la qualification Docker A → B lit le diff par `git diff --name-only`, qui ne donne que le **nouveau** chemin d'un renommage (`scripts/ci/plugin-gate.py:58`, repris par `scripts/ci/docker-gate.py:105`). Déplacer une classe de production vers `src/test/` ou `docs/` rend la pull request « non concernée » : la qualification est sautée et le check exigé `Docker upgrade evidence gate` passe, alors que la classe quitte l'image. La règle annoncée est l'inverse (« tout doute répond concerné »). Le plugin IntelliJ partage la même fonction et le même défaut : déplacer un rendu JSON hors de `minos-application/src/main/java/com/minos/output/` le laisse hors portée.

## What Changes

- La lecture du diff des deux portées (`changed_files` de `plugin-gate.py`, chargée par `docker-gate.py`) passe `--no-renames` : un renommage compte comme la suppression de l'ancien chemin et l'ajout du nouveau, et les deux chemins entrent dans la portée.
- Les auto-tests des deux gates rejouent, dans un dépôt Git temporaire, un renommage `src/main` → `src/test`, `src/main` → `docs/` (Docker) et un rendu déplacé vers `docs/` (plugin) ; un renommage interne à la liste d'exclusion reste hors portée.

Aucune rupture : une pull request sans renommage a la même portée qu'au HEAD.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `controles-de-fusion` : une exigence ajoutée (les deux chemins d'un renommage comptent dans une portée calculée). Les exigences de portée du plugin et de la qualification Docker ne changent pas d'énoncé.

## Hors périmètre

- La liste fermée d'exclusion de la qualification Docker et les chemins du plugin : inchangés.
- La décision D1 du changement `borner-la-qualification-docker-aux-pr-concernees` et la réévaluation de `DOCKER_LOGIN_KEPT` : restent ouvertes.
- La détection des copies (`git diff` ne les détecte pas par défaut ; une copie ajoute un chemin, déjà compté).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| La portée lit `git diff --name-only <range>` | [Existant vérifié] | `scripts/ci/plugin-gate.py:51-62` (HEAD `fc1002e0`) |
| La portée Docker charge cette fonction | [Existant vérifié] | `scripts/ci/docker-gate.py:37-41`, `:105` |
| `src/test/` et `docs/` sont exclus de la portée Docker | [Existant vérifié] | `scripts/ci/docker-gate.py:43-51` |
| Un renommage `src/main` → `src/test` ou `docs/` rend la portée `false` | [Existant vérifié] | tests rouges écrits pour la tâche 1.1 (`test_docker_gate.py`, `test_plugin_gate.py`), échec au HEAD |
| Aucun auto-test ne couvrait un renommage | [Existant vérifié] | `grep -n renam scripts/ci/test_docker_gate.py scripts/ci/test_plugin_gate.py` : 0 au HEAD |

## Impact

- **Modules du reactor** : aucun (scripts de CI).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/ci/test_docker_gate.py`, `python scripts/ci/test_plugin_gate.py`, `python scripts/quality/check-ci-wiring.py`, `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : script exécuté sur `ubuntu-24.04` en CI ; auto-tests exécutés sous Windows et Linux (Git seul).
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/controles-de-fusion.md` (prose).

## Questions ouvertes

Aucune.

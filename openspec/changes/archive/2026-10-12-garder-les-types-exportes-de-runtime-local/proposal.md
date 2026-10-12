# Proposal

## Why

Le rangement de `minos-runtime-local` en sous-packages (PR #416) a rendu publics 13 types et 22 membres pour qu'ils se voient entre `process`, `sandbox` et `workspace`. Java n'a pas de visibilité « module » sans JPMS : ces primitives de confinement et d'environnement (`ProviderProcessEnvironment`, `ProviderWorkspaceFiles`, `LinuxCgroupJob`, `ProcessSandboxCapableIndexerExecutor`…) sont devenues appelables par tout module qui dépend de `minos-runtime-local`. La relecture de sécurité de la PR #416 a relevé que l'invariant « seul le runtime remplace la frontière de propriété d'une exécution » n'est plus tenu que par une Javadoc. Le propriétaire a retenu, le 12 octobre 2026, de le faire tenir par un gate.

## What Changes

- Règle **A10** dans `scripts/architecture/check-module-boundaries.py` : hors de `minos-runtime-local`, les sources de production et de test ne nomment que les types de `process`, `sandbox` et `workspace` inscrits dans une liste fermée, `RUNTIME_LOCAL_EXPORTED_TYPES` (les 11 types réellement utilisés aujourd'hui).
- L'import par joker d'un package gardé est refusé, ainsi que l'usage des membres réservés `ProcessPlanTransformer` et `executeSandboxed`.
- La liste est un cliquet : une entrée sans fichier, ou qu'aucun autre module ne nomme plus, échoue.
- Aucun code de production ne change.

## Capabilities

### Modified Capabilities
- `frontieres-de-modules` : une exigence ajoutée sur les types de `minos-runtime-local` nommables par les autres modules.

## Hors périmètre

- Introduire JPMS (`module-info`).
- Restreindre les packages `distributed` et racine de `minos-runtime-local`.
- Appliquer la même règle aux autres modules rangés (`minos-cli`, `minos-storage-postgresql`, `minos-provider-scip`) : aucun autre module n'importe leurs sous-packages, sauf `minos-app` pour `FindSymbolCommand`.
- Filtrer les variables déclarées dans `ProviderProcessEnvironment.sanitize` (changement `epingler-et-isoler-l-installation-des-providers`).

## Impact

- Modules : aucun code Java modifié. Scripts : `check-module-boundaries.py` et son auto-test.
- Surfaces publiques (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- ADR : aucun créé ni amendé ; la règle met en œuvre la direction des dépendances de l'ADR 0022 et le principe de la racine de composition de l'ADR 0042 au niveau des types.
- Gates à rejouer : `check-module-boundaries.py`, `test_check_module_boundaries.py`, `check-ci-wiring.py`, `check-single-execution.py`, `check-p0-p2.py` (qui cite le gate), `check-current-docs.py`, `product-facts.py --check`.
- Documentation : `docs/developer/quality-gates.md`, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/architecture/arc42/05-vue-blocs.md`, règles `.claude/`.
- Plateformes : Ubuntu et Windows, sans différence (script Python, bibliothèque standard).

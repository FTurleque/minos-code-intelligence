# MINOS — Facts produit générés

> Ce fichier est généré depuis les sources par `scripts/docs/product-facts.py`.
> Ne pas modifier manuellement.

## Versions

- version Maven : `0.2.0-SNAPSHOT`
- contrat API Java : `v1`

## Catalogue MCP

Nombre de tools : **26**

- `minos_project_structure`
- `minos_index_status`
- `minos_search_code`
- `minos_find_symbols`
- `minos_find_usages`
- `minos_find_implementations`
- `minos_find_callers`
- `minos_find_callees`
- `minos_dependencies`
- `minos_dependents`
- `minos_related_tests`
- `minos_symbol_context`
- `minos_module_context`
- `minos_architecture`
- `minos_architecture_graph`
- `minos_impact`
- `minos_program_graph`
- `minos_impact_v2`
- `minos_security_paths`
- `minos_semantic_index_status`
- `minos_semantic_search`
- `minos_hybrid_search`
- `minos_hybrid_context`
- `minos_runtime_sessions`
- `minos_runtime_report`
- `minos_runtime_symbol`

## Commandes CLI

- `project add`
- `project list`
- `project inspect`
- `inspect`
- `index`
- `import-scip`
- `index-status`
- `remote`
- `runtime`
- `doctor`
- `tools list`
- `tools install`
- `tools verify`
- `providers`
- `search`
- `find-symbol`
- `get-source`
- `find-usages`
- `find-implementations`
- `find-callers`
- `find-callees`
- `dependencies`
- `dependents`
- `related-tests`
- `architecture`
- `impact`
- `ide handshake`
- `git-activity`
- `nexus-export`

## Providers qualifiés

### `scip-java` `0.13.1`

Capabilities : `IMPLEMENTATION_RELATIONS`, `MULTI_MODULE`, `REFERENCES`, `RUNTIME_INSTALLATION`, `STABLE_SYMBOL_IDENTITY`, `SYMBOLS`, `TEST_SOURCES`

### `scip-typescript` `0.4.0`

Capabilities : `MULTI_MODULE`, `PARTIAL_INDEX_ON_BUILD_FAILURE`, `REFERENCES`, `RUNTIME_INSTALLATION`, `STABLE_SYMBOL_IDENTITY`, `STRUCTURAL_RELATIONS`, `SYMBOLS`, `TEST_SOURCES`

### `scip-python` `0.6.6`

Capabilities : `REFERENCES`, `RUNTIME_INSTALLATION`, `SYMBOLS`, `TEST_SOURCES`

## Formats calculables

- formats symboles : `text`, `json`
- formats architecture : `text`, `json`, `mermaid`, `dot`

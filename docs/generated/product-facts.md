# MINOS — Facts produit générés

> Ce fichier est généré depuis les sources par `scripts/docs/product-facts.py`.
> Ne pas modifier manuellement.

## Versions

- version Maven : `1.2.0-SNAPSHOT`
- contrat API Java : `v1`

## Catalogue MCP

Nombre de tools : **31**

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
- `minos_team_tenant`
- `minos_team_workspaces`
- `minos_team_workspace`
- `minos_team_members`
- `minos_team_audit`

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
- `semantic status`
- `hybrid status`
- `ide handshake`
- `git-activity`
- `nexus-export`
- `team`

## Providers qualifiés

### `scip-java` `0.13.1`

Disposition : `QUALIFIED_WITH_CONSTRAINTS`

Langages : `JAVA`, `KOTLIN`

Capabilities : `IMPLEMENTATION_RELATIONS`, `MULTI_MODULE`, `REFERENCES`, `RUNTIME_INSTALLATION`, `STABLE_SYMBOL_IDENTITY`, `SYMBOLS`, `TEST_SOURCES`

### `scip-typescript` `0.4.0`

Disposition : `QUALIFIED_WITH_CONSTRAINTS`

Langages : `TYPESCRIPT`

Capabilities : `MULTI_MODULE`, `PARTIAL_INDEX_ON_BUILD_FAILURE`, `REFERENCES`, `RUNTIME_INSTALLATION`, `STABLE_SYMBOL_IDENTITY`, `STRUCTURAL_RELATIONS`, `SYMBOLS`, `TEST_SOURCES`

### `scip-python` `0.6.6`

Disposition : `QUALIFIED_WITH_CONSTRAINTS`

Langages : `PYTHON`

Capabilities : `REFERENCES`, `RUNTIME_INSTALLATION`, `SYMBOLS`, `TEST_SOURCES`

### `scip-clang` `0.4.0`

Disposition : `QUALIFIED_WITH_CONSTRAINTS`

Langages : `C`, `CPP`

Capabilities : `IMPLEMENTATION_RELATIONS`, `PARTIAL_INDEX_ON_BUILD_FAILURE`, `REFERENCES`, `STABLE_SYMBOL_IDENTITY`, `STRUCTURAL_RELATIONS`, `SYMBOLS`, `TEST_SOURCES`, `UNRESOLVED_REFERENCES`

### `scip-dotnet` `0.2.14`

Disposition : `QUALIFIED_WITH_CONSTRAINTS`

Langages : `CSHARP`

Capabilities : `IMPLEMENTATION_RELATIONS`, `MULTI_MODULE`, `REFERENCES`, `RUNTIME_INSTALLATION`, `STABLE_SYMBOL_IDENTITY`, `STRUCTURAL_RELATIONS`, `SYMBOLS`, `TEST_SOURCES`, `UNRESOLVED_REFERENCES`

### `scip-go` `0.2.7`

Disposition : `QUALIFIED_WITH_CONSTRAINTS`

Langages : `GO`

Capabilities : `IMPLEMENTATION_RELATIONS`, `REFERENCES`, `RUNTIME_INSTALLATION`, `STABLE_SYMBOL_IDENTITY`, `STRUCTURAL_RELATIONS`, `SYMBOLS`, `TEST_SOURCES`, `UNRESOLVED_REFERENCES`

### `rust-analyzer-scip` `0.3.2989`

Disposition : `QUALIFIED_WITH_CONSTRAINTS`

Langages : `RUST`

Capabilities : `IMPLEMENTATION_RELATIONS`, `REFERENCES`, `STABLE_SYMBOL_IDENTITY`, `STRUCTURAL_RELATIONS`, `SYMBOLS`, `TEST_SOURCES`, `UNRESOLVED_REFERENCES`

## Formats calculables

- formats symboles : `text`, `json`
- formats architecture : `text`, `json`, `mermaid`, `dot`

## ADDED Requirements

### Requirement: Le contrôle hébergé est consommé par une façade applicative
`minos-api`, `minos-cli` et `minos-mcp` SHALL obtenir le plan de contrôle de l'équipe par `TeamControlPlane` de `minos-application`, que rend `MinosApplication.teamControlPlane()`. Leurs sources de production SHALL NOT nommer ni utiliser un type de `com.minos.hosted`, par import, nom qualifié, chaînage d'accesseur ou en bytecode. Leurs sorties SHALL rester identiques.

#### Scenario: Le moteur réimporté dans l'API
- **GIVEN** une copie du dépôt où `LocalMinosTeamApi` importe de nouveau `com.minos.hosted.HostedRole`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** il échoue en A11 en nommant `minos-api`, le fichier et le type, absent de `ENGINE_TYPES_NAMED_BY_SURFACE`

#### Scenario: Un accesseur du moteur chaîné par la CLI
- **GIVEN** une copie du dépôt où `MinosCliRunner` appelle de nouveau `opened.hostedControlPlaneService()`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A11 sur `hosted.HostedControlPlaneService` pour `minos-cli`

#### Scenario: Aucune entrée hosted dans les tables
- **GIVEN** le dépôt
- **WHEN** `test_check_module_boundaries.py` et `ModuleArchitectureTest` sont exécutés
- **THEN** ils sont verts et aucune surface n'a d'entrée `hosted.*` dans l'une ou l'autre table

#### Scenario: Sorties inchangées
- **GIVEN** le dépôt
- **WHEN** `A2SurfaceCharacterizationTest` et `RendererKeyOrderTest` sont exécutés
- **THEN** `team.golden`, `retention.golden`, `api.golden` et `mcp-all-tools.golden` sont égaux à l'octet et l'ordre des clés est inchangé

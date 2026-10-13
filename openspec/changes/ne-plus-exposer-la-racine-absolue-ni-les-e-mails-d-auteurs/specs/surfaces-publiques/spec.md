## ADDED Requirements

### Requirement: Le MCP n'expose pas la racine absolue d'un projet
La réponse de `minos_project_structure` SHALL NOT contenir le chemin absolu de la racine du projet. La clé `rootPath` SHALL rester présente avec une valeur non absolue, et `rootAvailable` SHALL rester inchangé. La fiche projet rendue par la CLI SHALL rester inchangée, chemin absolu compris, pour les clients qui s'en servent.

#### Scenario: Fiche projet par le MCP
- **GIVEN** un projet enregistré dont la racine est un répertoire temporaire `T/demo`
- **WHEN** `minos_project_structure` est appelé
- **THEN** la réponse contient la clé `rootPath`, sa valeur n'est pas un chemin absolu et la réponse ne contient pas le chemin de `T`

#### Scenario: Fiche projet par la CLI
- **GIVEN** le même projet
- **WHEN** la fiche projet est demandée par la CLI au format JSON
- **THEN** `rootPath` vaut le chemin absolu de la racine, comme avant

#### Scenario: Racine indisponible
- **GIVEN** un projet enregistré dont la racine n'existe plus
- **WHEN** `minos_project_structure` est appelé
- **THEN** `rootAvailable` vaut faux et la réponse ne contient aucun chemin absolu

#### Scenario: Résolution du projet par le plugin IntelliJ
- **GIVEN** les tests de protocole du plugin
- **WHEN** ils sont exécutés après le changement
- **THEN** la résolution du projet ouvert par `rootPath` réussit comme avant

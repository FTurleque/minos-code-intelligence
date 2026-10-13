## ADDED Requirements

### Requirement: Un graphe de programme servi par le cache ne relit pas les sources
Quand le graphe de programme d'un snapshot est en cache, un nouvel appel SHALL être servi sans relire ni hacher les fichiers source du projet, que le snapshot ait ou non une empreinte exacte de l'arbre source.

#### Scenario: Second appel avec empreinte exacte
- **GIVEN** un snapshot avec empreinte exacte et un espace de sources qui compte ses lectures
- **WHEN** le graphe de programme est demandé deux fois
- **THEN** le compteur de lectures de sources n'augmente pas au second appel

#### Scenario: Second appel sans empreinte exacte
- **GIVEN** un snapshot sans empreinte exacte et le même compteur
- **WHEN** le graphe de programme est demandé deux fois
- **THEN** le compteur de lectures de sources n'augmente pas au second appel

### Requirement: Un snapshot sans empreinte exacte est déclaré, jamais présenté comme frais
Quand le snapshot actif n'a pas d'empreinte exacte de l'arbre source, les réponses du graphe de programme, de l'impact avancé et des chemins de sécurité SHALL porter une limitation qui le dit et nomme la commande d'indexation. Cette limitation SHALL être additive et SHALL NOT apparaître quand l'empreinte est exacte.

#### Scenario: Limitation émise sans empreinte
- **GIVEN** un snapshot produit par une indexation pendant laquelle un fichier a changé
- **WHEN** `minos_program_graph` est appelé
- **THEN** la réponse contient la limitation d'état des sources non vérifié et le graphe

#### Scenario: Aucune limitation avec empreinte exacte
- **GIVEN** un snapshot avec empreinte exacte
- **WHEN** `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` sont appelés
- **THEN** aucune des trois réponses ne contient cette limitation et les sorties sont identiques aux goldens

#### Scenario: Même limitation sur les trois outils
- **GIVEN** un snapshot sans empreinte exacte
- **WHEN** les trois outils sont appelés par le MCP et par la CLI
- **THEN** chacun rend la même limitation, avec le même identifiant

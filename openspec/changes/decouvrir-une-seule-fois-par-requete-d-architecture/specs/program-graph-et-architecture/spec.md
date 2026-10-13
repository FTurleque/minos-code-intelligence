## ADDED Requirements

### Requirement: Une requête de graphe d'architecture avec module découvre le dépôt une seule fois
Une requête de graphe d'architecture en Mermaid ou DOT filtrée sur un module SHALL exécuter une seule découverte du dépôt et un seul chargement du snapshot actif, par le MCP comme par la CLI. La sortie SHALL être identique à celle d'avant et un module inconnu SHALL produire la même erreur publique.

#### Scenario: Graphe Mermaid avec module par le MCP
- **GIVEN** un service de découverte et un store de snapshots qui comptent leurs appels
- **WHEN** `minos_architecture_graph` est appelé en Mermaid avec un module existant
- **THEN** le compteur de découvertes vaut 1 et celui de chargements du snapshot vaut 1

#### Scenario: Graphe DOT avec module par la CLI
- **GIVEN** les mêmes compteurs
- **WHEN** la commande d'architecture est exécutée en DOT avec un module existant
- **THEN** le compteur de découvertes vaut 1 et celui de chargements du snapshot vaut 1

#### Scenario: Sortie inchangée
- **GIVEN** les fixtures et goldens d'architecture
- **WHEN** le graphe avec module est produit par chaque surface
- **THEN** la sortie est identique, à l'octet, à celle d'avant ce changement

#### Scenario: Module inconnu
- **GIVEN** un nom de module absent du projet
- **WHEN** le graphe avec module est demandé par le MCP puis par la CLI
- **THEN** chaque surface rend la même erreur publique qu'avant ce changement, après une seule découverte

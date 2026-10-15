## ADDED Requirements

### Requirement: Les index d'impact sont construits une fois par vue ou par graphe en cache
L'analyse d'impact SHALL utiliser un index des arêtes entrantes construit une seule fois pour une vue de requête en cache, et l'impact avancé SHALL utiliser une adjacence construite une seule fois pour un graphe de programme en cache. Le contenu et l'ordre des rapports SHALL être identiques à ceux d'un index reconstruit à chaque appel.

#### Scenario: Deux analyses sur la même vue
- **GIVEN** une vue de requête en cache et un compteur de constructions d'index
- **WHEN** deux analyses d'impact sont exécutées sur des symboles différents
- **THEN** le compteur de constructions de l'index des arêtes entrantes vaut 1

#### Scenario: Deux impacts avancés sur le même graphe
- **GIVEN** un graphe de programme en cache et un compteur de constructions d'adjacence
- **WHEN** deux analyses d'impact avancé sont exécutées
- **THEN** le compteur vaut 1

#### Scenario: Rapports identiques
- **GIVEN** les fixtures et les goldens d'impact
- **WHEN** les rapports d'impact et d'impact avancé sont produits
- **THEN** ils sont identiques, ordre compris, à ceux d'avant ce changement

#### Scenario: Premières analyses simultanées
- **GIVEN** une vue dont l'index n'est pas encore construit
- **WHEN** plusieurs analyses démarrent en même temps
- **THEN** toutes rendent le rapport attendu

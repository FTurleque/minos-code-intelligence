# Spec Delta

## ADDED Requirements

### Requirement: Les caches du graphe de programme sont plafonnés par une part du tas
Le cache des graphes de programme et le cache des empreintes du provider Java SHALL appliquer comme plafond de poids le plus petit de leur plafond configuré et de leur part déclarée du tas maximal de la JVM. Un graphe plus lourd que ce plafond SHALL NOT être gardé, et la requête SHALL rendre le même graphe que s'il l'était.

#### Scenario: Petit tas
- **GIVEN** un tas maximal injecté dont la part déclarée du cache de graphes est inférieure à son plafond configuré
- **WHEN** le service est construit avec les valeurs par défaut et ses statistiques de cache sont lues
- **THEN** le plafond de poids rapporté est égal à cette part du tas

#### Scenario: Grand tas
- **GIVEN** un tas maximal injecté dont la part déclarée dépasse le plafond configuré
- **WHEN** les statistiques de cache sont lues
- **THEN** le plafond de poids rapporté est le plafond configuré

#### Scenario: Graphe plus lourd que le plafond effectif
- **GIVEN** un projet dont le graphe de programme pèse plus que le plafond effectif du cache
- **WHEN** la même requête de graphe est exécutée deux fois
- **THEN** les deux réponses sont identiques à celles obtenues sous un plafond qui garde le graphe
- **AND** le poids rapporté par le cache de graphes reste nul

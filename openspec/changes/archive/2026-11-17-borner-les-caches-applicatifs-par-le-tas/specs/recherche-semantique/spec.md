# Spec Delta

## ADDED Requirements

### Requirement: Les caches de la recherche sémantique sont plafonnés par une part du tas
Le cache du corpus hybride, le cache des vecteurs de requête et le cache de l'index vectoriel du stockage local SHALL appliquer comme plafond de poids le plus petit de leur plafond configuré et de leur part déclarée du tas maximal de la JVM. Une entrée plus lourde que ce plafond SHALL NOT être gardée, et la requête SHALL rendre le même résultat que si elle l'était.

#### Scenario: Petit tas
- **GIVEN** un tas maximal injecté dont la part déclarée d'un cache est inférieure à son plafond configuré
- **WHEN** le service ou le store est construit et ses statistiques de cache sont lues
- **THEN** le plafond de poids rapporté est égal à cette part du tas

#### Scenario: Grand tas
- **GIVEN** un tas maximal injecté dont la part déclarée dépasse le plafond configuré
- **WHEN** les statistiques de cache sont lues
- **THEN** le plafond de poids rapporté est le plafond configuré

#### Scenario: Corpus plus lourd que le plafond effectif
- **GIVEN** un projet dont le corpus hybride pèse plus que le plafond effectif du cache
- **WHEN** la même requête hybride est exécutée deux fois
- **THEN** les deux réponses sont identiques à celles obtenues sous un plafond qui garde le corpus
- **AND** le poids rapporté par le cache du corpus reste nul

#### Scenario: Part du tas invalide
- **GIVEN** une part déclarée nulle, négative ou supérieure à cent pour cent
- **WHEN** un plafond effectif est calculé
- **THEN** le calcul échoue par une erreur d'argument qui nomme la part

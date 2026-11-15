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

### Requirement: Un index vectoriel local plus lourd que sa part du tas n'est pas décodé en entier
Avant de décoder en entier un index vectoriel du stockage local, le store SHALL estimer une borne de son poids en mémoire d'après ses métadonnées et la taille de son fichier. Un index dont cette borne dépasse la part du tas déclarée pour ce décodage SHALL être refusé par une erreur d'E/S explicite, sans chemin, qui donne le poids estimé et le plafond en octets ; il SHALL NOT être décodé en entier. Les lectures qui ne chargent pas l'index SHALL rester servies.

#### Scenario: Index qui tient dans sa part
- **GIVEN** un index dont le poids estimé tient dans la part du tas déclarée
- **WHEN** il est chargé en entier
- **THEN** le chargement réussit et rend les mêmes documents et vecteurs qu'avant ce changement

#### Scenario: Index trop lourd pour le tas
- **GIVEN** un tas maximal injecté sous lequel le poids estimé d'un index publié dépasse sa part
- **WHEN** l'index est chargé en entier
- **THEN** la lecture échoue par une erreur dont le message commence par « semantic index is too large to load in this process »
- **AND** aucun vecteur n'a été décodé, rien n'est gardé en cache et le message ne contient aucun chemin

#### Scenario: Lecture paginée d'un index trop lourd
- **GIVEN** le même index et le même tas
- **WHEN** une page de documents est lue sans charger l'index
- **THEN** la page est rendue sans erreur

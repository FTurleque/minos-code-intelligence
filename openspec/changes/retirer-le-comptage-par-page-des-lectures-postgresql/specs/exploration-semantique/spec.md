## ADDED Requirements

### Requirement: Une page PostgreSQL de l'index sémantique ne recompte pas les documents
Sous PostgreSQL, le système SHALL rendre une page de documents, une page de vecteurs, un document stocké et les métadonnées d'un index sémantique sans compter les lignes de l'index dans la requête. Le nombre de documents rendu avec la réponse SHALL être celui de l'index lu, tenu à jour par chaque écriture de l'index dans la même transaction que cette écriture.

#### Scenario: Plan d'une requête de page
- **GIVEN** un index sémantique PostgreSQL de 3 000 documents
- **WHEN** la requête d'une page de vecteurs, puis d'une page de documents, est préparée sur le serveur et son plan lu par `EXPLAIN EXECUTE`
- **THEN** aucun des deux plans ne contient de nœud d'agrégat
- **AND** chaque page rendue annonce 3 000 documents

#### Scenario: Compte après une synchronisation différentielle
- **GIVEN** un index de 120 documents
- **WHEN** une synchronisation retire 20 documents, en ajoute 5, puis une page de documents est lue
- **THEN** la page annonce 105 documents
- **AND** ce nombre est égal au nombre de lignes de l'index

#### Scenario: Index remplacé entre deux pages
- **GIVEN** une première page de vecteurs lue sur un index de 120 documents
- **WHEN** l'index est remplacé par un index de 80 documents, puis la page suivante est lue
- **THEN** les métadonnées de la seconde page diffèrent de celles de la première par le snapshot et par le nombre de documents
- **AND** aucune des deux pages ne porte le nombre de documents de l'autre index

### Requirement: Une page suivante PostgreSQL est bornée par la clé stable dans l'index
Sous PostgreSQL, le système SHALL lire une page qui suit une clé stable par une condition d'index sur cette clé, y compris quand le serveur exécute la requête en plan générique. Le système SHALL NOT parcourir les lignes qui précèdent la clé pour les écarter une à une.

#### Scenario: Plan générique forcé
- **GIVEN** un index de 3 000 documents et une session dont `plan_cache_mode` vaut `force_generic_plan`
- **WHEN** la requête de page suivante est préparée par `PREPARE`, puis son plan lu par `EXPLAIN (ANALYZE) EXECUTE` avec la 2 000e clé stable
- **THEN** le plan porte une condition d'index sur `stable_key`
- **AND** il n'écarte par filtre aucune ligne dont la clé précède la borne

#### Scenario: Borne absente sur une page suivante
- **GIVEN** un store PostgreSQL ouvert
- **WHEN** une page est demandée avec une clé de reprise vide
- **THEN** la demande est refusée par une erreur d'argument
- **AND** aucune requête n'est envoyée au serveur

### Requirement: La migration du schéma PostgreSQL reprend le nombre de documents de chaque index sémantique
La migration d'un schéma PostgreSQL antérieur SHALL inscrire, pour chaque index sémantique existant, son nombre réel de documents. Après migration, une lecture SHALL annoncer ce nombre sans resynchronisation de l'index ni recalcul de vecteur.

#### Scenario: Migration d'un index existant
- **GIVEN** une base au schéma précédent qui porte un index sémantique de 250 documents
- **WHEN** le runtime migre le schéma puis lit la première page de documents
- **THEN** la colonne du nombre de documents de cet index vaut 250 et la page annonce 250 documents
- **AND** la page rend les mêmes clés stables qu'avant la migration

#### Scenario: Migration rejouée
- **GIVEN** une base déjà migrée dont un index sémantique porte 250 documents
- **WHEN** la migration est relancée
- **THEN** elle n'échoue pas et le nombre de documents inscrit reste 250

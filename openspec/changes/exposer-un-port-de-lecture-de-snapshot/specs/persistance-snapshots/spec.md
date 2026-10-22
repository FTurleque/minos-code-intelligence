## ADDED Requirements

### Requirement: Les requêtes du snapshot actif sont servies par un port de lecture neutre
Les cas d'usage de lecture (symboles, usages, relations, recherche de code, impact, rapport runtime d'un symbole) SHALL lire le snapshot actif par une vue neutre dont le lecteur est le port de lecture du cœur, sans recevoir la classe d'index mémoire ni ses écritures. Une implémentation de lecture qui n'est pas l'index mémoire SHALL suffire à les servir. Sans snapshot actif, ils SHALL échouer comme avant ce changement.

#### Scenario: Double de lecture non mémoire
- **GIVEN** un store de snapshots dont la vue neutre porte un lecteur de test qui n'est pas l'index mémoire
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test` interroge symboles, usages, relations, impact et rapport runtime
- **THEN** les réponses sont celles obtenues avec l'index mémoire sur le même snapshot

#### Scenario: Aucun snapshot actif
- **GIVEN** un store de snapshots sans snapshot actif pour le projet
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test` demande une recherche de symboles puis une analyse d'impact
- **THEN** elle échoue avec la même exception et le même message qu'avant ce changement

### Requirement: Les résultats des requêtes sont inchangés
Les réponses des requêtes de lecture, leur ordre, leurs limites et leurs budgets SHALL rester identiques à ceux d'avant ce changement, sous le store fichier et sous PostgreSQL. Les goldens existants SHALL NOT être régénérés pour ce changement.

#### Scenario: Goldens de caractérisation
- **GIVEN** les goldens de `minos-app/src/test/resources/characterization/` et les rapports d'impact de référence
- **WHEN** `./mvnw -B -ntp verify` est exécuté
- **THEN** les tests de caractérisation et d'impact passent sans fichier de référence modifié

#### Scenario: Store PostgreSQL, sous Linux seulement
- **GIVEN** un poste Linux avec Docker
- **WHEN** `./mvnw -B -ntp -pl minos-storage-postgresql -am test "-Dminos.postgresql.tests.required=true"` est exécuté
- **THEN** les tests du cache de vues PostgreSQL passent avec les mêmes décomptes que le store fichier ; sans Docker les tests sont sautés, ce qui n'est pas une preuve

### Requirement: L'ancien contrat de vue reste disponible sans seconde construction d'index
`SnapshotQueryView`, son accesseur de la classe mémoire, ses métriques et la lecture `loadActiveQueryView` SHALL rester disponibles, dépréciés, avec les mêmes résultats. Sous un store du dépôt, l'ancien et le nouveau contrat SHALL partager le même index : lire les deux pour un même snapshot actif SHALL construire une seule vue et au plus un index des arêtes entrantes.

#### Scenario: Les deux contrats sur un même snapshot
- **GIVEN** un store fichier à compteurs avec un snapshot actif
- **WHEN** `./mvnw -B -ntp -pl minos-storage-local -am test` lit la vue neutre puis l'ancienne vue et calcule l'impact par chacune
- **THEN** le compteur de constructions de vue vaut 1, celui de l'index des arêtes vaut au plus 1, et les deux rapports d'impact sont égaux

### Requirement: Une implémentation existante du port de snapshot reste valide
Une implémentation du port de snapshot qui ne redéfinit que `loadActiveQueryView` SHALL servir la nouvelle lecture par dérivation de l'ancienne. Une implémentation qui ne redéfinit que la nouvelle lecture SHALL obtenir l'ancienne vue par une fabrique dépréciée, sans boucle d'appels.

#### Scenario: Faux store écrit pour l'ancien contrat
- **GIVEN** un faux store de test du cœur qui ne redéfinit que `loadActiveQueryView`
- **WHEN** `./mvnw -B -ntp -pl minos-engine -am test` lit la vue neutre
- **THEN** elle porte le snapshot, le descripteur et l'index de l'ancienne vue

#### Scenario: Store qui ne sert que la vue neutre
- **GIVEN** un faux store dont la vue neutre porte un lecteur non mémoire
- **WHEN** `./mvnw -B -ntp -pl minos-engine -am test` obtient l'ancienne vue par la fabrique dépréciée
- **THEN** elle porte un index mémoire construit depuis le snapshot et l'appel se termine

### Requirement: La vue neutre garde la cohérence pendant une promotion concurrente
La vue neutre SHALL être publiée et mise en cache sous les mêmes règles que la vue actuelle : identité `(projectId, snapshotId)`, descripteur comparé avant un succès de cache, pointeur actif relu après construction. Une vue construite pour un snapshot qui n'est plus actif SHALL NOT être publiée.

#### Scenario: Promotion pendant la construction
- **GIVEN** un store fichier dont le snapshot actif est promu par une autre instance pendant la construction de la vue
- **WHEN** `./mvnw -B -ntp -pl minos-storage-local -am test` lit la vue neutre
- **THEN** la vue rendue est celle du nouveau snapshot actif et aucune vue de l'ancien n'est en cache

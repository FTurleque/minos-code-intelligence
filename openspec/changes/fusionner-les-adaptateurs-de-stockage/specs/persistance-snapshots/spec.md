## MODIFIED Requirements

### Requirement: Une seconde publication identique ne change pas l'état persisté
Publier deux fois le même snapshot, ou la même empreinte, sous le même identifiant SHALL laisser le même nombre de lignes persistées et le même contenu rechargé qu'après la première publication. Le test d'idempotence de chaque store PostgreSQL SHALL relire l'état après le second appel et SHALL échouer si une ligne est dupliquée ou si le contenu rechargé diffère.

#### Scenario: Snapshot publié deux fois
- **GIVEN** un snapshot publié une fois dans le store PostgreSQL
- **WHEN** il est publié une seconde fois avec le même contenu
- **THEN** le nombre de lignes du projet est inchangé et le snapshot rechargé est égal au snapshot publié

#### Scenario: Empreinte publiée deux fois
- **GIVEN** une empreinte de projet publiée une fois
- **WHEN** elle est publiée une seconde fois
- **THEN** une seule empreinte active est relue et elle est égale à l'empreinte publiée

#### Scenario: Duplication détectée
- **GIVEN** une publication modifiée localement pour insérer sans contrôle d'existence
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am verify` est exécuté avec PostgreSQL disponible
- **THEN** le test d'idempotence échoue

### Requirement: Les résultats des requêtes sont inchangés
Les réponses des requêtes de lecture, leur ordre, leurs limites et leurs budgets SHALL rester identiques à ceux d'avant ce changement, sous le store fichier et sous PostgreSQL. Les goldens existants SHALL NOT être régénérés pour ce changement.

#### Scenario: Goldens de caractérisation
- **GIVEN** les goldens de `minos-app/src/test/resources/characterization/` et les rapports d'impact de référence
- **WHEN** `./mvnw -B -ntp verify` est exécuté
- **THEN** les tests de caractérisation et d'impact passent sans fichier de référence modifié

#### Scenario: Store PostgreSQL, sous Linux seulement
- **GIVEN** un poste Linux avec Docker
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dminos.postgresql.tests.required=true"` est exécuté
- **THEN** les tests du cache de vues PostgreSQL passent avec les mêmes décomptes que le store fichier ; sans Docker les tests sont sautés, ce qui n'est pas une preuve

### Requirement: L'ancien contrat de vue reste disponible sans seconde construction d'index
`SnapshotQueryView`, son accesseur de la classe mémoire, ses métriques et la lecture `loadActiveQueryView` SHALL rester disponibles, dépréciés, avec les mêmes résultats. Sous un store du dépôt, l'ancien et le nouveau contrat SHALL partager le même index : lire les deux pour un même snapshot actif SHALL construire une seule vue et au plus un index des arêtes entrantes.

#### Scenario: Les deux contrats sur un même snapshot
- **GIVEN** un store fichier à compteurs avec un snapshot actif
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test` lit la vue neutre puis l'ancienne vue et calcule l'impact par chacune
- **THEN** le compteur de constructions de vue vaut 1, celui de l'index des arêtes vaut au plus 1, et les deux rapports d'impact sont égaux

#### Scenario: Descripteurs binaires de la version publiée
- **GIVEN** les méthodes publiques de l'ancien contrat telles que publiées en 1.2.0 (lectures appelées par le port d'écriture, accesseur de la classe mémoire, métriques, ancienne lecture du port de snapshot)
- **WHEN** `./mvnw -B -ntp -pl minos-engine -am test` les résout par leur nom et leur descripteur exacts, sans recompiler d'appelant
- **THEN** chacune est trouvée ; une seule absente fait échouer le test

### Requirement: La vue neutre garde la cohérence pendant une promotion concurrente
La vue neutre SHALL être publiée et mise en cache sous les mêmes règles que la vue actuelle : identité `(projectId, snapshotId)`, descripteur comparé avant un succès de cache, pointeur actif relu après construction. Une vue construite pour un snapshot qui n'est plus actif SHALL NOT être publiée.

#### Scenario: Promotion pendant la construction
- **GIVEN** un store fichier dont le snapshot actif est promu par une autre instance pendant la construction de la vue
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test` lit la vue neutre
- **THEN** la vue rendue est celle du nouveau snapshot actif et aucune vue de l'ancien n'est en cache

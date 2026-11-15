## ADDED Requirements

### Requirement: Une fermeture refusée depuis un autre fil laisse le bail tenu
Quand un fil qui n'est pas le propriétaire d'un bail de projet tente de le fermer, la fermeture SHALL être refusée et le bail SHALL rester tenu par son propriétaire. Les tests du bail en mémoire, du bail du magasin fichier, du bail de fichier d'indexation et du bail de snapshot SHALL le prouver en observant qu'un concurrent ne l'obtient pas après le refus, puis l'obtient après la fermeture par le propriétaire. Ils SHALL NOT tenir une affirmation constante pour preuve.

#### Scenario: Concurrent refusé après la fermeture étrangère
- **GIVEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=ProjectLeaseOwnershipTest,SnapshotProjectLeaseTest" "-Dsurefire.failIfNoSpecifiedTests=false"`, où un bail de fichier d'indexation est tenu par un fil et une fermeture tentée depuis un autre fil est refusée par une erreur d'état
- **WHEN** un troisième fil demande le même bail avec une échéance courte
- **THEN** sa demande échoue à l'échéance, puis réussit après la fermeture par le propriétaire

#### Scenario: Concurrent en attente sur un bail sans échéance
- **GIVEN** un bail en mémoire tenu par un fil et une fermeture étrangère refusée
- **WHEN** un autre fil demande le même bail
- **THEN** ce fil est observé en attente sur le verrou avant toute autre affirmation, et il obtient le bail après la fermeture par le propriétaire

#### Scenario: Plus d'affirmation constante
- **GIVEN** les sources de test du dépôt
- **WHEN** `grep -rn "assertTrue(true" --include=*.java minos-*/src/test` est exécuté
- **THEN** la recherche ne retourne aucune ligne

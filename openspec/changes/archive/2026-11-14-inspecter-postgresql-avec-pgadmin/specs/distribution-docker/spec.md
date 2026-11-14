## ADDED Requirements

### Requirement: pgAdmin est un service optionnel publié sur la seule boucle locale
Le runtime Docker SHALL fournir pgAdmin comme un service hors profil par défaut, publié uniquement sur la boucle locale de l'hôte. PostgreSQL SHALL rester non publié. Les mots de passe SHALL provenir de fichiers privés hors du dépôt.

#### Scenario: Installation sans pgAdmin
- **GIVEN** une installation Docker démarrée sans le profil `pgadmin`
- **WHEN** les conteneurs et les ports publiés sont listés
- **THEN** aucun conteneur pgAdmin n'existe et aucun port n'est publié

#### Scenario: pgAdmin activé
- **GIVEN** le profil `pgadmin` activé
- **WHEN** les ports publiés sont listés
- **THEN** le seul port publié est celui de pgAdmin, lié à `127.0.0.1`
- **AND** `minos-postgres` ne publie aucun port

#### Scenario: Secret absent
- **GIVEN** le profil `pgadmin` activé sans fichier de mot de passe
- **WHEN** le service est démarré
- **THEN** le démarrage échoue en nommant la variable manquante

### Requirement: pgAdmin se connecte par un rôle PostgreSQL limité à la lecture
Le serveur préenregistré dans pgAdmin SHALL utiliser un rôle PostgreSQL dédié qui ne détient que des droits `SELECT` sur une liste fermée de tables et de colonnes, sans la racine des projets. La lecture seule SHALL tenir aux droits, pas à un réglage de session.

#### Scenario: Écriture refusée
- **GIVEN** une session ouverte avec le rôle d'inspection, `default_transaction_read_only` remis à `off`
- **WHEN** un `DELETE` est tenté sur `semantic_documents`
- **THEN** PostgreSQL refuse avec « permission denied »

#### Scenario: Racine des projets non lisible
- **GIVEN** une session ouverte avec le rôle d'inspection
- **WHEN** `SELECT root_value FROM projects` est exécuté
- **THEN** PostgreSQL refuse avec « permission denied »

### Requirement: Retirer pgAdmin ne supprime aucune donnée MINOS
L'action de retrait de pgAdmin SHALL supprimer son conteneur, son volume de configuration, ses fichiers privés et le rôle d'inspection, et SHALL NOT modifier le volume PostgreSQL ni les données MINOS.

#### Scenario: Retrait
- **GIVEN** une installation avec pgAdmin activé et un projet indexé
- **WHEN** l'action de retrait est exécutée
- **THEN** le conteneur, le volume de pgAdmin et le rôle d'inspection n'existent plus
- **AND** le nombre de lignes de `semantic_documents` et le volume PostgreSQL sont inchangés

# Spec Delta

## ADDED Requirements

### Requirement: Une chaîne d'audit stockée altérée est refusée à la lecture
La lecture de l'état d'un tenant SHALL refuser une chaîne d'audit stockée dont la structure a été altérée, même si chaque événement porte un HMAC valide : événement retiré, événements permutés, événement d'un autre tenant, lien `previousHash` rompu, ancre de séquence incohérente. Le refus SHALL rendre le tenant inutilisable jusqu'à une intervention, sans corriger silencieusement la chaîne.

#### Scenario: Événement retiré au milieu de la chaîne
- **WHEN** l'état stocké contient les événements 1, 2 et 3, que l'événement 2 en est retiré et que les autres restent signés
- **THEN** la lecture de l'état échoue et le message nomme une chaîne d'audit altérée

#### Scenario: Événements permutés
- **WHEN** deux événements consécutifs de la chaîne stockée sont échangés
- **THEN** la lecture de l'état échoue

#### Scenario: Séquence croissante mais non contiguë
- **WHEN** `verify` reçoit une chaîne dont les séquences sont 1 puis 3, avec des liens et des HMAC valides
- **THEN** la vérification échoue avec le message de séquence non contiguë

#### Scenario: Chaîne vide
- **WHEN** l'état d'un tenant ne contient aucun événement et une ancre de genèse
- **THEN** la vérification réussit ; avec une ancre qui n'est pas celle de genèse et une séquence nulle, la lecture échoue

### Requirement: La lecture de l'audit et du plan de rétention exige la permission du rôle
Un membre dont le rôle n'accorde pas `AUDIT_READ` SHALL être refusé sur la lecture de la piste d'audit, et un membre sans `RETENTION_MANAGE` sur le plan de rétention, par une erreur d'autorisation. Conformément à l'ADR 0035, qui chaîne les mutations refusées et non les lectures, un refus en lecture SHALL NOT ajouter d'événement à la chaîne.

#### Scenario: VIEWER lisant l'audit
- **WHEN** un membre VIEWER demande la piste d'audit du tenant
- **THEN** la demande est refusée par une erreur d'autorisation qui nomme `AUDIT_READ`

#### Scenario: CONTRIBUTOR lisant le plan de rétention
- **WHEN** un membre CONTRIBUTOR demande le plan de rétention
- **THEN** la demande est refusée par une erreur d'autorisation qui nomme `RETENTION_MANAGE`

#### Scenario: AUDITOR
- **WHEN** un membre AUDITOR demande la piste d'audit puis le plan de rétention
- **THEN** la piste d'audit lui est rendue et le plan de rétention lui est refusé

### Requirement: L'identifiant de requête est validé après l'autorisation
Le système SHALL authentifier et autoriser une mutation avant de valider la forme de son identifiant de requête. Un appelant sans droit qui présente un identifiant de requête invalide SHALL recevoir un refus d'autorisation tracé, l'identifiant étant enregistré par un remplaçant borné et jamais par sa valeur brute ; un appelant autorisé SHALL recevoir une requête invalide, sans événement de refus.

#### Scenario: Appelant sans droit, identifiant de requête invalide
- **WHEN** un VIEWER crée un espace de travail avec l'identifiant de requête `bad request`
- **THEN** l'appel est refusé comme refus d'autorisation et un événement de refus est chaîné, dont l'identifiant de requête commence par `invalid:`

#### Scenario: Propriétaire, identifiant de requête invalide
- **WHEN** le propriétaire crée un espace de travail avec l'identifiant de requête `bad request`
- **THEN** l'appel est rejeté comme requête invalide et l'état du tenant est inchangé

### Requirement: Une panne d'un puits d'audit externe ne change pas le résultat d'un appel
Une exception levée par un puits d'audit externe, contrôlée ou non, SHALL être journalisée et contenue : une mutation déjà persistée SHALL rester un succès, et un refus SHALL rester un refus d'autorisation.

#### Scenario: Puits qui lève une exception d'exécution après une mutation
- **WHEN** le puits lève une `IllegalStateException` à la publication d'une mutation persistée
- **THEN** l'appel réussit et la version du tenant est incrémentée

#### Scenario: Puits qui lève une exception d'exécution pendant un refus
- **WHEN** le puits lève une `IllegalStateException` à la livraison d'un refus
- **THEN** l'appelant reçoit un refus d'autorisation

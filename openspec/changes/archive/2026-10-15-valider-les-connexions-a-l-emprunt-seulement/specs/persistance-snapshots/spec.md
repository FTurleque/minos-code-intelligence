## ADDED Requirements

### Requirement: La validation réseau des connexions du pool PostgreSQL est bornée
Le pool SHALL NOT valider une connexion par un aller-retour réseau à sa restitution. À l'emprunt, il SHALL ne la valider que si elle est restée au repos au-delà du seuil d'inactivité ; une connexion invalide SHALL être fermée et remplacée.

#### Scenario: Emprunt puis restitution immédiats
- **GIVEN** un pool dont la connexion compte ses appels de validation
- **WHEN** une connexion est empruntée, utilisée et restituée deux fois de suite sans attente
- **THEN** le compteur de validations réseau vaut 0

#### Scenario: Emprunt après inactivité
- **GIVEN** une connexion au repos depuis plus longtemps que le seuil, selon l'horloge injectée
- **WHEN** elle est empruntée
- **THEN** elle est validée une fois avant d'être remise à l'appelant

#### Scenario: Connexion invalide après inactivité
- **GIVEN** une connexion au repos au-delà du seuil dont la validation échoue
- **WHEN** elle est empruntée
- **THEN** elle est fermée et l'appelant reçoit une connexion neuve

### Requirement: Une connexion rendue dans un état incertain n'est pas réutilisée
Une connexion restituée fermée, hors de son mode d'auto-commit attendu ou après une exception de l'appelant SHALL être fermée et retirée du pool. Une rupture de connexion constatée à la première instruction d'un appel SHALL produire le comportement défini par la décision du design, sans jamais rejouer une écriture.

#### Scenario: Restitution après une exception
- **GIVEN** un appel qui se termine par une exception SQL
- **WHEN** sa connexion est restituée
- **THEN** elle est fermée et l'emprunt suivant obtient une autre connexion

#### Scenario: Transaction laissée ouverte
- **GIVEN** une connexion restituée avec l'auto-commit désactivé
- **WHEN** le pool la reçoit
- **THEN** il la ferme et ne la remet pas au repos

#### Scenario: Connexion rompue sous le seuil
- **GIVEN** une connexion au repos depuis moins que le seuil, rompue côté serveur
- **WHEN** la première instruction d'un appel en lecture échoue par une erreur de connexion
- **THEN** la connexion est écartée et l'appel suit la décision retenue (nouvel essai unique ou erreur), avec une erreur publique sans URL ni chemin

#### Scenario: Aucune écriture rejouée
- **GIVEN** un appel qui a déjà exécuté une écriture puis subit une erreur de connexion
- **WHEN** l'erreur survient
- **THEN** l'appel échoue et aucune instruction n'est rejouée

# Spec Delta

## ADDED Requirements

### Requirement: La table de symboles de l'explorateur ne survit pas à sa vue
Le service d'exploration SHALL résoudre les symboles d'une page depuis la vue active que rend le store de snapshots. La table de symboles qu'il bâtit pour une vue SHALL être tenue par cette vue seule : une vue que le store ne retient plus et qu'aucune requête n'utilise SHALL NOT rester joignable depuis le service, ni sa table. Les pages rendues SHALL rester identiques.

#### Scenario: Deux pages consécutives du même projet
- **GIVEN** un projet indexé dont le snapshot actif est celui de l'index sémantique
- **WHEN** deux pages de documents sont demandées de suite
- **THEN** la vue est demandée au store pour chaque page et le store ne la charge qu'une fois
- **AND** les deux pages portent les mêmes symboles

#### Scenario: Vue évincée du store
- **GIVEN** une page servie pour un projet, puis l'éviction de la vue par le store
- **WHEN** aucune requête n'est en cours et une collecte du ramasse-miettes a eu lieu
- **THEN** la vue évincée n'est plus joignable depuis le service d'exploration

#### Scenario: Snapshot actif différent de celui de l'index
- **GIVEN** un index sémantique construit sur un snapshot qui n'est plus le snapshot actif
- **WHEN** une page de documents est demandée
- **THEN** les symboles sont rendus non résolus et aucune vue n'est demandée au store

### Requirement: Une projection plus lourde que sa part du tas est refusée avant décodage
La lecture d'une projection SHALL estimer son poids décodé depuis son en-tête avant de décoder ses points. Une projection dont le poids estimé dépasse la part du tas déclarée pour elle SHALL être refusée par une erreur d'E/S explicite, sans chemin, qui donne le poids estimé et le plafond en octets ; elle SHALL NOT être décodée. Le poids d'une projection gardée en cache SHALL être celui de cette estimation.

#### Scenario: Projection qui tient dans sa part
- **GIVEN** une projection publiée dont le poids estimé tient dans la part du tas déclarée
- **WHEN** elle est lue deux fois sans changement du fichier
- **THEN** elle est décodée une seule fois et les deux lectures rendent les mêmes points

#### Scenario: Projection trop lourde pour le tas
- **GIVEN** un tas maximal injecté sous lequel le poids estimé d'une projection publiée dépasse sa part
- **WHEN** la projection est lue
- **THEN** la lecture échoue par une erreur dont le message commence par « semantic projection is too large for this process »
- **AND** aucun point n'a été décodé, rien n'est gardé en cache et le message ne contient aucun chemin

#### Scenario: En-tête incohérent
- **GIVEN** un fichier de projection dont l'en-tête annonce plus de points que la limite du format
- **WHEN** la projection est lue
- **THEN** la lecture échoue par l'erreur existante de projection illisible, avant toute estimation

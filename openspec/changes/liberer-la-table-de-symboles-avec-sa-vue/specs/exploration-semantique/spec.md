## MODIFIED Requirements

### Requirement: La table de symboles de l'explorateur ne survit pas à sa vue
Le service d'exploration SHALL résoudre les symboles d'une page par les lectures de la vue active que rend le store de snapshots. Il SHALL NOT garder hors de cette vue d'objet qui retienne ses symboles : une vue que le store ne retient plus et qu'aucune requête n'utilise SHALL NOT laisser ni elle-même ni ses symboles joignables depuis le service, sans attendre une nouvelle requête. Les pages rendues SHALL rester identiques.

#### Scenario: Deux pages consécutives du même projet
- **GIVEN** un projet indexé dont le snapshot actif est celui de l'index sémantique
- **WHEN** deux pages de documents sont demandées de suite
- **THEN** la vue est demandée au store pour chaque page et le store ne la charge qu'une fois
- **AND** les deux pages portent les mêmes symboles

#### Scenario: Vue évincée du store
- **GIVEN** une page servie pour un projet, puis l'éviction de la vue par le store
- **WHEN** aucune requête n'est en cours, aucun appel n'est fait au service et une collecte du ramasse-miettes a eu lieu
- **THEN** la vue évincée n'est plus joignable depuis le service d'exploration
- **AND** aucun symbole de son snapshot n'est joignable depuis le service

#### Scenario: Fichier décrit par le symbole de plus petite clé
- **GIVEN** un fichier indexé qui porte plusieurs symboles, dont celui de plus petite clé n'est pas le premier par position
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test` exécute le test de page de ce fichier
- **THEN** le langage et le module rendus pour le document du fichier sont ceux du symbole de plus petite clé

#### Scenario: Snapshot actif différent de celui de l'index
- **GIVEN** un index sémantique construit sur un snapshot qui n'est plus le snapshot actif
- **WHEN** une page de documents est demandée
- **THEN** les symboles sont rendus non résolus et aucune vue n'est demandée au store

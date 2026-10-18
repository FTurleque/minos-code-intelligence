## ADDED Requirements

### Requirement: La configuration du backend MCP est écrite par une primitive privée
Le fichier de configuration du backend MCP SHALL être écrit par une primitive d'E/S privée, sans appel brut de `Files` listé dans la liste de tolérance, avec un contenu identique à l'octet près. Un fichier temporaire remplacé par un lien symbolique SHALL faire échouer la sauvegarde sans modifier la cible du lien.

#### Scenario: Lien substitué au temporaire
- **GIVEN** un lien symbolique vers un fichier sentinelle à l'emplacement du fichier temporaire
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test` exécute le test de sauvegarde
- **THEN** la sauvegarde échoue et la sentinelle est intacte

#### Scenario: Plus d'entrée de liste pour le magasin
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée pour `McpBackendConfigurationStore.java`

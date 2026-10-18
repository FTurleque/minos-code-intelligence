## ADDED Requirements

### Requirement: L'historique d'indexation et les empreintes de sources ne suivent pas de lien
L'historique d'indexation de la CLI SHALL être écrit par une primitive d'E/S privée avec un contenu identique à l'octet près, et les sources du projet et le fichier de configuration de sécurité SHALL être lus pour l'empreinte par une ouverture qui refuse un lien au dernier composant. Une source remplacée par un lien SHALL faire échouer l'empreinte.

#### Scenario: Source remplacée par un lien
- **GIVEN** une source Java dont le dernier composant devient un lien symbolique entre la résolution et la lecture
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test` exécute le test d'empreinte
- **THEN** l'empreinte échoue et le contenu visé par le lien n'est pas haché

#### Scenario: Lien substitué au temporaire de l'historique
- **GIVEN** un lien symbolique vers un fichier sentinelle à l'emplacement du temporaire de l'historique
- **WHEN** l'historique est écrit
- **THEN** l'écriture échoue et la sentinelle est intacte

#### Scenario: Plus d'entrée de liste pour le module
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-application/`

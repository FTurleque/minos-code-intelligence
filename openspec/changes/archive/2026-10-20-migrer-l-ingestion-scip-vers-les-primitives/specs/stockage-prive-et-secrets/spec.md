## ADDED Requirements

### Requirement: L'artefact d'un provider est lu sans suivre de lien et l'index figé est écrit par une primitive privée
L'ingestion SCIP SHALL ouvrir l'artefact d'index par un canal qui refuse lien symbolique et objet spécial, et SHALL écrire le fichier figé par une primitive d'E/S privée. Un artefact remplacé par un lien entre le contrôle et l'ouverture SHALL être refusé.

#### Scenario: Index remplacé par un lien
- **GIVEN** un index SCIP remplacé par un lien symbolique entre le contrôle de chemin et l'ouverture
- **WHEN** `./mvnw -B -ntp -pl minos-provider-scip -am test` exécute les tests de l'ingestion
- **THEN** l'ingestion est refusée et le contenu visé par le lien n'est pas lu

#### Scenario: Plus d'entrée de liste pour l'ingestion
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée pour `ScipIndexReader.java` ni `ScipSymbolSnapshotImporter.java`

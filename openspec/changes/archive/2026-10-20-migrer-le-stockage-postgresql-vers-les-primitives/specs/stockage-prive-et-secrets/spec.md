## ADDED Requirements

### Requirement: Le magasin PostgreSQL n'écrit pas ses fichiers de travail par un appel brut
Le magasin de snapshots PostgreSQL SHALL écrire ses fichiers de travail par les primitives d'E/S privées. Un fichier de travail remplacé par un lien symbolique SHALL faire échouer l'opération sans modifier la cible du lien et sans laisser de fichier de travail.

#### Scenario: Lien substitué au fichier de travail
- **GIVEN** un fichier de travail remplacé par un lien symbolique vers un fichier sentinelle
- **WHEN** `./mvnw -B -ntp -pl minos-storage-postgresql -am test` exécute le test du magasin
- **THEN** l'opération échoue, la sentinelle est intacte et le fichier de travail est supprimé

#### Scenario: Plus d'entrée de liste pour le magasin
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée sous `minos-storage-postgresql/`

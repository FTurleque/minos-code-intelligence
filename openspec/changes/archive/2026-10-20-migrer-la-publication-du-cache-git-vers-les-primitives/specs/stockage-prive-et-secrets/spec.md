## ADDED Requirements

### Requirement: Une entrée de cache de dépôt distant est publiée par un déplacement atomique de répertoire
Le matérialiseur de dépôts distants SHALL publier une entrée de cache par un déplacement atomique de répertoire, sans repli par copie et sans remplacer une entrée existante. Un système de fichiers sans rename atomique SHALL faire échouer la matérialisation avec le message public existant.

#### Scenario: Rename atomique non supporté
- **GIVEN** un déplaceur de répertoire qui signale l'absence de rename atomique
- **WHEN** `./mvnw -B -ntp -pl minos-integration-git -am test` exécute le test de matérialisation
- **THEN** la matérialisation échoue avec « remote repository cache requires atomic directory publication » et aucune entrée n'est créée

#### Scenario: Entrée apparue avant le déplacement
- **GIVEN** une entrée de cache créée entre la suppression de l'ancienne et le déplacement
- **WHEN** le répertoire temporaire est publié
- **THEN** la publication échoue et l'entrée présente n'est pas remplacée

#### Scenario: Plus d'entrée de liste pour le matérialiseur
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée pour `JGitRemoteRepositoryMaterializer.java`

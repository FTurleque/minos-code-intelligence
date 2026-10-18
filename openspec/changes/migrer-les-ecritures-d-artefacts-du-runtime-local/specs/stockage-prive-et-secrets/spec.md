## ADDED Requirements

### Requirement: Un paquet d'artefact et une mise de côté de répertoire sont atomiques sans repli
Un paquet d'artefact distribué SHALL être publié et un répertoire hostile ou périmé SHALL être mis de côté par un déplacement atomique. Un système de fichiers qui ne le permet pas SHALL faire échouer l'opération, sans copie ni déplacement non atomique, et sans modifier les droits du dossier de sortie de l'appelant.

#### Scenario: Déplacement atomique refusé pour un paquet
- **GIVEN** un déplaceur qui signale l'absence de déplacement atomique
- **WHEN** `./mvnw -B -ntp -pl minos-runtime-local -am test` exécute le test de publication d'un paquet
- **THEN** la publication échoue et la cible n'existe pas

#### Scenario: Dossier de sortie de l'appelant
- **GIVEN** un dossier de sortie dont le mode ou l'ACL est connu
- **WHEN** un paquet y est créé
- **THEN** le mode ou l'ACL du dossier est inchangé

#### Scenario: Plus d'entrée de liste pour les artefacts
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée `Files.move`, `Files.createTempFile`, `Files.newOutputStream` ni `Files.newByteChannel` pour ces six fichiers (hors l'entrée `LocalIsolatedIndexWorker`, reclassée)

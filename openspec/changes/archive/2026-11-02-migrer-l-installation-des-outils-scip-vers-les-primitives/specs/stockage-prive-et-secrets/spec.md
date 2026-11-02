## ADDED Requirements

### Requirement: Un fichier d'arbre d'installation hérite de l'accès de son dossier privé
Un fichier extrait ou copié dans un arbre d'installation SHALL être créé sans suivre de lien et sans réécriture de DACL : droits propriétaire seul sous POSIX, ACL du dossier hérité sous Windows. Une archive qui contient deux fois le même chemin SHALL être refusée.

#### Scenario: Entrée dupliquée dans une archive
- **GIVEN** une archive ZIP qui contient deux fois le même chemin
- **WHEN** l'extraction bornée est exécutée par `./mvnw -B -ntp -pl minos-provider-scip -am test`
- **THEN** l'extraction échoue sans chemin absolu dans le message et le premier fichier est intact

#### Scenario: Héritage d'ACL sous Windows
- **GIVEN** un dossier privé portant une entrée héritable accordée à un tiers de test
- **WHEN** un fichier d'arbre y est copié
- **THEN** le fichier porte cette entrée héritée (test `@EnabledOnOs(WINDOWS)`)

#### Scenario: Plus d'entrée de liste pour l'installation
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée pour `LockedNpmPackage.java`, `ManagedPolyglotScipRuntimeManager.java`, `ManagedScipProviderRuntimeManager.java` (hors `createDirectories`), `ManagedScipPythonRuntimeManager.java`, `PinnedArtifactSource.java`

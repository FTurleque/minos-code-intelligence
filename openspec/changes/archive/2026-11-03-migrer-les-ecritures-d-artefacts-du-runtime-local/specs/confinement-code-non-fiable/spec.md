## ADDED Requirements

### Requirement: Ce que MINOS relit dans une zone inscriptible par le provider n'est jamais hérité
Un journal de processus, une copie d'artefact conservée ou un paquet que MINOS relit après l'exécution d'un provider SHALL être créé exclusivement et durci, sans hériter l'accès de la zone que le provider peut écrire. La copie du projet que le provider lit SHALL, à l'inverse, garder l'accès hérité de son dossier.

#### Scenario: Copie d'artefact conservée sous Windows
- **GIVEN** un dossier de run portant une entrée héritable accordée à une identité tierce de test
- **WHEN** `./mvnw -B -ntp -pl minos-runtime-local -am test` exécute le test de conservation d'artefact (`@EnabledOnOs(WINDOWS)`)
- **THEN** la copie conservée ne porte pas cette entrée

#### Scenario: Nom réapparu avant la création d'un journal
- **GIVEN** un fichier qui réapparaît au nom d'un journal entre sa suppression et sa création
- **WHEN** le journal est ouvert
- **THEN** l'ouverture échoue et le fichier réapparu n'est pas adopté

#### Scenario: Copie du projet lisible par le conteneur
- **GIVEN** un dossier de travail portant une entrée héritable supplémentaire
- **WHEN** un fichier du projet y est copié
- **THEN** le fichier copié porte cette entrée héritée

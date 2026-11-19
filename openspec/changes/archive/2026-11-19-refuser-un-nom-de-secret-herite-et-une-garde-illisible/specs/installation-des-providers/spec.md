## ADDED Requirements

### Requirement: Une variable héritée à motif de secret n'atteint ni un provider ni un lanceur
L'environnement d'un provider d'indexation et celui d'un lanceur de confiance SHALL retirer toute variable héritée du processus MINOS dont le nom correspond au motif de secret, même si ce nom figure dans une liste d'autorisation. Les listes d'autorisation héritées SHALL NOT contenir de nom correspondant au motif ; un test SHALL échouer dans le cas contraire.

#### Scenario: Entrée de liste à motif de secret
- **GIVEN** une liste d'autorisation héritée à laquelle un nom à motif de secret (par exemple `EXAMPLE_TOKEN_FILE`) a été ajouté, et un environnement hérité qui définit cette variable
- **WHEN** `./mvnw -B -ntp -pl minos-runtime-local -am test "-Dtest=ProviderProcessEnvironmentInheritedSecretsTest" "-Dsurefire.failIfNoSpecifiedTests=false"` construit l'environnement du provider et celui du lanceur
- **THEN** la variable est absente des deux environnements

#### Scenario: Listes du dépôt
- **GIVEN** les listes d'autorisation héritées du dépôt
- **WHEN** le même test les parcourt
- **THEN** aucune entrée ne correspond au motif de secret et le test réussit

#### Scenario: Variable ordinaire héritée
- **GIVEN** un environnement hérité qui définit `PATH` et `JAVA_HOME`
- **WHEN** l'environnement du provider est construit
- **THEN** les deux variables sont transmises comme avant le changement

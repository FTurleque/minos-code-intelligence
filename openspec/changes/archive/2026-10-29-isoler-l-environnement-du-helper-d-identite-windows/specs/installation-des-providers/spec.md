## ADDED Requirements

### Requirement: Le helper d'identité de chemin Windows n'hérite pas de l'environnement de MINOS
Le helper PowerShell qui capture l'identité physique d'une racine de projet sous Windows SHALL recevoir la liste d'autorisation des lanceurs de confiance et ses deux variables cibles, jamais l'environnement hérité de MINOS. Une variable à motif de secret et `PSModulePath` SHALL en être absentes. Si l'environnement autorisé ne suffit pas au helper, la capture SHALL échouer et le lancement être refusé.

#### Scenario: Secrets présents dans l'environnement de la JVM
- **GIVEN** un environnement parent qui contient `SystemRoot`, `MINOS_TEAM_KEY_1`, `GITHUB_TOKEN` et `PGPASSWORD`
- **WHEN** `./mvnw.cmd -pl minos-runtime-local -am test "-Dtest=WindowsExecutionPathIdentityHelperEnvironmentTest"` est exécuté
- **THEN** l'environnement du helper est exactement `SystemRoot` et les deux cibles `MINOS_*_PATH_IDENTITY_TARGET`

#### Scenario: Chemin de modules hérité
- **GIVEN** un environnement parent qui contient `PSModulePath`, `JAVA_HOME`, `TEMP` et `TMP`
- **WHEN** l'environnement du helper est construit
- **THEN** `TEMP` et `TMP` sont transmis, `PSModulePath` et `JAVA_HOME` ne le sont pas

#### Scenario: Capture réelle sous Windows
- **GIVEN** deux répertoires existants sur un poste Windows
- **WHEN** leur identité est capturée avec l'environnement autorisé
- **THEN** deux identités distinctes sont rendues, sans aucune variable de secret transmise

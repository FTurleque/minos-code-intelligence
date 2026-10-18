## ADDED Requirements

### Requirement: Le lanceur de l'AppContainer est publié sans écraser une cible existante
Le lanceur PowerShell du bac à sable Windows SHALL être publié de façon exclusive et durable. Une cible publiée entre-temps par un autre processus SHALL n'être acceptée que si la vérification du lanceur passe ; un système de fichiers sans lien dur SHALL faire échouer la publication, sans chemin absolu dans le message.

#### Scenario: Lanceur publié par un autre processus
- **GIVEN** un lanceur déjà publié au contenu attendu
- **WHEN** `./mvnw -B -ntp -pl minos-runtime-local -am test` exécute le test de publication concurrente sous Windows
- **THEN** le lanceur est accepté après vérification et n'est pas remplacé

#### Scenario: Lanceur publié par un autre processus avec un contenu différent
- **GIVEN** un fichier au nom du lanceur dont le contenu diffère
- **WHEN** la publication s'exécute
- **THEN** le lanceur est refusé et le fichier existant n'est pas écrasé

### Requirement: La sortie de la sonde de l'AppContainer est bornée et privée
La sonde de capacité de l'AppContainer SHALL lire la sortie du lanceur par un tube vidé en continu vers un fichier privé, borné par octets par flux. Une sortie supérieure au tampon d'un tube SHALL ne pas faire déclarer la sonde indisponible.

#### Scenario: Sortie volumineuse
- **GIVEN** un lanceur qui écrit plus que le tampon d'un tube puis réussit
- **WHEN** `AppContainerProbeOutputTest` s'exécute sous Windows
- **THEN** la sonde réussit et le fichier de sortie est borné et privé

#### Scenario: Plus d'entrée de liste pour le bac à sable Windows
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit et la liste ne contient plus d'entrée `Files.move` pour `SandboxLauncherScript.java` ni `ProcessBuilder.redirect(file)` pour `WindowsAppContainerWorkerSandboxBackend.java`

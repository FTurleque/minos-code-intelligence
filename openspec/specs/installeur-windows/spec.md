# installeur-windows Specification

## Purpose
Définit le comportement de l'installeur Windows de MINOS au-delà de la copie des fichiers : pendant la configuration, une rangée de progression par étape sélectionnée, alimentée par un protocole fermé que les scripts écrivent dans un fichier et dont l'état final découle de leur code de sortie ; à la désinstallation, le retrait de l'instance Docker (projet Compose, et sur purge ses volumes et images inutilisées), du bloc Codex borné par les marqueurs MINOS et du dossier vide du menu Démarrer, chaque échec étant journalisé et annoncé.

## Requirements

### Requirement: La désinstallation retire le projet Compose de l'instance Docker
À la désinstallation, l'installeur Windows SHALL arrêter puis retirer tous les conteneurs du projet Compose de l'instance, services des profils PostgreSQL et Ollama compris, puis ses réseaux, avant de supprimer ses fichiers. Avec la purge, il SHALL ensuite retirer les volumes du projet et les volumes externes de l'instance. Il SHALL NOT toucher les ressources d'un autre projet.

#### Scenario: Purge d'une instance avec PostgreSQL et Ollama
- **GIVEN** un démon simulé portant l'instance `minos-mcp-prod` (conteneurs PostgreSQL et Ollama en marche, volumes montés, deux réseaux) et une autre instance
- **WHEN** `powershell -File scripts/install/verify-docker-instance-removal.ps1` est exécuté
- **THEN** conteneurs, réseaux et volumes de `minos-mcp-prod` sont retirés et ceux de l'autre instance restent

#### Scenario: Ordre dans le désinstalleur
- **GIVEN** le gabarit de l'installeur
- **WHEN** `UninstallCleanupContractTest` est exécuté par `./mvnw -B -ntp -pl minos-app -am test`
- **THEN** `RemoveManagedDockerInstance` précède `RemoveMinosProgramPayload` et `DeleteMinosUserData`, et `compose down` inclut les profils `postgresql` et `ollama`

### Requirement: Sans purge, aucune donnée Docker n'est supprimée
Quand l'utilisateur refuse la purge, la désinstallation SHALL arrêter et retirer les conteneurs et les réseaux du projet Compose de l'instance, et SHALL NOT retirer aucun volume.

#### Scenario: Désinstallation sans purge
- **GIVEN** l'instance simulée `minos-mcp-prod`
- **WHEN** `verify-docker-instance-removal.ps1` appelle le script de retrait sans `-Purge`
- **THEN** aucun conteneur de l'instance ne tourne, tous les volumes restent et `docker volume rm` n'a pas été appelé

### Requirement: Un échec de retrait Docker est journalisé et annoncé
Chaque appel Docker du retrait SHALL être vérifié par son code de sortie. Un échec SHALL produire une ligne qui nomme l'objet, un verdict d'échec et un code de sortie non nul ; Docker indisponible SHALL donner un code distinct. Le désinstalleur SHALL recopier ces lignes dans son journal, toujours écrit, et SHALL annoncer l'échec à l'utilisateur hors mode silencieux.

#### Scenario: Volume refusé par le démon
- **GIVEN** un démon simulé qui refuse `docker volume rm`
- **WHEN** le script de retrait est exécuté avec `-Purge`
- **THEN** il sort en code 1, écrit `step=volume result=failed name=minos-mcp-prod-postgres-data` puis `MINOS DOCKER INSTANCE REMOVAL FAILED`

#### Scenario: Démon arrêté
- **GIVEN** un démon simulé qui ne répond pas
- **WHEN** le script de retrait est exécuté
- **THEN** il sort en code 3 et écrit `step=docker result=unavailable`

#### Scenario: Aucune redirection qui masque
- **GIVEN** le gabarit de l'installeur
- **WHEN** `powershell -File scripts/install/verify-installer-template.ps1` est exécuté
- **THEN** il exige `UninstallLogging=yes` et `LogScriptOutput`, et refuse `docker volume rm` dans le gabarit

### Requirement: La désinstallation retire le bloc Codex borné par les marqueurs MINOS
`configure-codex-mcp.ps1 -Action Uninstall` SHALL retirer, après sauvegarde, le bloc borné par `# BEGIN/END MINOS MANAGED MCP SERVER`, que son fichier d'état soit absent ou que le bloc ait été adopté, sans modifier le reste du fichier. Il SHALL NOT modifier une section `[mcp_servers.minos]` sans marqueurs. Un avertissement de retrait SHALL atteindre le journal de désinstallation.

#### Scenario: Fichier d'état absent
- **GIVEN** un `config.toml` avec un autre serveur, le bloc MINOS, une section qui le suit, et aucun fichier d'état
- **WHEN** `powershell -File scripts/install/verify-codex-mcp-integration.ps1` est exécuté
- **THEN** le bloc MINOS a disparu, les deux autres sections sont intactes et une sauvegarde existe

#### Scenario: Section non gérée
- **GIVEN** un `config.toml` contenant seulement `[mcp_servers.minos]` sans marqueurs, et aucun fichier d'état
- **WHEN** l'action `Uninstall` est exécutée
- **THEN** le fichier est inchangé

### Requirement: La purge retire les images MINOS inutilisées
Avec la purge, la désinstallation SHALL retirer toutes les images `minos-code-intelligence:*` qu'aucun conteneur n'utilise, et la question de purge SHALL l'annoncer. Une image utilisée par un conteneur SHALL être gardée et journalisée, sans échec. Sans purge, le désinstalleur SHALL NOT demander le retrait des images. Une image d'un autre dépôt SHALL NOT être retirée.

#### Scenario: Images de plusieurs installations
- **GIVEN** un démon simulé portant trois images MINOS inutilisées, une image MINOS utilisée par une autre instance et une image d'un autre dépôt
- **WHEN** `verify-docker-instance-removal.ps1` appelle le script de retrait avec `-Purge -RemoveImages`
- **THEN** les trois images inutilisées sont retirées, l'image utilisée reste avec `result=kept-in-use`, l'autre dépôt reste, et le code de sortie est 0

#### Scenario: Paramètres du désinstalleur
- **GIVEN** le gabarit de l'installeur
- **WHEN** `UninstallCleanupContractTest` est exécuté
- **THEN** `-RemoveImages` n'est passé qu'avec `-Purge`, et la question de purge nomme les images Docker MINOS

### Requirement: Le dossier vide du menu Démarrer est retiré
La désinstallation SHALL retirer le dossier `{userprograms}\MINOS` une fois vide.

#### Scenario: Gabarit
- **GIVEN** le gabarit de l'installeur
- **WHEN** `UninstallCleanupContractTest` est exécuté
- **THEN** `[UninstallDelete]` contient `Type: dirifempty; Name: "{userprograms}\MINOS"`

### Requirement: Une rangée de progression par étape de configuration sélectionnée
Pendant la configuration post-installation, l'installeur Windows SHALL afficher sur la page d'installation une rangée (libellé et barre) pour chaque étape que les choix de l'assistant déclenchent : paramètres d'exécution, image Docker, PostgreSQL, Ollama, modèle Ollama, validation du backend, chaque client IA coché. Une étape non sélectionnée SHALL NOT avoir de rangée. Le libellé SHALL nommer l'étape sans son état, que seule la barre porte.

#### Scenario: Libellé sans état
- **GIVEN** le modèle de l'installeur au HEAD
- **WHEN** `InstallerSetupProgressContractTest.stepLabelNamesTheStepAndLeavesTheStateToTheBar` est exécuté
- **THEN** le libellé d'une rangée est le nom de son étape, sans « en attente », « en cours » ni « terminée »

#### Scenario: Backend Docker avec PostgreSQL et un client
- **GIVEN** le modèle de l'installeur au HEAD
- **WHEN** le test de contrat `InstallerSetupProgressContractTest` est exécuté par `./mvnw -B -ntp -pl minos-app -am test`
- **THEN** il constate que chaque rangée est créée sous la condition de sélection de son étape

#### Scenario: Installeur construit
- **GIVEN** un installeur construit par `scripts/release/build-local-windows-candidate.ps1`
- **WHEN** une installation interactive choisit le backend natif et un client IA
- **THEN** la page d'installation montre trois rangées, qui passent chacune au vert à la fin de leur étape

### Requirement: Les scripts signalent leurs étapes par un protocole fermé
Les scripts de configuration SHALL n'écrire la progression que si `-ProgressPath` est fourni, sous la forme `MINOS-PROGRESS step=<id> state=<état>[ percent=<n>]`, avec un identifiant d'une liste fermée, un état parmi `running`, `done`, `skipped` et un entier de 0 à 100. Toute autre valeur SHALL être ignorée sans écriture. Sans `-ProgressPath`, leur sortie SHALL rester inchangée.

#### Scenario: Ligne valide
- **GIVEN** la fonction d'émission extraite d'un script livré
- **WHEN** `python scripts/install/test_setup_progress.py -v` l'appelle avec `docker-image` et `running`
- **THEN** le fichier contient exactement `MINOS-PROGRESS step=docker-image state=running`

#### Scenario: Valeur refusée
- **GIVEN** la même fonction
- **WHEN** elle reçoit l'état `failed`, l'identifiant `Image Docker` ou le pourcentage 150
- **THEN** rien n'est écrit

#### Scenario: Sans chemin
- **GIVEN** la même fonction sans `-ProgressPath`
- **WHEN** elle est appelée
- **THEN** aucun fichier n'est créé

### Requirement: La progression est lue sans rediriger la sortie des scripts
L'installeur SHALL lancer les étapes par `Exec`, sans redirection de leur sortie, et SHALL lire le fichier de progression par un minuteur pendant l'attente. Il SHALL n'appliquer que les lignes terminées qui respectent la grammaire et désignent une rangée existante, et SHALL NOT afficher ni journaliser le reste du fichier.

#### Scenario: Contrat du modèle
- **GIVEN** le modèle de l'installeur
- **WHEN** `scripts/install/verify-installer-template.ps1` et le test de contrat sont exécutés
- **THEN** ils constatent `SetTimer`, `CreateCallback`, `KillTimer`, le passage de `-ProgressPath` aux scripts du backend et des clients, et l'absence d'`ExecAndLogOutput`

### Requirement: L'état final d'une étape découle du code de sortie
À la fin d'un script, l'installeur SHALL passer en vert les rangées en cours si le code est 0, et en rouge les rangées en cours si le code est non nul ; si aucune n'est en cours, la première rangée en attente, ou à défaut la dernière rangée du groupe, SHALL passer en rouge. Une rangée jamais signalée SHALL NOT passer au vert. Le message d'erreur existant SHALL être conservé.

#### Scenario: Échec du backend
- **GIVEN** le modèle de l'installeur
- **WHEN** le test de contrat lit la fin de l'étape du backend
- **THEN** l'échec marque les rangées en cours en rouge avant le `MsgBox` existant, et les rangées des clients restent « non exécutée »

### Requirement: Un pourcentage n'est affiché que s'il est fourni par l'outil
Une barre SHALL être indéterminée pendant une étape en cours, sauf si un pourcentage a été reçu pour elle. Seul le téléchargement du modèle Ollama SHALL émettre un pourcentage, lu dans la sortie de `ollama pull` ; si cette sortie n'en contient pas, aucun SHALL NOT être émis. La sortie reste conservée pour le message d'échec.

#### Scenario: Sortie d'Ollama avec retours chariot
- **GIVEN** la fonction d'analyse extraite de `configure-m30-docker-services.ps1`
- **WHEN** `python scripts/install/test_setup_progress.py -v` lui passe une sortie avec `\r`, séquences ANSI et `42%`
- **THEN** elle rend 42, et elle ne rend rien pour une sortie sans pourcentage

### Requirement: La progression ne contient aucun secret et laisse le mode smoke inchangé
Une ligne de protocole SHALL ne contenir ni chemin, ni URL, ni nom de modèle, ni mot de passe. En mode smoke (`IsSmokeBuild`), l'installeur SHALL NOT créer de rangée ni passer `-ProgressPath`. Les scripts livrés SHALL rester en ASCII seul.

#### Scenario: Fonctions d'émission sans texte libre
- **GIVEN** les scripts livrés
- **WHEN** le test de contrat et `M29InstallerBackendLifecycleContractTest` sont exécutés
- **THEN** chaque fonction d'émission n'a que les paramètres d'étape, d'état et de pourcentage, et les scripts sont en ASCII

#### Scenario: Mode smoke
- **GIVEN** le modèle de l'installeur
- **WHEN** le test de contrat lit la création des rangées
- **THEN** elle sort sur `IsSmokeBuild()` avant de créer une rangée

## Documentation liée

- Dossier durable (vues, traçabilité, écarts) : [installeur-windows](../../../docs/architecture/diagrams/capacites/installeur-windows.md)

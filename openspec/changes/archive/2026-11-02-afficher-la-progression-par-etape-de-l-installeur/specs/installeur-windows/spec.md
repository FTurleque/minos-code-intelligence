## ADDED Requirements

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

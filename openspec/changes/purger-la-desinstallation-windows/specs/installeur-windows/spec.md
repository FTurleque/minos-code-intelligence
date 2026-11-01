## ADDED Requirements

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

### Requirement: Le dossier vide du menu Démarrer est retiré
La désinstallation SHALL retirer le dossier `{userprograms}\MINOS` une fois vide.

#### Scenario: Gabarit
- **GIVEN** le gabarit de l'installeur
- **WHEN** `UninstallCleanupContractTest` est exécuté
- **THEN** `[UninstallDelete]` contient `Type: dirifempty; Name: "{userprograms}\MINOS"`

# Proposal

## Why

Constat du 1er novembre 2026, sur le poste de l'utilisateur. Une installation Windows 1.3.0 avec backend MCP Docker (instance `minos-mcp-prod`, PostgreSQL et Ollama gérés) est désinstallée en répondant « Oui » à « Supprimer également toutes les données MINOS locales ? ». Les dossiers, la clé `HKCU\Software\FTurleque\MINOS`, l'entrée du `PATH` et le conteneur `minos-mcp-prod` sont supprimés. Restent :

- les conteneurs `minos-mcp-prod-minos-postgres-1` et `minos-mcp-prod-minos-ollama-1`, toujours en marche, et `minos-mcp-prod-minos-ollama-bootstrap-1` arrêté ;
- les volumes `minos-mcp-prod-postgres-data` et `minos-mcp-prod-ollama-models` ;
- les réseaux `minos-mcp-prod_default` et `minos-mcp-prod_minos-runtime` ;
- les images `minos-code-intelligence:*` des installations successives ;
- le bloc `# BEGIN/END MINOS MANAGED MCP SERVER` de `~/.codex/config.toml`, qui désigne un `minos.exe` supprimé ;
- le dossier vide `{userprograms}\MINOS` du menu Démarrer.

Aucun de ces échecs n'a été signalé : la suppression des volumes masquait son erreur, et le retrait Codex avalait ses avertissements.

## What Changes

- **Retrait du projet Compose de l'instance, par étiquette** : un script livré, `docker/scripts/remove-docker-instance.ps1`, arrête puis retire les conteneurs du projet Compose (services des profils `postgresql` et `ollama` compris) et ses réseaux ; avec la purge, ses volumes (ceux du projet et les deux volumes externes de l'instance). Il ne dépend pas des fichiers Compose. Chaque appel Docker est vérifié ; chaque étape écrit une ligne ; le verdict et le code de sortie distinguent succès, échec et Docker indisponible.
- **Désinstalleur** : ce script est appelé à `usPostUninstall`, avant la suppression de `{app}\docker`, avec `-Purge` seulement si l'utilisateur a choisi la purge. Sa sortie est recopiée dans le journal de désinstallation, désormais toujours écrit (`UninstallLogging=yes`) ; un échec est annoncé à l'utilisateur (hors mode silencieux). L'ancien `docker volume rm … >NUL 2>&1` disparaît.
- **Cycle de vie Docker** : l'action `Uninstall` de `mcp-lifecycle.ps1` fait `compose down` avec les profils `postgresql` et `ollama` ; les volumes de données restent `external`, donc conservés.
- **Codex** : `configure-codex-mcp.ps1 -Action Uninstall` retire le bloc borné par les marqueurs MINOS même sans fichier d'état, ou quand il avait été adopté (`ownership=preexisting`), après sauvegarde, sans toucher au reste du fichier ni à une section `[mcp_servers.minos]` sans marqueurs. Un bloc modifié par l'utilisateur après l'installation reste préservé, avec un avertissement.
- **Avertissements des retraits de clients** : le script temporaire de `RemoveMcpClientIntegrations` recopie avertissements et erreurs dans le journal de désinstallation et sort en code 1.
- **Menu Démarrer** : `[UninstallDelete] Type: dirifempty` sur `{userprograms}\MINOS`.
- **Images** : décision du propriétaire en attente (voir `design.md`, D4).

## Capabilities

### New Capabilities

(aucune ; la capacité `installeur-windows` est aussi introduite par le changement actif `afficher-la-progression-par-etape-de-l-installeur`)

### Modified Capabilities

- `installeur-windows` : exigences ajoutées sur la désinstallation (retrait du projet Compose, conservation sans purge, échecs journalisés, bloc Codex, dossier du menu Démarrer).

## Hors périmètre

- Entrées des autres clients IA (Claude, Copilot) : gérées par `configure-mcp-clients.ps1` et son fichier d'état, non mis en cause par le constat.
- Une base PostgreSQL externe : jamais touchée.
- Installation ZIP (`install-windows.ps1`) : elle n'a pas de désinstalleur.
- Désinstallation Linux : MINOS n'en livre pas.
- Ressources d'une instance dont le nom n'est plus enregistré (clé de registre déjà supprimée par une désinstallation précédente) : le remède est manuel et le message d'échec donne les commandes de recherche.

## Impact

- **Modules du reactor** : aucun code Java de production ; test ajouté dans `minos-app` (`com.minos.packaging.UninstallCleanupContractTest`).
- **Scripts livrés** (ASCII, Windows PowerShell 5.1) : `docker/scripts/remove-docker-instance.ps1` (nouveau, ajouté à `build-windows-distribution.ps1` et à la liste exigée de `build-windows-installer.ps1` ; le dossier `docker` est déjà géré en entier par `update-installation.ps1`), `docker/scripts/mcp-lifecycle.ps1`, `scripts/install/configure-codex-mcp.ps1`, `packaging/windows/minos-installer.iss.template`.
- **Surfaces publiques** : CLI, API Java, MCP, IntelliJ et NEXUS inchangés.
- **ADR** : aucun nouvel ADR ; applique `.claude/rules/securite.md` (point 1, fail-closed : aucun échec masqué) et la règle de purge explicite existante.
- **Gates** : `scripts/install/verify-installer-template.ps1`, `scripts/install/verify-codex-mcp-integration.ps1` (contrat `preexisting` modifié), `scripts/install/verify-docker-instance-removal.ps1` (nouveau) ; les deux derniers sont exécutés par chaque construction de distribution Windows et par `UninstallCleanupContractTest` (`Verify (windows-2022)`).
- **Plateformes** : Windows seulement.

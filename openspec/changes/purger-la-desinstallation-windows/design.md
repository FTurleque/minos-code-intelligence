# Design

## Context

Constat d'un essai réel du 1er novembre 2026 : désinstallation d'une installation 1.3.0 avec backend MCP Docker et purge acceptée (voir `proposal.md`). `TPL` = `packaging/windows/minos-installer.iss.template`, `LC` = `docker/scripts/mcp-lifecycle.ps1`, `CX` = `scripts/install/configure-codex-mcp.ps1`, `CC` = `docker/compose-mcp.connected.yaml`. HEAD analysé : `98dfbcca` (`develop`).

## État vérifié au HEAD

- Séquence de désinstallation (`TPL:1796-1811`) : à `usUninstall`, `RemoveMcpClientIntegrations`, retrait du `PATH`, puis la question de purge (`PromptForUserDataRemoval`, `TPL:1541-1560`) ; Inno exécute ensuite `[UninstallRun]` (`TPL:60-62`), dont `configure-docker-mcp.ps1 -Stop`. À `usPostUninstall`, `RemoveMinosProgramPayload` (supprime `{app}\docker`), puis `DeleteMinosUserData`.
- `configure-docker-mcp.ps1 -Stop` (`docker/scripts/configure-docker-mcp.ps1:120-131`) appelle l'action `Uninstall` de `LC` (`LC:676-693`) : `compose down --timeout 10 --remove-orphans --volumes`, sans `--profile`.
- `CC:287-289`, `CC:330-332`, `CC:364-366` : `minos-postgres`, `minos-ollama-bootstrap` et `minos-ollama` sont sous les profils `postgresql` et `ollama`. Compose n'agit que sur les services des profils actifs : `down` les laisse tourner, et le réseau `minos-runtime` qu'ils occupent ne peut pas être retiré. **Cause des conteneurs et réseaux restants.**
- `CC:414-419` : les volumes `minos-postgres-data` et `minos-ollama-models` sont `external`, nommés `<projet>-postgres-data` et `<projet>-ollama-models` (`docker/scripts/configure-m30-docker-services.ps1:429-432`), créés avec l'étiquette `io.minos.installation=<projet>` (`:228-235`). `down --volumes` ne les retire jamais.
- `PurgeManagedDockerVolumes` (`TPL:1562-1571`) : `cmd /C docker volume rm "<i>-postgres-data" "<i>-ollama-models" >NUL 2>&1`, code ignoré, alors que les conteneurs PostgreSQL et Ollama montent encore ces volumes. **Cause des volumes restants et du silence.**
- `LC:681-689` : l'action `Uninstall` retire les images de l'installation courante (`installation.json`, `image` et `queryImage`) ; les images des installations précédentes, de tag différent (`LC:328-335`), ne sont jamais retirées.
- `CX:244-246` (avant correctif) : `Uninstall-Toml` rend la main sans rien retirer si `ownership=preexisting` ; `CX:315-317` : sans fichier d'état, l'action `Uninstall` ne fait rien. Or `CX:184-189` adopte en `preexisting` un bloc compatible trouvé sans état, cas exact d'une réinstallation après une purge qui a supprimé l'état (`%LOCALAPPDATA%\MINOS`) en laissant le bloc. Le commentaire de `CX:194-196` fait déjà des marqueurs la frontière de propriété.
- `TPL:1696-1723` : le script temporaire de `RemoveMcpClientIntegrations` transforme toute exception en `Write-Warning`, sort en code 0, et sa sortie n'est lue par personne.
- `TPL:57-58` : `[Icons]` crée `{userprograms}\MINOS\…` ; `[UninstallDelete]` (`TPL:64-65`) ne retire pas le dossier.
- `TPL:37` : `UninstallLogMode=overwrite` ; aucun `UninstallLogging` : sans `/LOG`, le désinstalleur n'écrit pas de journal, et les `Log(...)` existants sont perdus.
- Poste de l'utilisateur au 1er novembre (lecture `docker ps -a`, `docker volume ls`, `docker network ls`, `docker images`) : les ressources `minos-mcp-prod*` et les images `minos-code-intelligence:*` ont déjà été retirées à la main ; la preuve sur poste réel demande une nouvelle installation.

## Décisions

### D1 — Retrait par étiquette, par un script livré

Options :

- **A. `docker compose down` avec les profils, dans l'action `Uninstall`.** Nécessaire (corrige la cause), mais dépend des fichiers Compose du runtime et du `.env` ; l'action supprime ensuite le runtime, et un échec antérieur (`Compose` lève) laisse tout en place.
- **B. Retrait par étiquette `com.docker.compose.project=<instance>`** (et `io.minos.installation=<instance>`), sans fichiers Compose.
- **C. Script Pascal en ligne, comme `RemoveMinosProgramPayload`.** Pas testable hors de l'installeur.

**Retenu : A + B.** L'action `Uninstall` de `LC` inclut les profils (correct en soi, et utile hors désinstalleur). Le désinstalleur appelle ensuite `docker/scripts/remove-docker-instance.ps1`, livré dans `{app}\docker\scripts`, qui retrouve conteneurs, réseaux et volumes par étiquette : il fonctionne même si le runtime a déjà disparu. Il s'exécute à `usPostUninstall`, avant `RemoveMinosProgramPayload` (qui supprime son dossier) ; l'ordre `arrêt → retrait des conteneurs → réseaux → volumes` garantit qu'aucun volume n'est encore monté. Le script est testé hors installeur contre un faux `docker` qui reproduit les refus du démon.

### D2 — Sans purge

Les conteneurs du projet sont arrêtés (`docker stop --time 10`, PostgreSQL termine proprement) puis retirés, et les réseaux retirés : ce ne sont pas des données. Aucun volume n'est touché ; une réinstallation retrouve PostgreSQL et Ollama. C'est le comportement attendu de l'utilisateur (« arrêter tout de même les conteneurs »).

### D3 — Échecs visibles

- Chaque appel Docker est vérifié (`$LASTEXITCODE`, stderr non terminal sous Windows PowerShell 5.1). Une ligne `MINOS-DOCKER-REMOVAL step=… result=…` par objet, le verdict en dernière ligne ; code 0, 1 (au moins un échec) ou 3 (Docker absent ou démon arrêté). Les lignes ne portent que des noms d'objets Docker, aucun chemin.
- Le désinstalleur recopie ces lignes dans son journal (`LogScriptOutput`), désormais toujours écrit (`UninstallLogging=yes`, dossier temporaire de l'utilisateur). Un code non nul est annoncé par une boîte de message qui nomme l'instance et donne deux commandes de recherche ; pas de boîte en mode silencieux. Code 3 sur un poste dont le backend enregistré n'est pas Docker : journalisé seulement (aucune ressource à retirer).
- Le script de retrait des clients MCP recopie avertissements et erreurs dans le même journal et sort en code 1.

### D4 — Images `minos-code-intelligence:*` (décision du propriétaire, en attente)

- **A. (recommandée)** La purge retire toutes les images `minos-code-intelligence:*` qu'aucun conteneur n'utilise ; la boîte de confirmation de purge l'annonce. Une image utilisée par une autre instance est gardée et journalisée.
- **B.** Une seconde question explicite après le « Oui » (« Non » par défaut).
- **C.** Statu quo : seules les images de l'installation courante sont retirées (`LC:681-689`, déjà fait même sans purge).

Le script expose `-RemoveImages` (testé) ; le désinstalleur ne le passe qu'une fois la décision prise.

### D5 — Codex : les marqueurs font la propriété

Seul MINOS écrit `# BEGIN/END MINOS MANAGED MCP SERVER`. À la désinstallation, le bloc qu'ils bornent est retiré, après sauvegarde, que l'état soit absent, `managed` ou `preexisting`, et que le mode enregistré soit `toml` ou `cli` (reste d'une installation Desktop précédente). Sans fichier d'état, aucune empreinte n'est connue : le bloc est retiré sans ce contrôle, avec une sauvegarde complète du fichier et la raison `missing-state` au journal. Exceptions conservées : avec un état, un bloc dont l'empreinte diffère de celle enregistrée (modifié par l'utilisateur) reste en place avec un avertissement, désormais journalisé ; une section `[mcp_servers.minos]` sans marqueurs n'est jamais touchée. Rupture voulue du contrat `preexisting` de `scripts/install/verify-codex-mcp-integration.ps1`.

## Direction des dépendances

Aucun code Java de production ; aucun module touché hors tests de `minos-app`.

## Capacités

- Retrait du projet Compose à la désinstallation, avec et sans purge : **qualifié** par simulation (`verify-docker-instance-removal.ps1`) ; **partiel** tant que la tâche manuelle 4.2 n'a pas désinstallé une installation réelle.
- Retrait du bloc Codex sans état : **qualifié** (`verify-codex-mcp-integration.ps1`).

## Plateformes

Windows seulement : installeur Inno Setup, Windows PowerShell 5.1 et `pwsh`. Linux n'a pas de désinstalleur MINOS ; le script de retrait n'y est pas livré.

## Risques

- Une seconde instance nommée comme la première partagerait les étiquettes : impossible, le nom d'instance est le nom du projet Compose, unique sur un démon.
- Retrait forcé (`rm -f`) après un arrêt incomplet : seulement après `stop --time 10` ; PostgreSQL est conçu pour reprendre après un arrêt brutal.

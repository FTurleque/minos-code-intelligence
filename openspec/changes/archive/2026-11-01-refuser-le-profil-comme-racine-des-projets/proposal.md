# Proposal

## Why

Constat du 31 octobre 2026, sur le poste réel de l'utilisateur. Sans réglage précédent (première installation, ou réinstallation après une désinstallation avec purge, qui efface `HKCU\Software\FTurleque\MINOS`), l'installeur Windows propose le profil de l'utilisateur comme « Racine des projets » du backend MCP Docker (`packaging/windows/minos-installer.iss.template:943`, valeur par défaut `{%USERPROFILE}`). Accepter la page tel quel a produit `MINOS_PROJECTS_DIR="C:/Users/<compte>"` dans `%LOCALAPPDATA%\MINOS\docker\runtime\.env` : tout le profil (AppData, `.ssh`, données des navigateurs, jetons des clients IA, secrets de MINOS lui-même) est monté en lecture seule dans `minos-mcp` et dans `minos-admin`, et ce dernier a une sortie réseau.

Aucune des trois portes d'entrée ne refuse une telle racine : l'assistant ne vérifie que l'existence du dossier (`:1055`), le paramètre `/DOCKERPROJECTSROOT` et le mode silencieux passent directement au commutateur, et `scripts/install/switch-mcp-backend.ps1:98-103` comme `scripts/install/install-windows.ps1:82-89` n'exigent qu'un dossier existant.

## What Changes

- **Plus de valeur par défaut** : sans réglage précédent ni `/DOCKERPROJECTSROOT`, le champ « Racine des projets » est vide et obligatoire.
- **Refus d'une racine dangereuse**, à chaque porte d'entrée (page de l'assistant, commutateur de backend appelé par l'installeur en mode interactif ou silencieux, installation ZIP) : une racine de volume, le profil de l'utilisateur ou l'un de ses parents, et tout dossier qui contient, est, ou se trouve dans les données d'application de l'utilisateur (`%LOCALAPPDATA%`, `%APPDATA%`) ou un dossier de MINOS (installation, données, runtime Docker, données Docker). Le refus a lieu avant toute écriture et son message ne contient aucun chemin.
- **Une réinstallation ne reprend pas silencieusement une racine dangereuse** enregistrée : la page la refuse et demande un autre dossier ; le commutateur la refuse en mode silencieux.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `distribution-docker` : deux exigences ajoutées (aucune racine de projets proposée par défaut ; racine dangereuse refusée avant tout montage).

## Hors périmètre

- **Migration d'un runtime Docker existant** qui monte déjà le profil. Choisir une autre racine bute sur la garde de correspondance des chemins (`ProjectPathMappingService.configure`, issue `CONFLICT`, « refusing to replace ») : le remède documenté est une désinstallation avec purge puis une réinstallation. Une commande de remplacement explicite de la correspondance est un changement séparé.
- **Points de reparse et jonctions** : une jonction placée dans un dossier accepté et pointant vers le profil n'est pas résolue ; Docker Desktop monte la cible. Le refus porte sur le chemin saisi.
- Dossiers sensibles placés ailleurs que dans le profil, AppData ou les dossiers de MINOS (par exemple un `.ssh` déplacé) : le choix d'un dossier dédié reste de la responsabilité de l'utilisateur.
- Installation Linux et images Docker : aucune racine par défaut n'y est proposée (`docker/scripts/*.ps1` reçoivent la racine du commutateur).

## Impact

- **Modules du reactor** : aucun code de production Java. Tests ajoutés dans `minos-app` (`com.minos.packaging`).
- **Scripts livrés** : `packaging/windows/minos-installer.iss.template`, `scripts/install/switch-mcp-backend.ps1`, `scripts/install/install-windows.ps1` (ASCII, Windows PowerShell 5.1). Aucun nouveau fichier livré : les listes de `build-windows-distribution.ps1` et `update-installation.ps1` sont inchangées.
- **Surfaces publiques** : CLI, API Java, MCP, IntelliJ et NEXUS inchangés. Le paramètre `-ProjectsRoot` des scripts d'installation et `/DOCKERPROJECTSROOT` de l'installeur gardent leur nom ; ils refusent désormais les racines dangereuses (rupture voulue, fail-closed).
- **ADR** : aucun nouvel ADR ; la règle applique `.claude/rules/securite.md` (points 1 et 2) et rejoint celle de `PrivateLocalStorage.refuseSharedLocation` pour les dossiers privés.
- **Gates à rejouer** : `scripts/install/verify-installer-template.ps1` (`Require`/`Forbid`), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows seulement (installeur Inno Setup et scripts d'installation Windows).

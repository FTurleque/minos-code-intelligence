# Utiliser MINOS via MCP

MINOS expose un serveur **Model Context Protocol local via STDIO**. Les **16 tools** restent read-only.

## Mode recommandé : runtime natif

Après installation Windows, le serveur natif recommandé est l'exécutable embarqué :

```text
command = C:\Users\<user>\AppData\Local\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=C:\Users\<user>\AppData\Local\MINOS\data
```

Exemple conceptuel :

```json
{
  "command": "C:\\Users\\<user>\\AppData\\Local\\Programs\\MINOS\\app\\minos.exe",
  "args": ["mcp"],
  "env": {
    "MINOS_HOME": "C:\\Users\\<user>\\AppData\\Local\\MINOS\\data"
  }
}
```

La CLI et le MCP lisent ainsi le même registre et le même snapshot actif.

`minos.cmd mcp` reste valide pour un lancement manuel, mais les clients MCP sont configurés avec `app\minos.exe` afin d'éviter les différences de lancement des wrappers `.cmd` sous Windows.

## Configuration automatique depuis `setup.exe`

Le setup Windows propose désormais des cases à cocher indépendantes :

```text
Connecter le MCP natif MINOS à :
  ☐ GitHub Copilot — JetBrains / IntelliJ
  ☐ GitHub Copilot CLI
  ☐ Claude Code
  ☐ Claude Desktop
  ☐ OpenAI Codex
```

Docker n'est requis pour **aucune** de ces intégrations.

Toutes utilisent :

```text
<installation>\app\minos.exe mcp
MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Le gestionnaire installé est :

```text
<installation>\integration\configure-mcp-clients.ps1
```

Il applique les règles suivantes :

- aucune entrée MCP existante nommée `minos` n'est écrasée si elle n'a pas été créée par MINOS ;
- les fichiers JSON modifiés sont sauvegardés avant écriture ;
- les autres serveurs MCP et les autres propriétés des fichiers de configuration sont conservés ;
- l'état des intégrations créées est enregistré sous `%LOCALAPPDATA%\MINOS\mcp-client-integrations.json` ;
- la désinstallation retire uniquement les entrées gérées par MINOS ;
- si une entrée gérée a été modifiée manuellement depuis l'installation, elle est conservée plutôt que supprimée aveuglément ;
- le journal est `%LOCALAPPDATA%\MINOS\mcp-clients.log`.

Les cases sont décochées par défaut : la modification d'un client tiers reste un choix explicite de l'utilisateur.

## GitHub Copilot dans IntelliJ / JetBrains

Le setup peut fusionner l'entrée `minos` dans la configuration utilisateur globale du plugin Copilot JetBrains :

```text
%LOCALAPPDATA%\github-copilot\intellij\mcp.json
```

La structure utilisée est :

```json
{
  "servers": {
    "minos": {
      "command": "C:\\Users\\<user>\\AppData\\Local\\Programs\\MINOS\\app\\minos.exe",
      "args": ["mcp"],
      "env": {
        "MINOS_HOME": "C:\\Users\\<user>\\AppData\\Local\\MINOS\\data"
      }
    }
  }
}
```

Dans Copilot Chat, passer en mode **Agent** puis ouvrir la liste des tools MCP. Les tools MINOS doivent apparaître.

Configuration manuelle équivalente : dans Copilot Chat → Agent → outils → **Add MCP Tools / Configure your MCP server**, ajouter le serveur ci-dessus dans `mcp.json`.

## GitHub Copilot CLI

Lorsque `copilot` est disponible dans le `PATH`, le setup peut enregistrer MINOS dans la configuration MCP utilisateur de Copilot CLI via la commande officielle :

```powershell
$MinosExe = "$env:LOCALAPPDATA\Programs\MINOS\app\minos.exe"
$MinosHome = "$env:LOCALAPPDATA\MINOS\data"

copilot mcp add minos `
  --env "MINOS_HOME=$MinosHome" `
  -- "$MinosExe" mcp
```

Contrôle :

```powershell
copilot mcp get minos --json
copilot mcp list --json
```

Suppression manuelle :

```powershell
copilot mcp remove minos
```

La configuration utilisateur Copilot CLI est conservée sous `~/.copilot/mcp-config.json` par le client GitHub lui-même.

## Claude Code

Lorsque `claude` est disponible dans le `PATH`, le setup utilise le scope `user` :

```powershell
$MinosExe = "$env:LOCALAPPDATA\Programs\MINOS\app\minos.exe"
$MinosHome = "$env:LOCALAPPDATA\MINOS\data"

claude mcp add minos `
  --scope user `
  --env "MINOS_HOME=$MinosHome" `
  -- "$MinosExe" mcp
```

Vérifier :

```powershell
claude mcp list
claude mcp get minos
```

Puis, dans Claude Code :

```text
/mcp
```

Le scope peut être adapté lors d'une configuration manuelle :

```text
local    configuration privée liée au projet courant
project  configuration partageable avec le projet
user     configuration utilisateur gérée par Claude Code
```

Suppression manuelle de l'entrée créée au scope utilisateur :

```powershell
claude mcp remove minos --scope user
```

## Claude Desktop

Claude Desktop sait démarrer les serveurs MCP locaux. Sur Windows, le fichier de configuration développeur est :

```text
%APPDATA%\Claude\claude_desktop_config.json
```

Le setup fusionne uniquement `mcpServers.minos` :

```json
{
  "mcpServers": {
    "minos": {
      "command": "C:\\Users\\<user>\\AppData\\Local\\Programs\\MINOS\\app\\minos.exe",
      "args": ["mcp"],
      "env": {
        "MINOS_HOME": "C:\\Users\\<user>\\AppData\\Local\\MINOS\\data"
      }
    }
  }
}
```

Quitter complètement Claude Desktop puis le relancer après l'installation pour recharger les serveurs locaux.

Claude Desktop propose également le format moderne d'extensions MCP (`.dxt` / MCP bundle). MINOS utilise ici la configuration locale développeur, qui permet de référencer directement le binaire déjà installé sans dupliquer le runtime MINOS dans une extension.

## OpenAI Codex

Lorsque la commande `codex` est disponible dans le `PATH`, le setup peut enregistrer le serveur MCP MINOS dans la configuration utilisateur Codex :

```powershell
$MinosExe = "$env:LOCALAPPDATA\Programs\MINOS\app\minos.exe"
$MinosHome = "$env:LOCALAPPDATA\MINOS\data"

codex mcp add minos `
  --env "MINOS_HOME=$MinosHome" `
  -- "$MinosExe" mcp
```

Contrôle :

```powershell
codex mcp get minos
codex mcp list
```

Suppression manuelle :

```powershell
codex mcp remove minos
```

## Configuration portable / manuelle de plusieurs clients

La distribution ZIP contient également le gestionnaire. Par exemple :

```powershell
$Minos = "$env:LOCALAPPDATA\Programs\MINOS"

& "$Minos\integration\configure-mcp-clients.ps1" `
  -InstallRoot $Minos `
  -CopilotJetBrains `
  -CopilotCli `
  -ClaudeCode `
  -ClaudeDesktop `
  -Codex
```

Pour retirer uniquement les intégrations gérées :

```powershell
& "$Minos\integration\configure-mcp-clients.ps1" `
  -InstallRoot $Minos `
  -Action Uninstall
```

## Depuis un checkout de développement

```powershell
$env:MINOS_HOME = 'N:\minos-data'
java -jar .\target\minos-code-intelligence-0.2.0-SNAPSHOT-all.jar mcp
```

Le point d’entrée historique reste disponible :

```powershell
java -cp .\target\minos-code-intelligence-0.2.0-SNAPSHOT-all.jar `
  com.minos.mcp.MinosMcpServer
```

## Catalogue des tools

| Tool | Usage |
|---|---|
| `minos_project_structure` | structure, langages, builds, état et snapshot |
| `minos_index_status` | état d’index et métadonnées |
| `minos_search_code` | contexte de code compact |
| `minos_find_symbols` | recherche de symboles |
| `minos_find_usages` | usages résolus |
| `minos_find_implementations` | implémentations |
| `minos_find_callers` | appels entrants disponibles |
| `minos_find_callees` | appels sortants disponibles |
| `minos_dependencies` | dépendances sortantes |
| `minos_dependents` | dépendances entrantes |
| `minos_related_tests` | tests liés avec explications |
| `minos_symbol_context` | contexte compact d’un symbole |
| `minos_module_context` | contexte architectural d’un module |
| `minos_architecture` | vue d’architecture projet + arêtes du graphe en JSON |
| `minos_architecture_graph` | graphe inter-modules en `json`, `mermaid` ou `dot`, avec filtre module optionnel |
| `minos_impact` | impact potentiel d’un symbole |

Le MCP reste **read-only** : `project add`, `tools install` et `index` sont des opérations administratives CLI explicites.

### Exemple : demander le graphe à un agent

Un client MCP peut appeler :

```text
minos_architecture_graph
  project = my-project
  format  = mermaid
```

ou se concentrer sur un module :

```text
minos_architecture_graph
  project = my-project
  module  = packages/api
  format  = json
```

La réponse reflète uniquement les arêtes observées dans le snapshot actif ; MINOS n'invente pas de relation pour compléter le dessin.

## Préparer un projet avant MCP

```powershell
minos.cmd tools install scip-java
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd index my-project
```

Le client MCP peut ensuite interroger le snapshot actif.

## Mode Docker durci optionnel

Docker n’est pas le chemin PROD principal. Il reste utile lorsque l’on veut isoler la surface MCP :

```text
network_mode: none
filesystem: read-only
projects: read-only
capabilities: dropped
STDIO only
```

Le home Docker est volontairement séparé :

```text
%LOCALAPPDATA%\MINOS\docker-data
```

Ne pas partager directement un registre natif avec le conteneur : les chemins projet diffèrent.

### Construire Docker depuis le même JAR de release

Avec le shaded JAR correspondant exactement à la release :

```powershell
.\docker\scripts\prod-mcp-release.ps1 `
  -Action Install `
  -Jar .\minos-code-intelligence-0.2.0-all.jar `
  -Version 0.2.0 `
  -Commit <sha> `
  -ProjectsRoot N:\workspace-dev

.\docker\scripts\prod-mcp-release.ps1 -Action Start
```

## Contraintes STDIO

Le serveur utilise stdout pour le protocole MCP. Aucun wrapper ne doit écrire de diagnostics arbitraires sur stdout pendant la session.

Les entrées restent bornées par schema : recherche, profondeurs, usages, relations, graphe et impact disposent de limites explicites.

## Erreurs

Pour diagnostiquer une erreur MCP, reproduire d’abord la requête équivalente avec la CLI puis vérifier :

```powershell
minos.cmd index-status <project> --format json
minos.cmd architecture <project> --format json
minos.cmd doctor --format json
```

Les journaux d'intégration setup sont disponibles sous :

```text
%LOCALAPPDATA%\MINOS\mcp-clients.log
```

# Utiliser MINOS via MCP

MINOS expose un serveur **Model Context Protocol local via STDIO**. Les 15 tools restent read-only.

## Mode recommandé M14 : runtime natif

Après installation Windows :

```text
command = C:\Users\<user>\AppData\Local\Programs\MINOS\minos.cmd
args    = mcp
```

Exemple conceptuel :

```json
{
  "mcpServers": {
    "minos": {
      "command": "C:\\Users\\<user>\\AppData\\Local\\Programs\\MINOS\\minos.cmd",
      "args": ["mcp"]
    }
  }
}
```

Le launcher fixe par défaut :

```text
MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

La CLI et le MCP lisent donc le même registre et le même snapshot actif, avec les mêmes chemins Windows.

### Variante robuste pour les clients qui lancent mal les `.cmd`

Certains clients MCP Windows lancent plus fiablement un exécutable natif qu'un script `.cmd`. La distribution MINOS contient justement :

```text
%LOCALAPPDATA%\Programs\MINOS\app\minos.exe
```

On peut alors lancer directement l'exécutable et fournir explicitement `MINOS_HOME` :

```json
{
  "command": "C:\\Users\\<user>\\AppData\\Local\\Programs\\MINOS\\app\\minos.exe",
  "args": ["mcp"],
  "env": {
    "MINOS_HOME": "C:\\Users\\<user>\\AppData\\Local\\MINOS\\data"
  }
}
```

Cette variante expose le même serveur MCP natif ; elle ne passe pas par Docker.

## GitHub Copilot dans IntelliJ / JetBrains

Les versions actuelles de GitHub Copilot dans les IDE JetBrains prennent en charge les serveurs MCP **locaux**. Dans Copilot Chat, utiliser le mode **Agent**, ouvrir les outils puis **Add MCP Tools / Configure your MCP server** pour éditer `mcp.json`.

Exemple MINOS :

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

Après enregistrement, ouvrir la liste des outils de Copilot Agent. Les tools MINOS doivent apparaître avec des noms tels que :

```text
minos_project_structure
minos_search_code
minos_find_symbols
minos_find_usages
minos_architecture
minos_impact
```

Documentation GitHub :

```text
https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp-in-your-ide/extend-copilot-chat-with-mcp
```

> Pour Copilot Business / Enterprise, une politique d'organisation peut interdire ou autoriser les serveurs MCP. Ce point dépend de l'administration GitHub de l'organisation.

## Claude Code

Claude Code prend en charge les serveurs MCP locaux STDIO.

Sous PowerShell Windows :

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

Le scope peut être adapté :

```text
local    configuration privée liée au projet courant
project  configuration partageable avec le projet
user     disponible dans tous les projets de l'utilisateur
```

Documentation Anthropic :

```text
https://docs.anthropic.com/en/docs/claude-code/mcp
```

## Claude Desktop

Claude Desktop sait également démarrer des serveurs MCP locaux. Sur Windows, le fichier de configuration développeur est :

```text
%APPDATA%\Claude\claude_desktop_config.json
```

Exemple :

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

Quitter complètement Claude Desktop puis le relancer après modification.

La distribution moderne de Claude Desktop propose aussi les extensions Desktop (DXT). MINOS n'est pas encore empaqueté en extension DXT : la configuration ci-dessus correspond au serveur MCP local développeur classique.

Documentation MCP :

```text
https://modelcontextprotocol.io/docs/develop/connect-local-servers
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
| `minos_architecture` | vue d’architecture projet |
| `minos_impact` | impact potentiel d’un symbole |

Le MCP reste **read-only** : `project add`, `tools install` et `index` sont des opérations administratives CLI explicites.

## Préparer un projet avant MCP

```powershell
minos.cmd tools install scip-java
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd index my-project
```

Le client MCP peut ensuite interroger le snapshot actif.

## Mode Docker durci optionnel

Docker n’est plus le chemin PROD principal. Il reste utile lorsque l’on veut isoler la surface MCP :

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

Configuration MCP :

```text
command = powershell.exe
args = -NoProfile -ExecutionPolicy Bypass -File <repo>\docker\scripts\prod-mcp-release.ps1 -Action Attach
```

Contrôle :

```powershell
.\docker\scripts\prod-mcp-release.ps1 -Action Status
.\docker\scripts\prod-mcp-release.ps1 -Action Validate
.\docker\scripts\prod-mcp-release.ps1 -Action Stop
```

## Contraintes STDIO

Le serveur utilise stdout pour le protocole MCP. Aucun wrapper ne doit écrire de diagnostics arbitraires sur stdout pendant la session.

Les entrées restent bornées par schema : recherche, profondeurs, usages, relations et impact disposent de limites explicites.

## Erreurs

Pour diagnostiquer une erreur MCP, reproduire d’abord la requête équivalente avec la CLI et `--format json`, puis vérifier :

```powershell
minos.cmd index-status <project> --format json
minos.cmd doctor --format json
```

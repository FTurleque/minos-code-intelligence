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

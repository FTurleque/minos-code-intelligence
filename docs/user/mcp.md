# Utiliser MINOS via MCP

MINOS expose un serveur **Model Context Protocol local via STDIO**. Les **31 tools** restent read-only.

Les trois tools M26 `minos_runtime_sessions`, `minos_runtime_report` et `minos_runtime_symbol` consultent uniquement les sessions runtime déjà importées. Le MCP ne peut ni importer une trace, ni muter le snapshot statique, ni promouvoir une capability provider.

Les cinq tools M27 `minos_team_tenant`, `minos_team_workspaces`, `minos_team_workspace`, `minos_team_members` et `minos_team_audit` sont également read-only. Leur bearer token provient uniquement de `MINOS_TEAM_TOKEN` dans l’environnement du processus MCP ; aucun schéma tool n’accepte un secret.

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

## Activer la couche sémantique M20

La couche sémantique est **désactivée par défaut**. Le fonctionnement historique de MINOS n'a besoin d'aucun modèle d'embeddings.

Le runtime M20 fournit un provider local de référence, sans réseau, activable explicitement :

```powershell
$env:MINOS_SEMANTIC_PROVIDER = 'local-hash'
minos.cmd index my-project
```

Une fois le provider activé, `minos index` synchronise l'index sémantique après le snapshot structuré. Même si le plan structuré est `NONE`, cette commande peut construire ou réaligner un index sémantique manquant/stale.

Pour un client MCP, ajouter la même variable à son environnement :

```json
{
  "env": {
    "MINOS_HOME": "C:\\Users\\<user>\\AppData\\Local\\MINOS\\data",
    "MINOS_SEMANTIC_PROVIDER": "local-hash"
  }
}
```

`local-hash` est un provider déterministe de référence qui prouve le pipeline local et les contrats. **Il n'est pas présenté comme un modèle de langage.** Un autre provider local peut implémenter le SPI `EmbeddingProvider` sans changer les services de recherche.

Le MCP reste read-only : il peut consulter/rechercher l'index, mais ne déclenche jamais un ré-embedding. La création/synchronisation passe par l'indexation locale ou par l'API Java explicite.

## Configuration automatique depuis `setup.exe`

Le setup Windows propose des cases à cocher indépendantes :

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
- la désinstallation retire uniquement les entrées qui correspondent encore à la configuration créée par MINOS ;
- si une entrée gérée a été modifiée manuellement depuis l'installation, elle est conservée plutôt que supprimée aveuglément ;
- le journal est `%LOCALAPPDATA%\MINOS\mcp-clients.log`.

Les cases sont décochées par défaut : la modification d'un client tiers reste un choix explicite de l'utilisateur.

## GitHub Copilot dans IntelliJ / JetBrains

Le setup peut fusionner l'entrée `minos` dans la configuration utilisateur du plugin Copilot JetBrains utilisée par l'intégration MINOS :

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

Configuration manuelle équivalente : dans Copilot Chat → Agent → outils → **Add MCP Tools / Configure your MCP server**, ajouter le serveur ci-dessus dans le `mcp.json` ouvert par le plugin.

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

Lorsque `claude` est disponible dans le `PATH`, le setup utilise le scope `user`.

Claude Code impose que les options de `mcp add` soient placées **avant** le nom du serveur :

```powershell
$MinosExe = "$env:LOCALAPPDATA\Programs\MINOS\app\minos.exe"
$MinosHome = "$env:LOCALAPPDATA\MINOS\data"

claude mcp add `
  --scope user `
  --env "MINOS_HOME=$MinosHome" `
  minos `
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

Suppression manuelle :

```powershell
claude mcp remove minos
```

Le setup enregistre MINOS au scope `user`, puis mémorise qu'il est propriétaire de cette entrée afin de ne pas supprimer une configuration étrangère lors de la désinstallation.

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

Claude Desktop propose également le format moderne d'extensions MCP (`.dxt`). MINOS utilise ici la configuration locale développeur afin de référencer directement le binaire déjà installé sans dupliquer le runtime MINOS dans une extension.

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

Codex CLI et l'extension IDE partagent la configuration MCP utilisateur du même environnement Codex.

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

Pour activer M20 localement :

```powershell
$env:MINOS_SEMANTIC_PROVIDER = 'local-hash'
minos.cmd index my-project
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
| `minos_search_code` | contexte de code compact structuré |
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
| `minos_impact` | impact potentiel M8 d’un symbole |
| `minos_program_graph` | graphe de programme M19 borné, capabilities et limitations explicites |
| `minos_impact_v2` | impact M8 + ajouts prouvés par appels/flux, comptés séparément |
| `minos_security_paths` | chemins source→sink observés, sanitizers et limitations explicites |
| `minos_semantic_index_status` | état de l’index sémantique optionnel, modèle, snapshot, taille et limitations |
| `minos_semantic_search` | recherche vectorielle par intention, résultats `HEURISTIC` |
| `minos_hybrid_search` | ranking lexical + graphe + sémantique avec signaux séparés |
| `minos_hybrid_context` | contexte hybride borné par documents et tokens |

Le MCP reste **read-only** : `project add`, `tools install`, `index` et la synchronisation explicite de l'index sémantique sont des opérations administratives hors MCP.

### Analyses avancées M19

Les trois tools M19 ne fabriquent pas de données lorsqu'une capability provider manque.

`minos_program_graph` retourne notamment :

```text
capabilities
nodes / edges
nature
confidence
providerId / evidence
limitations
```

Les limites de requête sont explicites : jusqu'à 100 000 nœuds et 500 000 arêtes par appel, avec des valeurs par défaut plus basses.

`minos_impact_v2` conserve M8 comme baseline et expose séparément `baselineImpactCount` et `advancedAddedCount`. Un chemin avancé n'efface donc jamais la distinction avec l'analyse historique.

`minos_security_paths` est une recherche bornée de chemins observés. Elle expose source, sink, chemin, sanitizers, confiance et nature ; **l'absence de chemin n'est pas une preuve de sûreté et un chemin observé n'est pas présenté comme une preuve runtime exhaustive de vulnérabilité**.

### Recherche sémantique et hybride M20

Les tools M20 distinguent explicitement les trois modes :

```text
structured  = facts/relations/graphes MINOS historiques
semantic    = similarité vectorielle HEURISTIC
hybrid      = combinaison de signaux de ranking, sans promotion en fait structurel
```

`minos_semantic_index_status` peut répondre `DISABLED`, `MISSING`, `STALE` ou `READY`. Une recherche sémantique exige `READY` et ne construit jamais l'index à la volée.

`minos_semantic_search` expose notamment `score`, `nature=HEURISTIC`, `providerId`, `modelId` et les limitations. La similarité vectorielle sert au rappel/ranking ; elle ne crée aucune relation `CALLS`, `DEPENDS_ON`, `DATA_FLOW` ou autre.

`minos_hybrid_search` expose pour chaque résultat :

```text
rankingScore
lexicalScore
 graphScore
semanticScore
rankingMode
signals[] { type, score, nature }
```

Sans provider/index sémantique READY, le tool conserve un fallback lexical+graph et annonce `SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED`.

`minos_hybrid_context` applique des bornes strictes au nombre de documents, au budget global et au budget par document. La réponse expose `usedTokens` et `truncated`.

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

Pour le graphe de programme M19 :

```text
minos_program_graph
  project  = my-project
  maxNodes = 10000
  maxEdges = 50000
```

Pour M20 :

```text
minos_hybrid_search
  project = my-project
  query   = "où la politique d'authentification est-elle appliquée ?"
  limit   = 20
```

## Préparer un projet avant MCP

Baseline structurée :

```powershell
minos.cmd tools install scip-java
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd index my-project
```

Avec M20 activé :

```powershell
$env:MINOS_SEMANTIC_PROVIDER = 'local-hash'
minos.cmd index my-project
```

Le client MCP peut ensuite interroger le snapshot actif. Les capabilities avancées et sémantiques disponibles dépendent des facts/providers réellement configurés et conservent leurs limitations explicites.

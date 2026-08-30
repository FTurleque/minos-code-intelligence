# Utiliser MINOS via MCP

MINOS expose un serveur **Model Context Protocol local via STDIO**. Les **31 tools** restent read-only.

Les trois tools M26 `minos_runtime_sessions`, `minos_runtime_report` et `minos_runtime_symbol` consultent uniquement les sessions runtime déjà importées. Le MCP ne peut ni importer une trace, ni muter le snapshot statique, ni promouvoir une capability provider.

Les cinq tools M27 `minos_team_tenant`, `minos_team_workspaces`, `minos_team_workspace`, `minos_team_members` et `minos_team_audit` sont également read-only. Leur bearer token provient uniquement de `MINOS_TEAM_TOKEN` dans l'environnement du processus MCP ; aucun schéma tool n'accepte un secret.

## Mode recommandé : runtime natif Windows

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

## Correctif runtime Windows 1.0.1

La release 1.0.0 Windows pouvait réussir :

```text
minos --version
```

puis échouer au bootstrap MCP avec :

```text
java.lang.NoClassDefFoundError: org/w3c/dom/Node
```

`org.w3c.dom.Node` est fourni par le module JDK `java.xml`. L'image `jpackage` 1.0.0 avait été créée avec une liste de modules trop étroite.

À partir du candidat 1.0.1, le packaging suit :

```text
fat JAR exact
→ JDK 24 jdeps --print-module-deps
→ modules racines calculés
→ jpackage --add-modules <liste calculée>
→ runtime/bin/java --list-modules
→ contrôle des modules + assertion java.xml
→ handshake MCP réel
```

Le runtime livré matérialise aussi sa liste dans :

```text
<installation>\RUNTIME-MODULES.txt
```

Le gate de release ne se contente plus de `--version`. Il lance le binaire packagé et exige :

```text
initialize
→ notifications/initialized
→ tools/list
→ minos_search_code présent
→ minos_impact présent
```

Ce handshake est exercé sur la distribution ZIP installée et sur une installation setup isolée.

## Activer la couche sémantique

La couche sémantique est **désactivée par défaut**. Le fonctionnement structuré de MINOS n'a besoin d'aucun modèle d'embeddings.

Le runtime fournit un provider local déterministe de référence, sans réseau, activable explicitement :

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

`local-hash` est un provider déterministe de référence qui prouve le pipeline local et les contrats. **Il n'est pas présenté comme un modèle learned.** Un autre provider local peut implémenter le SPI `EmbeddingProvider` sans changer les services de recherche.

Le MCP reste read-only : il peut consulter/rechercher l'index, mais ne déclenche jamais un ré-embedding. La création/synchronisation passe par l'indexation locale ou par l'API Java explicite.

## Configuration automatique depuis `setup.exe` — 1.0.1

Le setup ne commence plus par les clients IA. Après le choix du dossier et les tâches Windows, il présente d'abord :

```text
Mode MCP

☑ MCP natif local — recommandé, sans Docker
☐ MCP Docker — optionnel, isolation par conteneur
```

Le mode natif est sélectionné par défaut et fonctionne sans Docker. Les deux modes peuvent être activés simultanément.

Lorsque le mode natif est sélectionné, le setup affiche ensuite :

```text
Intégrations MCP natives
Connecter le MCP natif MINOS à vos clients IA détectés
```

Clients couverts :

```text
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

Si le mode natif est décoché, la page d'intégration des clients IA est ignorée. Le binaire MCP natif reste installé et peut être configuré manuellement plus tard.

Si le mode Docker est sélectionné, une page séparée demande ensuite la racine des projets à exposer au conteneur.

### Préflight

Les cases ne sont plus de simples tâches Inno statiques.

Le préflight :

- détecte GitHub Copilot JetBrains / IntelliJ ;
- refuse un chemin `copilot` identifié comme launcher/shim VS Code avant même le capability probe ;
- vérifie la capability MCP d'un vrai Copilot CLI ;
- vérifie la capability MCP de Claude Code ;
- détecte Claude Desktop ;
- sélectionne explicitement Codex CLI ou Codex Desktop/config utilisateur ;
- désactive un client indisponible et affiche la raison ;
- laisse toutes les cases disponibles décochées par défaut.

Exemple :

```text
GitHub Copilot CLI
Non disponible — launcher VS Code détecté, vrai CLI absent
```

La modification d'un client tiers reste donc un choix explicite de l'utilisateur.

## Règles d'ownership et sauvegardes

Les helpers installés sont notamment :

```text
<installation>\integration\detect-mcp-clients.ps1
<installation>\integration\configure-mcp-clients.ps1
<installation>\integration\configure-mcp-clients-setup.ps1
<installation>\integration\configure-codex-mcp.ps1
<installation>\integration\uninstall-mcp-clients.ps1
```

Ils appliquent les règles suivantes :

- aucune entrée MCP existante nommée `minos` n'est écrasée si elle n'est pas détenue par MINOS ;
- les fichiers modifiés sont sauvegardés avant écriture ;
- les autres serveurs MCP et propriétés de configuration sont conservés ;
- l'état des intégrations créées est enregistré ;
- la réinstallation compatible est idempotente ;
- la désinstallation retire uniquement les entrées correspondant encore à la configuration gérée ;
- si une entrée gérée a été modifiée manuellement, elle est conservée ;
- les chemins des CLI sont mémorisés afin qu'un changement ultérieur du `PATH` ne rende pas automatiquement le cleanup impossible.

État et journal :

```text
%LOCALAPPDATA%\MINOS\mcp-client-integrations.json
%LOCALAPPDATA%\MINOS\codex-mcp-integration.json
%LOCALAPPDATA%\MINOS\mcp-clients.log
%LOCALAPPDATA%\MINOS\backups\mcp-clients\...
```

Lors d'une désinstallation interactive, l'utilisateur peut en plus demander la suppression complète de `%LOCALAPPDATA%\MINOS`. Le choix par défaut reste **conserver** ; la purge complète est distincte du retrait sélectif des intégrations MCP.

## GitHub Copilot dans IntelliJ / JetBrains

Le setup peut fusionner l'entrée `minos` dans la configuration utilisateur du plugin Copilot JetBrains utilisée par l'intégration MINOS :

```text
%LOCALAPPDATA%\github-copilot\intellij\mcp.json
```

Structure :

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

Dans Copilot Chat, passer en mode **Agent** puis ouvrir la liste des tools MCP. Le serveur `minos` doit atteindre l'état connecté et les tools MINOS doivent apparaître.

Configuration manuelle équivalente : dans Copilot Chat → Agent → outils → **Add MCP Tools / Configure your MCP server**, ajouter le serveur ci-dessus dans le `mcp.json` ouvert par le plugin.

## GitHub Copilot CLI

La simple présence de `copilot` dans le `PATH` n'est plus une preuve suffisante.

Le setup 1.0.1 :

1. résout le chemin de la commande ;
2. rejette les launchers/shims VS Code connus ;
3. exerce la surface MCP attendue dans un processus non interactif et borné ;
4. ne rend la case sélectionnable que si le client est réellement compatible.

Lorsque le vrai CLI est disponible, l'intégration cible conceptuellement :

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

## Claude Code

Lorsque `claude` est disponible et que sa surface MCP répond au probe, le setup utilise le scope `user`.

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

Suppression manuelle :

```powershell
claude mcp remove minos
```

## Claude Desktop

Le fichier de configuration développeur Windows est :

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

Quitter complètement Claude Desktop puis le relancer après installation pour recharger les serveurs locaux.

## OpenAI Codex

Le candidat 1.0.1 distingue deux modes.

### Codex CLI

Lorsque `codex mcp --help` prouve la surface MCP attendue, le setup utilise le CLI.

Conceptuellement :

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

### Codex Desktop / configuration utilisateur

Lorsque le préflight choisit `desktop`, MINOS gère un bloc marqué dans :

```text
%USERPROFILE%\.codex\config.toml
```

```toml
# BEGIN MINOS MANAGED MCP SERVER
[mcp_servers.minos]
command = "C:\\Users\\<user>\\AppData\\Local\\Programs\\MINOS\\app\\minos.exe"
args = ["mcp"]
enabled = true

[mcp_servers.minos.env]
MINOS_HOME = "C:\\Users\\<user>\\AppData\\Local\\MINOS\\data"
# END MINOS MANAGED MCP SERVER
```

Un bloc `[mcp_servers.minos]` existant mais non géré par MINOS n'est jamais écrasé.

Le mode détecté par le préflight (`cli` ou `desktop`) est propagé au configurateur afin que le comportement ne change pas silencieusement entre la page Wizard et l'écriture effective.

## Configuration portable / manuelle

La distribution ZIP contient également les helpers.

Copilot / Claude :

```powershell
$Minos = "$env:LOCALAPPDATA\Programs\MINOS"

& "$Minos\integration\configure-mcp-clients.ps1" `
  -InstallRoot $Minos `
  -CopilotJetBrains `
  -CopilotCli `
  -ClaudeCode `
  -ClaudeDesktop
```

Codex :

```powershell
& "$Minos\integration\configure-codex-mcp.ps1" `
  -InstallRoot $Minos `
  -Mode auto
```

Pour retirer les intégrations gérées par le setup, préférer le wrapper canonique :

```powershell
& "$Minos\integration\uninstall-mcp-clients.ps1" `
  -InstallRoot $Minos
```

## Depuis un checkout de développement

Version de développement courante :

```text
1.2.0-SNAPSHOT
```

```powershell
$env:MINOS_HOME = 'N:\minos-data'
java -jar .\target\minos-code-intelligence-1.2.0-SNAPSHOT-all.jar mcp
```

Pour activer le sémantique local :

```powershell
$env:MINOS_SEMANTIC_PROVIDER = 'local-hash'
minos.cmd index my-project
java -jar .\target\minos-code-intelligence-1.2.0-SNAPSHOT-all.jar mcp
```

Le point d'entrée direct reste disponible :

```powershell
java -cp .\target\minos-code-intelligence-1.2.0-SNAPSHOT-all.jar `
  com.minos.mcp.MinosMcpServer
```

Attention : un succès avec le JDK complet du checkout ne suffit pas à qualifier l'image `jpackage` Windows. Le runner local de candidat 1.0.1 exerce le binaire packagé.

## Catalogue des tools

| Tool | Usage |
|---|---|
| `minos_project_structure` | structure, langages, builds, état et snapshot |
| `minos_index_status` | état d'index et métadonnées |
| `minos_search_code` | contexte de code compact structuré |
| `minos_find_symbols` | recherche de symboles |
| `minos_find_usages` | usages résolus |
| `minos_find_implementations` | implémentations |
| `minos_find_callers` | appels entrants disponibles |
| `minos_find_callees` | appels sortants disponibles |
| `minos_dependencies` | dépendances sortantes |
| `minos_dependents` | dépendances entrantes |
| `minos_related_tests` | tests liés avec explications |
| `minos_symbol_context` | contexte compact d'un symbole |
| `minos_module_context` | contexte architectural d'un module |
| `minos_architecture` | vue d'architecture projet + arêtes du graphe en JSON |
| `minos_architecture_graph` | graphe inter-modules en `json`, `mermaid` ou `dot`, avec filtre module optionnel |
| `minos_impact` | impact potentiel M8 d'un symbole |
| `minos_program_graph` | graphe de programme M19 borné, capabilities et limitations explicites |
| `minos_impact_v2` | impact M8 + ajouts prouvés par appels/flux, comptés séparément |
| `minos_security_paths` | chemins source→sink observés, sanitizers et limitations explicites |
| `minos_semantic_index_status` | état de l'index sémantique optionnel, modèle, snapshot, taille et limitations |
| `minos_semantic_search` | recherche vectorielle par intention, résultats `HEURISTIC` |
| `minos_hybrid_search` | ranking lexical + graphe + sémantique avec signaux séparés |
| `minos_hybrid_context` | contexte hybride borné par documents et tokens |
| `minos_runtime_sessions` | sessions runtime déjà importées |
| `minos_runtime_report` | rapport d'observations partielles d'une session |
| `minos_runtime_symbol` | observations partielles liées à un symbole |
| `minos_team_tenant` | état tenant courant, read-only |
| `minos_team_workspaces` | liste des workspaces accessibles |
| `minos_team_workspace` | détail d'un workspace |
| `minos_team_members` | membres/roles visibles |
| `minos_team_audit` | audit chaîné read-only |

Le MCP reste **read-only** : `project add`, `tools install`, `index`, `runtime import` et la synchronisation explicite de l'index sémantique sont des opérations administratives hors MCP.

### Analyses avancées

Les tools avancés ne fabriquent pas de données lorsqu'une capability provider manque.

`minos_program_graph` retourne notamment :

```text
capabilities
nodes / edges
nature
confidence
providerId / evidence
limitations
```

Les limites de requête sont explicites et les résultats restent bornés.

`minos_impact_v2` conserve l'impact structuré historique comme baseline et expose séparément les ajouts prouvés par les signaux avancés.

`minos_security_paths` est une recherche bornée de chemins observés. **L'absence de chemin n'est pas une preuve de sûreté et un chemin observé n'est pas présenté comme une preuve runtime exhaustive de vulnérabilité.**

### Recherche sémantique et hybride

Les tools distinguent explicitement :

```text
structured  = facts/relations/graphes MINOS
semantic    = similarité vectorielle HEURISTIC
hybrid      = combinaison de signaux de ranking sans promotion en fait structurel
```

`minos_semantic_index_status` peut répondre `DISABLED`, `MISSING`, `STALE` ou `READY`. Une recherche sémantique exige `READY` et ne construit jamais l'index à la volée.

`minos_semantic_search` expose notamment `score`, `nature=HEURISTIC`, `providerId`, `modelId` et les limitations. La similarité vectorielle sert au rappel/ranking ; elle ne crée aucune relation structurée.

`minos_hybrid_search` expose séparément ses signaux lexicaux, graphe et sémantiques. Sans provider/index sémantique READY, le fallback structuré reste explicite.

`minos_hybrid_context` applique des bornes strictes au nombre de documents et au budget de contexte.

### Runtime et Team/Hosted

Les tools runtime consultent uniquement des observations déjà importées et déclarent leur caractère partiel. Une absence d'observation n'est jamais une preuve de non-exécution.

Les tools Team/Hosted sont read-only et utilisent le contexte d'authentification du processus ; aucun secret n'est accepté dans le schéma d'un tool.

## Exemples MCP

Architecture :

```text
minos_architecture_graph
  project = my-project
  format  = mermaid
```

Voisinage d'un module :

```text
minos_architecture_graph
  project = my-project
  module  = packages/api
  format  = json
```

ProgramGraph :

```text
minos_program_graph
  project  = my-project
  maxNodes = 10000
  maxEdges = 50000
```

Recherche hybride :

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

Avec la couche sémantique activée :

```powershell
$env:MINOS_SEMANTIC_PROVIDER = 'local-hash'
minos.cmd index my-project
```

Le client MCP peut ensuite interroger le snapshot actif. Les capabilities avancées et sémantiques disponibles dépendent des facts/providers réellement configurés et conservent leurs limitations explicites.

## Construire/qualifier le candidat Windows

Le runner local sûr est :

```powershell
.\scripts\release\build-local-windows-candidate.ps1 -Version 1.1.0
```

Il génère le setup de production à vérifier manuellement, mais ne le publie pas, ne crée aucun tag et ne déclenche aucun GitHub Actions.

Voir [Installation PROD Windows](production-installation.md) pour le parcours complet.
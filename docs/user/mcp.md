# Utiliser MINOS via MCP

MINOS expose un serveur **Model Context Protocol local via STDIO**. Le catalogue courant contient **31 tools read-only**.

Le MCP ne réalise aucune opération administrative destructive : installation de providers, ajout de projet, indexation et mutations restent hors MCP.

## Lancement natif Windows

Après installation :

```text
command = C:\Users\<user>\AppData\Local\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=C:\Users\<user>\AppData\Local\MINOS\data
```

`minos.cmd mcp` reste disponible pour un lancement manuel, mais les intégrations clients utilisent directement `app\minos.exe`.

## Correctif runtime 1.0.1

La release 1.0.0 Windows pouvait démarrer `minos --version` tout en échouant au bootstrap MCP avec :

```text
java.lang.NoClassDefFoundError: org/w3c/dom/Node
```

Le candidat 1.0.1 construit le runtime `jpackage` à partir des modules calculés par `jdeps`, vérifie `java.xml` et impose un vrai handshake :

```text
initialize
→ notifications/initialized
→ tools/list
```

Ce test est réalisé sur le binaire packagé, pas seulement sur le JAR exécuté avec un JDK complet.

## Configuration depuis le setup 1.0.1

Le setup affiche une page dédiée :

```text
Intégrations MCP natives
Connecter le MCP natif MINOS à vos clients IA détectés
```

Chaque client :

- est décoché par défaut ;
- n'est sélectionnable que si son mode d'intégration est détecté ;
- affiche une raison lorsqu'il est indisponible ;
- est configuré uniquement sur choix explicite de l'utilisateur.

Clients :

- GitHub Copilot — JetBrains / IntelliJ ;
- GitHub Copilot CLI ;
- Claude Code ;
- Claude Desktop ;
- OpenAI Codex CLI ou Codex Desktop/config utilisateur.

Docker n'est requis pour aucune intégration MCP native.

## GitHub Copilot — JetBrains / IntelliJ

Configuration MINOS :

```text
%LOCALAPPDATA%\github-copilot\intellij\mcp.json
```

Entrée cible :

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

Après installation, ouvrir Copilot Chat/Agent et vérifier que `minos` est connecté et que les tools MINOS sont visibles.

## GitHub Copilot CLI

La simple présence d'une commande `copilot` ne suffit plus.

Le préflight 1.0.1 vérifie que l'interface MCP attendue répond. Un launcher/shim VS Code incompatible est désactivé avec un diagnostic explicite au lieu d'être présenté comme le vrai Copilot CLI.

Quand le CLI est compatible, MINOS utilise l'interface MCP du client pour ajouter le serveur utilisateur `minos`.

## Claude Code

`claude` doit être présent et sa capability MCP doit répondre au probe. MINOS configure l'entrée au scope utilisateur et conserve l'ownership nécessaire à une désinstallation sélective.

## Claude Desktop

Fichier par défaut :

```text
%APPDATA%\Claude\claude_desktop_config.json
```

MINOS fusionne uniquement `mcpServers.minos`, sans supprimer les autres serveurs/propriétés.

## OpenAI Codex

Deux modes sont supportés.

### Codex CLI

Lorsque `codex mcp --help` prouve la surface MCP attendue, le setup utilise le CLI pour gérer `minos`.

### Codex Desktop / configuration utilisateur

Lorsque le préflight choisit le mode Desktop, MINOS modifie :

```text
%USERPROFILE%\.codex\config.toml
```

avec un bloc explicitement marqué :

```toml
# BEGIN MINOS MANAGED MCP SERVER
[mcp_servers.minos]
command = "C:\\...\\MINOS\\app\\minos.exe"
args = ["mcp"]
enabled = true

[mcp_servers.minos.env]
MINOS_HOME = "C:\\...\\MINOS\\data"
# END MINOS MANAGED MCP SERVER
```

Une section `[mcp_servers.minos]` existante et non détenue par MINOS n'est jamais écrasée.

## Ownership, sauvegardes et désinstallation

MINOS conserve :

```text
%LOCALAPPDATA%\MINOS\mcp-client-integrations.json
%LOCALAPPDATA%\MINOS\codex-mcp-integration.json
%LOCALAPPDATA%\MINOS\mcp-clients.log
%LOCALAPPDATA%\MINOS\backups\mcp-clients\...
```

Règles :

- backup avant modification d'un fichier tiers existant ;
- préservation des autres serveurs/propriétés ;
- refus d'écraser une entrée `minos` non gérée ;
- réinstallation idempotente ;
- préservation d'une entrée gérée modifiée par l'utilisateur ;
- suppression uniquement d'une entrée dont l'ownership et le contenu correspondent encore ;
- chemins CLI sauvegardés réutilisés lors de la désinstallation lorsque le PATH a changé.

Le wrapper de désinstallation canonique du setup est :

```text
<installation>\integration\uninstall-mcp-clients.ps1
```

## Depuis un checkout de développement

Version courante :

```text
1.0.1-SNAPSHOT
```

Exemple :

```powershell
$env:MINOS_HOME = 'C:\minos-data'
java -jar .\target\minos-code-intelligence-1.0.1-SNAPSHOT-all.jar mcp
```

Pour tester la distribution Windows réelle avant release, ne pas se contenter du JAR : utiliser le runner local de candidat décrit dans [Installation depuis les sources](installation.md).

## Sémantique

La couche sémantique reste optionnelle. `local-hash` est un provider déterministe de référence ; les providers learned restent opt-in et les résultats sémantiques restent `HEURISTIC`.

Le MCP ne construit pas l'index sémantique à la volée : il consulte l'état produit par les opérations d'indexation administratives.

## Runtime et Team/Hosted

Les tools runtime M26 consultent uniquement les observations partielles déjà importées. Une absence d'observation ne prouve jamais une absence d'exécution.

Les tools Team/Hosted M27 restent read-only. Le token provient de l'environnement du processus MCP ; il n'est pas accepté dans le schéma des tools.

## Catalogue

Le catalogue exact et calculable est maintenu dans :

[`../generated/product-facts.md`](../generated/product-facts.md)

Il couvre notamment :

- structure/projets/index status ;
- recherche/symboles/usages/implémentations ;
- callers/callees/dépendances ;
- tests liés ;
- architecture/graphe ;
- impact ;
- ProgramGraph et sécurité ;
- recherche sémantique/hybride ;
- runtime ;
- surfaces Team/Hosted.

Les capacités absentes d'un provider ne sont jamais fabriquées par le MCP.

## MCP Docker

Le MCP Docker est séparé du MCP natif. Il utilise un JRE complet dans l'image et conserve les restrictions de production documentées (réseau désactivé, filesystem read-only lorsque applicable, capabilities supprimées, no-new-privileges).

La vraie sandbox OS du worker distant M25 est un sujet distinct suivi par #98 et ne doit pas être confondue avec le conteneur MCP.

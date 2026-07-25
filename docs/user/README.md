# Guide utilisateur MINOS

Ce guide s'adresse à une personne qui veut **installer, alimenter et interroger MINOS sans modifier son code source**.

Le parcours utilisateur normal commence par une **GitHub Release Windows**. Il ne faut pas cloner le dépôt MINOS ni lancer Maven pour installer le produit.

## Parcours recommandé

```text
GitHub Release
   ↓
MINOS-<version>-windows-x64-setup.exe
   ↓
installation Windows
   ├── runtime Java MINOS
   ├── CLI
   ├── MCP natif
   ├── PATH utilisateur optionnel
   ├── intégrations MCP natives optionnelles
   │    ├── Copilot JetBrains / IntelliJ
   │    ├── Copilot CLI
   │    ├── Claude Code
   │    ├── Claude Desktop
   │    └── OpenAI Codex
   └── MCP Docker optionnel
   ↓
minos doctor
   ↓
minos tools install <provider>
   ↓
minos project add <path> --name <name>
   ↓
minos index <name>
   ↓
search / architecture / graphe / impact / MCP
```

Le `setup.exe` est le **canal recommandé** pour un poste Windows. Le ZIP reste disponible comme distribution **portable / automatisation / diagnostic**.

L'utilisateur normal ne prépare plus `index.scip` manuellement. `minos index <project>` découvre le projet, sélectionne le provider qualifié, calcule la portée d'indexation, exécute le provider puis promeut le nouveau snapshot de manière atomique.

---

## 1. Installer MINOS

Commencer ici :

**[Installation PROD Windows](production-installation.md)**

Le guide couvre :

- téléchargement depuis GitHub Releases ;
- `setup.exe` recommandé et ZIP portable ;
- différence release stable / pre-release ;
- vérification SHA-256 ;
- installation utilisateur sans droits administrateur ;
- ajout de MINOS au `PATH` ;
- MCP natif installé avec MINOS ;
- choix optionnel des clients MCP natifs dans le setup ;
- GitHub Copilot JetBrains / CLI, Claude Code, Claude Desktop et Codex ;
- configuration optionnelle du MCP Docker lorsque Docker Desktop est déjà disponible ;
- emplacement du programme et de `MINOS_HOME` ;
- premier démarrage ;
- providers Java et TypeScript ;
- premier projet et visualisation du graphe ;
- mise à jour, rollback et désinstallation ;
- publication d'une release pour les mainteneurs.

Le parcours `git clone` / Maven est volontairement séparé dans [Installation depuis les sources](installation.md).

---

## 2. Démarrage rapide après installation

Dans un nouveau PowerShell :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd tools list
```

Pour un projet Java :

```powershell
$env:JAVA_HOME = 'C:\path\to\project-jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
minos.cmd tools install scip-java
```

Puis :

```powershell
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd inspect my-project
minos.cmd index my-project --dry-run
minos.cmd index my-project
minos.cmd index-status my-project
minos.cmd search my-project GreetingPort --format json
```

Pour voir le graphe de dépendances entre modules :

```powershell
minos.cmd architecture my-project --format json
minos.cmd architecture my-project --format mermaid
minos.cmd architecture my-project --format dot
```

Voir [Référence CLI](cli.md) pour l'export Mermaid/Graphviz et le filtrage par module.

---

## 3. MCP natif et MCP Docker

Le MCP natif est installé avec MINOS et reste le mode recommandé pour les clients :

```text
command = <installation>\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Le `setup.exe` peut enregistrer cette configuration dans les clients sélectionnés par l'utilisateur :

```text
GitHub Copilot — JetBrains / IntelliJ
GitHub Copilot CLI
Claude Code
Claude Desktop
OpenAI Codex
```

Docker n'est nécessaire pour aucune de ces intégrations.

Le `setup.exe` peut aussi **configurer, construire, démarrer et valider le MCP Docker** si l'utilisateur sélectionne cette option séparée et si Docker Desktop est déjà installé et démarré.

MINOS n'installe pas Docker Desktop lui-même. Si Docker n'est pas disponible pendant le setup, l'installation native reste valide.

Le MCP expose **16 tools read-only**, dont `minos_architecture_graph` pour le graphe en JSON, Mermaid ou DOT.

---

## 4. Choisir le bon document

| Besoin | Document |
|---|---|
| Télécharger, installer, mettre à jour ou désinstaller MINOS | [Installation PROD Windows](production-installation.md) |
| Comprendre l'indexation automatique | [Indexation autonome](autonomous-indexing.md) |
| Connaître toutes les commandes et visualiser le graphe | [Référence CLI](cli.md) |
| Connecter Copilot / Claude / Codex au MCP | [Serveur MCP](mcp.md) |
| Utiliser MINOS depuis Java et lire le graphe | [API Java locale](java-api.md) |
| Exporter vers NEXUS | [Intégration NEXUS](nexus.md) |
| Diagnostiquer un problème | [Dépannage](troubleshooting.md) |
| Développer MINOS lui-même | [Installation depuis les sources](installation.md) |

---

## 5. Fonctionnalités principales

| Besoin | Commande / surface |
|---|---|
| Diagnostiquer l'installation | `doctor` |
| Gérer les providers | `tools list/install/verify` |
| Enregistrer un projet | `project add` |
| Voir les projets | `project list` |
| Inspecter l'état | `inspect`, `index-status` |
| Indexer automatiquement | `index` |
| Importer un SCIP explicitement | `import-scip` |
| Rechercher du contexte | `search` |
| Trouver un symbole | `find-symbol` |
| Lire un fichier source | `get-source` |
| Trouver les usages | `find-usages` |
| Implémentations/appels/dépendances | commandes relationnelles |
| Trouver les tests liés | `related-tests` |
| Lire l'architecture | `architecture` |
| Voir/exporter le graphe | `architecture --format json|mermaid|dot` |
| Estimer un impact | `impact` |
| Consommer depuis Java | `MinosApi`, `MinosMultiRepositoryApi` |
| Lire le graphe depuis Java | `MinosApi.getArchitectureGraph` |
| Exposer MINOS à un agent | `minos mcp` |
| Lire le graphe depuis un agent | `minos_architecture_graph` |
| Exporter vers NEXUS | `nexus-export` |

---

## 6. Import SCIP manuel

Le chemin manuel est conservé pour le diagnostic ou pour un provider non piloté par MINOS :

```powershell
minos.cmd import-scip my-project `
  --file N:\temp\index.scip `
  --provider external-provider
```

`minos index --scip ...` reste temporairement accepté pour compatibilité mais est déprécié.

---

## 7. Où MINOS stocke ses données

Par défaut :

```text
programme        : %LOCALAPPDATA%\Programs\MINOS
MINOS_HOME       : %LOCALAPPDATA%\MINOS\data
intégrations MCP : %LOCALAPPDATA%\MINOS\mcp-client-integrations.json
Docker           : %LOCALAPPDATA%\MINOS\docker + docker-data
```

La séparation est volontaire : mettre à jour ou désinstaller le programme ne doit pas supprimer automatiquement les données persistantes.

---

## 8. En cas de problème

Commencer par :

```powershell
minos.cmd --version
minos.cmd doctor --format json
minos.cmd project list --format json
```

Pour les intégrations MCP du setup :

```powershell
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-clients.log" -Tail 200
```

Puis consulter [Dépannage](troubleshooting.md).

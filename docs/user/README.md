# Guide utilisateur MINOS

Ce guide s'adresse à une personne qui veut **installer, alimenter et interroger MINOS sans modifier son code source**.

Le parcours utilisateur normal commence par une **GitHub Release Windows**. Il ne faut pas cloner le dépôt MINOS ni lancer Maven pour installer le produit.

## Trouver une information en moins d'une minute

| Je veux... | Ouvrir directement |
|---|---|
| Installer / mettre à jour / désinstaller MINOS | **[Installation PROD Windows](production-installation.md)** |
| Utiliser MINOS directement dans IntelliJ | **[Plugin IntelliJ](intellij-plugin.md)** |
| **Voir le graphe d'architecture** | **[Visualiser le graphe d'architecture](architecture-graph.md)** |
| Comprendre `--format text/json/mermaid/dot` | [Référence CLI](cli.md) |
| Connecter Copilot, Claude ou Codex au MCP natif | [Serveur MCP](mcp.md) |
| Comprendre / lancer l'indexation automatique | [Indexation autonome](autonomous-indexing.md) |
| Utiliser MINOS depuis Java | [API Java locale](java-api.md) |
| Exporter vers NEXUS | [Intégration NEXUS](nexus.md) |
| Diagnostiquer un problème | [Dépannage](troubleshooting.md) |
| Développer MINOS depuis les sources | [Installation depuis les sources](installation.md) |

### Je veux juste voir mon graphe maintenant

MINOS propose deux façons complémentaires de visualiser l'architecture :

- **dans IntelliJ**, via la Tool Window du [plugin MINOS](intellij-plugin.md) ;
- **hors IDE**, en exportant le graphe en JSON, Mermaid ou Graphviz DOT.

```powershell
# Données du graphe
minos.cmd architecture my-project --format json

# Source Mermaid
minos.cmd architecture my-project --format mermaid |
  Set-Content .\architecture.mmd -Encoding utf8

# Source Graphviz DOT
minos.cmd architecture my-project --format dot |
  Set-Content .\architecture.dot -Encoding utf8
```

Pour savoir **quoi ouvrir ensuite, comment obtenir un SVG/PNG, comment filtrer un gros projet et comment diagnostiquer un graphe vide**, utiliser la page dédiée :

**→ [Visualiser le graphe d'architecture MINOS](architecture-graph.md)**

---

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
   ├── plugin IntelliJ optionnel
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
CLI / IntelliJ / API Java / MCP / NEXUS
```

Le `setup.exe` est le **canal recommandé** pour un poste Windows. Le ZIP reste disponible comme distribution **portable / automatisation / diagnostic**. Le plugin IntelliJ est, lui, distribué sous forme de ZIP installable depuis l'IDE.

L'utilisateur normal ne prépare plus `index.scip` manuellement. `minos index <project>` découvre le projet, sélectionne le provider qualifié, calcule la portée d'indexation, exécute le provider puis promeut le nouveau snapshot de manière atomique. Le plugin IntelliJ réutilise exactement ce lifecycle lorsqu'il déclenche une indexation ou un reindex.

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
- providers qualifiés Java, TypeScript et Python ;
- premier projet et visualisation du graphe ;
- mise à jour, rollback et désinstallation ;
- publication d'une release pour les mainteneurs.

Le parcours `git clone` / Maven est volontairement séparé dans [Installation depuis les sources](installation.md).

Pour installer l'intégration IDE native après MINOS : **[Plugin IntelliJ — installation et configuration](intellij-plugin.md)**.

---

## 2. Démarrage rapide après installation

Dans un nouveau PowerShell :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd tools list
minos.cmd ide handshake --format json
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

**Guide dédié : [Visualiser le graphe d'architecture](architecture-graph.md).**

Dans IntelliJ, la Tool Window **MINOS** fournit le statut projet/provider/snapshot, le graphe, l'impact, les tests liés, l'activité Git factuelle et les actions d'indexation. Voir [Plugin IntelliJ](intellij-plugin.md).

Les surfaces M19/M20 plus avancées — Program Graph, Impact v2, security paths, semantic search et hybrid search/context — sont disponibles via l'API Java et/ou le MCP lorsque leurs capacités sont disponibles. Leur convergence complète dans l'UX IntelliJ fait partie de M21.

---

## 3. MCP natif et MCP Docker

Le MCP natif est installé avec MINOS et reste le mode recommandé pour les clients agents :

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

Le MCP expose **23 tools read-only**. Le catalogue courant inclut notamment `minos_architecture_graph`, `minos_program_graph`, `minos_impact_v2`, `minos_security_paths`, `minos_semantic_search`, `minos_hybrid_search` et `minos_hybrid_context`. La liste exacte est générée dans [`../generated/product-facts.md`](../generated/product-facts.md).

Le plugin IntelliJ est distinct du MCP : il fonctionne sans LLM et ajoute une UX native, de la navigation et des actions locales d'administration. Les deux intégrations peuvent être installées simultanément.

---

## 4. Fonctionnalités principales

| Besoin | Commande / surface |
|---|---|
| Diagnostiquer l'installation | `doctor` |
| Négocier la compatibilité IDE | `ide handshake` |
| Gérer les providers | `tools list/install/verify` |
| Enregistrer un projet | `project add` ou bouton **Register** IntelliJ |
| Voir les projets | `project list` |
| Inspecter l'état | `inspect`, `index-status`, Tool Window IntelliJ |
| Indexer automatiquement | `index`, boutons **Index/Reindex Full** IntelliJ |
| Importer un SCIP explicitement | `import-scip` |
| Rechercher du contexte | `search` |
| Trouver un symbole | `find-symbol` |
| Lire un fichier source | `get-source` |
| Trouver les usages | `find-usages` ou menu contextuel IntelliJ |
| Implémentations/appels/dépendances | commandes relationnelles / actions IntelliJ |
| Trouver les tests liés | `related-tests` / action IntelliJ |
| Lire l'architecture | `architecture` |
| Voir l'architecture dans l'IDE | [Plugin IntelliJ](intellij-plugin.md) |
| **Voir/exporter le graphe** | `architecture --format json|mermaid|dot` — [guide](architecture-graph.md) |
| Estimer un impact baseline | `impact` / action IntelliJ |
| Program Graph / Impact v2 / security paths | API Java avancée / MCP |
| Semantic index / semantic & hybrid retrieval | API Java sémantique / MCP, provider optionnel |
| Lire l'activité Git factuelle | `git-activity` / onglet Git Activity IntelliJ |
| Consommer depuis Java | `MinosApi` + APIs additives Provider/Advanced/Semantic |
| Lire le graphe depuis Java | `MinosApi.getArchitectureGraph` |
| Exposer MINOS à un agent | `minos mcp` |
| Lire le graphe depuis un agent | `minos_architecture_graph` / `minos_program_graph` |
| Exporter vers NEXUS | `nexus-export` + signaux sémantiques v2 |

---

## 5. Import SCIP manuel

Le chemin manuel est conservé pour le diagnostic ou pour un provider non piloté par MINOS :

```powershell
minos.cmd import-scip my-project `
  --file N:\temp\index.scip `
  --provider external-provider
```

`minos index --scip ...` reste temporairement accepté pour compatibilité mais est déprécié.

---

## 6. Où MINOS stocke ses données

Par défaut :

```text
programme        : %LOCALAPPDATA%\Programs\MINOS
MINOS_HOME       : %LOCALAPPDATA%\MINOS\data
intégrations MCP : %LOCALAPPDATA%\MINOS\mcp-client-integrations.json
Docker           : %LOCALAPPDATA%\MINOS\docker + docker-data
```

Les réglages du plugin IntelliJ sont stockés dans la configuration du projet IDE ; ils référencent le launcher MINOS et éventuellement `MINOS_HOME`, mais le plugin ne possède pas de copie des snapshots.

La séparation est volontaire : mettre à jour ou désinstaller le programme ne doit pas supprimer automatiquement les données persistantes.

---

## 7. En cas de problème

Commencer par :

```powershell
minos.cmd --version
minos.cmd doctor --format json
minos.cmd ide handshake --format json
minos.cmd project list --format json
```

Pour un problème de graphe :

```powershell
minos.cmd index-status my-project --format json
minos.cmd architecture my-project --format json
```

Puis consulter **[Visualiser le graphe — diagnostic rapide](architecture-graph.md#le-graphe-est-vide--diagnostic-rapide)**.

Pour le plugin IntelliJ, consulter **[Plugin IntelliJ — Dépannage](intellij-plugin.md#dépannage)**.

Pour les intégrations MCP du setup :

```powershell
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-clients.log" -Tail 200
```

Puis consulter [Dépannage](troubleshooting.md).

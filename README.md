# MINOS — Code Intelligence Engine

**MINOS** construit une connaissance structurée, persistante, interrogeable et explicable d’un codebase.

Il répond notamment à des questions comme :

- où est défini ce symbole ?
- qui l’utilise, l’appelle, l’étend ou l’implémente ?
- de quoi dépend-il ?
- quels tests lui sont liés ?
- quelle est l’architecture observée du projet ?
- quelles sont les dépendances réelles entre modules et comment les visualiser ?
- quels éléments peuvent être potentiellement impactés par une modification ?
- quelles relations entre dépôts sont réellement prouvables ?
- quelles zones ont récemment changé dans Git ?

MINOS est **local-first**, **agnostique du langage**, indépendant des fournisseurs d’IA et découplé des formats d’indexation externes par une couche de normalisation.

## Architecture générale

```mermaid
flowchart TB
    SRC[Projet local] --> DISC[Discovery / negotiation]
    DISC --> IDX[Indexeurs qualifiés / SCIP]
    IDX --> MINOS[MINOS Code Intelligence]
    GIT[Git local] --> MINOS
    MINOS --> CLI[CLI]
    MINOS --> API[API Java]
    MINOS --> MCP[MCP STDIO]
    MINOS --> NX[NEXUS export JSON]
    NX --> NEXUS[NEXUS Context Intelligence]
```

MINOS n’est ni un chatbot ni un LLM. Il produit des **faits de code, dérivations explicables et vues structurées** consommables par des développeurs, outils, agents et moteurs de contexte.

## État courant

**C0 à M14 sont terminés et livrés.**

M14 a fermé l’indexation autonome, le runtime provider Windows, la distribution Windows native, le MCP natif et le packaging de release. La PR M14 #43 a été fusionnée dans `main` le 24 juillet 2026.

Le packaging Windows fournit un **setup.exe complet** tout en conservant le ZIP portable. Les évolutions post-M14 ajoutent la visualisation du graphe d'architecture et l'intégration optionnelle du MCP natif dans les clients IA locaux.

Voir :

- [`docs/STATUS.md`](docs/STATUS.md) — état livré sur `main` ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — roadmap produit ;
- [`docs/roadmap/M14_EXECUTION.md`](docs/roadmap/M14_EXECUTION.md) — qualification détaillée M14.

## Installer MINOS sous Windows

L’utilisateur normal **ne clone pas le dépôt MINOS et ne lance pas Maven**.

Une GitHub Release Windows expose deux canaux :

```text
MINOS-<version>-windows-x64-setup.exe
MINOS-<version>-windows-x64-setup.exe.sha256

minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256
```

Le **`setup.exe` est le canal recommandé** pour un poste Windows. Il installe l'application, son runtime Java, la CLI, le MCP natif, l'intégration PATH et le désinstalleur Windows.

Pendant l'installation, l'utilisateur peut choisir explicitement d'enregistrer le MCP natif MINOS dans :

```text
GitHub Copilot — JetBrains / IntelliJ
GitHub Copilot CLI
Claude Code
Claude Desktop
OpenAI Codex
```

Ces intégrations utilisent directement `app\minos.exe mcp` et **ne nécessitent pas Docker**. Le setup peut séparément configurer le MCP Docker si Docker Desktop est déjà installé et démarré.

Le ZIP reste le canal **portable / automatisation / diagnostic** et contient la même application MINOS, les scripts d'intégration MCP natifs, les scripts Docker et l'installateur PowerShell portable.

Parcours recommandé :

```text
GitHub Release
→ télécharger setup.exe + SHA-256
→ vérifier SHA-256
→ lancer setup.exe
→ choisir éventuellement les clients MCP natifs
→ choisir éventuellement le MCP Docker
→ minos.cmd doctor
```

Voir **[Installation PROD Windows](docs/user/production-installation.md)** pour le téléchargement, l’installation, les clients MCP natifs, le MCP Docker, les providers, la mise à jour, le rollback et la désinstallation.

## Utilisation après installation

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd tools install scip-java
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd index my-project --dry-run
minos.cmd index my-project
minos.cmd search my-project GreetingPort --format json
```

Le parcours normal ne demande plus de préparer `index.scip` manuellement : MINOS découvre le projet, négocie le provider, vérifie son runtime, calcule la portée d’indexation, exécute le provider, normalise, stage puis promeut le snapshot.

L’import d’un artefact SCIP explicite reste disponible pour le diagnostic :

```powershell
minos.cmd import-scip my-project `
  --file N:\temp\index.scip `
  --provider external-provider
```

## Visualiser le graphe d'architecture

MINOS expose les arêtes de dépendances agrégées entre modules dans la sortie JSON :

```powershell
minos.cmd architecture my-project --format json
```

Pour produire directement un diagramme Mermaid :

```powershell
minos.cmd architecture my-project --format mermaid |
  Set-Content .\architecture.mmd -Encoding utf8
```

Ou Graphviz DOT :

```powershell
minos.cmd architecture my-project --format dot |
  Set-Content .\architecture.dot -Encoding utf8
```

Sur un gros projet, limiter le graphe au voisinage direct d'un module :

```powershell
minos.cmd architecture my-project --module packages/api --format mermaid
```

Les rendus utilisent uniquement les arêtes réellement présentes dans le snapshot actif.

## Développer MINOS depuis les sources

### Prérequis

```text
Java 24
Maven 3.9.x via Maven Wrapper
Git
```

Sous Windows PowerShell :

```powershell
.\mvnw.cmd clean verify
```

La version de développement est actuellement :

```text
0.2.0-SNAPSHOT
```

Le packaging produit notamment un shaded JAR :

```text
target/minos-code-intelligence-0.2.0-SNAPSHOT-all.jar
```

Le launcher du checkout `minos.cmd` recherche automatiquement le shaded JAR courant :

```powershell
.\minos.cmd --help
.\minos.cmd doctor
```

## Construire ou publier une distribution Windows

Cette section concerne les mainteneurs. Les utilisateurs téléchargent une GitHub Release.

Construire la distribution portable :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0-rc2
```

Construire ensuite le setup Windows avec Inno Setup 6/7 :

```powershell
.\scripts\release\build-windows-installer.ps1 -Version 0.2.0-rc2
```

Sorties :

```text
target/dist/MINOS-0.2.0-rc2-windows-x64-setup.exe
target/dist/MINOS-0.2.0-rc2-windows-x64-setup.exe.sha256
target/dist/minos-0.2.0-rc2-windows-x64.zip
target/dist/minos-0.2.0-rc2-windows-x64.zip.sha256
```

Publier depuis un poste Windows avec GitHub CLI authentifié :

```powershell
.\scripts\release\publish-windows-release.ps1 -Version 0.2.0-rc2
```

Le même parcours est disponible manuellement dans GitHub Actions via **Publish Windows Release**. Le workflow valide aussi le cycle installation/désinstallation des intégrations MCP natives, construit les deux distributions, vérifie les checksums, smoke-teste le ZIP et le `setup.exe`, vérifie la désinstallation, refuse de remplacer une version/tag existant, puis attache les quatre assets à la GitHub Release.

## Capacités CLI

### Projet, runtime et index

```text
doctor
tools list / install / verify
project add
project list
project inspect / inspect
index
import-scip
index-status
```

### Code Intelligence

```text
search
find-symbol
get-source
find-usages
find-implementations
find-callers
find-callees
dependencies
dependents
related-tests
architecture
impact
```

`architecture` supporte `text`, `json`, `mermaid` et `dot` ; la sortie JSON contient les arêtes inter-modules détaillées.

### Intégrations

```text
API Java M11/M12 + getArchitectureGraph
MCP STDIO — 16 tools read-only
Git Intelligence via JGit
Workspaces multi-repositories
nexus-export — contrat JSON M13
```

## MCP

Le mode natif recommandé pour les clients est :

```text
command = <installation>\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Le tool `minos_architecture_graph` expose le graphe en JSON, Mermaid ou DOT.

Docker reste un mode MCP durci optionnel : pas de réseau, filesystem read-only et projets read-only. Le `setup.exe` peut préparer, démarrer et valider ce mode si Docker Desktop est déjà opérationnel. Docker n’est pas le moteur principal de compilation/indexation des projets et n'est pas requis pour les intégrations MCP natives.

## Documentation

### Utilisateur

- [Guide utilisateur](docs/user/README.md)
- [Installation PROD Windows](docs/user/production-installation.md)
- [Indexation autonome](docs/user/autonomous-indexing.md)
- [Installation depuis les sources](docs/user/installation.md)
- [CLI](docs/user/cli.md)
- [API Java](docs/user/java-api.md)
- [MCP](docs/user/mcp.md)
- [MINOS → NEXUS](docs/user/nexus.md)
- [Dépannage](docs/user/troubleshooting.md)

### Développeur

- [Guide développeur](docs/developer/README.md)
- [Architecture interne](docs/developer/architecture.md)
- [Modèle de domaine](docs/developer/domain-model.md)
- [Indexation, lifecycle et stockage](docs/developer/indexing-and-storage.md)
- [Surfaces publiques](docs/developer/public-surfaces.md)
- [Multi-dépôts et Git](docs/developer/multi-repo-git.md)
- [Tests et contribution](docs/developer/testing.md)

### Architecture et historique

- [ADR](docs/adr/README.md)
- [Historique](docs/history/README.md)
- [Roadmap](docs/ROADMAP.md)

## Stack

```text
Java             24
Build            Maven 3.9.x
SCIP bindings    0.9.0
MCP Java SDK     2.0.0
Git              Eclipse JGit 7.6.0.202603022253-r
MCP transport    STDIO
Serveur HTTP     aucun requis dans le cœur
```

## Principes

- **MINOS-first, Glean-optional** ;
- faits, dérivations et heuristiques restent distincts ;
- provenance et preuves sont conservées ;
- les limitations fournisseur ne deviennent jamais des garanties ;
- les snapshots sont promus de façon cohérente ;
- l’incrémental n’est activé que lorsqu’un provider le prouve ;
- l’impact est potentiel, pas une certitude runtime ;
- une relation cross-repository exige une identité exacte et unique ;
- l’activité Git n’est pas une mesure automatique d’importance architecturale ;
- CLI, API, MCP et NEXUS sont des surfaces d’exposition, pas des duplications du métier.

> Règle de développement : **mesurer avant d’industrialiser**.

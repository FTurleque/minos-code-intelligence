# Installation PROD de MINOS sous Windows

Ce guide décrit le parcours **utilisateur** de MINOS sous Windows.

Le parcours normal ne nécessite **ni clone Git de MINOS, ni Maven, ni JDK pour exécuter MINOS**. Une release Windows contient son propre runtime Java. Le JDK, Maven, Node/npm ou d'autres toolchains peuvent en revanche être nécessaires au **projet analysé** et à son provider.

> État au **2 août 2026** : `v1.0.0` est publiée. `1.0.1` reste **NON PUBLIÉE**. Le runtime Windows/MCP est durci et M29 ajoute un backend Docker autonome, mais aucune équivalence métier native/Docker n'est revendiquée avant M29-S8.

---

## 1. Parcours recommandé

Pour un poste Windows, utiliser le `setup.exe` :

```text
GitHub Release MINOS
        ↓
MINOS-<version>-windows-x64-setup.exe
        +
MINOS-<version>-windows-x64-setup.exe.sha256
        ↓
vérifier SHA-256
        ↓
lancer setup.exe
        ↓
dossier d'installation
        ↓
tâches Windows (PATH)
        ↓
Mode MCP — un seul choix
  ├── MCP natif Windows — recommandé
  ├── MCP Docker — isolation renforcée
  └── Ne pas configurer maintenant
        ↓
si un backend est choisi : Clients IA détectés
  ├── GitHub Copilot — JetBrains / IntelliJ
  ├── GitHub Copilot CLI
  ├── Claude Code
  ├── Claude Desktop
  └── OpenAI Codex CLI / Desktop
        ↓
si Docker : racine des projets exposés
        ↓
validation + handshake du backend candidat
        ↓
commit de backend.properties
        ↓
%LOCALAPPDATA%\Programs\MINOS
```

**Un seul backend MCP est actif à la fois.** Le point d'entrée des clients ne change jamais : `app\minos.exe mcp` avec `MINOS_HOME=%LOCALAPPDATA%\MINOS\data`.

Le natif reste le choix recommandé tant que M29-S8 n'a pas qualifié la parité métier complète.

Le ZIP reste disponible comme distribution **portable / automatisation / diagnostic**. Le checkout source est réservé au développement : [Installation depuis les sources](installation.md).

---

## 2. Télécharger et vérifier une release

Une release Windows complète publie huit assets :

```text
MINOS-<version>-windows-x64-setup.exe
MINOS-<version>-windows-x64-setup.exe.sha256
minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256
minos-<version>.cdx.json
minos-<version>.cdx.json.sha256
MINOS-<version>-THIRD-PARTY-NOTICES.txt
MINOS-<version>-THIRD-PARTY-NOTICES.txt.sha256
```

Le dépôt étant privé, GitHub peut demander une authentification.

Vérification du setup :

```powershell
$Version = '1.0.1'
Get-FileHash ".\MINOS-$Version-windows-x64-setup.exe" -Algorithm SHA256
Get-Content ".\MINOS-$Version-windows-x64-setup.exe.sha256"
```

Les 64 caractères hexadécimaux doivent être identiques. **Ne pas lancer l'installateur si les empreintes diffèrent.**

---

## 3. Contenu installé

Emplacement par défaut :

```text
%LOCALAPPDATA%\Programs\MINOS
```

Le setup installe notamment :

```text
MINOS
├── app\
│   ├── minos.exe
│   └── runtime\
├── lib\minos.jar
├── integration\
│   ├── detect-mcp-clients.ps1
│   ├── configure-mcp-clients.ps1
│   ├── configure-mcp-clients-setup.ps1
│   ├── configure-codex-mcp.ps1
│   ├── uninstall-mcp-clients.ps1
│   ├── probe-mcp-backend.ps1
│   └── switch-mcp-backend.ps1
├── docker\
│   ├── Dockerfile.mcp.release
│   ├── compose.mcp.prod.yaml
│   └── scripts\
├── minos.cmd
├── minos-mcp.cmd
├── VERSION
├── RUNTIME-MODULES.txt
├── RELEASE-MANIFEST.json
├── supply-chain\
│   ├── minos.cdx.json
│   └── THIRD-PARTY-NOTICES.txt
└── désinstalleur Windows
```

À partir de 1.0.1, `RUNTIME-MODULES.txt` matérialise les modules réellement présents dans le runtime Java produit. Ils sont dérivés du JAR final avec `jdeps`, puis vérifiés avec `runtime\bin\java --list-modules`, avec assertion explicite de `java.xml`.

---

## 4. Wizard Windows

### 4.1 Tâches Windows / PATH

La page standard **Select Additional Tasks** contient uniquement les tâches Windows indépendantes :

```text
☑ Ajouter MINOS au PATH de l'utilisateur
```

Après installation, ouvrir un **nouveau terminal** avant d'utiliser `minos.cmd` par son nom.

### 4.2 Page « Mode MCP »

Le Wizard propose exactement :

```text
Mode MCP
Choisissez le backend du serveur MCP MINOS

( ) MCP natif Windows — recommandé
( ) MCP Docker — isolation renforcée
( ) Ne pas configurer maintenant
```

Les choix sont **exclusifs**. Lors d'une mise à niveau, le backend déjà persisté dans :

```text
%LOCALAPPDATA%\MINOS\data\runtime\backend.properties
```

est présélectionné lorsqu'il est reconnu.

Si **Ne pas configurer maintenant** est choisi, le programme est installé mais aucun backend MCP ni aucun client IA n'est configuré automatiquement.

### 4.3 Page « Clients IA »

Cette page apparaît lorsque **native ou Docker** est choisi.

Chaque client reçoit :

- une case activée uniquement si son mode d'intégration est réellement détecté ;
- un diagnostic visible ;
- aucune sélection par défaut ;
- aucune écriture dans un logiciel tiers sans choix explicite.

Exemple :

```text
☐ GitHub Copilot — JetBrains / IntelliJ
  Détecté

☐ GitHub Copilot CLI
  Non disponible — launcher VS Code détecté, vrai CLI absent

☐ Claude Code
  Détecté — CLI MCP compatible

☐ Claude Desktop
  Détecté

☐ OpenAI Codex
  Détecté — Codex CLI ou Codex Desktop
```

Le préflight est borné et non interactif. Pour les CLI, un simple `Get-Command` ne suffit pas : MINOS vérifie la capability MCP. Un launcher/shim VS Code est rejeté avant le probe pour ne pas le confondre avec le vrai Copilot CLI.

### 4.4 Contrat client backend-agnostic

Tous les clients utilisent le même contrat :

```text
command = %LOCALAPPDATA%\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Les configurations clientes **ne contiennent ni `docker exec`, ni nom de conteneur, ni Compose**. Le choix réel est lu derrière le launcher dans `backend.properties`.

Changer `native ↔ docker` ne nécessite donc pas de réécrire Copilot, Claude ou Codex.

Règles d'ownership :

- ne jamais écraser une entrée MCP `minos` étrangère ;
- sauvegarder les fichiers utilisateur avant modification ;
- conserver tous les autres serveurs/propriétés ;
- enregistrer l'ownership MINOS ;
- préserver une entrée gérée si l'utilisateur l'a modifiée ;
- supprimer sélectivement uniquement ce qui appartient encore à MINOS.

État et diagnostics :

```text
%LOCALAPPDATA%\MINOS\mcp-client-integrations.json
%LOCALAPPDATA%\MINOS\codex-mcp-integration.json
%LOCALAPPDATA%\MINOS\mcp-clients.log
%LOCALAPPDATA%\MINOS\backups\mcp-clients\...
```

#### GitHub Copilot — JetBrains / IntelliJ

Le setup fusionne `servers.minos` dans :

```text
%LOCALAPPDATA%\github-copilot\intellij\mcp.json
```

Ouvrir ensuite Copilot Chat en mode Agent et vérifier que `minos` est connecté.

#### GitHub Copilot CLI

Lorsqu'un vrai CLI compatible est disponible :

```powershell
copilot mcp get minos --json
copilot mcp list --json
```

#### Claude Code

```powershell
claude mcp get minos
claude mcp list
```

Puis `/mcp` dans Claude Code.

#### Claude Desktop

Le setup fusionne `mcpServers.minos` dans `%APPDATA%\Claude\claude_desktop_config.json`. Quitter complètement puis relancer Claude Desktop après modification.

#### OpenAI Codex / Codex Desktop

Codex CLI est utilisé si sa capability MCP est prouvée. Pour Codex Desktop, MINOS gère un bloc marqué dans :

```text
%USERPROFILE%\.codex\config.toml
```

Une section `[mcp_servers.minos]` non gérée par MINOS n'est jamais écrasée.

### 4.5 Backend Docker pendant le setup

Si **MCP Docker — isolation renforcée** est choisi, le setup demande une racine projets, par exemple `N:\workspace-dev`. Cette racine est montée read-only.

Docker Desktop doit déjà être installé et son daemon Linux démarré. **Docker explicitement choisi mais indisponible bloque le Wizard. MINOS ne bascule jamais silencieusement vers le natif.**

Le parcours est transactionnel :

```text
prepare Docker
→ validate
→ handshake MCP candidat
→ commit backend.properties
→ retire ancien backend si nécessaire
```

Le handshake exige :

```text
initialize
→ notifications/initialized
→ tools/list
→ minos_search_code
→ minos_impact
```

Un échec conserve ou restaure le backend précédent.

Journal :

```text
%LOCALAPPDATA%\MINOS\data\runtime\backend-switch.log
```

---

## 5. Programme et données persistantes

```text
programme          : %LOCALAPPDATA%\Programs\MINOS
MINOS_HOME         : %LOCALAPPDATA%\MINOS\data
backend config     : %LOCALAPPDATA%\MINOS\data\runtime\backend.properties
switch state/log   : %LOCALAPPDATA%\MINOS\data\runtime\backend-switch.*
intégrations MCP   : %LOCALAPPDATA%\MINOS\mcp-client-integrations.json
Codex MCP state    : %LOCALAPPDATA%\MINOS\codex-mcp-integration.json
backups MCP        : %LOCALAPPDATA%\MINOS\backups\mcp-clients
Docker runtime     : %LOCALAPPDATA%\MINOS\docker
Docker data        : %LOCALAPPDATA%\MINOS\docker-data
```

Le programme et les données sont séparés. Une mise à jour ou une désinstallation standard ne doit pas supprimer automatiquement projets enregistrés, snapshots ou index.

---

## 6. Vérifier l'installation

```powershell
minos.cmd --version
minos.cmd doctor
```

Pour vérifier les intégrations :

```powershell
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-clients.log" -Tail 100
```

Pour le MCP, la preuve réelle reste un handshake `initialize → tools/list`; après installation, vérifier aussi depuis le client réellement utilisé que `minos` est `Connected`.

---

## 7. Providers et premier projet

Lister les providers :

```powershell
minos.cmd tools list
```

Java/Maven qualifié : `scip-java 0.13.1`. MINOS embarque son runtime Java, mais `scip-java` utilise le JDK du projet.

TypeScript :

```powershell
node --version
npm --version
minos.cmd tools install scip-typescript
```

Premier projet :

```powershell
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd inspect my-project
minos.cmd index my-project --dry-run
minos.cmd index my-project
minos.cmd index-status my-project --format json
minos.cmd search my-project SearchService --format json
```

Voir [Référence CLI](cli.md) et [Providers polyglottes](polyglot-providers.md).

---

## 8. MCP natif

Le natif utilise le point d'entrée stable `minos.exe mcp`. Le wrapper `minos.cmd mcp` reste disponible. Le catalogue courant expose 31 tools read-only.

### Pourquoi 1.0.1 corrige `org/w3c/dom/Node`

1.0.0 avait une image Java `jpackage` trop réduite. `org.w3c.dom.Node` appartient à `java.xml`. Le correctif utilise :

```text
JAR final
→ jdeps --print-module-deps
→ jpackage --add-modules <liste calculée>
→ runtime/bin/java --list-modules
→ assertion java.xml
→ handshake MCP réel
```

---

## 9. MCP Docker et switching

Le query plane Docker conserve :

```text
network_mode: none
filesystem conteneur: read-only
projets: read-only
cap_drop: ALL
no-new-privileges: true
```

Pour basculer après installation, utiliser le switcher autoritatif :

```powershell
$Minos = "$env:LOCALAPPDATA\Programs\MINOS"

& "$Minos\integration\switch-mcp-backend.ps1" `
  -InstallRoot $Minos `
  -TargetBackend docker `
  -ProjectsRoot N:\workspace-dev
```

Retour natif :

```powershell
& "$Minos\integration\switch-mcp-backend.ps1" `
  -InstallRoot $Minos `
  -TargetBackend native
```

Un runtime Docker de même version/commit/racines est **réutilisé** avec Start + Validate + handshake. Un vrai upgrade prépare un nouveau runtime et possède un rollback du runtime précédent.

---

## 10. Distribution ZIP portable

Le ZIP est autonome : aucun checkout Git de MINOS n'est nécessaire.

Vérification :

```powershell
$Version = '1.0.1'
Get-FileHash ".\minos-$Version-windows-x64.zip" -Algorithm SHA256
Get-Content ".\minos-$Version-windows-x64.zip.sha256"
```

Installation sans MCP :

```powershell
& ".\minos-1.0.1-windows-x64\install.ps1" `
  -Package ".\minos-1.0.1-windows-x64" `
  -McpBackend none `
  -AddToPath
```

Installation native :

```powershell
& ".\minos-1.0.1-windows-x64\install.ps1" `
  -Package ".\minos-1.0.1-windows-x64" `
  -McpBackend native `
  -AddToPath
```

Installation Docker :

```powershell
& ".\minos-1.0.1-windows-x64\install.ps1" `
  -Package ".\minos-1.0.1-windows-x64" `
  -McpBackend docker `
  -ProjectsRoot N:\workspace-dev `
  -AddToPath
```

Le ZIP sauvegarde le répertoire programme précédent avant remplacement. Si la validation du nouveau payload/backend échoue, l'ancien répertoire est restauré. Les données restent séparées du programme.

---

## 11. Mettre MINOS à jour

MINOS n'a pas encore d'auto-updater.

Avec le setup, l'AppId Windows stable retrouve l'installation précédente et le backend persisté est présélectionné. Le candidat backend n'est committé qu'après validation/handshake.

Avec le ZIP, `install.ps1` sauvegarde le payload précédent avec un nom collision-safe, installe le nouveau payload puis valide le backend demandé. Un échec restaure le backup programme.

Après mise à jour :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd project list
```

---

## 12. Désinstaller MINOS

Utiliser **Paramètres Windows → Applications → Applications installées → MINOS Code Intelligence → Désinstaller**.

Le cleanup standard retire le programme, le PATH géré, les intégrations MCP encore détenues par MINOS et le runtime/conteneur/image Docker gérés lorsque présents. Il préserve les configurations tierces non détenues et les données persistantes.

### Choix de suppression des données locales

En désinstallation interactive :

```text
Supprimer également toutes les données MINOS locales ?

%LOCALAPPDATA%\MINOS

[Oui] [Non]
```

Le choix par défaut est **Non / conserver**.

Si **Non** est choisi, `%LOCALAPPDATA%\MINOS` est conservé. Si **Oui** est choisi, le cleanup normal se termine d'abord puis l'arborescence est supprimée : registre, snapshots, index, runs, providers/outils gérés, logs, backups et `docker-data`.

Cette suppression est **irréversible**. Les désinstallations silencieuses et les setups de smoke conservent les données et n'affichent jamais le prompt.

---

## 13. Publication — mainteneurs uniquement

Candidat local sûr :

```powershell
.\scripts\release\build-local-windows-candidate.ps1 -Version 1.0.1
```

Ce runner construit la distribution, le setup, SBOM/notices/manifest, exerce les handshakes MCP et **ne publie rien**.

Le mainteneur vérifie notamment :

1. la page **Mode MCP** et ses trois choix exclusifs ;
2. le parcours natif ;
3. le parcours Docker fail-closed ;
4. la page **Clients IA** pour les deux backends ;
5. Codex Desktop et le rejet du `launcher VS Code détecté` comme faux Copilot CLI ;
6. le switching native↔Docker ;
7. la conservation des données à la désinstallation ;
8. la purge uniquement quand elle est explicitement demandée ;
9. un client réel, notamment Copilot, avant toute publication.

Qualification sans publication :

```powershell
.\scripts\release\publish-windows-release.ps1 `
  -Version 1.0.1 `
  -ValidateOnly
```

`.github/workflows/release-windows.yml` reste `workflow_dispatch`. M29 ne doit déclencher aucune GitHub Actions, PR ou publication sans autorisation explicite.

---

## Références

- [MCP](mcp.md)
- [Docker runtime](docker-runtime.md)
- [CLI](cli.md)
- [Providers polyglottes](polyglot-providers.md)
- [`docs/releases/1.0.1.md`](../releases/1.0.1.md)
- [`docs/roadmap/M29_EXECUTION.md`](../roadmap/M29_EXECUTION.md)

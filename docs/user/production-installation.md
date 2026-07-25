# Installation PROD de MINOS sous Windows

Ce guide décrit le parcours **utilisateur** de MINOS sous Windows.

Le parcours normal ne nécessite **ni clone Git de MINOS, ni Maven, ni JDK pour exécuter MINOS**. Une release Windows contient son propre runtime Java.

> Le JDK, Maven, Node ou npm peuvent toutefois être nécessaires au **projet analysé** et à son provider d'indexation. Ils ne servent pas à démarrer MINOS lui-même.

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
%LOCALAPPDATA%\Programs\MINOS
        ↓
CLI + MCP natif + PATH optionnel
        ↓
intégrations MCP natives optionnelles
  ├── GitHub Copilot — JetBrains / IntelliJ
  ├── GitHub Copilot CLI
  ├── Claude Code
  ├── Claude Desktop
  └── OpenAI Codex
        ↓
MCP Docker optionnel et séparé
        ↓
minos.cmd doctor
        ↓
provider → project add → index → search / architecture / MCP
```

Le ZIP reste disponible comme distribution **portable / automatisation / diagnostic**.

Le checkout source est un troisième parcours, réservé au développement de MINOS : [Installation depuis les sources](installation.md).

---

## 2. Télécharger une GitHub Release

Ouvrir la page **Releases** du dépôt GitHub MINOS.

Le dépôt étant privé, GitHub peut demander une authentification avant d'afficher ou télécharger les assets.

Une release Windows complète publie quatre assets :

```text
MINOS-<version>-windows-x64-setup.exe
MINOS-<version>-windows-x64-setup.exe.sha256

minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256
```

Une version avec suffixe comme `-rc4` est une **pre-release**. Une version comme `0.2.0` est une release stable.

---

## 3. Vérifier le SHA-256 du setup

Depuis le répertoire de téléchargement :

```powershell
$Version = '0.2.0-rc4'

Get-FileHash ".\MINOS-$Version-windows-x64-setup.exe" -Algorithm SHA256
Get-Content ".\MINOS-$Version-windows-x64-setup.exe.sha256"
```

Les 64 caractères hexadécimaux doivent être identiques. La casse n'a pas d'importance.

**Ne pas lancer l'installateur si les deux empreintes diffèrent.**

---

## 4. Installer avec `setup.exe`

Lancer :

```text
MINOS-<version>-windows-x64-setup.exe
```

L'installation est conçue pour l'utilisateur courant et ne demande normalement pas de droits administrateur.

Emplacement par défaut :

```text
%LOCALAPPDATA%\Programs\MINOS
```

Le setup installe :

```text
MINOS
├── app\                         runtime Java + minos.exe
├── lib\minos.jar
├── integration\
│   └── configure-mcp-clients.ps1
├── minos.cmd                    CLI + entrée MCP native
├── minos-mcp.cmd
├── docker\                      assets MCP Docker optionnels
├── VERSION
└── désinstalleur Windows
```

### 4.1 PATH utilisateur

La tâche :

```text
Ajouter MINOS au PATH de l'utilisateur
```

est proposée par le setup.

Après installation, ouvrir un **nouveau terminal** avant d'utiliser :

```powershell
minos.cmd --version
```

### 4.2 Choisir les clients MCP natifs

Le setup propose un groupe indépendant :

```text
Connecter le MCP natif MINOS à :
  ☐ GitHub Copilot — JetBrains / IntelliJ
  ☐ GitHub Copilot CLI
  ☐ Claude Code
  ☐ Claude Desktop
  ☐ OpenAI Codex
```

Toutes ces cases sont **décochées par défaut**. Modifier la configuration d'un logiciel tiers reste donc un choix explicite.

Ces intégrations sont **100 % natives** et n'utilisent pas Docker. Elles pointent vers :

```text
command = %LOCALAPPDATA%\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Le gestionnaire applique des règles de sécurité :

- ne jamais écraser une entrée MCP `minos` existante qu'il n'a pas créée ;
- sauvegarder un fichier JSON avant de le modifier ;
- conserver tous les autres serveurs/propriétés du client ;
- enregistrer les intégrations gérées pour permettre leur suppression sélective ;
- préserver une entrée si l'utilisateur l'a modifiée après l'installation.

État et diagnostics :

```text
%LOCALAPPDATA%\MINOS\mcp-client-integrations.json
%LOCALAPPDATA%\MINOS\mcp-clients.log
%LOCALAPPDATA%\MINOS\backups\mcp-clients\...
```

#### GitHub Copilot — JetBrains / IntelliJ

Le setup fusionne `servers.minos` dans :

```text
%LOCALAPPDATA%\github-copilot\intellij\mcp.json
```

Après installation, ouvrir Copilot Chat en mode **Agent** et vérifier la liste des tools MCP.

#### GitHub Copilot CLI

Si la commande `copilot` est disponible dans le `PATH`, le setup utilise l'interface MCP du client pour ajouter le serveur utilisateur `minos`.

Contrôle :

```powershell
copilot mcp get minos --json
copilot mcp list --json
```

#### Claude Code

Si `claude` est disponible dans le `PATH`, le setup ajoute MINOS au scope `user`.

Contrôle :

```powershell
claude mcp get minos
claude mcp list
```

Puis dans Claude Code :

```text
/mcp
```

#### Claude Desktop

Le setup fusionne `mcpServers.minos` dans :

```text
%APPDATA%\Claude\claude_desktop_config.json
```

Quitter complètement puis relancer Claude Desktop pour recharger la configuration.

#### OpenAI Codex

Si `codex` est disponible dans le `PATH`, le setup utilise l'interface MCP du client.

Contrôle :

```powershell
codex mcp get minos
codex mcp list
```

Voir [Utiliser MINOS via MCP](mcp.md) pour les configurations manuelles et le catalogue des tools.

### 4.3 MCP Docker pendant le setup

Le setup propose séparément :

```text
Configurer et démarrer le MCP Docker
```

**Docker n'est pas requis pour le MCP natif ni pour les intégrations ci-dessus.**

Si l'option Docker est sélectionnée, le setup demande la **racine des projets** à exposer au conteneur, par exemple :

```text
N:\workspace-dev
```

Cette racine est montée en lecture seule dans le conteneur.

Le setup exécute alors :

```text
construction de l'image MINOS de la release
→ génération de la configuration Docker
→ montage des projets read-only
→ démarrage du conteneur
→ validation du runtime Docker MCP
```

Docker Desktop doit déjà être installé et démarré. MINOS n'installe pas Docker Desktop.

Journal :

```text
%LOCALAPPDATA%\MINOS\docker-setup.log
```

---

## 5. Programme et données persistantes

Installation par défaut :

```text
programme          : %LOCALAPPDATA%\Programs\MINOS
MINOS_HOME         : %LOCALAPPDATA%\MINOS\data
intégrations MCP   : %LOCALAPPDATA%\MINOS\mcp-client-integrations.json
backups MCP        : %LOCALAPPDATA%\MINOS\backups\mcp-clients
Docker config      : %LOCALAPPDATA%\MINOS\docker
Docker data        : %LOCALAPPDATA%\MINOS\docker-data
```

Le home se remplit progressivement :

```text
%LOCALAPPDATA%\MINOS\data\
├── registry\
├── symbol-snapshots\
├── fingerprint-snapshots\
├── index-state\
├── staged-snapshots\
├── runs\
└── tools\
```

Cette séparation est volontaire : mettre à jour ou désinstaller le programme ne doit pas supprimer automatiquement les snapshots, projets enregistrés ou providers.

Pour utiliser temporairement un autre home :

```powershell
$env:MINOS_HOME = 'N:\minos-data'
minos.cmd project list
```

---

## 6. Vérifier l'installation

Dans un nouveau terminal :

```powershell
minos.cmd --version
minos.cmd doctor
```

`doctor` distingue notamment :

- le runtime Java embarqué utilisé par MINOS ;
- les commandes projet disponibles (`java`, `javac`, `mvn`, `node`, `npm`) ;
- Docker, qui reste optionnel ;
- l'état des providers gérés ;
- les actions nécessaires lorsqu'un provider est absent ou bloqué.

Le code de sortie de `doctor` peut être `1` lorsqu'une action provider reste nécessaire. Cela ne signifie pas que l'installation native est corrompue.

Pour contrôler les intégrations MCP sélectionnées, consulter aussi :

```powershell
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-clients.log" -Tail 100
```

---

## 7. Installer le provider du projet

Lister :

```powershell
minos.cmd tools list
```

### 7.1 Java / Maven

Provider qualifié :

```text
scip-java 0.13.1
```

MINOS embarque son propre runtime Java, mais `scip-java` utilise le **JDK du projet**.

Exemple :

```powershell
$env:JAVA_HOME = 'C:\path\to\project-jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

java -version
javac -version
minos.cmd tools install scip-java
```

MINOS n'installe pas un Maven global. Il utilise en priorité le Maven Wrapper du projet.

### 7.2 TypeScript

Préconditions :

```powershell
node --version
npm --version
```

Puis :

```powershell
minos.cmd tools install scip-typescript
```

MINOS installe le provider sous `MINOS_HOME\tools`, mais n'exécute pas silencieusement `npm install`, `yarn install` ou `pnpm install` pour les dépendances métier du projet.

---

## 8. Premier projet

```powershell
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd inspect my-project
minos.cmd index my-project --dry-run
minos.cmd index my-project
minos.cmd index-status my-project --format json
minos.cmd search my-project SearchService --format json
```

### Visualiser son architecture

JSON détaillé :

```powershell
minos.cmd architecture my-project --format json
```

Le champ `moduleDependencies` contient les arêtes du graphe inter-modules.

Mermaid :

```powershell
minos.cmd architecture my-project --format mermaid |
  Set-Content .\architecture.mmd -Encoding utf8
```

Graphviz DOT :

```powershell
minos.cmd architecture my-project --format dot |
  Set-Content .\architecture.dot -Encoding utf8
```

Voisinage d'un seul module :

```powershell
minos.cmd architecture my-project --module packages/api --format mermaid
```

Voir [Référence CLI](cli.md) pour les formats et exemples détaillés.

---

## 9. MCP natif — mode recommandé

Le serveur natif installé est :

```text
command = %LOCALAPPDATA%\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Le wrapper suivant reste disponible :

```powershell
minos.cmd mcp
```

CLI et MCP natif utilisent le même `MINOS_HOME` et les mêmes snapshots.

Le MCP expose **16 tools read-only**, dont `minos_architecture_graph` pour obtenir le graphe en `json`, `mermaid` ou `dot`.

Voir [MCP](mcp.md).

---

## 10. MCP Docker — mode durci optionnel

Le mode Docker conserve les invariants :

```text
network_mode: none
filesystem conteneur: read-only
projets: read-only
capabilities: dropped
```

Il utilise un home distinct :

```text
%LOCALAPPDATA%\MINOS\docker-data
```

### 10.1 Configuration après installation

Si l'option Docker n'a pas été sélectionnée pendant le setup :

```powershell
$Minos = "$env:LOCALAPPDATA\Programs\MINOS"

& "$Minos\docker\scripts\configure-docker-mcp.ps1" `
  -InstallRoot $Minos `
  -ProjectsRoot N:\workspace-dev `
  -Start
```

### 10.2 État / démarrage / arrêt

```powershell
$DockerMcp = "$env:LOCALAPPDATA\Programs\MINOS\docker\scripts\prod-mcp-release.ps1"

& $DockerMcp -Action Status
& $DockerMcp -Action Start
& $DockerMcp -Action Validate
& $DockerMcp -Action Stop
```

Ne pas partager le registre natif avec Docker : les racines ne sont pas représentées avec les mêmes chemins (`N:\...` côté Windows, `/workspace/projects/...` côté conteneur).

---

## 11. Distribution ZIP portable

Le ZIP est utile pour l'automatisation, le diagnostic ou une installation sans setup Windows.

### 11.1 Vérifier le ZIP

```powershell
$Version = '0.2.0-rc4'

Get-FileHash ".\minos-$Version-windows-x64.zip" -Algorithm SHA256
Get-Content ".\minos-$Version-windows-x64.zip.sha256"
```

### 11.2 Installer le ZIP

Le ZIP est autonome : **aucun checkout Git de MINOS n'est nécessaire**.

```powershell
$Version = '0.2.0-rc4'
$Zip = (Resolve-Path ".\minos-$Version-windows-x64.zip").Path
$ExtractRoot = Join-Path $PWD "minos-$Version-extracted"
$Distribution = Join-Path $ExtractRoot "minos-$Version-windows-x64"

Expand-Archive -LiteralPath $Zip -DestinationPath $ExtractRoot -Force
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& "$Distribution\install.ps1" `
  -Package $Distribution `
  -AddToPath
```

Le chemin par défaut reste :

```text
%LOCALAPPDATA%\Programs\MINOS
```

### 11.3 Configurer les clients MCP après une installation ZIP

Le gestionnaire est livré dans la distribution :

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

---

## 12. Mettre MINOS à jour

MINOS n'a pas encore d'auto-updater.

### 12.1 Mise à jour avec setup.exe

Télécharger le nouveau setup + checksum, vérifier le SHA-256, puis lancer le nouveau setup.

Le setup utilise un **AppId Windows stable**, retrouve l'installation précédente et réutilise son répertoire.

Les données restent dans :

```text
%LOCALAPPDATA%\MINOS\data
%LOCALAPPDATA%\MINOS\docker-data
```

Les cases d'intégration MCP peuvent être sélectionnées à nouveau. Une entrée déjà gérée par MINOS est actualisée ; une entrée `minos` étrangère ou modifiée n'est pas écrasée.

Après mise à jour :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd project list
```

### 12.2 Mise à jour ZIP

Le parcours PowerShell conserve le backup automatique de l'ancienne installation programme.

---

## 13. Désinstaller MINOS

Avec le `setup.exe`, utiliser **Paramètres Windows → Applications → Applications installées → MINOS Code Intelligence → Désinstaller**.

Le désinstalleur :

- retire le programme ;
- retire le chemin MINOS ajouté au `PATH` ;
- retire, lorsqu'elles correspondent encore aux entrées gérées, les intégrations MCP natives créées par le setup ;
- ne touche pas aux autres serveurs MCP des clients ;
- retire le conteneur et la configuration runtime du MCP Docker géré par le setup ;
- tente de supprimer l'image Docker MINOS correspondante ;
- **ne supprime pas automatiquement les données persistantes MINOS**.

Sont notamment conservés :

```text
%LOCALAPPDATA%\MINOS\data
%LOCALAPPDATA%\MINOS\docker-data
%LOCALAPPDATA%\MINOS\backups
%LOCALAPPDATA%\MINOS\*.log
```

La configuration runtime `%LOCALAPPDATA%\MINOS\docker` est supprimée lors de la désinstallation d'un MCP Docker géré ; `docker-data` reste conservé.

Si une entrée MCP créée par MINOS a été modifiée manuellement après l'installation, elle est volontairement **préservée**. Consulter `mcp-clients.log` pour la retirer manuellement si nécessaire.

### 13.1 Suppression complète des données

Cette opération est irréversible :

```powershell
Remove-Item "$env:LOCALAPPDATA\MINOS" -Recurse -Force
```

Elle supprime registre, snapshots, états d'indexation, providers gérés, logs, backups et données Docker MINOS.

---

## 14. Revenir à une version précédente

Les releases publiées restent versionnées dans GitHub Releases.

Pour revenir à une version antérieure :

1. fermer les clients MCP utilisant MINOS ;
2. télécharger le setup ou le ZIP de la version souhaitée ;
3. vérifier son SHA-256 ;
4. réinstaller cette version ;
5. vérifier `minos.cmd --version` et `minos.cmd doctor`.

`MINOS_HOME` reste séparé du programme. Avant un rollback important, sauvegarder `%LOCALAPPDATA%\MINOS` si les données sont critiques.

---

## 15. Publication d'une release — mainteneurs uniquement

Un utilisateur normal **ne doit pas exécuter cette section**.

Une release complète doit produire :

```text
MINOS-<version>-windows-x64-setup.exe
MINOS-<version>-windows-x64-setup.exe.sha256
minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256
```

### 15.1 Qualification locale Windows

Le parcours recommandé avant publication est :

```powershell
.\scripts\release\publish-windows-release.ps1 `
  -Version 0.2.0-rc4 `
  -ValidateOnly
```

Le build de distribution exécute aussi :

```text
scripts\install\verify-mcp-client-integration.ps1
```

Ce smoke-test utilise des configurations temporaires et de faux exécutables clients pour valider :

```text
Copilot JetBrains JSON : fusion + sauvegarde + uninstall sélectif
Claude Desktop JSON    : fusion + sauvegarde + uninstall sélectif
Copilot CLI            : add/get/remove
Claude Code            : add/get/remove
Codex                   : add/get/remove
collision minos        : préservation obligatoire
```

### 15.2 GitHub Actions

Dans **Actions → Publish Windows Release → Run workflow**, sélectionner `main` et fournir la version.

Le workflow :

```text
Java 24
→ validation intégrations MCP natives
→ clean verify
→ jpackage app-image
→ ZIP + SHA-256
→ installation d'Inno Setup
→ setup.exe + SHA-256
→ smoke install ZIP
→ smoke install setup.exe
→ vérification MINOS <version>
→ désinstallation silencieuse du setup
→ création du tag v<version>
→ création GitHub Release
→ upload des quatre assets
```

Une release/tag déjà existante est refusée.

### 15.3 Poste Windows mainteneur

Prérequis supplémentaires au build du setup : **Inno Setup avec `ISCC.exe`**.

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0-rc4
.\scripts\release\build-windows-installer.ps1 -Version 0.2.0-rc4
```

Publier avec `gh` authentifié :

```powershell
gh auth status
.\scripts\release\publish-windows-release.ps1 -Version 0.2.0-rc4
```

---

## 16. Dépannage

Commencer par :

```powershell
minos.cmd --version
minos.cmd doctor --format json
```

Pour les intégrations MCP natives :

```powershell
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-clients.log" -Tail 200
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-client-integrations.json"
```

Pour Docker :

```powershell
docker version
Get-Content "$env:LOCALAPPDATA\MINOS\docker-setup.log" -Tail 200
```

Pour le graphe :

```powershell
minos.cmd architecture <project> --format json
minos.cmd architecture <project> --format mermaid
```

Puis consulter [Dépannage](troubleshooting.md).

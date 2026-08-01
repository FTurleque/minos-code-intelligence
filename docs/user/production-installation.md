# Installation PROD de MINOS sous Windows

Ce guide décrit le parcours **utilisateur** de MINOS sous Windows.

Le parcours normal ne nécessite **ni clone Git de MINOS, ni Maven, ni JDK pour exécuter MINOS**. Une release Windows contient son propre runtime Java.

> Le JDK, Maven, Node ou npm peuvent toutefois être nécessaires au **projet analysé** et à son provider d'indexation. Ils ne servent pas à démarrer MINOS lui-même.

> État au 1er août 2026 : `v1.0.0` est publiée. Un défaut du runtime Java embarqué affectant le MCP natif a été identifié après publication. Le correctif est préparé pour `1.0.1`, qui reste **non publiée** tant que le setup final n'a pas été vérifié manuellement.

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
préflight des clients IA
        ↓
intégrations MCP natives optionnelles
  ├── GitHub Copilot — JetBrains / IntelliJ
  ├── GitHub Copilot CLI
  ├── Claude Code
  ├── Claude Desktop
  └── OpenAI Codex CLI / Desktop
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

Une version avec suffixe comme `-rc1` est une **pre-release**. Une version comme `1.0.1` est une release stable.

---

## 3. Vérifier le SHA-256 du setup

Depuis le répertoire de téléchargement :

```powershell
$Version = '1.0.1'

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

Le setup installe notamment :

```text
MINOS
├── app\
│   ├── minos.exe
│   └── runtime\                  runtime Java embarqué
├── lib\minos.jar
├── integration\
│   ├── detect-mcp-clients.ps1
│   ├── configure-mcp-clients.ps1
│   ├── configure-mcp-clients-setup.ps1
│   ├── configure-codex-mcp.ps1
│   └── uninstall-mcp-clients.ps1
├── minos.cmd                    CLI + entrée MCP native
├── minos-mcp.cmd
├── docker\                      assets MCP Docker optionnels
├── VERSION
├── RUNTIME-MODULES.txt
├── RELEASE-MANIFEST.json
├── supply-chain\
│   ├── minos.cdx.json
│   └── THIRD-PARTY-NOTICES.txt
└── désinstalleur Windows
```

`RUNTIME-MODULES.txt` enregistre les modules présents dans le runtime Java produit. À partir de 1.0.1, la liste est dérivée du JAR final avec `jdeps` puis vérifiée sur l'image `jpackage` créée.

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

### 4.2 Page « Intégrations MCP natives »

À partir du candidat 1.0.1, les clients MCP ne sont plus des cases statiques dans **Select Additional Tasks**.

Le Wizard affiche une page dédiée :

```text
Intégrations MCP natives
Connecter le MCP natif MINOS à vos clients IA détectés
```

Chaque client reçoit :

- une case disponible uniquement si le mode d'intégration attendu est réellement détecté ;
- un diagnostic visible ;
- aucune sélection par défaut ;
- aucune écriture dans un logiciel tiers tant que l'utilisateur ne coche pas explicitement la case.

Exemple :

```text
☐ GitHub Copilot — JetBrains / IntelliJ
  Détecté

☐ GitHub Copilot CLI
  Non disponible — launcher VS Code détecté, vrai CLI absent

☐ Claude Code
  Non disponible — commande introuvable

☐ Claude Desktop
  Détecté

☐ OpenAI Codex
  Détecté — Codex Desktop (configuration via fichier utilisateur)
```

Le préflight est borné et non interactif. Pour les CLI, la présence d'un nom de commande ne suffit pas : MINOS vérifie la capability MCP attendue. Un chemin identifié comme launcher/shim VS Code est rejeté **avant** le probe de capacité afin de ne pas le confondre avec le vrai Copilot CLI.

Toutes les cases disponibles restent **décochées par défaut**. Modifier la configuration d'un logiciel tiers reste donc un choix explicite.

Ces intégrations sont **100 % natives** et n'utilisent pas Docker. Elles pointent vers :

```text
command = %LOCALAPPDATA%\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Le gestionnaire applique des règles de sécurité :

- ne jamais écraser une entrée MCP `minos` existante qu'il n'a pas créée ;
- sauvegarder un fichier utilisateur avant de le modifier ;
- conserver tous les autres serveurs/propriétés du client ;
- enregistrer les intégrations gérées pour permettre leur suppression sélective ;
- préserver une entrée si l'utilisateur l'a modifiée après l'installation ;
- mémoriser les chemins des CLI gérés afin qu'un changement ultérieur de `PATH` n'empêche pas automatiquement le cleanup.

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

Après installation, ouvrir Copilot Chat en mode **Agent** et vérifier que le serveur `minos` est connecté et que les tools MINOS sont visibles.

#### GitHub Copilot CLI

Le setup ne considère pas `Get-Command copilot` comme une preuve suffisante.

Il vérifie d'abord que le chemin n'est pas un launcher/shim VS Code connu, puis exerce la capability MCP du CLI.

Lorsqu'un vrai CLI compatible est disponible, MINOS utilise son interface MCP pour ajouter le serveur utilisateur `minos`.

Contrôle :

```powershell
copilot mcp get minos --json
copilot mcp list --json
```

#### Claude Code

La commande `claude` doit être disponible et sa surface MCP doit répondre au probe attendu.

Le setup ajoute ensuite MINOS au scope utilisateur.

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

Deux modes sont supportés.

**Codex CLI** : si `codex mcp --help` prouve la surface MCP attendue, le setup utilise le CLI.

**Codex Desktop / configuration utilisateur** : lorsque le préflight choisit ce mode, MINOS gère un bloc marqué dans :

```text
%USERPROFILE%\.codex\config.toml
```

Exemple :

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

Voir [Utiliser MINOS via MCP](mcp.md) pour les configurations manuelles et le catalogue des tools.

### 4.3 Additional Tasks

Après la page MCP, **Select Additional Tasks** ne contient plus les cinq clients natifs.

Elle conserve les choix indépendants :

```text
Intégration Windows :
  ☑ Ajouter MINOS au PATH de l'utilisateur

MCP Docker optionnel :
  ☐ Configurer et démarrer le MCP Docker (Docker Desktop requis)
```

### 4.4 MCP Docker pendant le setup

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
Codex MCP state    : %LOCALAPPDATA%\MINOS\codex-mcp-integration.json
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

Pour contrôler les intégrations MCP sélectionnées :

```powershell
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-clients.log" -Tail 100
```

### 6.1 Vérifier réellement le MCP

Depuis 1.0.1, le gate de release ne considère plus `--version` comme une preuve suffisante du MCP.

La qualification exécute un handshake réel :

```text
initialize
→ notifications/initialized
→ tools/list
→ minos_search_code présent
→ minos_impact présent
```

Après installation utilisateur, faire en plus la vérification réelle depuis le client choisi (par exemple Copilot Agent) et confirmer que `minos` atteint l'état `Connected`.

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

### 7.3 Python et providers polyglottes

Les providers optionnels Python, C/C++, C#, Go et Rust gardent leurs contraintes de runtime/plateforme. Voir [Providers polyglottes](polyglot-providers.md) et `minos.cmd providers --format json` pour les dispositions courantes.

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

Le MCP expose **31 tools read-only** dans le catalogue courant. Voir [MCP](mcp.md) et [`../generated/product-facts.md`](../generated/product-facts.md).

### Pourquoi 1.0.1 corrige `org/w3c/dom/Node`

Le setup 1.0.0 embarquait une image Java `jpackage` trop réduite. Le MCP initialisait Jackson/networknt JSON Schema et demandait :

```text
org.w3c.dom.Node
```

fourni par :

```text
java.xml
```

Le correctif 1.0.1 suit désormais :

```text
JAR final
→ jdeps --print-module-deps
→ modules racines
→ jpackage --add-modules <liste calculée>
→ runtime/bin/java --list-modules
→ validation des modules
→ assertion java.xml
→ handshake MCP réel
```

Le fichier `RUNTIME-MODULES.txt` matérialise l'évidence du runtime livré.

---

## 10. MCP Docker — mode durci optionnel

Le mode Docker conserve les invariants :

```text
network_mode: none
filesystem conteneur: read-only
projets: read-only
capabilities: dropped
no-new-privileges: true
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
$Version = '1.0.1'

Get-FileHash ".\minos-$Version-windows-x64.zip" -Algorithm SHA256
Get-Content ".\minos-$Version-windows-x64.zip.sha256"
```

### 11.2 Installer le ZIP

Le ZIP est autonome : **aucun checkout Git de MINOS n'est nécessaire**.

```powershell
$Version = '1.0.1'
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

Le gestionnaire historique multi-clients reste disponible pour Copilot/Claude :

```powershell
$Minos = "$env:LOCALAPPDATA\Programs\MINOS"

& "$Minos\integration\configure-mcp-clients.ps1" `
  -InstallRoot $Minos `
  -CopilotJetBrains `
  -CopilotCli `
  -ClaudeCode `
  -ClaudeDesktop
```

Codex 1.0.1 utilise le gestionnaire capability-aware dédié :

```powershell
& "$Minos\integration\configure-codex-mcp.ps1" `
  -InstallRoot $Minos `
  -Mode auto
```

Le setup reste recommandé car son préflight choisit et affiche le mode approprié avant écriture.

---

## 12. Mettre MINOS à jour

MINOS n'a pas encore d'auto-updater.

### 12.1 Mise à jour avec setup.exe

Télécharger le nouveau setup + checksum, vérifier le SHA-256, puis lancer le nouveau setup.

Le setup de production utilise un **AppId Windows stable**, retrouve l'installation précédente et réutilise son répertoire.

Les données restent dans :

```text
%LOCALAPPDATA%\MINOS\data
%LOCALAPPDATA%\MINOS\docker-data
```

Les intégrations MCP sont reproposées après préflight. Une entrée déjà gérée par MINOS est réutilisée/actualisée selon son ownership ; une entrée `minos` étrangère ou modifiée n'est pas écrasée.

Après mise à jour :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd project list
```

Puis tester le MCP dans le client réellement utilisé.

### 12.2 Mise à jour ZIP

Le parcours PowerShell conserve le backup automatique de l'ancienne installation programme selon le contrat de `install.ps1`.

---

## 13. Désinstaller MINOS

Avec le `setup.exe`, utiliser **Paramètres Windows → Applications → Applications installées → MINOS Code Intelligence → Désinstaller**.

Le désinstalleur :

- retire le programme ;
- retire le chemin MINOS ajouté au `PATH` lorsqu'il est encore géré ;
- retire, lorsqu'elles correspondent encore aux entrées gérées, les intégrations MCP natives créées par le setup ;
- utilise les chemins CLI enregistrés lorsque le `PATH` a changé ;
- ne touche pas aux autres serveurs MCP des clients ;
- préserve une entrée gérée que l'utilisateur a modifiée ;
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

### 13.1 Suppression complète des données

Cette opération est irréversible et ne fait pas partie de la désinstallation normale :

```powershell
Remove-Item "$env:LOCALAPPDATA\MINOS" -Recurse -Force
```

Elle supprime registre, snapshots, états d'indexation, providers gérés, logs, backups et données Docker MINOS. Ne l'utiliser qu'après sauvegarde et décision explicite de supprimer **toutes** les données MINOS.

---

## 14. Revenir à une version précédente

Les releases publiées restent versionnées dans GitHub Releases.

Pour revenir à une version antérieure :

1. fermer les clients MCP utilisant MINOS ;
2. télécharger le setup ou le ZIP de la version souhaitée ;
3. vérifier son SHA-256 ;
4. réinstaller cette version ;
5. vérifier `minos.cmd --version` et `minos.cmd doctor` ;
6. vérifier également le MCP si cette fonctionnalité est utilisée.

`MINOS_HOME` reste séparé du programme. Avant un rollback important, sauvegarder `%LOCALAPPDATA%\MINOS` si les données sont critiques.

> Pour le défaut MCP Windows connu de 1.0.0, ne pas considérer un rollback vers 1.0.0 comme une correction du runtime natif. Le correctif prévu est 1.0.1.

---

## 15. Publication d'une release — mainteneurs uniquement

Un utilisateur normal **ne doit pas exécuter cette section**.

Une release complète doit produire les huit assets listés en section 2.

### 15.1 Candidat local sûr avant publication

Pour 1.0.1, le parcours recommandé avant toute publication est :

```powershell
.\scripts\release\build-local-windows-candidate.ps1 -Version 1.0.1
```

Ce runner :

```text
worktree propre
→ Product Facts --check
→ documentation/release contract check
→ verifiers MCP/preflight/Codex/installer
→ Maven release build
→ jdeps
→ jpackage
→ runtime --list-modules
→ assertion java.xml
→ SBOM / notices / manifest
→ ZIP
→ handshake MCP sur distribution
→ setup.exe production
→ PAS d'installation automatique du setup production
→ PAS de tag
→ PAS de GitHub Release
→ PAS de GitHub Actions
```

Artefact à tester manuellement :

```text
target\dist\MINOS-1.0.1-windows-x64-setup.exe
```

Le mainteneur doit ensuite vérifier visuellement la page de détection MCP et tester un client réel, notamment Copilot, avant d'autoriser la publication.

### 15.2 Qualification complète sans publication

Le script autoritatif de release supporte :

```powershell
.\scripts\release\publish-windows-release.ps1 `
  -Version 1.0.1 `
  -ValidateOnly
```

En `-ValidateOnly`, il ne crée ni tag ni GitHub Release. Il construit aussi un **setup de smoke isolé** distinct du setup production :

- AppId distinct ;
- pas de mutation PATH utilisateur ;
- pas de configuration MCP réelle ;
- pas de Docker réel ;
- pas de cleanup global d'une installation existante.

Ce setup isolé est installé/désinstallé automatiquement et doit passer le handshake MCP réel.

### 15.3 GitHub Actions — publication volontaire

Le workflow `.github/workflows/release-windows.yml` est uniquement `workflow_dispatch`.

Il ne doit être lancé qu'après validation locale/utilisateur et autorisation explicite de publication.

Sur une branche autre que `main`, il qualifie les artefacts sans publier. Sur `main`, il peut publier la release demandée après les gates du script autoritatif.

Séquence :

```text
Java 24 + Python + Inno Setup
→ build distribution
→ jdeps / jpackage runtime gate
→ verifiers intégrations MCP
→ setup production
→ setup de smoke isolé
→ smoke ZIP
→ MCP initialize/tools-list sur ZIP
→ smoke setup isolé
→ MCP initialize/tools-list sur setup isolé
→ checksums / supply-chain
→ seulement sur main : tag v<version> + GitHub Release
→ upload des huit assets
```

Une release/tag déjà existante est refusée.

### 15.4 Build bas niveau

Pour diagnostic :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 1.0.1
.\scripts\release\build-windows-installer.ps1 -Version 1.0.1
```

Ne pas appeler la publication tant que le setup produit n'a pas été testé manuellement.

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
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-client-integrations.json" -ErrorAction SilentlyContinue
Get-Content "$env:LOCALAPPDATA\MINOS\codex-mcp-integration.json" -ErrorAction SilentlyContinue
```

Pour contrôler le runtime embarqué :

```powershell
Get-Content "$env:LOCALAPPDATA\Programs\MINOS\RUNTIME-MODULES.txt"
& "$env:LOCALAPPDATA\Programs\MINOS\app\runtime\bin\java.exe" --list-modules
```

`java.xml` doit être présent sur la ligne 1.0.1 corrigée.

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

---

## 17. Limites produit inchangées par 1.0.1

La maintenance Windows 1.0.1 ne change pas les claims d'architecture :

- la sandbox OS réelle du worker distant reste suivie par #98 ;
- `DENY` reste fail-closed sans backend OS qualifié ;
- Team/Hosted reste embarqué/local-first et n'est pas un SaaS opéré ;
- les observations runtime restent partielles ;
- le retrieval sémantique learned reste optionnel et `HEURISTIC`.

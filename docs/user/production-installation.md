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
CLI + MCP natif + PATH
        ↓
MCP Docker optionnel
        ↓
minos.cmd doctor
        ↓
provider → project add → index → search / MCP
```

Le ZIP reste disponible comme distribution **portable / automatisation / diagnostic**.

Le checkout source est un troisième parcours, réservé au développement de MINOS : [Installation depuis les sources](installation.md).

> La release historique `0.2.0-rc1` a été publiée avant l'introduction du `setup.exe` et ne contient que le ZIP. Le setup commence avec la release qui intègre l'issue #46.

---

## 2. Télécharger une GitHub Release

Ouvrir :

```text
https://github.com/FTurleque/minos-code-intelligence/releases
```

Le dépôt étant privé, GitHub peut demander une authentification avant d'afficher ou télécharger les assets.

Une release Windows complète publie quatre assets :

```text
MINOS-<version>-windows-x64-setup.exe
MINOS-<version>-windows-x64-setup.exe.sha256

minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256
```

Exemple :

```text
MINOS-0.2.0-rc2-windows-x64-setup.exe
MINOS-0.2.0-rc2-windows-x64-setup.exe.sha256
minos-0.2.0-rc2-windows-x64.zip
minos-0.2.0-rc2-windows-x64.zip.sha256
```

Une version avec suffixe comme `-rc2` est une **pre-release**. Une version comme `0.2.0` est une release stable.

---

## 3. Vérifier le SHA-256 du setup

Depuis le répertoire de téléchargement :

```powershell
$Version = '0.2.0-rc2'

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
├── minos.cmd                    CLI + entrée MCP native
├── minos-mcp.cmd
├── docker\                      assets MCP Docker
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

### 4.2 MCP natif

Aucune installation supplémentaire n'est nécessaire pour le MCP natif.

Le même launcher sert de serveur MCP :

```text
command = <installation>\minos.cmd
args    = mcp
```

### 4.3 MCP Docker pendant le setup

Le setup propose la tâche optionnelle :

```text
Configurer et démarrer le MCP Docker
```

Si elle est sélectionnée, le setup demande la **racine des projets** à exposer au conteneur, par exemple :

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

**Docker Desktop doit déjà être installé et démarré.** MINOS n'installe pas Docker Desktop.

Si Docker n'est pas disponible, le setup affiche un avertissement mais poursuit l'installation native. CLI et MCP natif restent utilisables.

Le journal de configuration Docker est conservé sous :

```text
%LOCALAPPDATA%\MINOS\docker-setup.log
```

---

## 5. Programme et données persistantes

Installation par défaut :

```text
programme    : %LOCALAPPDATA%\Programs\MINOS
MINOS_HOME   : %LOCALAPPDATA%\MINOS\data
Docker config: %LOCALAPPDATA%\MINOS\docker
Docker data  : %LOCALAPPDATA%\MINOS\docker-data
```

Le launcher fixe par défaut :

```text
MINOS_HOME=%LOCALAPPDATA%\MINOS\data
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

Exemple :

```text
MINOS 0.2.0-rc2
```

`doctor` distingue notamment :

- le runtime Java embarqué utilisé par MINOS ;
- les commandes projet disponibles (`java`, `javac`, `mvn`, `node`, `npm`) ;
- Docker, qui reste optionnel ;
- l'état des providers gérés ;
- les actions nécessaires lorsqu'un provider est absent ou bloqué.

Le code de sortie de `doctor` peut être `1` lorsqu'une action provider reste nécessaire. Cela ne signifie pas que l'installation native est corrompue.

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

Préconditions Java Windows actuellement qualifiées :

```text
JAVA_HOME -> JDK avec java/javac/jar
pom.xml
mvnw.cmd dans le projet ou un parent, sinon Maven dans PATH
Git Bash
csc.exe du .NET Framework Windows
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

Enregistrer :

```powershell
minos.cmd project add N:\workspace-dev\my-project --name my-project
```

Inspecter :

```powershell
minos.cmd inspect my-project
```

Planifier sans exécuter :

```powershell
minos.cmd index my-project --dry-run
```

Indexer :

```powershell
minos.cmd index my-project
```

Vérifier :

```powershell
minos.cmd index-status my-project --format json
```

Rechercher :

```powershell
minos.cmd search my-project SearchService --format json
minos.cmd architecture my-project --format json
```

---

## 9. MCP natif — mode recommandé

Configuration conceptuelle :

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

CLI et MCP natif utilisent le même `MINOS_HOME` et les mêmes chemins Windows.

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

Le script lit lui-même la version et le commit dans `VERSION`.

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

Le ZIP reste utile lorsque l'on veut :

```text
ne pas utiliser le setup Windows
installer par script
faire du CI/CD
tester plusieurs distributions
inspecter précisément le contenu de la release
gérer manuellement le répertoire d'installation
```

Ce n'est pas un niveau de compétence : c'est simplement un **mode de déploiement portable**.

### 11.1 Vérifier le ZIP

```powershell
$Version = '0.2.0-rc2'

Get-FileHash ".\minos-$Version-windows-x64.zip" -Algorithm SHA256
Get-Content ".\minos-$Version-windows-x64.zip.sha256"
```

### 11.2 Installer le ZIP

Le ZIP est autonome : **aucun checkout Git de MINOS n'est nécessaire**.

Depuis le répertoire qui contient le ZIP, le décompresser puis exécuter le
`install.ps1` embarqué à la racine de la distribution :

```powershell
$Version = '0.2.0-rc2'
$Zip = (Resolve-Path ".\minos-$Version-windows-x64.zip").Path
$ExtractRoot = Join-Path $PWD "minos-$Version-extracted"
$Distribution = Join-Path $ExtractRoot "minos-$Version-windows-x64"

Expand-Archive -LiteralPath $Zip -DestinationPath $ExtractRoot -Force

# Le script est exécuté par le PowerShell déjà ouvert : aucune dépendance
# à une commande `powershell.exe` ou `pwsh.exe` présente dans le PATH.
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& "$Distribution\install.ps1" `
  -Package $Distribution `
  -AddToPath
```

Le script `scripts\install\install-windows.ps1` appartient au **checkout
source** et ne doit pas être utilisé comme chemin d'installation dans le
parcours utilisateur d'une GitHub Release.

Le chemin par défaut reste :

```text
%LOCALAPPDATA%\Programs\MINOS
```

Le parcours ZIP conserve le mécanisme de backup `<InstallRoot>.backup-YYYYMMDD-HHMMSS` lors d'un remplacement.

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

Après mise à jour :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd project list
```

Si l'option Docker est sélectionnée à nouveau pendant la mise à jour, la configuration Docker est reconstruite depuis le JAR de la nouvelle release.

### 12.2 Mise à jour ZIP

Le parcours PowerShell conserve le backup automatique de l'ancienne installation programme.

---

## 13. Désinstaller MINOS

Avec le `setup.exe`, utiliser **Paramètres Windows → Applications → Applications installées → MINOS Code Intelligence → Désinstaller**.

Le désinstalleur :

- retire le programme ;
- retire le chemin MINOS ajouté au `PATH` ;
- tente de stopper le MCP Docker s'il a été configuré et si Docker répond ;
- **ne supprime pas automatiquement les données persistantes**.

Les répertoires suivants sont conservés :

```text
%LOCALAPPDATA%\MINOS\data
%LOCALAPPDATA%\MINOS\docker
%LOCALAPPDATA%\MINOS\docker-data
```

Une réinstallation peut donc retrouver l'état natif existant.

### 13.1 Suppression complète des données

Après avoir arrêté Docker MCP si nécessaire, cette opération est irréversible :

```powershell
Remove-Item "$env:LOCALAPPDATA\MINOS" -Recurse -Force
```

Elle supprime registre, snapshots, états d'indexation, providers gérés, logs et données Docker MINOS.

---

## 14. Revenir à une version précédente

Les releases publiées restent versionnées dans GitHub Releases.

Pour revenir à une version antérieure :

1. arrêter les clients MCP ;
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

### 15.1 GitHub Actions

Dans **Actions → Publish Windows Release → Run workflow**, sélectionner `main` et fournir la version, par exemple :

```text
0.2.0-rc2
```

Le workflow :

```text
Java 24
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

Une version avec suffixe (`-rc2`, `-beta1`, etc.) devient automatiquement une pre-release.

Une release/tag déjà existant est refusé.

### 15.2 Poste Windows mainteneur

Prérequis supplémentaires au build du setup : **Inno Setup avec `ISCC.exe`**.

Construire :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0-rc2
.\scripts\release\build-windows-installer.ps1 -Version 0.2.0-rc2
```

Publier avec `gh` authentifié :

```powershell
gh auth status
.\scripts\release\publish-windows-release.ps1 -Version 0.2.0-rc2
```

`-SkipBuild` ne peut être utilisé que si **les quatre assets** existent déjà dans `target\dist`.

---

## 16. Dépannage

Commencer par :

```powershell
minos.cmd --version
minos.cmd doctor --format json
```

Pour Docker :

```powershell
docker version
Get-Content "$env:LOCALAPPDATA\MINOS\docker-setup.log" -Tail 200
```

Puis consulter [Dépannage](troubleshooting.md).

Pour un problème de release, conserver :

```text
nom exact du setup/ZIP
fichier .sha256 correspondant
MINOS --version
commande d'installation exacte
sortie PowerShell / log setup
```

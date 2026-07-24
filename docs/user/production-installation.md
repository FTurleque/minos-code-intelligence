# Installation PROD de MINOS sous Windows

Ce guide décrit le parcours **utilisateur** de MINOS sous Windows.

Le parcours normal ne nécessite **ni clone Git de MINOS, ni Maven, ni JDK pour exécuter MINOS**. Une release Windows contient son propre runtime Java.

> Le JDK, Maven, Node ou npm peuvent toutefois être nécessaires au **projet analysé** et à son provider d'indexation. Ils ne servent pas à démarrer MINOS lui-même.

## 1. Parcours recommandé

```text
GitHub Release MINOS
        ↓
télécharger ZIP + SHA-256
        ↓
vérifier SHA-256
        ↓
décompresser le ZIP
        ↓
lancer install.ps1
        ↓
%LOCALAPPDATA%\Programs\MINOS
        ↓
minos.cmd doctor
        ↓
installer le provider du projet
        ↓
project add → index → search / MCP
```

Le checkout source est un autre parcours, réservé au développement de MINOS : [Installation depuis les sources](installation.md).

---

## 2. Télécharger une GitHub Release

Ouvrir la page **Releases** du dépôt :

```text
https://github.com/FTurleque/minos-code-intelligence/releases
```

Le dépôt étant privé, GitHub peut demander une authentification avant d'afficher ou télécharger les assets.

Une release Windows publie deux assets :

```text
minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256
```

Exemple release candidate :

```text
minos-0.2.0-rc1-windows-x64.zip
minos-0.2.0-rc1-windows-x64.zip.sha256
```

Une version contenant un suffixe comme `-rc1` est publiée comme **pre-release**. Une version stable comme `0.2.0` est publiée comme release normale.

## 3. Contenu du ZIP

Le ZIP contient :

```text
minos-<version>-windows-x64/
├── app/                                  # app-image jpackage + runtime Java embarqué
├── lib/
│   └── minos.jar                         # shaded JAR exact de la release
├── docker/
│   ├── Dockerfile.mcp.release
│   ├── compose.mcp.prod.yaml
│   └── scripts/
│       └── prod-mcp-release.ps1
├── minos.cmd                             # launcher CLI
├── minos-mcp.cmd                         # launcher MCP direct
├── install.ps1                           # installateur utilisateur
├── VERSION                               # version, commit, Java de build
└── README.txt
```

Le runtime Java de MINOS est inclus dans `app/`.

---

## 4. Vérifier le SHA-256

Depuis le répertoire où les deux fichiers ont été téléchargés :

```powershell
$Version = '0.2.0-rc1'

Get-FileHash ".\minos-$Version-windows-x64.zip" -Algorithm SHA256
Get-Content ".\minos-$Version-windows-x64.zip.sha256"
```

Exemple :

```text
Get-FileHash
845DE6C42CE3C497EC48F5CCF93420CCCE0E64559AA80178727772156CF63B37

fichier .sha256
845de6c42ce3c497ec48f5ccf93420ccce0e64559aa80178727772156cf63b37  minos-0.2.0-rc1-windows-x64.zip
```

La casse hexadécimale n'a pas d'importance. Les 64 caractères du hash doivent être identiques.

**Ne pas installer l'archive si les deux empreintes diffèrent.**

---

## 5. Installation utilisateur sans droits administrateur

### 5.1 Décompresser la release

Exemple si le ZIP est dans le répertoire courant :

```powershell
$Version = '0.2.0-rc1'
$Staging = Join-Path $env:TEMP "minos-$Version-install"

Remove-Item $Staging -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive ".\minos-$Version-windows-x64.zip" $Staging

$Package = Join-Path $Staging "minos-$Version-windows-x64"
```

### 5.2 Lancer l'installateur contenu dans le ZIP

```powershell
& "$Package\install.ps1" `
  -Package $Package `
  -AddToPath
```

Si la politique PowerShell bloque l'exécution du script :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File "$Package\install.ps1" `
  -Package $Package `
  -AddToPath
```

L'emplacement par défaut est :

```text
%LOCALAPPDATA%\Programs\MINOS
```

`-AddToPath` ajoute ce répertoire au `PATH` de l'utilisateur courant.

Ouvrir **un nouveau terminal** après l'installation pour utiliser `minos.cmd` directement par son nom.

### 5.3 Installation sans modification du PATH

Omettre `-AddToPath` :

```powershell
& "$Package\install.ps1" -Package $Package
```

Puis appeler explicitement :

```powershell
& "$env:LOCALAPPDATA\Programs\MINOS\minos.cmd" --version
```

---

## 6. Installation sous Program Files

Cette variante nécessite un PowerShell élevé :

```powershell
& "$Package\install.ps1" `
  -Package $Package `
  -InstallRoot "$env:ProgramFiles\MINOS" `
  -AddToPath
```

Le programme et les données restent séparés.

---

## 7. Programme et données persistantes

Installation utilisateur par défaut :

```text
programme : %LOCALAPPDATA%\Programs\MINOS

données   : %LOCALAPPDATA%\MINOS\data
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

Une mise à jour du programme ne doit donc pas supprimer les snapshots, projets enregistrés ou providers stockés dans `MINOS_HOME`.

`MINOS_HOME` peut être remplacé explicitement avant le lancement :

```powershell
$env:MINOS_HOME = 'N:\minos-data'
minos.cmd project list
```

---

## 8. Vérifier l'installation

Dans un nouveau terminal :

```powershell
minos.cmd --version
minos.cmd doctor
```

Exemple attendu pour la version :

```text
MINOS 0.2.0-rc1
```

`doctor` distingue notamment :

- le runtime Java embarqué utilisé par MINOS ;
- les commandes projet disponibles (`java`, `javac`, `mvn`, `node`, `npm`) ;
- Docker, qui reste optionnel ;
- l'état des providers gérés ;
- les actions nécessaires lorsqu'un provider est absent ou bloqué.

Le code de sortie de `doctor` peut être `1` lorsqu'une action provider reste nécessaire. Cela ne signifie pas que l'installation native de MINOS est elle-même corrompue.

---

## 9. Installer le provider du projet

Lister les providers :

```powershell
minos.cmd tools list
```

### 9.1 Projet Java / Maven

Le provider Windows qualifié est :

```text
scip-java 0.13.1
```

MINOS embarque son propre runtime Java, mais `scip-java` doit utiliser le **JDK du projet**.

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
Git Bash (Git for Windows)
csc.exe du .NET Framework Windows
```

MINOS n'installe pas un Maven global. Il utilise en priorité le Maven Wrapper du projet.

Le runtime géré est installé sous :

```text
%LOCALAPPDATA%\MINOS\data\tools\scip-java\0.13.1\runtime\
```

### 9.2 Projet TypeScript

Préconditions :

```powershell
node --version
npm --version
```

Puis :

```powershell
minos.cmd tools install scip-typescript
```

Le provider est installé sous `MINOS_HOME\tools`. Aucune installation npm globale n'est effectuée.

MINOS **ne lance pas** `npm install`, `yarn install` ou `pnpm install` pour préparer les dépendances métier du projet. Elles doivent déjà être disponibles selon le workflow normal du projet.

---

## 10. Premier projet : de zéro à une recherche

Enregistrer un projet :

```powershell
minos.cmd project add N:\workspace-dev\my-project --name my-project
```

Inspecter la découverte :

```powershell
minos.cmd inspect my-project
```

Voir le plan sans exécuter le provider :

```powershell
minos.cmd index my-project --dry-run
```

Indexer :

```powershell
minos.cmd index my-project
```

Vérifier le snapshot :

```powershell
minos.cmd index-status my-project --format json
```

Faire une première recherche :

```powershell
minos.cmd search my-project SearchService --format json
minos.cmd architecture my-project --format json
```

Le parcours autonome est :

```text
discovery
→ provider negotiation
→ runtime check
→ fingerprint/invalidation
→ provider execution
→ staging
→ atomic promotion
→ active snapshot
```

Les versions de providers actuellement qualifiées ne revendiquent pas l'incrémental provider :

```text
aucun changement -> NONE
changement       -> FULL
```

---

## 11. MCP natif — mode recommandé

La CLI et le MCP natif utilisent le même `MINOS_HOME` et les mêmes chemins Windows.

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

Pour une installation sous Program Files :

```text
command = C:\Program Files\MINOS\minos.cmd
args    = mcp
```

Le serveur utilise STDIO. Il n'expose pas de serveur HTTP dans le cœur MINOS.

Voir [MCP](mcp.md) pour les clients et les outils disponibles.

---

## 12. Docker MCP durci — optionnel

Docker n'est **pas** requis pour le parcours normal CLI/indexation/MCP natif.

Le mode Docker reste disponible dans la distribution pour un MCP plus isolé :

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

Exemple après installation :

```powershell
$Minos = "$env:LOCALAPPDATA\Programs\MINOS"

& "$Minos\docker\scripts\prod-mcp-release.ps1" `
  -Action Install `
  -Jar "$Minos\lib\minos.jar" `
  -Version 0.2.0-rc1 `
  -Commit <release-commit> `
  -ProjectsRoot N:\workspace-dev

& "$Minos\docker\scripts\prod-mcp-release.ps1" -Action Start
& "$Minos\docker\scripts\prod-mcp-release.ps1" -Action Validate
```

Ne pas partager le registre natif avec Docker : les racines de projet ne sont pas représentées avec les mêmes chemins (`N:\...` côté Windows, `/workspace/projects/...` côté conteneur).

---

## 13. Mettre MINOS à jour

MINOS n'a pas encore d'auto-updater. Une mise à jour suit le même parcours qu'une première installation :

```text
nouvelle GitHub Release
→ télécharger ZIP + SHA-256
→ vérifier le SHA-256
→ décompresser
→ exécuter install.ps1 vers le même InstallRoot
```

L'installateur détecte une installation existante et la déplace avant remplacement vers :

```text
<InstallRoot>.backup-YYYYMMDD-HHMMSS
```

Exemple installation utilisateur :

```text
%LOCALAPPDATA%\Programs\MINOS
%LOCALAPPDATA%\Programs\MINOS.backup-20260724-230000
```

Les données restent dans :

```text
%LOCALAPPDATA%\MINOS\data
```

Après mise à jour :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd project list
```

Ne supprimer un backup d'ancienne installation qu'après avoir validé la nouvelle version.

---

## 14. Revenir à l'installation précédente

Cette opération concerne le **programme** ; elle ne remplace pas `MINOS_HOME`.

Fermer les clients MCP utilisant MINOS, puis identifier le backup :

```powershell
$Programs = "$env:LOCALAPPDATA\Programs"
Get-ChildItem $Programs -Directory -Filter 'MINOS.backup-*' |
  Sort-Object LastWriteTime -Descending
```

Exemple de rollback manuel :

```powershell
$InstallRoot = "$env:LOCALAPPDATA\Programs\MINOS"
$Backup = "$env:LOCALAPPDATA\Programs\MINOS.backup-20260724-230000"

Move-Item $InstallRoot "$InstallRoot.failed" 
Move-Item $Backup $InstallRoot

& "$InstallRoot\minos.cmd" --version
```

Après validation, l'installation `.failed` peut être supprimée manuellement.

---

## 15. Désinstaller MINOS

### 15.1 Supprimer le programme en conservant les données

```powershell
$InstallRoot = "$env:LOCALAPPDATA\Programs\MINOS"
Remove-Item $InstallRoot -Recurse -Force
```

Si `-AddToPath` avait été utilisé, retirer aussi le chemin du `PATH` utilisateur :

```powershell
$InstallRoot = "$env:LOCALAPPDATA\Programs\MINOS"
$UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$NewPath = (($UserPath -split ';' |
  Where-Object { $_ -and $_ -ne $InstallRoot }) -join ';')

[Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')
```

Ouvrir ensuite un nouveau terminal.

Cette désinstallation **conserve** :

```text
%LOCALAPPDATA%\MINOS\data
```

Une réinstallation peut donc retrouver les données MINOS existantes.

### 15.2 Suppression complète des données

Cette opération est irréversible :

```powershell
Remove-Item "$env:LOCALAPPDATA\MINOS" -Recurse -Force
```

Elle supprime notamment registre, snapshots, états d'indexation, providers gérés et logs de runs.

---

## 16. Publication d'une release — mainteneurs uniquement

Un utilisateur normal **ne doit pas exécuter cette section**.

La publication est volontairement explicite : le dépôt ne déclenche pas automatiquement une release sur chaque push.

### Depuis GitHub Actions

Dans **Actions → Publish Windows Release → Run workflow**, sélectionner `main` et fournir la version :

```text
0.2.0-rc1
```

ou :

```text
0.2.0
```

Le workflow :

```text
Java 24
→ clean verify
→ jpackage
→ ZIP + SHA-256
→ vérification checksum
→ installation smoke depuis le ZIP
→ vérification MINOS <version>
→ création du tag v<version>
→ création GitHub Release
→ upload ZIP + SHA-256
```

Une version avec suffixe (`-rc1`, `-beta1`, etc.) devient automatiquement une pre-release.

Une release/tag déjà existant est refusé afin de ne pas remplacer silencieusement un artefact publié.

### Depuis un poste Windows mainteneur

Avec `gh` installé et authentifié :

```powershell
gh auth status
.\scripts\release\publish-windows-release.ps1 -Version 0.2.0-rc1
```

Le script construit et publie la release depuis le **HEAD Git exact** d'un worktree propre.

Pour publier un ZIP déjà construit et contrôlé :

```powershell
.\scripts\release\publish-windows-release.ps1 `
  -Version 0.2.0-rc1 `
  -SkipBuild
```

Le ZIP attendu reste :

```text
target\dist\minos-0.2.0-rc1-windows-x64.zip
```

Le script vérifie son checksum et réalise un smoke d'installation avant publication.

---

## 17. Dépannage

Commencer par :

```powershell
minos.cmd --version
minos.cmd doctor --format json
```

Puis consulter [Dépannage](troubleshooting.md).

Pour un problème de release, conserver :

```text
nom exact du ZIP
fichier .sha256
MINOS --version
commande d'installation exacte
sortie PowerShell
```

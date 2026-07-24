# Installation PROD de MINOS sous Windows

Ce guide décrit l'installation **utilisateur** de MINOS. Il ne nécessite pas de checkout Git ni de Maven pour exécuter MINOS une fois l'artefact de release construit.

## 1. Artefacts de release

Une release Windows M14 produit :

```text
minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256
```

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
├── VERSION
└── README.txt
```

Le runtime Java nécessaire à MINOS est inclus dans `app/`. L'utilisateur n'a donc pas besoin de définir `JAVA_HOME` pour exécuter la CLI ou le MCP.

> Les providers peuvent, eux, exiger la toolchain du projet analysé. `scip-java` nécessite le JDK et le build Maven du projet ; `scip-typescript` nécessite Node/npm.

## 2. Vérifier le SHA-256

Après téléchargement :

```powershell
Get-FileHash .\minos-0.2.0-windows-x64.zip -Algorithm SHA256
Get-Content .\minos-0.2.0-windows-x64.zip.sha256
```

Les deux empreintes doivent être identiques.

## 3. Installation sans droits administrateur

Décompresser le ZIP :

```powershell
Expand-Archive .\minos-0.2.0-windows-x64.zip .\minos-dist
```

Puis :

```powershell
.\minos-dist\minos-0.2.0-windows-x64\install.ps1 `
  -Package .\minos-dist\minos-0.2.0-windows-x64 `
  -AddToPath
```

Emplacement par défaut :

```text
%LOCALAPPDATA%\Programs\MINOS
```

Ouvrir un nouveau terminal si `-AddToPath` a été utilisé.

## 4. Installation sous Program Files

Depuis un PowerShell élevé :

```powershell
.\install.ps1 `
  -Package .\minos-0.2.0-windows-x64 `
  -InstallRoot "$env:ProgramFiles\MINOS" `
  -AddToPath
```

L'emplacement du programme et celui des données restent séparés.

## 5. Données MINOS

Le launcher de la distribution fixe par défaut :

```text
MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

On obtient progressivement :

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

`MINOS_HOME` peut toujours être remplacé explicitement avant le lancement.

## 6. Premier diagnostic

```powershell
minos.cmd --version
minos.cmd doctor
```

`doctor` distingue :

- le runtime Java utilisé par MINOS ;
- les commandes projet disponibles (`java`, `javac`, `mvn`, `node`, `npm`) ;
- Docker, qui reste optionnel ;
- l'état des providers gérés ;
- les actions à effectuer lorsqu'un provider est absent ou bloqué.

Le code de sortie est `1` lorsque des actions sont nécessaires.

## 7. Installer les providers

Lister :

```powershell
minos.cmd tools list
```

### Java

Le provider Windows qualifié est verrouillé sur :

```text
scip-java 0.13.1
```

Pour indexer un projet Java, positionner d'abord le JDK du **projet** :

```powershell
$env:JAVA_HOME = 'C:\path\to\project-jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Puis :

```powershell
minos.cmd tools install scip-java
```

MINOS gère Coursier dans son home et extrait son runner de compatibilité Windows sous :

```text
%LOCALAPPDATA%\MINOS\data\tools\scip-java\0.13.1\runtime\
```

Sur Windows, `scip-java 0.13.1` présente trois contraintes déjà mesurées pendant M0. MINOS les prend en charge sans modifier le PATH utilisateur :

- shim local `mvn.exe` vers le Maven Wrapper du projet ou un Maven disponible ;
- shim local `javac.exe` exécutant le launcher fournisseur via Git Bash ;
- patch local de `ScipWriter` supprimant uniquement l'attribut POSIX non supporté par Windows.

Préconditions Java Windows :

```text
JAVA_HOME -> JDK avec java/javac/jar
pom.xml
mvnw.cmd dans le projet ou un parent, sinon Maven dans PATH
Git Bash (installé avec Git for Windows)
csc.exe du .NET Framework Windows
```

MINOS n'installe pas un Maven global. Il utilise en priorité le wrapper trouvé dans le projet ou ses répertoires parents.

### TypeScript

Préconditions : `node` et `npm` disponibles.

```powershell
node --version
npm --version
minos.cmd tools install scip-typescript
```

Le provider est installé sous `MINOS_HOME/tools`. Aucune installation npm globale n'est effectuée.

MINOS **ne lance pas** `npm install`, `yarn install` ou `pnpm install` pour les dépendances métier du projet. Elles doivent déjà être prêtes.

## 8. Enregistrer un projet

```powershell
minos.cmd project add N:\workspace-dev\nexus-context-engine --name nexus
minos.cmd inspect nexus
```

## 9. Voir ce que MINOS va faire

```powershell
minos.cmd index nexus --dry-run
```

Le plan expose notamment :

```text
langages
build systems
provider sélectionné
état du runtime provider
NONE / FULL / INCREMENTAL
raisons
fichiers modifiés lorsque disponibles
```

## 10. Indexer

```powershell
minos.cmd index nexus
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

Les versions actuellement qualifiées ne revendiquent pas l'incrémental provider. Par conséquent :

```text
aucun changement -> NONE
changement       -> FULL
```

## 11. Vérifier le résultat

```powershell
minos.cmd index-status nexus --format json
minos.cmd search nexus SearchService --format json
minos.cmd architecture nexus --format json
```

## 12. Import SCIP manuel

Pour le diagnostic ou un provider non piloté par MINOS :

```powershell
minos.cmd import-scip nexus `
  --file N:\temp\index.scip `
  --provider external-provider
```

L'ancienne forme `minos index --scip ...` reste temporairement acceptée avec un avertissement de dépréciation.

## 13. MCP natif

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

CLI et MCP utilisent alors les mêmes chemins Windows et le même `MINOS_HOME`.

## 14. Docker MCP durci

Docker reste optionnel et utilise un home séparé :

```text
%LOCALAPPDATA%\MINOS\docker-data
```

Le mode Docker est inclus dans l'installation utilisateur. Aucun checkout Git de MINOS n'est nécessaire :

```powershell
$Minos = "$env:LOCALAPPDATA\Programs\MINOS"

& "$Minos\docker\scripts\prod-mcp-release.ps1" `
  -Action Install `
  -Jar "$Minos\lib\minos.jar" `
  -Version 0.2.0 `
  -Commit <release-commit> `
  -ProjectsRoot N:\workspace-dev

& "$Minos\docker\scripts\prod-mcp-release.ps1" -Action Start
& "$Minos\docker\scripts\prod-mcp-release.ps1" -Action Validate
```

Ne pas partager le registre natif avec Docker : les racines de projets ne sont pas représentées avec les mêmes chemins (`N:\...` côté Windows, `/workspace/projects/...` côté conteneur).

Voir [mcp.md](mcp.md).

## 15. Construire une distribution depuis les sources

Cette section concerne un mainteneur/release engineer, pas l'utilisateur normal.

Avec un JDK 24 :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0
```

Le script exécute par défaut `clean verify`, construit l'app-image `jpackage`, copie le shaded JAR et les assets Docker dans la distribution, puis produit le ZIP et son SHA-256.

Pour une qualification exploratoire uniquement :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0-rc1 -SkipVerify
```

Un artefact construit avec `-SkipVerify` ne constitue pas une validation de release.

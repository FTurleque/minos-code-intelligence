# Installation depuis les sources

Ce document décrit le parcours **développeur/mainteneur**. Pour utiliser MINOS comme produit installé, voir [Installation PROD Windows](production-installation.md).

## Prérequis

```text
JDK     24.x uniquement
Maven   3.9.x via Maven Wrapper
Git
Python  3.x pour plusieurs gates/release helpers
```

Le `pom.xml` impose Java `[24,25)` et Maven `[3.9,4.0)`.

Pour construire le setup Windows, Inno Setup 6/7 doit également être disponible.

## Checkout et validation Maven

```powershell
git clone https://github.com/FTurleque/minos-code-intelligence.git
cd minos-code-intelligence

java -version
.\mvnw.cmd -version
.\mvnw.cmd clean verify
```

## Version de développement

La ligne de maintenance courante est :

```text
1.2.0-SNAPSHOT
```

Le shaded JAR de développement est donc notamment :

```text
target\minos-code-intelligence-1.2.0-SNAPSHOT-all.jar
```

Les scripts de release remplacent la propriété Maven CI-friendly `revision` avec `-Drevision=<version>` ; ils ne nécessitent pas de modifier temporairement les POM.

## Exécuter depuis le checkout

```powershell
$env:MINOS_HOME = 'C:\minos-data'
$minos = '.\target\minos-code-intelligence-1.2.0-SNAPSHOT-all.jar'

java -jar $minos --help
java -jar $minos doctor
```

## Parcours autonome

```powershell
java -jar $minos tools install scip-java
java -jar $minos project add C:\workspace\my-project --name my-project
java -jar $minos index my-project --dry-run
java -jar $minos index my-project
```

Pour un provider qui invoque la toolchain du projet, les outils correspondants doivent être installés sur le poste.

## MCP depuis le shaded JAR

```powershell
java -jar $minos mcp
```

Entrée directe équivalente :

```powershell
java -cp $minos com.minos.mcp.MinosMcpServer
```

Attention : un succès avec le JDK complet de développement ne prouve pas que le runtime `jpackage` Windows est complet. Depuis 1.0.1, la qualification Windows teste explicitement le MCP dans le runtime packagé.

## Construire une distribution Windows

Le script bas niveau est :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 1.1.0
```

Il :

- construit le reactor avec `-Drevision=1.1.0` ;
- analyse le fat JAR via le `jdeps.exe` du JDK 24 ;
- génère l'image `jpackage` avec les modules requis ;
- vérifie les modules du runtime créé ;
- exige notamment `java.xml` ;
- écrit `RUNTIME-MODULES.txt` ;
- génère SBOM/notices/manifest ;
- produit le ZIP portable.

## Construire le setup local à vérifier avant release

Pour la maintenance 1.1.0, utiliser de préférence :

```powershell
.\scripts\release\build-local-windows-candidate.ps1 -Version 1.1.0
```

Ce runner est prévu pour la validation locale avant publication. Il :

- refuse un worktree sale ;
- construit la distribution ;
- vérifie les intégrations MCP et leur préflight ;
- lance un vrai handshake MCP sur le binaire packagé ;
- génère le setup production (payload embarqué, activé via `integration\update-installation.ps1` — voir `docs/user/production-installation.md` §11.2) ;
- n'installe pas automatiquement ce setup ;
- ne crée pas de tag ;
- ne publie pas de GitHub Release ;
- ne déclenche pas de GitHub Actions.

Sortie principale :

```text
target\dist\MINOS-1.1.0-windows-x64-setup.exe
```

La prochaine étape est humaine : lancer ce setup, vérifier le Wizard/détection MCP et tester un client réel avant toute publication.

Pour vérifier le moteur de mise à jour transactionnel lui-même (staging, rollback, crash recovery), indépendamment d'une vraie compilation Inno Setup :

```powershell
.\scripts\install\verify-windows-upgrade-transaction.ps1
```

Pour vérifier la détection/configuration des clients IA (Copilot JetBrains/CLI, Claude CLI/Code/Desktop classique et MSIX, Codex CLI/Desktop) de façon isolée, sans dépendre de ce qui est réellement installé sur la machine qui exécute le test :

```powershell
.\scripts\install\verify-mcp-client-preflight.ps1
```

Ce script enchaîne également `verify-codex-mcp-integration.ps1` (TOML géré, fallback CLI→TOML, entrées préexistantes), `verify-mcp-client-backend-routing.ps1` et `verify-installer-template.ps1`.

## Validation de release complète

`scripts/release/publish-windows-release.ps1` est le script de qualification/publication. En mode `-ValidateOnly`, il ne publie pas mais exerce également un setup de smoke **isolé** avec un AppId distinct et un handshake MCP réel.

La publication ne doit être autorisée qu'après validation du setup de production par le mainteneur.

## Docker MCP depuis les sources

Le workflow Docker reste indépendant du runtime `jpackage` Windows. Voir [mcp.md](mcp.md) pour le MCP natif et Docker.

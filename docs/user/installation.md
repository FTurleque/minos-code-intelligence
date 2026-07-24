# Installation depuis les sources

Ce document décrit le parcours **développeur/mainteneur**.

Pour utiliser MINOS comme produit installé, voir [Installation PROD Windows](production-installation.md).

## Prérequis de développement

```text
JDK     24.x uniquement
Maven   3.9.x via Maven Wrapper
Git
```

Le `pom.xml` impose Java `[24,25)` et Maven `[3.9,4.0)`.

## Checkout et validation

```powershell
git clone https://github.com/FTurleque/minos-code-intelligence.git
cd minos-code-intelligence

java -version
.\mvnw.cmd -version
.\mvnw.cmd clean verify
```

Le runtime Maven doit lui aussi utiliser Java 24.

## Artefacts de développement

La version de développement M14 est `0.2.0-SNAPSHOT`.

```text
target/minos-code-intelligence-0.2.0-SNAPSHOT.jar
target/minos-code-intelligence-0.2.0-SNAPSHOT-all.jar
```

Le shaded JAR contient les dépendances de la CLI et du MCP.

## Exécuter depuis le checkout

```powershell
$env:MINOS_HOME = 'N:\minos-data'
$minos = '.\target\minos-code-intelligence-0.2.0-SNAPSHOT-all.jar'

java -jar $minos --help
java -jar $minos doctor
```

## Parcours autonome

```powershell
java -jar $minos tools install scip-java
java -jar $minos project add N:\workspace-dev\my-project --name my-project
java -jar $minos index my-project --dry-run
java -jar $minos index my-project
```

Pour un projet Java, `JAVA_HOME` doit pointer vers le JDK du projet lors de l’exécution du provider.

## Import manuel

```powershell
java -jar $minos import-scip my-project `
  --file N:\temp\index.scip `
  --provider external-provider
```

## MCP depuis le shaded JAR

Le launcher principal expose désormais :

```powershell
java -jar $minos mcp
```

Le point d’entrée historique reste également utilisable :

```powershell
java -cp $minos com.minos.mcp.MinosMcpServer
```

## Construire la distribution Windows

Avec JDK 24 et `jpackage` :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0
```

Le script exécute `clean verify` par défaut puis produit :

```text
target/dist/minos-0.2.0-windows-x64.zip
target/dist/minos-0.2.0-windows-x64.zip.sha256
```

## Docker MCP depuis les sources

Le workflow historique `docker/scripts/prod-mcp.ps1` reste utile au développement.

Pour un mode Docker construit à partir du **même JAR qu’une release**, utiliser le workflow packagé décrit dans [mcp.md](mcp.md).

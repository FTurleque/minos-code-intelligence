# Référence CLI MINOS

Le launcher stable est `com.minos.cli.MinosLauncher`.

Installation native :

```powershell
minos.cmd <commande>
```

Checkout source :

```powershell
java -jar .\target\minos-code-intelligence-0.2.0-SNAPSHOT-all.jar <commande>
```

`--help` reste la source de vérité exécutable.

## Administration

```text
project add <path> [--name <name>] [--format <text|json>]
project list [--format <text|json>]
project inspect <project> [--format <text|json>]
inspect <project> [--format <text|json>]
index-status <project> [--format <text|json>]
```

## Diagnostic runtime

```text
doctor [--format <text|json>]
tools list [--format <text|json>]
tools verify [--format <text|json>]
tools install <provider> [--format <text|json>]
```

`doctor` retourne `1` lorsqu'une action est requise pour obtenir tous les runtimes providers gérés.

## Indexation autonome

```text
index <project> [options]
```

Options :

```text
--provider <id>       override de négociation
--force-full          exécution FULL explicite
--dry-run             calculer le plan sans lancer le provider
--format <text|json>
```

Exemples :

```powershell
minos.cmd index nexus --dry-run
minos.cmd index nexus
minos.cmd index nexus --force-full --format json
```

Le plan expose le provider, son runtime, la portée et les raisons.

### Compatibilité M9

Pendant la transition M14, cette forme reste acceptée avec warning :

```text
index <project> --scip <index.scip> --provider <id>
```

Préférer désormais `import-scip`.

## Import SCIP manuel

```text
import-scip <project> --file <index.scip> --provider <id> [options]
```

Options :

```text
--provider-version <version>
--module <module>
--snapshot <id>
--format <text|json>
```

## Recherche contextuelle

```text
search <project> <query> [options]
```

Options principales :

```text
--qualified-name <name>
--kind <kind>
--module <module>
--limit <1..20>              défaut 5
--depth <0..3>               défaut 1
--usages <0..50>             défaut 3
--relationships <0..50>      défaut 10
--context-lines <0..50>      défaut 2
--max-tokens <count>         défaut 4000
--no-source
--format <text|json>
```

## Symboles et sources

```text
find-symbol <project> <symbol> [options]
get-source <project> <file-id> [--format <text|json>]
find-usages <project> <symbol-id> [--limit <count>] [--format <text|json>]
```

## Relations

```text
find-implementations <project> <symbol-id>
find-callers <project> <symbol-id>
find-callees <project> <symbol-id>
dependencies <project> <symbol-id>
dependents <project> <symbol-id>
related-tests <project> <symbol-id>
```

Une liste vide signifie qu'aucune relation correspondante n'est présente dans le snapshot observé ; elle ne prouve pas une absence runtime.

## Architecture

```text
architecture <project> [--module <module>] [--format <text|json>]
```

## Impact

```text
impact <project> <symbol-id> [--depth <1..32>] [--limit <1..10000>] [--format <text|json>]
```

L'impact reste une estimation potentielle fondée sur le graphe observé.

## NEXUS

```text
nexus-export --root <project-root>
```

Le JSON versionné est écrit sur stdout.

## MCP

Le launcher système accepte :

```text
minos mcp
```

Cette commande bloque volontairement sur une session MCP STDIO jusqu'à fermeture par le client.

## Version

```text
minos --version
```

Dans un artefact packagé, la version est lue dans le manifest du JAR afin d'être identique à celle de la release.

## Codes de sortie

```text
0  succès
1  erreur d'exécution / diagnostic action requise
2  erreur d'usage
```

En automatisation : utiliser `--format json` et tester le code de sortie avant de consommer stdout.

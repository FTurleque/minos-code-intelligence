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

`--help` reste la source de vérité exécutable. Les commandes d'aide, y compris `providers --help`, n'initialisent pas `MINOS_HOME`.

## Formats de sortie

La plupart des commandes qui acceptent `--format` proposent deux représentations du même résultat métier :

- `text` : sortie compacte destinée au terminal ;
- `json` : sortie structurée destinée aux scripts, CI et intégrations.

```text
--format <text|json>
```

Exemples :

```powershell
minos.cmd architecture my-project
minos.cmd architecture my-project --format json
```

La commande `architecture` ajoute :

```text
--format <text|json|mermaid|dot>
```

- `mermaid` : graphe `flowchart` ;
- `dot` : graphe Graphviz DOT.

## Administration des projets

```text
project add <path> [--name <name>] [--format <text|json>]
project list [--format <text|json>]
project inspect <project> [--format <text|json>]
inspect <project> [--format <text|json>]
index-status <project> [--format <text|json>]
```

`project inspect` et `inspect` exposent les faits de découverte : langages, systèmes de build, modules et état d'indexation.

M17 reconnaît notamment :

```text
Langages     JAVA, KOTLIN, TYPESCRIPT, PYTHON
Builds       MAVEN, GRADLE, NPM, PNPM, YARN
```

La détection d'un build system ne signifie pas qu'un runtime d'indexation est automatiquement qualifié pour ce build. Par exemple, Gradle Java/Kotlin est découvert, mais le runtime Windows `scip-java` qualifié par MINOS reste limité aux projets Maven tant qu'une qualification Gradle n'a pas été réalisée.

## Diagnostic runtime

```text
doctor [--format <text|json>]
tools list [--format <text|json>]
tools verify [--format <text|json>]
tools install <provider> [--format <text|json>]
providers [provider-id] [--format <text|json>]
```

### `doctor`

`doctor` vérifie l'environnement MINOS et les runtimes **requis par défaut**.

Un provider M17 peut être :

- requis par la baseline : son absence rend `doctor` non READY ;
- optionnel : il reste visible et installable, mais son absence ne casse pas la baseline historique.

Ainsi `scip-java` et `scip-typescript` restent les runtimes de baseline M14. `scip-python` est optionnel tant qu'un projet Python n'en a pas besoin.

Le format JSON expose `requiredByDefault` pour chaque runtime.

### `tools`

```powershell
minos.cmd tools list
minos.cmd tools verify
minos.cmd tools install scip-java
minos.cmd tools install scip-typescript
minos.cmd tools install scip-python
```

`tools verify` vérifie les runtimes requis par défaut. Une indexation qui sélectionne un provider optionnel exige néanmoins que son runtime soit `READY`.

`scip-python` est installé par MINOS sous `MINOS_HOME\tools`; aucune installation npm globale n'est nécessaire. Sa qualification M17 utilise `@sourcegraph/scip-python` `0.6.6` et requiert Node/npm ainsi que Python 3.10+ dans le `PATH`.

### `providers`

M17 ajoute une vue de diagnostic distincte de l'installation :

```powershell
minos.cmd providers
minos.cmd providers scip-java
minos.cmd providers scip-python --format json
```

Cette commande expose :

```text
id
version
languages
buildSystems
capabilities
conformanceScorePercent
limitations
runtimeState
runtimeDiagnostics
```

Chaque capability reçoit explicitement un niveau :

```text
FULL
PARTIAL
EXPERIMENTAL
UNSUPPORTED
```

Une capacité absente du profil n'est pas interprétée implicitement comme supportée.

Exemple de consommation PowerShell :

```powershell
$provider = minos.cmd providers scip-python --format json | ConvertFrom-Json
$provider.capabilities
$provider.limitations
$provider.runtimeState
```

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

Le plan expose les providers retenus, leurs runtimes, la portée (`NONE`, `INCREMENTAL`, `FULL`) et les raisons.

Une capacité incrémentale n'est utilisée que si le provider la déclare explicitement et si la planification MINOS l'autorise. M17 n'invente aucune capacité incrémentale pour les providers SCIP épinglés.

### Kotlin

Pour le périmètre M17 qualifié :

```text
KOTLIN + MAVEN → scip-java
```

Un projet Kotlin/Gradle est correctement découvert mais n'est pas automatiquement déclaré indexable par `scip-java`.

### Python

Pour un projet Python :

```powershell
minos.cmd tools install scip-python
minos.cmd project add C:\workspace\my-python-project --name my-python
minos.cmd index my-python --provider scip-python
```

Le provider est sélectionnable indépendamment d'un build system Python spécifique ; les limitations exactes sont consultables par `minos providers scip-python`.

## Import SCIP manuel

L'import manuel reste disponible pour diagnostic/fallback :

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

La forme historique suivante reste acceptée avec warning pendant la transition de compatibilité :

```text
index <project> --scip <index.scip> --provider <id>
```

Préférer `import-scip`.

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

Exemple :

```powershell
$symbols = minos.cmd find-symbol my-project GreetingService --format json | ConvertFrom-Json
$symbolId = $symbols.symbols[0].id
minos.cmd find-usages my-project $symbolId --format json
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

Une liste vide signifie qu'aucune relation correspondante n'est présente dans le snapshot observé ; elle ne prouve pas une absence runtime. Le profil provider indique séparément si cette famille de faits est `FULL`, `PARTIAL`, `EXPERIMENTAL` ou `UNSUPPORTED`.

## Architecture et graphe

```text
architecture <project> [--module <module>] [--format <text|json|mermaid|dot>]
```

MINOS dérive un graphe orienté de dépendances inter-modules à partir du snapshot actif.

### JSON

```powershell
$architecture = minos.cmd architecture my-project --format json | ConvertFrom-Json
$architecture.moduleDependencies
```

Chaque arête expose notamment source, cible, nombre de dépendances, nombres de symboles, exemples de relations, nature et confiance.

### Mermaid

```powershell
minos.cmd architecture my-project --format mermaid |
  Set-Content .\architecture.mmd -Encoding utf8
```

### Graphviz DOT

```powershell
minos.cmd architecture my-project --format dot |
  Set-Content .\architecture.dot -Encoding utf8
```

Graphviz n'est pas requis par MINOS ; il n'est nécessaire que pour transformer le DOT en image.

Avec `--module`, les formats graphiques bornent la vue au module choisi et à ses voisins directs.

## Impact

```text
impact <project> <symbol-id> [--depth <1..32>] [--limit <1..10000>] [--format <text|json>]
```

L'impact reste une estimation potentielle fondée sur le graphe observé et les capacités réelles du provider. Les limitations runtime/provider restent distinctes du résultat d'impact.

## NEXUS

```text
nexus-export --root <project-root>
```

Le JSON versionné est écrit sur stdout. M20 ajoute des signaux sémantiques v2 sans transférer à MINOS le ranking global, la sélection finale ou le budget multi-source de NEXUS.

## Runtime & Dynamic Intelligence

```text
runtime import <project> --file <path> [--format <text|json>]
runtime sessions <project> [--limit <1..128>] [--format <text|json>]
runtime report <project> [--session <id>] [--limit <1..1000>] [--format <text|json>]
runtime symbol <project> --symbol <id> [--session <id>] [--limit <1..1000>] [--format <text|json>]
```

L’import est la seule opération d’écriture M26. Il accepte le format UTF-8 TSV strict `minos-runtime-observation-v1` avec `completeness\tPARTIAL`, un UUID projet et un snapshot actif exact. Les lectures déclarent systématiquement `OBSERVED_PARTIAL` et `exhaustive: false` ; l’absence ne prouve jamais la non-exécution et le ratio observé n’est pas une couverture exhaustive.

## MCP

Le launcher système accepte :

```text
minos mcp
```

La session MCP STDIO reste read-only. Depuis M15, le MCP appelle directement les services applicatifs partagés ; il ne réexécute pas la CLI métier.

Le catalogue courant contient **31 tools read-only**. Il inclut les 16 tools historiques ainsi que :

```text
minos_program_graph
minos_impact_v2
minos_security_paths
minos_semantic_index_status
minos_semantic_search
minos_hybrid_search
minos_hybrid_context
minos_runtime_sessions
minos_runtime_report
minos_runtime_symbol
minos_team_tenant
minos_team_workspaces
minos_team_workspace
minos_team_members
minos_team_audit
```

Les réponses des tools historiques de structure/statut restent enrichies par les profils provider lorsque cela s'applique. Les surfaces M19/M20 restent bornées et exposent leurs limitations ; un score sémantique reste un signal `HEURISTIC`, jamais un fait structurel.

Le catalogue exact est généré dans [`../generated/product-facts.md`](../generated/product-facts.md). Voir aussi [Serveur MCP](mcp.md).

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

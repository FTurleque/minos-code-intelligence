# Référence CLI MINOS

Le launcher stable est `com.minos.cli.MinosLauncher`.

Installation native Windows :

```powershell
minos.cmd <commande>
```

Checkout source sur la ligne de maintenance courante :

```powershell
java -jar .\target\minos-code-intelligence-1.2.0-SNAPSHOT-all.jar <commande>
```

`--help` reste la source de vérité exécutable. Les commandes d'aide n'ont pas besoin d'initialiser un projet MINOS pour afficher leur syntaxe.

## Version

```text
minos --version
```

Dans un artefact packagé, la version provient du manifest du JAR afin d'être identique à la release construite avec `-Drevision=<version>`.

Version de développement courante :

```text
1.2.0-SNAPSHOT
```

## Formats de sortie

La plupart des commandes qui acceptent `--format` proposent deux représentations du même résultat métier :

- `text` : sortie compacte destinée au terminal ;
- `json` : sortie structurée destinée aux scripts et intégrations.

```text
--format <text|json>
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

Le catalogue provider courant couvre Java/Kotlin, TypeScript, Python, C/C++, C#, Go et Rust selon les profils et plateformes explicitement qualifiés. Une détection de langage/build ne vaut jamais preuve qu'un provider donné offre toutes les capabilities avancées.

## Diagnostic runtime

```text
doctor [--format <text|json>]
tools list [--format <text|json>]
tools verify [--format <text|json>]
tools install <provider> [--format <text|json>]
providers [provider-id] [--format <text|json>]
```

### `doctor`

`doctor` vérifie l'environnement MINOS et les runtimes nécessaires au parcours demandé.

Il distingue notamment :

- le runtime Java **embarqué** utilisé pour exécuter MINOS ;
- les toolchains du projet analysé ;
- les providers gérés ;
- Docker, qui reste optionnel ;
- les actions nécessaires pour rendre un provider utilisable.

Depuis la maintenance 1.0.1, le runtime Windows packagé est également contrôlé lors de la construction par `jdeps`, `java --list-modules` et un vrai handshake MCP. Le fait que `doctor` ou `--version` fonctionne ne remplace donc pas les gates spécifiques du binaire de release.

### `tools`

```powershell
minos.cmd tools list
minos.cmd tools verify
minos.cmd tools install scip-java
minos.cmd tools install scip-typescript
minos.cmd tools install scip-python
```

Les providers installables restent sous `MINOS_HOME\tools` lorsque le contrat du provider le prévoit.

### `providers`

```powershell
minos.cmd providers
minos.cmd providers scip-java
minos.cmd providers scip-go --format json
```

La vue expose notamment :

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

Les capabilities ont une disposition explicite. Une capacité absente n'est jamais interprétée comme supportée.

## Indexation autonome

```text
index <project> [options]
```

Options structurantes :

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

Une capacité incrémentale n'est utilisée que si le provider la déclare explicitement et si la planification MINOS l'autorise.

### Provider explicite

Pour diagnostic ou lorsqu'un projet possède plusieurs candidats :

```powershell
minos.cmd index my-project --provider scip-go --force-full --format json
```

L'override ne change ni les capacités réelles ni les limitations déclarées du provider.

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

Préférer `import-scip` au parcours historique `index --scip` lorsqu'un fichier SCIP externe doit être fourni explicitement.

## Recherche contextuelle

```text
search <project> <query> [options]
```

Options principales :

```text
--qualified-name <name>
--kind <kind>
--module <module>
--limit <1..20>
--depth <0..3>
--usages <0..50>
--relationships <0..50>
--context-lines <0..50>
--max-tokens <count>
--no-source
--format <text|json>
```

Exemple :

```powershell
minos.cmd search my-project GreetingPort --format json
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

Une liste vide signifie qu'aucune relation correspondante n'est présente dans le snapshot observé ; elle ne prouve pas une absence runtime. Le profil provider indique séparément les capacités réellement supportées.

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

Avec `--module`, les formats graphiques bornent la vue au module choisi et à ses voisins directs.

## Impact

```text
impact <project> <symbol-id> [--depth <1..32>] [--limit <1..10000>] [--format <text|json>]
```

L'impact reste une estimation potentielle fondée sur le graphe observé et les capabilities réellement disponibles.

## ProgramGraph et intelligence avancée

Les surfaces avancées utilisent le modèle `ProgramGraph` lorsque le provider actif prouve les capabilities correspondantes.

Elles couvrent notamment :

```text
call graph
CFG
local def-use / data-flow
bounded interprocedural flow
security / taint primitives
provider provenance
confidence
limitations
```

Les résultats restent capability-honest : un provider SCIP polyglotte fournissant symboles/références n'est pas automatiquement présenté comme capable de CFG ou data-flow avancé.

## Recherche sémantique / hybride

La couche sémantique reste optionnelle. `local-hash` est un provider déterministe de référence et les providers learned restent opt-in.

Les résultats vectoriels restent `HEURISTIC`. La similarité ne crée jamais une relation structurée `CALLS`, `DEPENDS_ON`, `DATA_FLOW`, etc.

Profil local learned de référence documenté :

```powershell
$env:MINOS_SEMANTIC_PROVIDER='ollama'
$env:MINOS_SEMANTIC_MODEL='embeddinggemma'
$env:MINOS_SEMANTIC_DIMENSIONS='768'
$env:MINOS_SEMANTIC_ENDPOINT='http://127.0.0.1:11434/api/embed'
```

Voir `docs/developer/semantic-retrieval-2.md` pour les limitations et gates.

## Remote & Distributed Indexing

M25 ajoute les opérations remote sur révision immuable. Exemple conceptuel :

```powershell
minos.cmd remote materialize https://github.com/acme/project --ref main `
  --commit 0123456789abcdef0123456789abcdef01234567 --format json
```

L'indexation remote exige le commit exact et conserve provenance/bundle vérifié.

La disposition sécurité est :

```text
ALLOW → réseau autorisé dans une sandbox OS qualifiée
DENY  → réseau bloqué dans une sandbox OS qualifiée
backend natif/process-only → refusé dans les deux modes
```

Linux utilise bubblewrap/namespaces et une frontière de job cgroup v2 (`memory.max`, `pids.max`, `cpu.max`, `cgroup.kill`); Windows utilise AppContainer + Job Object. L’absence de primitive qualifiée provoque un échec avant l’exécution du provider distant.

## Runtime & Dynamic Intelligence

```text
runtime import <project> --file <path> [--format <text|json>]
runtime sessions <project> [--limit <1..128>] [--format <text|json>]
runtime report <project> [--session <id>] [--limit <1..1000>] [--format <text|json>]
runtime symbol <project> --symbol <id> [--session <id>] [--limit <1..1000>] [--format <text|json>]
```

L'import est l'opération d'écriture. Les lectures déclarent systématiquement leur caractère partiel (`OBSERVED_PARTIAL`, `exhaustive: false`) ; l'absence ne prouve jamais la non-exécution.

## Team / Hosted Mode

Le mode est opt-in et local-first. Les secrets restent injectés par l'environnement.

Exemple de bootstrap opérateur :

```powershell
$env:MINOS_HOSTED_MODE='enabled'
$env:MINOS_TEAM_KEY_KEY_A='<base64-32-bytes>'
minos.cmd team bootstrap --tenant <uuid> --name Team --key-id key-a `
  --owner alice --owner-name Alice --request-id bootstrap-1
```

Le token retourné ensuite est placé dans `MINOS_TEAM_TOKEN`, pas persisté dans une ligne de commande partagée.

Le contrôle tenant, RBAC, workspaces, audit, chiffrement et rétention ne constituent pas un service SaaS opéré.

## NEXUS

```text
nexus-export --root <project-root>
```

Le JSON versionné est écrit sur stdout. NEXUS reste propriétaire du ranking global et du budget multi-source.

## MCP

Le launcher accepte :

```text
minos mcp
```

La session MCP STDIO reste read-only et appelle les services applicatifs partagés.

Le catalogue courant contient **31 tools read-only**, notamment :

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

Le catalogue exact est généré dans [`../generated/product-facts.md`](../generated/product-facts.md). Voir aussi [Serveur MCP](mcp.md).

### Particularité Windows

Le binaire `app\minos.exe mcp` de la distribution Windows est désormais directement exercé par les gates de packaging. Une build locale du candidat peut être générée avec :

```powershell
.\scripts\release\build-local-windows-candidate.ps1 -Version 1.1.0
```

Ce runner ne crée aucun tag, ne publie aucune release et ne déclenche aucun GitHub Actions.

## Codes de sortie

```text
0  succès
1  erreur d'exécution / diagnostic action requise
2  erreur d'usage
```

En automatisation : utiliser `--format json` et tester le code de sortie avant de consommer stdout.

# Runtime Docker autonome MINOS

> État M29 : cette surface est en cours de qualification sur la branche `m29-autonomous-docker-runtime`. Elle ne constitue pas encore une claim de parité native/Docker ni une fonctionnalité publiée de `1.0.1`.

M29 sépare volontairement le runtime Docker en plusieurs plans afin que l'administration et l'indexation puissent écrire l'état MINOS sans rendre le serveur MCP des agents mutable.

## Plans d'exécution

| Plan | Service Compose | Durée | `/var/lib/minos` | `/workspace/projects` | Réseau |
|---|---|---:|---|---|---|
| MCP query | `minos-mcp` | persistant | read-only | read-only | `none` |
| Administration / indexation | `minos-admin` | éphémère | read-write | read-only | `none` |
| Bootstrap mapping | `minos-bootstrap` | éphémère | read-write | non requis | `none` |

Les trois plans gardent :

```text
container filesystem read-only
cap_drop: ALL
no-new-privileges: true
network_mode: none
bounded tmpfs
MINOS_RUNTIME_LOCATION=docker
```

Le plan admin n'obtient donc pas le droit de modifier le code source des projets. Il ne peut écrire que l'état métier MINOS monté sous `/var/lib/minos`.

## Mapping des projets

Lors de l'installation Docker, MINOS crée une configuration runtime versionnée :

```text
<MINOS_HOME>/runtime/project-paths.properties
```

Exemple :

```text
N:/workspace-dev <-> /workspace/projects
```

Le registre métier persiste ensuite un `rootRelativePath`, pas le chemin physique du poste ou du conteneur. Le bootstrap est idempotent et refuse de remplacer implicitement un mapping existant différent.

Dans les commandes exécutées **dans Docker**, utiliser le chemin visible par le conteneur :

```text
/workspace/projects/my-project
```

et non :

```text
N:\workspace-dev\my-project
```

## Installation du runtime Docker de travail

Depuis un checkout M29 qualifié :

```powershell
$Jar = '.\target\minos-code-intelligence-1.0.1-SNAPSHOT-all.jar'

.\docker\scripts\prod-mcp-release.ps1 `
  -Action Install `
  -Jar $Jar `
  -Version '1.0.1-SNAPSHOT' `
  -Commit (git rev-parse HEAD) `
  -ProjectsRoot 'N:\workspace-dev'
```

`Install` :

1. construit l'image depuis le JAR exact ;
2. valide `docker compose config` ;
3. contrôle le runtime Java de l'image ;
4. initialise le mapping host/container ;
5. vérifie que le plan admin expose le CLI stable ;
6. persiste les métadonnées de l'installation.

## Exécuter le CLI MINOS dans Docker

Le workflow packagé accepte `-Action Admin` :

```powershell
$Docker = '.\docker\scripts\prod-mcp-release.ps1'

& $Docker -Action Admin -MinosArguments @('doctor', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'list', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('tools', 'verify', '--format', 'json')

& $Docker -Action Admin -MinosArguments @(
  'project', 'add', '/workspace/projects/my-project',
  '--name', 'my-project', '--format', 'json'
)

& $Docker -Action Admin -MinosArguments @('project', 'list', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('project', 'inspect', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('index-status', 'my-project', '--format', 'json')
```

Un raccourci source est également disponible :

```powershell
.\docker\scripts\minos-docker.ps1 project list --format json
```

## État sémantique et hybride

M29 ajoute deux diagnostics CLI read-only :

```text
semantic status <project> [--format <text|json>]
hybrid status <project> [--format <text|json>]
```

Exemples Docker :

```powershell
& $Docker -Action Admin -MinosArguments @('semantic', 'status', 'my-project', '--format', 'json')
& $Docker -Action Admin -MinosArguments @('hybrid', 'status', 'my-project', '--format', 'json')
```

`semantic status` reflète l'état du vector store persistant : `DISABLED`, `NO_ACTIVE_SNAPSHOT`, `MISSING`, `STALE` ou `READY`.

`hybrid status` distingue :

```text
NO_ACTIVE_SNAPSHOT
READY_STRUCTURED_FALLBACK
READY_WITH_SEMANTIC
```

Le fallback hybride lexical/graphe reste utilisable avec un snapshot actif lorsque le signal sémantique n'est pas `READY`. Le signal vectoriel reste `HEURISTIC` et ne crée jamais de fait structurel.

## MCP query-only

Démarrer le plan MCP :

```powershell
& $Docker -Action Start
```

Ouvrir une session STDIO :

```powershell
& $Docker -Action Attach
```

Le processus MCP est lancé directement dans le conteneur query-only. Ce plan voit les projets et l'état MINOS en lecture seule.

Arrêt :

```powershell
& $Docker -Action Stop
```

## Validation et état

```powershell
& $Docker -Action Validate
& $Docker -Action Status
```

`Validate` revalide :

- la syntaxe Compose ;
- Java dans l'image ;
- l'idempotence du mapping ;
- l'accès au CLI via le plan admin.

## Désinstallation

```powershell
& $Docker -Action Uninstall
```

Le conteneur et la configuration runtime gérés sont retirés. Les données persistantes sont conservées par défaut.

La purge explicite des données et le switching transactionnel natif/Docker relèvent de M29-S7 et ne doivent pas être simulés par suppression manuelle pendant la qualification S3.

## Limite actuelle avant M29-S4

Le plan admin permet maintenant d'exécuter l'indexation depuis Docker, mais **M29-S4 doit encore rendre l'image provider-complete et qualifier les providers offline**. Tant que S4 n'est pas passé, l'absence d'un runtime provider dans l'image est une limitation réelle et non une preuve d'échec du plan d'administration.

Aucun provider ne doit être déclaré supporté dans l'image avant qualification réelle sans réseau en RUN.

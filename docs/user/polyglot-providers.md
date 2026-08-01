# Providers polyglottes M24

M24 étend la découverte MINOS à C, C++, C#, Go et Rust sans assimiler **détection de projet** et **indexation qualifiée**.

La présence d'un fichier `.go`, `.rs`, `.cs`, `CMakeLists.txt` ou `Cargo.toml` permet à MINOS de décrire le projet. Elle ne garantit pas que l'indexeur externe correspondant est installé, prêt, qualifié sur la plateforme courante ni capable de produire toutes les formes d'intelligence MINOS.

## Disposition finale M24

Les quatre providers ont été qualifiés avec contraintes sur le HEAD exact `927f57768a79af162e2cdc765d0f54d274cbe02e` :

| Langage | Provider | Version M24 | Disposition | Installation MINOS | Plateformes qualifiées |
|---|---|---:|---|---|---|
| C / C++ | `scip-clang` | `0.4.0` | `QUALIFIED_WITH_CONSTRAINTS` | non ; binaire opérateur | Linux x86_64 ; Windows hors contrat runtime M24 |
| C# | `scip-dotnet` | `0.2.14` | `QUALIFIED_WITH_CONSTRAINTS` | oui, locale sous `MINOS_HOME/tools` | Linux x86_64 ; Windows 10 Pro 22H2 hors preuve car .NET 10 non supporté |
| Go | `scip-go` | `0.2.7` | `QUALIFIED_WITH_CONSTRAINTS` | oui, locale sous `MINOS_HOME/tools` | Windows x86_64 + Linux x86_64 |
| Rust | `rust-analyzer scip` | `0.3.2989` / release `2026-07-27` / `12c3381` | `QUALIFIED_WITH_CONSTRAINTS` | non ; toolchain opérateur | Windows x86_64 + Linux x86_64 |

Cette table n'est pas une promesse de capacité exhaustive. Les symboles/références et la disponibilité runtime restent distincts des capabilities avancées et d'une preuve e2e sur une plateforme donnée.

## Discovery versus indexation

Discovery est purement locale et passe par les SPI M17 :

```text
C / C++  -> .c/.h/.cc/.cpp/... + CMakeLists.txt
C#       -> .cs + .csproj/.sln
Go       -> .go + go.mod/go.work
Rust     -> .rs + Cargo.toml
```

Les builds découverts sont `CMAKE`, `DOTNET`, `GO_MODULE` et `CARGO`.

Une discovery réussie n'installe rien et ne lance aucun provider.

## Diagnostic provider

```powershell
minos.cmd providers
minos.cmd providers scip-clang --format json
minos.cmd providers scip-dotnet --format json
minos.cmd providers scip-go --format json
minos.cmd providers rust-analyzer-scip --format json
```

La vue provider expose notamment :

```text
qualification
languages
buildSystems
capabilities
limitations
qualificationPlatforms
runtimeRequirements
readinessBehavior
installationBehavior
stableIdentityBehavior
provenanceBehavior
runtimeState
runtimeDiagnostics
```

Un profil de capability est exhaustif. L'absence d'une capacité ne signifie jamais « probablement supportée ».

## Installation et readiness

### C / C++

MINOS M24 ne télécharge pas `scip-clang` automatiquement. Le runtime vérifie un binaire `scip-clang` 0.4.0 fourni par l'opérateur sur Linux x86_64.

Le projet doit produire une compilation database :

```bash
cmake -S . -B build -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
```

MINOS accepte `compile_commands.json` à la racine ou sous `build/`.

Sous Windows, `scip-clang` M24 est signalé `BLOCKED` : la discovery C/C++ continue de fonctionner, mais MINOS ne falsifie pas un runtime d'indexation non qualifié.

### C#

Prérequis lorsque la plateforme est supportée :

```text
.NET SDK 10+
```

En juillet 2026, la matrice officielle .NET 10 ne prend pas en charge Windows 10 Pro 22H2. Sur cet hôte, M24 **n'impose pas** l'installation d'un SDK non supporté : `scip-dotnet` reste `BLOCKED/NOT_RUN` côté Windows et sa preuve e2e est portée par Linux ou par un Windows officiellement supporté.

Installation locale sur une plateforme supportée :

```powershell
minos.cmd tools install scip-dotnet
```

MINOS utilise `dotnet tool install --tool-path` sous `MINOS_HOME/tools`; aucune installation globale n'est réalisée.

### Go

Prérequis : une toolchain Go disponible dans `PATH`.

Installation locale :

```powershell
minos.cmd tools install scip-go
```

MINOS épingle `scip-go` 0.2.7 et impose `GOBIN` sous `MINOS_HOME/tools`. La fixture de qualification utilise un projet `go.mod`. `go.work` est découvert mais n'est pas présenté comme preuve de sémantique multi-workspace tant que ce cas n'est pas mesuré.

### Rust

Prérequis :

```text
cargo
rustc
rust-analyzer 2026-07-27 / v0.3.2989 (commit 12c3381)
```

MINOS ne lance jamais `rustup update` et n'installe pas implicitement de compilateur Rust. Le runtime reste operator-managed et doit déjà exposer la version qualifiée de `rust-analyzer`.

## Sélectionner explicitement un provider

Les quatre providers M24 participent désormais à la négociation automatique sous leurs contraintes. Un override utilisateur reste utile pour le diagnostic ou pour imposer un provider précis :

```powershell
minos.cmd index mon-projet --provider scip-go --dry-run --format json
minos.cmd index mon-projet --provider scip-go --force-full --format json
```

Ce mécanisme ne modifie ni la qualification, ni les plateformes, ni les capabilities déclarées par le provider.

## Ce que symboles/références ne prouvent pas

Les nouveaux providers SCIP peuvent fournir des symboles, références, usages et certaines relations. Cela **ne prouve pas** :

```text
CFG
def-use
argument flow
return flow
interprocedural data-flow
security taint
```

Ces capacités avancées restent liées aux preuves M22 du provider Java. M24 ne les extrapole pas aux nouveaux langages.

De même, `architecture` et `impact` sont des dérivations MINOS uniquement lorsque les faits structurés nécessaires sont présents. Une relation absente du provider n'est jamais inventée.

## Stable identity et provenance

Pour chaque provider M24, MINOS conserve :

- une identité MINOS déterministe et explicitement qualifiée comme fallback structurel tant qu'aucune canonicité cross-provider n'est prouvée ;
- le symbol SCIP brut comme `ProviderReference` ;
- le provider, sa version et l'index run dans `Origin` ;
- les symboles externes sous une identité provider-scoped au lieu de les maquiller en symboles locaux canoniques.

Les gates M24 répètent l'indexation des fixtures afin de vérifier la stabilité et les collisions triviales.

## Couche sémantique M23

Les snapshots polyglottes structurés peuvent alimenter les documents sémantiques existants, mais M24 ne change pas leur autorité :

- les snapshots structurés restent la source factuelle ;
- la recherche sémantique reste opt-in ;
- les résultats sémantiques restent `HEURISTIC` ;
- le backend M20/M23 courant reste en place.

Le profil learned canonique reste :

```text
MINOS_SEMANTIC_PROVIDER=ollama
MINOS_SEMANTIC_MODEL=embeddinggemma
MINOS_SEMANTIC_DIMENSIONS=768
MINOS_SEMANTIC_ENDPOINT=http://127.0.0.1:11434/api/embed
```

## En cas d'échec

Commencer par :

```powershell
minos.cmd project inspect mon-projet --format json
minos.cmd providers <provider-id> --format json
minos.cmd tools list --format json
minos.cmd doctor --format json
```

Puis vérifier les prérequis du provider, sa version exacte et les limitations affichées. Un état `BLOCKED`, `NOT_INSTALLED` ou `INVALID` n'est jamais contourné par une promotion de snapshot ; `BLOCKED` peut être une disposition attendue lorsque la plateforme elle-même n'est pas supportée par le provider ou son runtime.

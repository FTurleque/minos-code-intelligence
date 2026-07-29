# M24 — Polyglot Expansion — exécution

Statut : **EN COURS — S1 cadré ; S2→S8 implémentation/qualification en cours ; aucune promotion avant double qualification exact-head Windows + Linux.**

```text
Issue   : #81 — OPEN
PR      : #82 — DRAFT
Branche : m24-polyglot-expansion
Base    : develop @ 8dbe34cb9e524acb62becda4faa263d74b90b9a9
Date    : 28 juillet 2026
```

M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**. M24 n’inspecte, ne relance, ne modifie et n’utilise aucun workflow CI comme preuve.

## Question produit

> MINOS peut-il étendre sa couverture de langages sans abaisser les exigences de capabilities, stable identity, provenance et conformance ?

## Architecture retenue

M24 réutilise les frontières M17 et le pipeline SCIP existant :

```text
ProjectDiscoveryService
        │
        └─ SPI discovery
              ├─ ProjectDetector
              ├─ BuildSystemDetector
              ├─ SourceRootDetector
              └─ LanguageDetector

IndexerProviderRegistry
        │
        └─ providers SCIP évalués
              ├─ scip-clang       C / C++
              ├─ scip-dotnet      C#
              ├─ scip-go          Go
              └─ rust-analyzer    Rust / SCIP

CompositeProviderRuntimeManager
        │
        └─ extensions runtime indépendantes
              ↓
       index.scip
              ↓
ScipSymbolSnapshotImporter
              ↓
 snapshot structuré MINOS autoritatif
```

Aucun `switch` de langage n’est ajouté dans `ProjectDiscoveryService`, CLI, MCP, IntelliJ ou NEXUS. Les surfaces consomment les contrats provider/discovery existants.

## Audit du tree

Le tree de base prouve déjà :

- `ProjectDiscovery.Language` historique = Java, Kotlin, TypeScript, Python ;
- `ProjectDiscovery.BuildSystem` historique = Maven, Gradle, npm, pnpm, yarn ;
- `DefaultDiscoveryPlugins` branche les marqueurs/langages derrière les SPI ;
- `IndexerCapability` possède un profil exhaustif de 13 capacités d’indexation ;
- `CompositeProviderRuntimeManager` compose les extensions sans branchement public sur les ids ;
- les installations gérées sont confinées sous `MINOS_HOME/tools` ;
- `ScipSymbolNormalizer` conserve provenance SCIP et raw provider symbol dans `ProviderReference` ;
- M22 advanced intelligence reste un provider Java séparé : aucune capacité CFG/data-flow/security n’est déduite d’un simple index SCIP ;
- M23 garde le snapshot structuré autoritatif et les résultats sémantiques `HEURISTIC`.

M24 a ajouté sans changer ce modèle :

- `C`, `CPP`, `CSHARP`, `GO`, `RUST` ;
- `CMAKE`, `DOTNET`, `GO_MODULE`, `CARGO` ;
- `ProviderOperationalProfile` : plateformes qualifiées, runtime requirements, readiness/install, stable identity et provenance ;
- conformance croisée descriptor/capability/operational profile ;
- runtime polyglotte derrière `ProviderRuntimeManager` ;
- fixtures déterministes sous `fixtures/m24/` ;
- fingerprints build M24, avec lecture compatible des snapshots FORMAT_VERSION=1 calculés sous la politique M17 ;
- opt-in explicite `--provider <id>` pour exercer un provider `EXPERIMENTAL`, sans le rendre éligible à la négociation automatique.

## Évaluation upstream et pins M24

Les versions suivantes sont **pinnées pour M24** ; leur présence ne constitue pas une qualification MINOS.

| Écosystème | Provider/indexeur | Version M24 | Licence | Runtime / plateforme upstream | Disposition avant gates |
|---|---|---:|---|---|---|
| C / C++ | `sourcegraph/scip-clang` | `0.4.0` | Apache-2.0 | binaires upstream Linux x86_64 et macOS arm64 ; compilation database requise | `EXPERIMENTAL`, cible de qualification runtime Linux x86_64 ; Windows runtime explicitement bloqué |
| C# | `sourcegraph/scip-dotnet` | `0.2.14` | Apache-2.0 | outil .NET ; release 0.2.14 construite avec SDK .NET 10 | `EXPERIMENTAL`, candidat Windows + Linux |
| Go | `scip-code/scip-go` | `0.2.7` | Apache-2.0 | `go install`; projet `go.mod` canonique | `EXPERIMENTAL`, candidat Windows + Linux |
| Rust | `rust-lang/rust-analyzer scip` | `2026-07-27 / v0.3.2989`, commit `12c3381` | Apache-2.0 / MIT | `cargo`, `rustc`, `rust-analyzer`; le wrapper `scip-rust` n'est pas un moteur distinct | `EXPERIMENTAL` jusqu’à preuve stable identity + plateforme |

Les résultats empiriques M24 priment sur la documentation upstream pour toute promotion.

## Matrice de capacité M24

La qualification distingue explicitement :

```text
discovery
indexation
symbols
references
usages
relationships
stable identity
provenance
architecture
impact
CFG
def-use
data-flow
security
semantic documents
```

Règles :

- `symbols/references/usages/relationships` ne sont promus que sur fixtures réelles ;
- `architecture/impact` sont des dérivations MINOS et exigent les faits source nécessaires ;
- `CFG/def-use/data-flow/security` restent non revendiqués pour les nouveaux providers M24 tant qu’aucun provider avancé spécifique ne les prouve ;
- `semantic documents` passent uniquement par le chemin M20/M23 existant ; les résultats restent `HEURISTIC` et opt-in ;
- aucune absence de fait upstream n’est remplacée par une relation inventée.

## Sous-incréments

### M24-S1 — Cadrage, audit provider, ADR, matrice de qualification ✅ IMPLÉMENTÉ

Preuves de structure : issue #81, branche unique, Draft PR #82, ADR-0032, audit tree/upstream, versions et contraintes pinnées.

### M24-S2 — Infrastructure polyglot + conformance renforcée 🚧 IMPLÉMENTÉ / TEST NON ENCORE EXÉCUTÉ

Vocabulaires language/build étendus ; discovery derrière les SPI ; `ProviderOperationalProfile` additif ; conformance durcie ; sept providers exposés, dont quatre M24 `EXPERIMENTAL` ; CLI provider enrichie.

### M24-S3 — Stable identities, provenance et normalisation cross-language 🚧 IMPLÉMENTÉ / TEST NON ENCORE EXÉCUTÉ

Tests de répétabilité/non-collision ; raw provider symbol conservé ; `Origin` provider/version/run conservé ; externes provider-scoped ; aucune relation fabriquée.

### M24-S4 — C / C++ / scip-clang 🚧 IMPLÉMENTÉ / E2E À QUALIFIER

Discovery C/C++ + CMake ; provider 0.4.0 ; process plan `--compdb-path=...` ; Linux x86_64 cible runtime ; Windows `BLOCKED` ; fixture mixte C/C++ ; installation operator-managed.

### M24-S5 — C# / scip-dotnet 🚧 IMPLÉMENTÉ / E2E À QUALIFIER

Discovery `.cs/.csproj/.sln` ; provider 0.2.14 ; installation locale `dotnet tool --tool-path` ; readiness .NET SDK 10+ ; fixture namespace/interface/implémentation/usages.

### M24-S6 — Go / scip-go 🚧 IMPLÉMENTÉ / E2E À QUALIFIER

Discovery `.go/go.mod/go.work` ; provider 0.2.7 ; installation via `GOBIN` local ; fixture module/package/interface/usages ; `go.work` discovery-only tant que le multi-workspace n'est pas mesuré.

### M24-S7 — Rust / rust-analyzer SCIP 🚧 IMPLÉMENTÉ / E2E À QUALIFIER

Discovery `.rs/Cargo.toml` ; rust-analyzer 2026-07-27 / v0.3.2989 commit `12c3381` ; readiness cargo/rustc/rust-analyzer ; aucun `rustup` implicite ; fixture crate/module/trait/impl/usages.

### M24-S8 — Surfaces publiques, documentation, packaging/runtime 🚧

Implémenté : composition M24, CLI provider, guides user/developer, gate statique `scripts/m24/check-polyglot.py`, scope JaCoCo M24 sans baisse historique, isolation sémantique M23. Packaging/release et IntelliJ restent à prouver par logs.

### M24-S9 — Qualification finale exact-head Windows + Linux ⏳

```powershell
.\scripts\m24\run-final.ps1 -ExpectedHead <sha>
```

```bash
./scripts/m24/run-final.sh <sha>
```

Le helper partagé `scripts/m24/run-provider-e2e.py` copie les fixtures en temporaire, inspecte/installe les runtimes gérés lorsque possible, exécute deux indexations FULL, compare stable identity, vérifie provenance provider/version/run et exerce usages/relations sans salir le checkout.

Les runners vérifient : SHA/worktree propres au début et à la fin, zéro changement `.github/workflows` depuis la base M24, docs/ADR, conformance, fixtures e2e applicables, stable identity/provenance, Maven Java 24 + JaCoCo, régressions M17/M20/M21-local/M22/M23, release Windows M21-S5 et IntelliJ/Plugin Verifier M21-S6 sur Windows.

Marqueurs :

```text
M24 FINAL POLYGLOT EXPANSION VALIDATION SUCCESS
Validated HEAD: <sha>
```

```text
M24 LINUX POLYGLOT EXPANSION VALIDATION SUCCESS
Validated HEAD: <sha>
```

## Politique de disposition

Un provider reste `EXPERIMENTAL` tant que sa plateforme n'est pas enregistrée comme preuve qualifiée. L'évaluateur tente néanmoins l'e2e lorsqu'il peut rendre le runtime `READY`.

Après réception des logs Windows + Linux : un provider réellement prouvé est promu au niveau justifié et reçoit uniquement les plateformes prouvées ; un provider non prêt garde `EXPERIMENTAL` avec diagnostics visibles. Toute promotion modifie le HEAD et impose un replay exact-head final.

## Promotion

```text
évaluation provider Windows + Linux
      ↓
finalisation dispositions + docs
      ↓
dernier commit M24
      ↓
SHA candidat exact
      ↓
Windows exact-head PASS
      ↓
Linux exact-head PASS
      ↓
même SHA + worktree propre
      ↓
PR #82 Ready
      ↓
merge develop avec expected_head_sha
      ↓
issue #81 CLOSED / completed
      ↓
PR documentaire post-merge
      ↓
ROADMAP/STATUS : M24 terminé, M25 prochain
```

Tout changement après qualification invalide le PASS du SHA précédent, y compris un changement documentaire.

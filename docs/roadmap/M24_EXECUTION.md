# M24 — Polyglot Expansion — exécution

Statut : **TERMINÉ — S1→S9 validés, 9/9 ; double qualification exact-head Windows + Linux réussie ; fusionné dans `develop`.**

```text
Issue          : #81 — CLOSED / completed
PR             : #82 — MERGED
Branche        : m24-polyglot-expansion
Base           : develop @ 8dbe34cb9e524acb62becda4faa263d74b90b9a9
Qualified HEAD : 927f57768a79af162e2cdc765d0f54d274cbe02e
Merge develop  : 2a499a7aedd71b7cf4c5fb8339c5b914e3dd46fa
Date           : 29 juillet 2026
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
- sélection explicite `--provider <id>` pour le diagnostic et les fixtures, sans altérer disposition, plateformes ou capabilities.

## Évaluation upstream et pins M24

Les versions suivantes sont **pinnées pour M24** ; leur présence ne constitue pas une qualification MINOS.

| Écosystème | Provider/indexeur | Version M24 | Licence | Disposition finale | Plateformes qualifiées |
|---|---|---:|---|---|---|
| C / C++ | `sourcegraph/scip-clang` | `0.4.0` | Apache-2.0 | `QUALIFIED_WITH_CONSTRAINTS` ; compilation database requise | Linux x86_64 uniquement ; Windows explicitement hors contrat |
| C# | `sourcegraph/scip-dotnet` | `0.2.14` | Apache-2.0 | `QUALIFIED_WITH_CONSTRAINTS` ; .NET SDK 10+ | Linux x86_64 uniquement pour M24 |
| Go | `scip-code/scip-go` | `0.2.7` | Apache-2.0 | `QUALIFIED_WITH_CONSTRAINTS` ; `go.mod` canonique | Windows x86_64 + Linux x86_64 |
| Rust | `rust-lang/rust-analyzer scip` | `2026-07-27 / v0.3.2989`, commit `12c3381` | Apache-2.0 / MIT | `QUALIFIED_WITH_CONSTRAINTS` ; toolchain opérateur | Windows x86_64 + Linux x86_64 |

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

Preuves de structure : issue #81, branche unique, PR #82 ouverte initialement en Draft puis `MERGED`, ADR-0032, audit tree/upstream, versions et contraintes pinnées.

### M24-S2 — Infrastructure polyglot + conformance renforcée ✅ VALIDÉ

Vocabulaires language/build étendus ; discovery derrière les SPI ; `ProviderOperationalProfile` additif ; conformance durcie ; sept providers exposés, dont quatre M24 `QUALIFIED_WITH_CONSTRAINTS` après preuves ; CLI provider enrichie.

### M24-S3 — Stable identities, provenance et normalisation cross-language ✅ VALIDÉ

Tests de répétabilité/non-collision ; raw provider symbol conservé ; `Origin` provider/version/run conservé ; externes provider-scoped ; aucune relation fabriquée.

### M24-S4 — C / C++ / scip-clang ✅ VALIDÉ LINUX X86_64

Discovery C/C++ + CMake ; provider 0.4.0 ; process plan `--compdb-path=...` ; Linux x86_64 cible runtime ; Windows `BLOCKED` ; fixture mixte C/C++ ; installation operator-managed.

### M24-S5 — C# / scip-dotnet ✅ VALIDÉ LINUX X86_64

Discovery `.cs/.csproj/.sln` ; provider 0.2.14 ; installation locale `dotnet tool --tool-path` ; readiness .NET SDK 10+ ; fixture namespace/interface/implémentation/usages.

### M24-S6 — Go / scip-go ✅ VALIDÉ WINDOWS + LINUX X86_64

Discovery `.go/go.mod/go.work` ; provider 0.2.7 ; installation via `GOBIN` local ; fixture module/package/interface/usages ; `go.work` discovery-only tant que le multi-workspace n'est pas mesuré.

### M24-S7 — Rust / rust-analyzer SCIP ✅ VALIDÉ WINDOWS + LINUX X86_64

Discovery `.rs/Cargo.toml` ; rust-analyzer 2026-07-27 / v0.3.2989 commit `12c3381` ; readiness cargo/rustc/rust-analyzer ; aucun `rustup` implicite ; fixture crate/module/trait/impl/usages.

### M24-S8 — Surfaces publiques, documentation, packaging/runtime ✅ VALIDÉ

Composition M24, CLI provider, guides user/developer, gate statique `scripts/m24/check-polyglot.py`, scope JaCoCo M24 sans baisse historique et isolation sémantique M23 validés. Packaging/release Windows et IntelliJ/Plugin Verifier ont été rejoués par le runner final.

### M24-S9 — Qualification finale exact-head Windows + Linux ✅ VALIDÉ

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
Validated HEAD: 927f57768a79af162e2cdc765d0f54d274cbe02e
```

```text
M24 LINUX POLYGLOT EXPANSION VALIDATION SUCCESS
Validated HEAD: 927f57768a79af162e2cdc765d0f54d274cbe02e
```

Évidence provider détaillée :

| Plateforme | Providers obligatoires | Résultat JSON |
|---|---|---|
| Windows x86_64 | `scip-go`, `rust-analyzer-scip` | `READY`, `e2e: PASS`, plateforme revendiquée pour les deux |
| Linux x86_64 | `scip-clang`, `scip-dotnet`, `scip-go`, `rust-analyzer-scip` | `READY`, `e2e: PASS`, plateforme revendiquée pour les quatre |

Le Windows de preuve est Windows 10 Pro 22H2 build 19045 : `scip-clang` y est hors contrat M24 et `scip-dotnet` y est `BLOCKED/NOT_RUN` parce que .NET 10 n'y est pas supporté. Aucun de ces deux états n'a été compté comme e2e Windows requis.

## Politique de disposition

Les preuves ont conduit à une disposition finale `QUALIFIED_WITH_CONSTRAINTS` pour les quatre providers. Les plateformes enregistrées sont strictement celles exercées avec `e2e: PASS` : Linux x86_64 pour `scip-clang`/`scip-dotnet`, Windows x86_64 et Linux x86_64 pour `scip-go`/`rust-analyzer-scip`.

Cette promotion ne change pas les profils de capabilities mesurés : stable identity reste un fallback structurel partiel ; `CALL_RELATIONS` et l'indexation incrémentale restent non revendiquées ; CFG, def-use, data-flow et security restent hors des claims M24.

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

La séquence a été achevée : PR #82 Ready puis fusionnée avec protection du HEAD attendu, issue #81 fermée comme completed, et branche documentaire post-merge dédiée. Tout changement produit après qualification aurait invalidé le PASS du SHA précédent ; la réconciliation documentaire post-merge ne modifie pas le HEAD produit qualifié.

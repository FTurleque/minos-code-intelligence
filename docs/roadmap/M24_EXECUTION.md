# M24 — Polyglot Expansion — exécution

Statut : **EN COURS — cadrage M24-S1 ouvert ; aucune promotion avant double qualification exact-head Windows + Linux.**

```text
Issue   : #81 — OPEN
PR      : à créer en Draft
Branche : m24-polyglot-expansion
Base    : develop @ 8dbe34cb9e524acb62becda4faa263d74b90b9a9
Date    : 28 juillet 2026
```

M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**. M24 n’inspecte, ne relance, ne modifie et n’utilise aucun workflow CI comme preuve.

## Question produit

> MINOS peut-il étendre sa couverture de langages sans abaisser les exigences de capabilities, stable identity, provenance et conformance ?

## Réponse architecturale visée

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
        └─ providers SCIP qualifiés
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

## Audit initial du tree

Le tree de base prouve déjà :

- `ProjectDiscovery.Language` = Java, Kotlin, TypeScript, Python ;
- `ProjectDiscovery.BuildSystem` = Maven, Gradle, npm, pnpm, yarn ;
- `DefaultDiscoveryPlugins` branche les marqueurs/langages derrière les SPI ;
- `IndexerCapability` possède un profil exhaustif de 13 capacités d’indexation ;
- `ProviderConformanceKit` vérifie actuellement identité/version/langages/builds/capabilities/limitations mais pas encore explicitement les exigences runtime ni les comportements stable identity/provenance ;
- `CompositeProviderRuntimeManager` compose les extensions sans branchement public sur les ids ;
- `ManagedScipProviderRuntimeManager` et `ManagedScipPythonRuntimeManager` confinent les installations sous `MINOS_HOME/tools` ;
- `ScipSymbolNormalizer` conserve provenance SCIP et références provider, mais utilise encore une identité structurelle/fallback qui doit être requalifiée pour chaque nouveau langage ;
- M22 advanced intelligence reste un provider Java séparé : aucune capacité CFG/data-flow/security n’est déduite d’un simple index SCIP ;
- M23 garde le snapshot structuré autoritatif et les résultats sémantiques `HEURISTIC`.

## Évaluation upstream figée pour l’implémentation

Les versions suivantes sont **pinnées pour M24** ; leur présence ne constitue pas une qualification MINOS.

| Écosystème | Provider/indexeur | Version M24 | Licence | Runtime / plateforme upstream | Disposition de départ |
|---|---|---:|---|---|---|
| C / C++ | `sourcegraph/scip-clang` | `0.4.0` | Apache-2.0 | binaires upstream : Linux x86_64 glibc>=2.16 et macOS arm64 ; compilation database requise | `QUALIFIED_WITH_CONSTRAINTS` candidat Linux uniquement ; Windows runtime non supporté par M24 |
| C# | `sourcegraph/scip-dotnet` | `0.2.14` | Apache-2.0 | outil .NET ; release 0.2.14 construite avec SDK .NET 10 | `QUALIFIED_WITH_CONSTRAINTS` candidat Windows + Linux |
| Go | `scip-code/scip-go` | `0.2.7` | Apache-2.0 | `go install` ; projet `go.mod` canonique | `QUALIFIED_WITH_CONSTRAINTS` candidat Windows + Linux |
| Rust | `rust-lang/rust-analyzer scip` | `2026-05-25 / v0.3.2913` | Apache-2.0 / MIT | `cargo`, `rustc`, `rust-analyzer`; wrapper `scip-rust` 0.0.6 ne rajoute pas de sémantique | `EXPERIMENTAL` jusqu’à preuve stable identity + Windows/Linux |

Sources upstream de cadrage : dépôts et releases officielles GitHub de `scip-clang`, `scip-dotnet`, `scip-go`, `scip-rust` et `rust-analyzer`. Les résultats empiriques M24 priment sur la documentation upstream pour toute promotion.

## Matrice de capacité M24

La matrice finale doit distinguer au minimum :

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
- `CFG/def-use/data-flow/security` restent `UNSUPPORTED` pour les nouveaux providers M24 tant qu’aucun provider avancé spécifique ne les prouve ;
- `semantic documents` décrivent uniquement la capacité du snapshot à alimenter la couche M20/M23 ; les résultats restent `HEURISTIC` et opt-in ;
- aucune absence de fait upstream n’est remplacée par une relation inventée.

## Sous-incréments

### M24-S1 — Cadrage, audit provider, ADR, matrice de qualification 🚧

Sortie :

- issue #81 ;
- branche unique `m24-polyglot-expansion` depuis le HEAD exact de `develop` ;
- audit tree + upstream ;
- ADR-0032 ;
- versions et contraintes de plateforme pinnées ;
- Draft PR vers `develop`.

### M24-S2 — Infrastructure polyglot + conformance renforcée ⏳

Sortie :

- extensions de `Language` / `BuildSystem` strictement nécessaires ;
- discovery derrière les SPI existants ;
- métadonnées opérationnelles provider exhaustives : exigences runtime, plateformes, installation/readiness, stable identity, provenance ;
- `ProviderConformanceKit` durci sans casser son contrat historique ;
- aucune capability absente du profil.

### M24-S3 — Stable identities, provenance et normalisation cross-language ⏳

Sortie :

- tests de répétabilité sur deux indexations identiques ;
- absence de collisions triviales entre namespaces/packages/modules/symboles homonymes ;
- raw provider symbol conservé dans `ProviderReference` ;
- `Origin` provider/version/run conservé ;
- externes/non résolus explicitement représentés ;
- aucune relation fabriquée.

### M24-S4 — C / C++ / scip-clang ⏳

Sortie :

- discovery C/C++ + CMake/compilation database ;
- provider `scip-clang` 0.4.0 ;
- runtime fail-closed : Linux x86_64 seulement pour l’installation/exécution M24 ;
- fixture CMake avec `compile_commands.json` reproductible ;
- indexation + snapshot + symbol/usages/relations/provenance/stable identity ;
- Windows expose explicitement la limitation au lieu de simuler un PASS.

### M24-S5 — C# / scip-dotnet ⏳

Sortie :

- discovery `.cs`, `.csproj`, `.sln` ;
- provider `scip-dotnet` 0.2.14 ;
- installation locale sous `MINOS_HOME/tools`, jamais globale ;
- .NET SDK requis diagnostiqué ;
- fixture namespace + classe + interface + appels/références ;
- e2e Windows + Linux si les gates runtime passent.

### M24-S6 — Go / scip-go ⏳

Sortie :

- discovery `.go`, `go.mod`, `go.work` visible sans prétention multi-workspace non prouvée ;
- provider `scip-go` 0.2.7 ;
- installation pinnée sous `MINOS_HOME/tools` via `GOBIN` ;
- fixture package/module ;
- e2e Windows + Linux.

### M24-S7 — Rust / rust-analyzer SCIP ⏳

Sortie :

- discovery `.rs`, `Cargo.toml` ;
- provider SCIP basé sur `rust-analyzer` 2026-05-25 / v0.3.2913 ;
- readiness exige `cargo`, `rustc` et la version rust-analyzer qualifiée ;
- aucun téléchargement opaque ni toolchain implicite ;
- fixture crate/module/trait ;
- promotion au plus haut niveau réellement prouvé Windows + Linux, sinon `EXPERIMENTAL` explicite.

### M24-S8 — Surfaces publiques, documentation, packaging/runtime ⏳

Sortie :

- CLI/API/MCP/IntelliJ/NEXUS réutilisent les profils core ;
- aucune liste de langages divergente codée en dur dans les surfaces ;
- documentation utilisateur : support, prérequis, installation, diagnostic, limitations, plateformes ;
- documentation développeur : extension provider + preuve ;
- packaging/supply-chain et Plugin Verifier rejoués si impactés ;
- variables M23 neutralisées dans les replays historiques sans opt-in.

### M24-S9 — Qualification finale exact-head Windows + Linux ⏳

Runners de référence :

```powershell
.\scripts\m24\run-final.ps1 -ExpectedHead <sha>
```

```bash
./scripts/m24/run-final.sh <sha>
```

Les runners sont fail-closed et vérifient au minimum :

1. SHA exact au démarrage ;
2. worktree tracked propre ;
3. cohérence docs/ADR/provider matrix ;
4. conformance M24 exhaustive ;
5. discovery + runtimes + fixtures e2e applicables ;
6. stable identity + provenance cross-language ;
7. Maven Java 24 complet + JaCoCo sans baisse de seuil ;
8. régressions M17/M20/M21-local/M22/M23 pertinentes ;
9. supply-chain/release Windows M21-S5 applicable ;
10. IntelliJ parity + Plugin Verifier M21-S6 applicable ;
11. absence de changement `.github/workflows` ;
12. HEAD et worktree revérifiés à la fin.

Marqueurs finaux attendus :

```text
M24 FINAL POLYGLOT EXPANSION VALIDATION SUCCESS
Validated HEAD: <sha>
```

et sous Linux :

```text
M24 LINUX POLYGLOT EXPANSION VALIDATION SUCCESS
Validated HEAD: <sha>
```

## Promotion

Ordre impératif :

```text
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
PR Ready
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

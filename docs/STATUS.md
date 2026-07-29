# État courant — MINOS

Dernière mise à jour documentaire : **29 juillet 2026 — M27 Team / Hosted Mode implémenté, PR #91 draft, qualification exact-head en attente**

Ce fichier décrit l'état courant. Les preuves détaillées de chaque jalon restent dans [`roadmap/`](roadmap/), les preuves historiques dans [`history/milestones/`](history/milestones/) et les décisions durables dans [`adr/`](adr/README.md).

## Synthèse

```text
C0 — Cadrage                          TERMINÉ
M0 — Faisabilité technique           TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration     TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles       TERMINÉ ET LIVRÉ
M3 — Intelligence des relations      TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact   TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations       TERMINÉ ET LIVRÉ
M6 — Intelligence d'architecture     TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale         TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d'impact                TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                  TERMINÉ, VALIDÉ ET LIVRÉ
M10 — Serveur MCP                    TERMINÉ, VALIDÉ ET LIVRÉ
M11 — API publique                   TERMINÉ, VALIDÉ ET LIVRÉ
M12 — Multi-dépôts + Git             TERMINÉ, VALIDÉ ET LIVRÉ
M13 — Intégration NEXUS              TERMINÉ, VALIDÉ ET LIVRÉ
M14 — Indexation autonome + PROD     TERMINÉ, VALIDÉ ET LIVRÉ
M15 — Industrialisation Core         TERMINÉ, VALIDÉ ET LIVRÉ
M16 — Scalabilité et performance     TERMINÉ, VALIDÉ ET LIVRÉ
M17 — Provider & Discovery Platform  TERMINÉ, VALIDÉ ET LIVRÉ
M18 — MINOS for IntelliJ             TERMINÉ, VALIDÉ ET LIVRÉ
M19 — Advanced Code Intelligence     TERMINÉ, VALIDÉ ET LIVRÉ
M20 — Semantic & Hybrid Intelligence TERMINÉ, VALIDÉ ET LIVRÉ
M21 — Production Integrity           S2 EN PAUSE — S1/S3→S9 localement validés
M22 — Advanced Provider Intelligence TERMINÉ, VALIDÉ, MERGÉ develop
M23 — Semantic Retrieval 2.0         TERMINÉ, VALIDÉ, MERGÉ develop
M24 — Polyglot Expansion             TERMINÉ, VALIDÉ, MERGÉ develop
M25 — Remote & Distributed Indexing  TERMINÉ, VALIDÉ, MERGÉ develop
M26 — Runtime & Dynamic Intelligence TERMINÉ, VALIDÉ, MERGÉ develop
M27 — Team / Hosted Mode             ACTIF, CANDIDAT QUALIFICATION
```

**État livré sur `main` : C0→M20.**

`develop` contient le tree M21 localement qualifié ainsi que M22 à M26 validés et fusionnés. M21 reste administrativement ouvert uniquement pour S2/CI, explicitement gelé jusqu’en août 2026. M27 est implémenté sur `m27-team-hosted-mode` / draft PR #91 ; il n’est pas encore qualifié ni fusionné.

## M21 — Production Integrity & Surface Convergence

Issue : **#73**. Roadmap : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md).

```text
S1   governance + docs + runner local                 VALIDÉ
S2   CI recovery + readiness branch protection        EN PAUSE jusqu’en août 2026
S3   quality gates M19/M20                            VALIDÉ
S4   Maven module-boundary hardening                  VALIDÉ
S5   supply-chain + release hardening                 VALIDÉ
S6   IntelliJ parity M19/M20                          VALIDÉ
S7   advanced provider productionization              VALIDÉ
S8   semantic scale qualification                     VALIDÉ
S9   final production integrity gate                  VALIDÉ exact-head
```

Tree qualifié et intégration :

```text
M21 qualified tree : 60c1aba43e2d005991152cc4f3fe0b0dadef1c2d
develop merge      : 4222706502c54e10f0bf0400a18360fb99e6208c
M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS
```

M21-S8 a conclu :

```text
M21 S8 STANDARD MEASUREMENT status=PASS decision=KEEP_CURRENT_M20_BACKEND
```

Aucun Lucene/HNSW/vector database n'était requis. Cette décision reste la base de M23 et des évolutions futures : un ANN ne peut être introduit qu'après une nouvelle mesure démontrant un bottleneck.

## M22 — Advanced Provider Intelligence

**TERMINÉ, VALIDÉ exact-head, MERGÉ dans `develop`.**

```text
Issue          : #76 CLOSED / completed
PR             : #77 MERGED
Qualified HEAD : 75d6169be6d46d4e60ca19e781ff61704ca1613c
Merge develop  : 37a3c904fd92c25b343344a26991531c75ebc4b6
```

Contrat livré :

- provider Java `minos-java-source-v1` ;
- source units confinées au snapshot actif ;
- CFG, def-use local, argument/return flow si cible unique `(simpleName, arity)` ;
- security taxonomy explicite et taint statique borné ;
- arêtes `DERIVED` avec preuve, provenance et confiance ;
- `FACTUAL`, `DERIVED`, `HEURISTIC` restent distincts ;
- aucun runtime/exhaustiveness claim dérivé d'un chemin statique ;
- TypeScript/Python non promus sans qualification équivalente ;
- runtime Windows qualifié avec `jdk.compiler`.

Roadmap : [`roadmap/M22_EXECUTION.md`](roadmap/M22_EXECUTION.md). Décision : [ADR-0030](adr/0030-java-ast-reference-provider-with-explicit-capability-limits.md).

## M23 — Semantic Retrieval 2.0

**TERMINÉ, VALIDÉ exact-head, MERGÉ dans `develop` — 9/9.**

```text
Issue          : #78 CLOSED / completed
PR             : #79 MERGED
Branch         : m23-semantic-retrieval-2
Base M22       : develop @ 37a3c904fd92c25b343344a26991531c75ebc4b6
Qualified HEAD : 7a5fe2b96480a21e063b8ffa537009e5bdf99bc0
Merge develop  : ffe12d95ac46c25026661dca51949fb0d39626b4
```

M23 conserve les snapshots structurés comme autorité et ajoute une voie sémantique learned locale :

```text
EmbeddingProvider
  ├─ minos-local-hash     référence déterministe, non learned
  └─ minos-local-ollama   learned, opt-in, loopback-only

SemanticVectorStore
  ├─ lecture index-v1.bin float64
  └─ écriture index-v2.bin float32

SemanticSearchService
  ├─ cosine exact
  ├─ query-vector LRU <= 256
  └─ ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND
```

Profil canonique qualifié :

```text
provider   ollama
model      embeddinggemma
dimensions 768
endpoint   http://127.0.0.1:11434/api/embed
```

Qualité learned finale :

```text
Recall@3 = 1.000000
MRR      = 0.944444
nDCG@3   = 0.965936
```

Preuve finale :

```text
M23 SEMANTIC RETRIEVAL CONSISTENCY SUCCESS
M23 LEARNED SEMANTIC QUALITY SUCCESS
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
M22 ADVANCED PROVIDER CONSISTENCY SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS
Validated HEAD: 7a5fe2b96480a21e063b8ffa537009e5bdf99bc0
```

JaCoCo : **13/13 scopes PASS**, dont `semantic-learned-provider` line=0.903226 / branch=0.679245.

Release Windows `0.2.0-m23` : distribution, ZIP, setup, SBOM, notices, checksums et smoke install/uninstall PASS.

IntelliJ : parité M19/M20 PASS et Plugin Verifier compatible sur les deux IDE cibles qualifiés.

Roadmap : [`roadmap/M23_EXECUTION.md`](roadmap/M23_EXECUTION.md). Décision : [ADR-0031](adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md). Guide : [`developer/semantic-retrieval-2.md`](developer/semantic-retrieval-2.md).

## M24 — Polyglot Expansion

**TERMINÉ, VALIDÉ exact-head Windows + Linux, MERGÉ dans `develop`.**

```text
Base develop   : 8dbe34cb9e524acb62becda4faa263d74b90b9a9
Branch         : m24-polyglot-expansion
Issue          : #81 CLOSED / completed
PR             : #82 MERGED
Qualified HEAD : 927f57768a79af162e2cdc765d0f54d274cbe02e
Merge develop  : 2a499a7aedd71b7cf4c5fb8339c5b914e3dd46fa
ADR            : ADR-0032
```

Périmètre minimal évalué :

```text
C / C++  -> scip-clang 0.4.0
C#       -> scip-dotnet 0.2.14 (.NET SDK 10+)
Go       -> scip-go 0.2.7
Rust     -> rust-analyzer scip 2026-07-27 / v0.3.2989 / commit 12c3381
```

Dispositions finales et plateformes prouvées :

| Provider | Disposition | Plateformes qualifiées | Preuve e2e |
|---|---|---|---|
| `scip-clang` 0.4.0 | `QUALIFIED_WITH_CONSTRAINTS` | Linux x86_64 | Linux `PASS` ; Windows hors contrat M24 |
| `scip-dotnet` 0.2.14 | `QUALIFIED_WITH_CONSTRAINTS` | Linux x86_64 | Linux `PASS` ; Windows 10 Pro 22H2 `BLOCKED/NOT_RUN` |
| `scip-go` 0.2.7 | `QUALIFIED_WITH_CONSTRAINTS` | Windows x86_64, Linux x86_64 | Windows + Linux `PASS` |
| `rust-analyzer-scip` 0.3.2989 / `12c3381` | `QUALIFIED_WITH_CONSTRAINTS` | Windows x86_64, Linux x86_64 | Windows + Linux `PASS` |

Les capabilities restent explicites et exhaustives ; stable identity/provenance sont des gates ; CFG/def-use/data-flow/security ne sont jamais extrapolés depuis symboles/références.

Le runtime C/C++ M24 est explicitement limité à Linux x86_64 pour la qualification ; Windows expose la limitation au lieu d'un faux PASS. C# et Go utilisent des installations locales sous `MINOS_HOME/tools`. Rust reste operator-managed et MINOS ne modifie pas `rustup`/la toolchain.

Preuves exact-head enregistrées :

```text
M24 FINAL POLYGLOT EXPANSION VALIDATION SUCCESS
Validated HEAD: 927f57768a79af162e2cdc765d0f54d274cbe02e

M24 LINUX POLYGLOT EXPANSION VALIDATION SUCCESS
Validated HEAD: 927f57768a79af162e2cdc765d0f54d274cbe02e
```

Les JSON d'évidence enregistrent `e2e: PASS` pour `scip-go` et `rust-analyzer-scip` sous Windows, et pour les quatre providers sous Linux. Les deux worktrees étaient propres et le diff `.github/workflows` était vide.

Roadmap : [`roadmap/M24_EXECUTION.md`](roadmap/M24_EXECUTION.md). Décision : [ADR-0032](adr/0032-evidence-gated-polyglot-scip-providers.md). Guides : [`user/polyglot-providers.md`](user/polyglot-providers.md) et [`developer/polyglot-providers.md`](developer/polyglot-providers.md).

## M25 — Remote & Distributed Indexing

**TERMINÉ, VALIDÉ EXACT-HEAD WINDOWS + LINUX ET FUSIONNÉ DANS `develop` — 9/9.**

```text
Base           : develop @ b17631de59871848351a4139b12be6e0354989bc
Branch         : m25-remote-distributed-indexing
Issue          : #84 CLOSED / completed
PR             : #85 MERGED
Qualified HEAD : fc395d189cf7fc5a0e06130210a3dc763fc48637
Merge develop  : 1a82f18115184606cbc13a9070b7cc78643ebb35
ADR            : ADR-0033
```

Contrat qualifié : GitHub.com/GitLab.com HTTPS uniquement, ref + commit SHA-1 complet, cache source borné, credential indirect et non sérialisé, worker provider-neutral en workspace éphémère, politique réseau obligatoire et `DENY` fail-closed, bundle `minos-distributed-artifact-v1` strict avec SHA-256/provenance, cache artefact borné, puis staging/promotion atomique existants.

| Surface | Disposition finale M25 | Preuve / limite |
|---|---|---|
| GitHub.com HTTPS | `QUALIFIED_WITH_CONSTRAINTS` | dépôt privé, révision exacte et cache MISS→HIT exercés sous Windows x86_64 et Linux x86_64 |
| GitLab.com HTTPS | `QUALIFIED_WITH_CONSTRAINTS` | dépôt public, révision exacte et cache MISS→HIT exercés sous Windows x86_64 et Linux x86_64 ; credential privé contract-tested, pas de preuve live privée |
| worker natif local | `QUALIFIED_WITH_CONSTRAINTS` | `PROCESS_EPHEMERAL_WORKSPACE` + `ALLOW` sous Windows/Linux ; `DENY` = `BLOCKED/NOT_RUN`, refus fail-closed faute d’isolation réseau OS |
| `minos-distributed-artifact-v1` | `QUALIFIED_WITH_CONSTRAINTS` | bundle strict, borné, SHA-256/provenance vérifiés sous Windows/Linux |
| caches source et artefact | `QUALIFIED_WITH_CONSTRAINTS` | caches bornés, reconstructibles, corruptions/écarts rejetés |

Les runners ont produit sur le même HEAD :

```text
M25 FINAL REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS
Validated HEAD: fc395d189cf7fc5a0e06130210a3dc763fc48637

M25 LINUX REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS
Validated HEAD: fc395d189cf7fc5a0e06130210a3dc763fc48637
```

Roadmap : [`roadmap/M25_EXECUTION.md`](roadmap/M25_EXECUTION.md). Décision : [ADR-0033](adr/0033-immutable-remote-revisions-and-verified-worker-artifacts.md). Guides : [`user/remote-indexing.md`](user/remote-indexing.md) et [`developer/remote-distributed-indexing.md`](developer/remote-distributed-indexing.md).

## M26 — Runtime & Dynamic Intelligence

**TERMINÉ, VALIDÉ EXACT-HEAD WINDOWS + LINUX ET FUSIONNÉ DANS `develop` — 9/9.**

```text
Base           : develop @ e37cf39fcf4f7e417c618fa0b16590100c1e0b91
Branch         : m26-runtime-dynamic-intelligence
Issue          : #87 CLOSED / completed
PR             : #88 MERGED
Qualified HEAD : bf702990125a485646b9b31817c7787086a1dbb3
Merge develop  : 9b6395ce9bcf6a7fe942d1f6c687a8ba97cbceef
ADR            : ADR-0034
```

Disposition finale : import strict, corrélation statique↔runtime, couverture/hot paths observés, store local runtime, CLI runtime et MCP runtime sont `QUALIFIED_WITH_CONSTRAINTS`. Le contrat reste `minos-runtime-observation-v1`, `PARTIAL` seulement, avec alignement UUID projet + snapshot actif exact, corrélation `RESOLVED/AMBIGUOUS/UNRESOLVED`, store immuable/atomique/checksum-vérifié/borné, import CLI explicite et trois tools MCP read-only.

Tous les résultats externes portent `OBSERVED_PARTIAL` et `exhaustive: false`. L’absence d’une observation ne prouve jamais la non-exécution ; les traces ne mutent pas le snapshot structuré et ne promeuvent aucune capability provider.

Les runners ont produit sur le même HEAD :

```text
M26 FINAL RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: bf702990125a485646b9b31817c7787086a1dbb3

M26 LINUX RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: bf702990125a485646b9b31817c7787086a1dbb3
```

Roadmap : [`roadmap/M26_EXECUTION.md`](roadmap/M26_EXECUTION.md). Décision : [ADR-0034](adr/0034-partial-runtime-observations-with-explicit-static-correlation.md). Guides : [`user/runtime-intelligence.md`](user/runtime-intelligence.md) et [`developer/runtime-dynamic-intelligence.md`](developer/runtime-dynamic-intelligence.md).

## M27 — Team / Hosted Mode

**ACTIF — IMPLÉMENTATION CANDIDATE ; QUALIFICATION EXACT-HEAD WINDOWS + LINUX EN ATTENTE — 8/9.**

```text
Base           : develop @ 5db06f2a778b60b318ae6d83ad76928c24672810
Branch         : m27-team-hosted-mode
Issue          : #90 OPEN
PR             : #91 OPEN / DRAFT
Qualified HEAD : PENDING
Merge develop  : PENDING
ADR            : ADR-0035
```

Le contrôle tenant est opt-in et local mode reste le défaut. Le candidat implémente identité HMAC de référence, RBAC, shared workspaces liés au snapshot actif exact, store AES-256-GCM alimenté par clés externes, rotation, audit HMAC chaîné, rétention explicite, CLI/API et cinq tools MCP read-only. Il ne revendique ni SaaS opéré, ni IdP/KMS, ni transport réseau/TLS.

Roadmap : [`roadmap/M27_EXECUTION.md`](roadmap/M27_EXECUTION.md). Décision : [ADR-0035](adr/0035-opt-in-tenant-control-plane-with-external-keys.md). Guides : [`user/team-hosted-mode.md`](user/team-hosted-mode.md) et [`developer/team-hosted-mode.md`](developer/team-hosted-mode.md).

## Prochaine étape

M27 doit encore obtenir un double PASS exact-head local, puis être promu selon la gouvernance de la PR #91. Aucun M28 n’est défini dans la roadmap courante.

## Gouvernance juillet 2026

M21-S2/CI reste **strictement en pause jusqu’en août 2026**. Les qualifications M22 à M27 de juillet sont locales ; aucun workflow GitHub Actions ne fait partie de leur preuve de promotion.

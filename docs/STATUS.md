# État courant — MINOS

Dernière mise à jour documentaire : **28 juillet 2026 — réconciliation post-merge M23**

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
M24 — Polyglot Expansion             PLANIFIÉ
M25 — Remote & Distributed Indexing  PLANIFIÉ
M26 — Runtime & Dynamic Intelligence PLANIFIÉ
M27 — Team / Hosted Mode             PLANIFIÉ
```

**État livré sur `main` : C0→M20.**

`develop` contient le tree M21 localement qualifié ainsi que M22 et M23 validés et fusionnés. M21 reste administrativement ouvert uniquement pour S2/CI, explicitement gelé jusqu’en août 2026. M24 est le prochain jalon fonctionnel planifié.

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

## Prochaine étape

M24 — Polyglot Expansion reste **PLANIFIÉ**. Son démarrage exige sa propre roadmap opérationnelle, ses critères de sortie mesurables, les ADR nécessaires et une qualification reproductible sur un SHA exact.

## Gouvernance juillet 2026

M21-S2/CI reste **strictement en pause jusqu’en août 2026**. Les qualifications M22/M23 de juillet sont locales ; aucun workflow GitHub Actions ne fait partie de leur preuve de promotion.

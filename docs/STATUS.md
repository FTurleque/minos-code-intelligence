# État courant — MINOS

Dernière mise à jour documentaire : **28 juillet 2026**

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
M23 — Semantic Retrieval 2.0         EN COURS — 9/9 implémenté, qualification exact-head en attente
```

**État livré sur `main` : C0→M20.**

`develop` contient le tree M21 localement qualifié et M22 validé/mergé. M21 reste administrativement ouvert uniquement pour S2/CI, explicitement gelé jusqu’en août 2026. M23 est le jalon fonctionnel actif sur `m23-semantic-retrieval-2`.

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

Gates structurants conservés :

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS
M21 JACOCO GATE SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS
M21-S8 SEMANTIC SCALE VALIDATION SUCCESS
```

M21-S8 a mesuré le backend exact et conclu :

```text
M21 S8 STANDARD MEASUREMENT status=PASS decision=KEEP_CURRENT_M20_BACKEND
```

Aucun Lucene/HNSW/vector database n'était requis. Cette décision reste la base de M23 : un ANN ne peut être introduit qu'après une nouvelle mesure démontrant un bottleneck.

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

Ground truth bloquante :

```text
CONTROL_FLOW   precision=1.0 recall=1.0
DEF_USE        precision=1.0 recall=1.0
ARGUMENT_FLOW  precision=1.0 recall=1.0
RETURN_FLOW    precision=1.0 recall=1.0
TAINT_FLOW     precision=1.0 recall=1.0
```

Verdict final :

```text
M22 ADVANCED PROVIDER CONSISTENCY SUCCESS
M22 PACKAGED JDK.COMPILER RUNTIME SUCCESS
M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: 75d6169be6d46d4e60ca19e781ff61704ca1613c
```

Roadmap : [`roadmap/M22_EXECUTION.md`](roadmap/M22_EXECUTION.md). Décision : [ADR-0030](adr/0030-java-ast-reference-provider-with-explicit-capability-limits.md).

## M23 — Semantic Retrieval 2.0

**EN COURS — 9/9 IMPLÉMENTÉS ; qualification locale exact-head en attente.**

```text
Issue  : #78 OPEN
Branch : m23-semantic-retrieval-2
Base   : develop @ 37a3c904fd92c25b343344a26991531c75ebc4b6
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

Le provider learned exige un modèle et des dimensions explicites. MINOS ne télécharge pas de modèle et refuse les endpoints non-loopback.

Qualité learned bloquante sur le corpus contrôlé M23 :

```text
Recall@3 >= 0.75
MRR      >= 0.70
nDCG@3   >= 0.72
```

Le gate appelle le **modèle local réellement configuré** ; absence de modèle, endpoint inaccessible, dimensions incorrectes ou métriques insuffisantes => FAIL.

Diagnostics importants :

```text
LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL
LOCAL_LEARNED_EMBEDDING_LOOPBACK_ONLY
LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC
SEMANTIC_RESULTS_REMAIN_HEURISTIC
VECTOR_SEARCH_LINEAR_SCAN
ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND
SEMANTIC_QUERY_VECTOR_CACHE_BOUNDED_256
```

Le 13e scope JaCoCo `semantic-learned-provider` s'ajoute sans abaisser les seuils antérieurs.

Roadmap : [`roadmap/M23_EXECUTION.md`](roadmap/M23_EXECUTION.md). Décision : [ADR-0031](adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md). Guide : [`developer/semantic-retrieval-2.md`](developer/semantic-retrieval-2.md).

Runner final :

```powershell
.\scripts\m23\run-final.ps1 -ExpectedHead <sha>
```

Verdict final attendu après qualification réelle :

```text
M23 SEMANTIC RETRIEVAL CONSISTENCY SUCCESS
M23 LEARNED SEMANTIC QUALITY SUCCESS
M21 JACOCO GATE SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS
Validated HEAD: <sha>
```

## Gouvernance juillet 2026

M21-S2/CI reste **strictement en pause jusqu’en août 2026**. Les qualifications M22/M23 de juillet sont locales ; aucun workflow GitHub Actions ne fait partie de leur preuve de promotion.

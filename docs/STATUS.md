# État courant — MINOS

Dernière mise à jour documentaire : **28 juillet 2026**

Ce fichier distingue l'état **livré sur `main`** du jalon de consolidation actuellement en cours. Les preuves historiques restent dans [`history/milestones/`](history/milestones/) et les décisions durables dans [`adr/`](adr/README.md).

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
M21 — Production Integrity           EN COURS — S9 final local en qualification
```

**État produit livré : C0→M20.** M21 reste sur `m21-production-integrity` et n'est pas encore livré sur `main`.

## M21 — Production Integrity & Surface Convergence

```text
S1   governance + docs + runner local                 VALIDÉ — b4403921bfe0e2a7fe5eef9380a122982f275e0e
S2   CI recovery + readiness branch protection        EN PAUSE jusqu’en août 2026 — aucun run CI
S3   quality gates M19/M20                            VALIDÉ — 27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94
S4   Maven module-boundary hardening                  VALIDÉ — 0699d06d6138dd77008b8ea31578a334468eec75
S5   supply-chain + release hardening                 VALIDÉ — bcc44ea5e7a5c354c1df25bb7d295ee57347629c
S6   IntelliJ parity M19/M20                          VALIDÉ — 8dff78af7cfbdab1c1d056e3b46b0fd9e5c75ee6
S7   advanced provider productionization              VALIDÉ — 57243384286ed623de2d9499c9ae6729f77f6845
S8   semantic scale qualification                     VALIDÉ — a668f0a09da08515396903fbe887ed9e70125201
S9   final production integrity gate                  EN COURS — local-only, exact-head
```

Issue : **#73**. Roadmap opérationnelle : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md).

### S1 — Governance

```text
Maven reactor: 13/13 SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

### S3 — Quality gates

Onze scopes JaCoCo sont bloquants : cinq scopes historiques et six scopes M19/M20.

```text
program-graph-analysis
advanced-impact-security
semantic-vector-store
semantic-hybrid-retrieval
advanced-public-api
m19-m20-mcp-catalogue
M21 JACOCO GATE SUCCESS
Validated HEAD: 27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94
```

### S4 — Maven boundaries

Les 12 modules compilent leur arbre `src/main/java` naturel ; les anciens filtres compiler d'ownership ont été supprimés.

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS
Validated HEAD: 0699d06d6138dd77008b8ea31578a334468eec75
```

### S5 — Supply-chain & release

```text
CycloneDX 1.6
M21 THIRD-PARTY NOTICES SUCCESS
M21 RELEASE MANIFEST SUCCESS
M21 SUPPLY-CHAIN EVIDENCE SUCCESS
MINOS Windows distribution SUCCESS
MINOS Windows setup SUCCESS
MINOS Windows release VALIDATION SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
Validated HEAD: bcc44ea5e7a5c354c1df25bb7d295ee57347629c
```

La signature Authenticode reste explicite et optionnelle tant qu'aucun certificat n'est configuré ; `MINOS_REQUIRE_SIGNED_RELEASE=1` la rend bloquante.

### S6 — IntelliJ parity

Le plugin reste un client externe Java 21 du moteur Java 24 via `minos-ide` v1. Il ne réimplémente aucune intelligence M19/M20.

```text
program-graph
impact-v2
security-paths
semantic-index-status
semantic-index-sync
semantic-search
hybrid-search
hybrid-context
```

```text
M21 INTELLIJ PARITY CONSISTENCY SUCCESS (capabilities=8, actions=8, ideBranch=261)
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
Validated HEAD: 8dff78af7cfbdab1c1d056e3b46b0fd9e5c75ee6
```

### S7 — Advanced provider productionization

`FileProgramGraphProvider` charge le sidecar local `.minos/program-graph-v1` uniquement lorsqu'il est aligné au snapshot actif. Un sidecar stale expose `ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT` et ne contribue aucune capability. `CALL_GRAPH + LOCAL_DATA_FLOW` ne prouve jamais implicitement l'interprocédural.

```text
M21 ADVANCED PROVIDER CONSISTENCY SUCCESS (capabilities=4, nodes=12, edges=7)
M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS
Validated HEAD: 57243384286ed623de2d9499c9ae6729f77f6845
```

### S8 — Semantic scale qualification

S8 a appliqué la règle M16 **mesurer avant d'optimiser** sur le STANDARD déterministe :

```text
seed                 16000031
fichiers logiques       10 000
symboles               100 000
occurrences            500 000
relations              250 000
semantic documents     210 000
vector dimensions          384
```

La première baseline complète, sur `37cbe22e91993e8aea040621396d2abd7e00da44`, a retourné :

```text
M21 S8 STANDARD MEASUREMENT status=FAIL decision=OPTIMIZE_MEASURED_BOTTLENECK
peak heap ratio      0.8966
vector-load p95      2910.409 ms
semantic p95         8457.386 ms
hybrid search p95   49412.429 ms
hybrid context p95  48520.565 ms
```

Les optimisations autorisées par cette mesure conservent le format disque v1, le cosine exact, les stable keys, les poids hybrides, `VECTOR_SEARCH_LINEAR_SCAN`, les facts structurés autoritatifs et le signal sémantique `HEURISTIC` : vecteurs primitifs, norm pré-calculée, cache snapshot-scoped du store, top-K exact borné et corpus hybride snapshot-scoped.

Qualification exacte finale S8 sur `a668f0a09da08515396903fbe887ed9e70125201` :

```text
added=0 changed=3 removed=0 reused=209997 reuse=0.999986
peak heap ratio      0.3407
index bytes          717000165
RSS                  4745732096
vector-load p95      0.0625 ms
semantic p95         102.8875 ms
hybrid search p95    210.487 ms
hybrid context p95   188.5624 ms
M21 S8 STANDARD MEASUREMENT status=PASS decision=KEEP_CURRENT_M20_BACKEND
M21 S8 SEMANTIC SCALE DECISION SUCCESS
M21-S8 SEMANTIC SCALE VALIDATION SUCCESS
Validated HEAD: a668f0a09da08515396903fbe887ed9e70125201
```

Contrat de décision conservé :

```text
INVALID_MEASUREMENT
OPTIMIZE_MEASURED_BOTTLENECK
KEEP_CURRENT_M20_BACKEND
```

Aucun Lucene/HNSW/vector database n'a été nécessaire pour satisfaire le STANDARD. Voir [`developer/semantic-scale-qualification.md`](developer/semantic-scale-qualification.md).

### S9 — Final production integrity gate

S9 est **local-only en juillet 2026**. `scripts/m21/run-s9.ps1` rejoue, sur un même HEAD exact :

1. S7 advanced provider ;
2. S8 STANDARD avec `KEEP_CURRENT_M20_BACKEND` ;
3. S5 supply-chain + Windows packaging/install ;
4. S6 IntelliJ parity + Plugin Verifier ;
5. docs courantes ;
6. HEAD/worktree final.

Verdict attendu :

```text
M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS
Validated HEAD: <sha>
```

S2 reste explicitement hors de ce gate jusqu'en août 2026 : aucun déclenchement CI manuel et aucune modification de workflow.

## M20 — Semantic & Hybrid Code Intelligence

M20 ajoute une couche sémantique locale et optionnelle au-dessus des facts structurés MINOS.

```text
SemanticDocument SYMBOL / FILE / CHUNK + stableKey/checksum              ✅
EmbeddingProvider SPI optionnel + provider local-hash                    ✅
vector store local versionné, atomique et reconstruisible               ✅
recherche sémantique bornée, nature HEURISTIC                           ✅
ranking hybride LEXICAL + GRAPH + SEMANTIC                              ✅
contexte v2 borné documents/tokens                                      ✅
index sémantique incrémental + réutilisation des vecteurs               ✅
SemanticCodeIntelligenceApi v1 + 4 tools MCP                            ✅
NEXUS semantic signals v2, frontière de responsabilité préservée        ✅
```

Autorité : facts structurés = autoritatifs ; semantic score = `HEURISTIC` ; hybrid ranking = sélection dérivée ; ranking global multi-source = NEXUS.

Surfaces publiques courantes :

- CLI stable et protocole IDE `minos-ide` v1 ;
- API Java : `MinosApi` v1 + APIs additives Provider/Advanced/Semantic ;
- MCP STDIO read-only : **23 tools** ;
- NEXUS : export local versionné + signaux sémantiques v2 ;
- IntelliJ : plugin externe Java 21 ;
- installation PROD Windows : ZIP/setup avec runtime et preuves supply-chain.

## M19 → M15

- M19 : Program Graph provider-independent, CFG/data-flow/interproc/security bornés, Impact v2 ;
- M18 : plugin IntelliJ externe, navigation et surfaces MINOS ;
- M17 : Provider & Discovery Platform capability-honest ;
- M16 : mesures reproductibles et gouvernance des décisions de backend ;
- M15 : reactor Maven multi-module, persistance décomposée, JaCoCo et facts calculables.

## Frontières architecturales courantes

- MINOS possède les faits de Code Intelligence ;
- snapshots persistés = source de vérité ;
- scores sémantiques = heuristiques ;
- NEXUS possède ranking global, sélection et budget multi-source ;
- IntelliJ reste un client externe ;
- aucune capability provider absente n'est inventée ;
- impact potentiel ≠ preuve runtime exhaustive ;
- chemins sécurité = chemins statiques observés et bornés ;
- cross-repository exige une identité exacte et unique ;
- toute évolution backend reste gouvernée par des mesures reproductibles.

## Suite

```text
M21  Production Integrity & Surface Convergence   EN COURS — S9
M22  Advanced Provider Intelligence               PLANIFIÉ
M23  Semantic Retrieval 2.0                       PLANIFIÉ
M24  Polyglot Expansion                           PLANIFIÉ
M25  Remote & Distributed Indexing                PLANIFIÉ
M26  Runtime & Dynamic Intelligence               PLANIFIÉ
M27  Team / Hosted Mode                           PLANIFIÉ
```

M22→M27 restent des directions planifiées tant qu'aucune roadmap opérationnelle et qualification associée ne les engage.

## Documentation

- portail : [`README.md`](../README.md) ;
- roadmap : [`ROADMAP.md`](ROADMAP.md) ;
- exécution M21 : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md) ;
- utilisateur : [`user/README.md`](user/README.md) ;
- développeur : [`developer/README.md`](developer/README.md) ;
- qualité : [`developer/quality-gates.md`](developer/quality-gates.md) ;
- supply-chain : [`developer/supply-chain.md`](developer/supply-chain.md) ;
- advanced provider : [`developer/advanced-program-provider.md`](developer/advanced-program-provider.md) ;
- semantic scale : [`developer/semantic-scale-qualification.md`](developer/semantic-scale-qualification.md) ;
- facts générés : [`generated/product-facts.md`](generated/product-facts.md) ;
- décisions : [`adr/README.md`](adr/README.md) ;
- preuves historiques : [`history/milestones/README.md`](history/milestones/README.md).

## Source de vérité

`STATUS.md` décrit l'état livré et le jalon actif. `ROADMAP.md` décrit la progression produit. Les ADR portent les décisions durables. Les facts calculables restent générés depuis le code. Les rapports historiques peuvent conserver des états intermédiaires propres à leur date de validation.

# État courant — MINOS

Dernière mise à jour documentaire : **28 juillet 2026**

Ce fichier distingue l'état **livré sur `main`**, les gates de production encore ouverts sur M21 et le jalon fonctionnel actif sur `develop`. Les preuves historiques restent dans [`history/milestones/`](history/milestones/) et les décisions durables dans [`adr/`](adr/README.md).

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
M22 — Advanced Provider Intelligence EN COURS sur develop — Java first
```

**État produit livré sur `main` : C0→M20.** Le tree M21 localement qualifié a été intégré dans `develop` par PR #75 afin de servir de base à M22. M21 reste administrativement ouvert tant que S2/CI n'a pas repris en août 2026.

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
S9   final production integrity gate                  VALIDÉ exact-head — 60c1aba43e2d005991152cc4f3fe0b0dadef1c2d
```

Issue : **#73**. Roadmap opérationnelle : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md).

Le tree qualifié M21 a été intégré dans `develop` via PR #75 :

```text
M21 qualified tree : 60c1aba43e2d005991152cc4f3fe0b0dadef1c2d
develop merge      : 4222706502c54e10f0bf0400a18360fb99e6208c
file diff           : 0
```

### S1 — Governance

```text
Maven reactor: 13/13 SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

### S3 — Quality gates

Les onze scopes JaCoCo M21 étaient bloquants. M22 ajoute un douzième scope `java-advanced-provider` sans abaisser les seuils existants.

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

Le plugin reste un client externe Java 21 du moteur Java 24 via `minos-ide` v1. Il ne réimplémente aucune intelligence M19/M20/M22.

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

Le replay final exact-head S9 a été **VALIDÉ localement le 28 juillet 2026** sur `60c1aba43e2d005991152cc4f3fe0b0dadef1c2d` avec HEAD inchangé et worktree tracked propre.

```text
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
M21-S9 retained S8 decision: PASS / KEEP_CURRENT_M20_BACKEND
M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS
Validated HEAD: 60c1aba43e2d005991152cc4f3fe0b0dadef1c2d
```

S2 reste explicitement hors de S9 jusqu'en août 2026 : aucun déclenchement CI manuel et aucune modification de workflow. Le jalon M21 reste donc ouvert tant que S2 n'a pas repris.

## M22 — Advanced Provider Intelligence

Statut : **EN COURS sur `m22-advanced-provider-intelligence`**, base `develop @ 4222706502c54e10f0bf0400a18360fb99e6208c`.

Issue : **#76**. Roadmap opérationnelle : [`roadmap/M22_EXECUTION.md`](roadmap/M22_EXECUTION.md). Décision : [ADR-0030](adr/0030-java-ast-reference-provider-with-explicit-capability-limits.md).

M22 ajoute un provider Java local de référence `minos-java-source-v1`, basé sur l'API AST du compilateur JDK et composé avec les providers M19/M21 existants.

```text
S1  roadmap + provider contract                       IMPLÉMENTÉ — qualification finale en attente
S2  Java discovery + source confinement               IMPLÉMENTÉ — qualification finale en attente
S3  Java CFG                                          IMPLÉMENTÉ — qualification finale en attente
S4  Java local def-use                                IMPLÉMENTÉ — qualification finale en attente
S5  Java interprocedural argument/return              IMPLÉMENTÉ — qualification finale en attente
S6  Java security source/sink/sanitizer               IMPLÉMENTÉ — qualification finale en attente
S7  capability/provenance/fallback hardening          IMPLÉMENTÉ — qualification finale en attente
S8  controlled precision/recall ground truth          IMPLÉMENTÉ — qualification finale en attente
S9  public surfaces + exact-head final gate           IMPLÉMENTÉ — qualification finale en attente
```

Contrat principal :

- fichiers Java provenant uniquement du snapshot actif ;
- chemins confinés et analyse projet fail-closed ;
- AST sans prétendre une attribution de types/classpath complète ;
- CFG/def-use/interproc/security uniquement lorsque les facts correspondants sont produits ;
- arêtes avancées `DERIVED` avec confiance, preuve et provenance ;
- security taxonomy explicitement opt-in ;
- fixtures indépendantes avec gate `precision=1.0 recall=1.0` ;
- runtime Windows obligé d'embarquer `jdk.compiler` ;
- TypeScript/Python non promus par M22 sans qualification équivalente.

Guide : [`developer/java-advanced-provider.md`](developer/java-advanced-provider.md).

Runner final :

```powershell
.\scripts\m22\run-final.ps1 -ExpectedHead <sha>
```

Verdict attendu :

```text
M22 ADVANCED PROVIDER CONSISTENCY SUCCESS
M22 PACKAGED JDK.COMPILER RUNTIME SUCCESS
M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: <sha>
```

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
```

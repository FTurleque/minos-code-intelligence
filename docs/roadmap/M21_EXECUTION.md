# M21 — Production Integrity & Surface Convergence — exécution

Statut : **EN COURS — S1 VALIDÉ ; S2 EN PAUSE jusqu’en août 2026 ; S3 VALIDÉ ; S4 VALIDÉ ; S5 VALIDÉ ; S6 VALIDÉ ; S7 VALIDÉ ; S8 VALIDÉ ; S9 VALIDÉ.**

Issue : **#73 — M21 — Production Integrity & Surface Convergence**.

Branche : `m21-production-integrity`.

## Question produit

> MINOS peut-il devenir un produit continuellement qualifié, cohérent sur toutes ses surfaces et distribuable avec un niveau de confiance production, sans affaiblir ses invariants local-first, capability-honest et measurement-gated ?

M21 reste un jalon de consolidation post-M20. Il ne transforme pas une dette de CI, de documentation, de qualité ou de distribution en nouvelle fonctionnalité métier.

## Invariants non négociables

- les snapshots structurés restent la source d'autorité ;
- `FACTUAL`, `DERIVED` et `HEURISTIC` restent distincts ;
- aucune capacité provider absente n'est inventée ;
- le sémantique reste optionnel et non autoritatif ;
- NEXUS conserve ranking global, sélection finale et budget multi-source ;
- IntelliJ reste un client externe du moteur ;
- caches/indexes restent reconstruisibles ;
- aucune migration backend n'est autorisée sans mesure reproductible ;
- la qualification finale reste rattachée à un HEAD exact et à un worktree propre.

## Sous-incréments

| Étape | Fonction | État / gate |
|---|---|---|
| M21-S1 | Governance & authoritative consolidation | **VALIDÉ** sur `b4403921bfe0e2a7fe5eef9380a122982f275e0e` |
| M21-S2 | CI recovery & branch protection readiness | **EN PAUSE jusqu’en août 2026 — aucun run CI** |
| M21-S3 | Quality gates M19/M20 | **VALIDÉ** sur `27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94` |
| M21-S4 | Maven module-boundary hardening | **VALIDÉ** sur `0699d06d6138dd77008b8ea31578a334468eec75` |
| M21-S5 | Supply-chain & release hardening | **VALIDÉ** sur `bcc44ea5e7a5c354c1df25bb7d295ee57347629c` |
| M21-S6 | IntelliJ parity M19/M20 | **VALIDÉ** sur `8dff78af7cfbdab1c1d056e3b46b0fd9e5c75ee6` |
| M21-S7 | Advanced provider productionization | **VALIDÉ** sur `57243384286ed623de2d9499c9ae6729f77f6845` |
| M21-S8 | Semantic scale qualification | **VALIDÉ** sur `a668f0a09da08515396903fbe887ed9e70125201` |
| M21-S9 | Final production integrity gate | **VALIDÉ** localement sur `0ec50bedc8c49e45347309f406830089e8e84941` |

## M21-S1 — Governance & authoritative consolidation

Livrables : roadmap, README/STATUS réalignés, checker documentaire et `scripts/m21/run-local.ps1`.

```text
Maven reactor: 13/13 SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

## M21-S2 — CI recovery & branch protection readiness

Statut : **EN PAUSE jusqu’en août 2026 — aucun déclenchement GitHub Actions, aucune modification de workflow avant reprise explicite.**

L'incident #5 reste documenté. En juillet, aucun diagnostic CI, aucune modification `.github/workflows` et aucun déclenchement manuel ne font partie de M21-S9.

À la reprise en août : politique Actions, quotas/runners, permissions, workflows durables et checks bloquants avant merge.

## M21-S3 — Quality gates M19/M20

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

Les seuils ne sont pas abaissés par M21.

## M21-S4 — Maven module-boundary hardening

Les 12 modules enfants compilent désormais leur arbre `src/main/java` naturel. `scripts/architecture/check-module-boundaries.py` interdit les anciens filtres compiler d'ownership, les sources dupliquées et les packages incohérents.

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS
Validated HEAD: 0699d06d6138dd77008b8ea31578a334468eec75
```

## M21-S5 — Supply-chain & release hardening

Livrables : CycloneDX 1.6, notices tierces strictes, manifest SHA-256, ZIP/setup Windows, sidecars et helper Authenticode optionnel.

```text
M21 THIRD-PARTY NOTICES SUCCESS
M21 RELEASE MANIFEST SUCCESS
M21 SUPPLY-CHAIN EVIDENCE SUCCESS
MINOS Windows distribution SUCCESS
MINOS Windows setup SUCCESS
MINOS Windows release VALIDATION SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
Validated HEAD: bcc44ea5e7a5c354c1df25bb7d295ee57347629c
```

L'épinglage GitHub Actions reste S2 et donc hors scope juillet.

## M21-S6 — IntelliJ parity M19/M20

Architecture : IntelliJ Java 21 → protocole local JSON `minos-ide` v1 → MINOS Java 24. Le plugin ne contient pas de logique métier M19/M20.

Capabilities additives :

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

## M21-S7 — Advanced provider productionization

`FileProgramGraphProvider` charge `.minos/program-graph-v1` uniquement si le sidecar est aligné au snapshot actif. Un sidecar stale expose `ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT` et contribue zéro capability.

`CALL_GRAPH + LOCAL_DATA_FLOW` ne produit jamais implicitement `INTERPROCEDURAL_DATA_FLOW`; il faut un `ARGUMENT_FLOW` ou `RETURN_FLOW` explicite. La sécurité exige `TAINT_FLOW` et des nœuds source/sink.

```text
M21 ADVANCED PROVIDER CONSISTENCY SUCCESS (capabilities=4, nodes=12, edges=7)
M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS
Validated HEAD: 57243384286ed623de2d9499c9ae6729f77f6845
```

Documentation : [`../developer/advanced-program-provider.md`](../developer/advanced-program-provider.md).

## M21-S8 — Semantic scale qualification

Statut : **VALIDÉ le 28 juillet 2026** sur `a668f0a09da08515396903fbe887ed9e70125201`.

Dataset STANDARD :

```text
seed                 16000031
fichiers logiques       10 000
symboles               100 000
occurrences            500 000
relations              250 000
semantic documents     210 000
vector dimensions          384
```

La première mesure exploitable, sur `37cbe22e91993e8aea040621396d2abd7e00da44`, a produit :

```text
M21 S8 STANDARD MEASUREMENT status=FAIL decision=OPTIMIZE_MEASURED_BOTTLENECK
peak heap ratio      0.8966
vector-store p95     2910.409 ms
semantic p95         8457.386 ms
hybrid search p95   49412.429 ms
hybrid context p95  48520.565 ms
```

La mesure a autorisé uniquement des optimisations ciblées : représentation primitive des vecteurs, norm pré-calculée, cache snapshot-scoped du store, top-K cosine exact borné, réutilisation du corpus hybride et du degré de graphe. Le format disque v1, le cosine exact, `VECTOR_SEARCH_LINEAR_SCAN`, les poids de ranking et les natures d'information sont restés inchangés.

Qualification STANDARD finale :

```text
added=0
changed=3
removed=0
reused=209997
reuse=0.999986
peak heap ratio=0.3407
indexBytes=717000165
rssBytes=4745732096
vector-store-load p95=0.0625 ms
semantic-search p95=102.8875 ms
hybrid-search p95=210.487 ms
hybrid-context p95=188.5624 ms
M21 S8 STANDARD MEASUREMENT status=PASS decision=KEEP_CURRENT_M20_BACKEND
M21 S8 SEMANTIC SCALE DECISION SUCCESS
M21-S8 SEMANTIC SCALE VALIDATION SUCCESS
Validated HEAD: a668f0a09da08515396903fbe887ed9e70125201
```

Contrat de décision durable :

```text
INVALID_MEASUREMENT
OPTIMIZE_MEASURED_BOTTLENECK
KEEP_CURRENT_M20_BACKEND
```

Aucun backend Lucene/HNSW/RocksDB/SQLite/Qdrant/Milvus/Weaviate n'a été nécessaire.

Harness : `M21SemanticScaleProbe.java`, `run-s8-benchmark.ps1`, `check-s8-results.py`, `run-s8.ps1`.

Documentation : [`../developer/semantic-scale-qualification.md`](../developer/semantic-scale-qualification.md).

## M21-S9 — Final production integrity gate

Statut : **VALIDÉ localement le 28 juillet 2026** sur `0ec50bedc8c49e45347309f406830089e8e84941`.

`scripts/m21/run-s9.ps1` réutilise volontairement les runners déjà qualifiés plutôt que de créer des variantes raccourcies de leurs contrats. Cette redondance est assumée pour le gate final.

Le replay a enchaîné :

1. `run-s7.ps1` — core M21, advanced provider et fixture ground-truth ;
2. `run-s8.ps1` — même STANDARD et décision `KEEP_CURRENT_M20_BACKEND` ;
3. capture en mémoire de la décision S8 avant les `clean` suivants ;
4. `run-s5.ps1` — supply-chain, ZIP/setup Windows, checksums et install/uninstall ;
5. `run-s6.ps1` — parité IntelliJ, tests, build plugin et Plugin Verifier ;
6. checker documentaire ;
7. HEAD exact et worktree propre à la fin.

Preuves :

```text
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21-S9 retained S8 decision: PASS / KEEP_CURRENT_M20_BACKEND
M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS
Validated HEAD: 0ec50bedc8c49e45347309f406830089e8e84941
```

S9 ne lit, ne déclenche et ne modifie aucun workflow CI. S2 reste le seul sous-incrément ouvert et reprendra explicitement en août 2026 avant toute décision de merge/fermeture M21.

## Validation locale

```powershell
.\scripts\m21\run-local.ps1 -ExpectedHead <sha>
.\scripts\m21\run-s5.ps1 -ExpectedHead <sha>
.\scripts\m21\run-s6.ps1 -ExpectedHead <sha>
.\scripts\m21\run-s7.ps1 -ExpectedHead <sha>
.\scripts\m21\run-s8.ps1 -ExpectedHead <sha> -Repetitions 5
.\scripts\m21\run-s9.ps1 -ExpectedHead <sha> -SemanticRepetitions 5
```

## Source de vérité

- état livré : [`../STATUS.md`](../STATUS.md) ;
- roadmap produit : [`../ROADMAP.md`](../ROADMAP.md) ;
- facts calculables : [`../generated/product-facts.md`](../generated/product-facts.md) ;
- supply-chain : [`../developer/supply-chain.md`](../developer/supply-chain.md) ;
- guide IntelliJ : [`../user/intellij-plugin.md`](../user/intellij-plugin.md) ;
- advanced provider : [`../developer/advanced-program-provider.md`](../developer/advanced-program-provider.md) ;
- semantic scale : [`../developer/semantic-scale-qualification.md`](../developer/semantic-scale-qualification.md) ;
- issue de pilotage : #73.

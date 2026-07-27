# M21 — Production Integrity & Surface Convergence — exécution

Statut : **EN COURS — S1 VALIDÉ ; S2 EN PAUSE jusqu’en août 2026 ; S3 VALIDÉ ; S4 VALIDÉ ; S5 VALIDÉ ; S6 VALIDÉ ; S7 VALIDÉ ; S8 EN COURS ; S9 planifié.**

Issue : **#73 — M21 — Production Integrity & Surface Convergence**.

Branche : `m21-production-integrity`.

## Question produit

> MINOS peut-il devenir un produit continuellement qualifié, cohérent sur toutes ses surfaces et distribuable avec un niveau de confiance production, sans affaiblir ses invariants local-first, capability-honest et measurement-gated ?

M21 est volontairement un jalon de **consolidation post-M20**. Il ne doit pas masquer une dette de CI, de qualité, de documentation ou de distribution derrière de nouvelles fonctionnalités métier.

## Invariants non négociables

- les snapshots structurés restent la source d'autorité ;
- `FACTUAL`, `DERIVED` et `HEURISTIC` restent distincts ;
- aucune capacité provider absente n'est inventée ;
- le sémantique reste optionnel et non autoritatif ;
- NEXUS conserve le ranking global, la sélection finale et le budget multi-source ;
- le plugin IntelliJ reste un client externe du moteur ;
- les vues, caches et indexes restent reconstruisibles depuis les sources autoritatives ;
- aucune migration de backend n'est autorisée sans mesure reproductible ;
- la qualification finale reste rattachée à un HEAD exact et à un worktree propre.

## Sous-incréments

| Étape | Fonction | État / gate |
|---|---|---|
| M21-S1 | Governance & authoritative consolidation | **VALIDÉ** sur `b4403921bfe0e2a7fe5eef9380a122982f275e0e` |
| M21-S2 | CI recovery & branch protection readiness | **PAUSE jusqu’en août 2026 — aucun run CI** |
| M21-S3 | Quality gates M19/M20 | **VALIDÉ** sur `27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94` |
| M21-S4 | Maven module-boundary hardening | **VALIDÉ** sur `0699d06d6138dd77008b8ea31578a334468eec75` |
| M21-S5 | Supply-chain & release hardening | **VALIDÉ** sur `bcc44ea5e7a5c354c1df25bb7d295ee57347629c` |
| M21-S6 | IntelliJ parity M19/M20 | **VALIDÉ** sur `8dff78af7cfbdab1c1d056e3b46b0fd9e5c75ee6` |
| M21-S7 | Advanced provider productionization | **VALIDÉ** sur `57243384286ed623de2d9499c9ae6729f77f6845` |
| M21-S8 | Semantic scale qualification | **EN COURS — baseline STANDARD à mesurer** |
| M21-S9 | Final production integrity gate | planifié |

## M21-S1 — Governance & authoritative consolidation

Statut : **VALIDÉ le 27 juillet 2026.**

Livrables :

- issue #73 et branche `m21-production-integrity` ;
- roadmap M21 et trajectoire M22→M27 ;
- README/STATUS/guide utilisateur réalignés ;
- `scripts/docs/check-current-docs.py` ;
- `scripts/m21/run-local.ps1`.

Qualification :

```text
Maven reactor: 13/13 SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

## M21-S2 — CI recovery & branch protection readiness

Statut : **EN PAUSE jusqu’en août 2026 — aucun déclenchement GitHub Actions, aucune modification de workflow avant reprise explicite.**

L'incident #5 reste documenté. Le symptôme distant historique `steps=[]`, `logs_url=null`, `BlobNotFound` n'est pas traité en juillet et ne doit pas être imputé au code MINOS sans preuve exploitable.

À la reprise en août :

- vérifier politique Actions, quotas/runners, restrictions et permissions ;
- obtenir au moins un workflow exposant steps/logs/artifacts ;
- supprimer les jobs historiques milestone-specific ;
- converger vers des workflows durables `ci`, `quality`, `intellij`, `release` ;
- définir les checks bloquants avant merge.

## M21-S3 — Quality gates M19/M20

Statut : **VALIDÉ le 27 juillet 2026** sur `27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94`.

Les cinq scopes historiques M15 sont conservés et six scopes M19/M20 ont été ajoutés :

```text
program-graph-analysis
advanced-impact-security
semantic-vector-store
semantic-hybrid-retrieval
advanced-public-api
m19-m20-mcp-catalogue
```

Résultats qualifiés S3 :

```text
program-graph-analysis      90.46 % lignes / 57.86 % branches
advanced-impact-security    93.18 % lignes / 57.69 % branches
semantic-vector-store       94.23 % lignes / 61.54 % branches
semantic-hybrid-retrieval   89.42 % lignes / 64.69 % branches
advanced-public-api         70.59 % lignes / 55.26 % branches
m19-m20-mcp-catalogue       93.49 % lignes / 60.00 % branches
M21 JACOCO GATE SUCCESS
```

S7 ajoute `FileProgramGraphProvider` au scope `program-graph-analysis` ; les seuils ne sont pas abaissés.

## M21-S4 — Maven module-boundary hardening

Statut : **VALIDÉ le 27 juillet 2026** sur `0699d06d6138dd77008b8ea31578a334468eec75`.

Livrables :

1. suppression des allowlists/denylists de compilation dans les 12 modules enfants ;
2. compilation naturelle de chaque `src/main/java` ;
3. conservation des exclusions du `maven-shade-plugin`, qui relèvent du packaging ;
4. `scripts/architecture/check-module-boundaries.py` ;
5. adaptation des runners M19/M20 pour qu'ils vérifient les invariants métier sans dépendre de l'ancien mécanisme d'ownership.

Qualification :

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules=12, sources=263)
Maven reactor: 13/13 SUCCESS
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: 0699d06d6138dd77008b8ea31578a334468eec75
```

## M21-S5 — Supply-chain & release hardening

Statut : **VALIDÉ le 27 juillet 2026** sur `bcc44ea5e7a5c354c1df25bb7d295ee57347629c`.

S5 reste volontairement séparé de la CI en juillet. L'épinglage immuable des GitHub Actions reste affecté à S2 lors de sa reprise en août.

Livrables :

- CycloneDX Maven Plugin `2.9.2`, SBOM JSON agrégé CycloneDX 1.6 depuis la racine Maven ;
- scope test exclu du SBOM de distribution ;
- notices tierces strictes : aucune licence inventée ;
- manifest de release avec version, commit, taille et SHA-256 ;
- ZIP/setup embarquant les preuves supply-chain ;
- sidecars publiables avec checksums ;
- helper Authenticode optionnel ;
- `scripts/m21/run-s5.ps1`.

Qualification :

```text
CycloneDX 1.6: 31 components
M21 THIRD-PARTY NOTICES SUCCESS (components=19, unknownLicenses=0)
M21 RELEASE MANIFEST SUCCESS (files=370)
M21 SUPPLY-CHAIN EVIDENCE SUCCESS (components=19, unknownLicenses=0, files=370)
MINOS Windows distribution SUCCESS
MINOS Windows setup SUCCESS
MINOS Windows release VALIDATION SUCCESS
Authenticode setup status: NotSigned (required=False)
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
Validated HEAD: bcc44ea5e7a5c354c1df25bb7d295ee57347629c
```

La signature n'est pas simulée. Sans certificat, un candidat peut rester non signé ; `MINOS_REQUIRE_SIGNED_RELEASE=1` transforme une signature Authenticode valide en exigence bloquante.

## M21-S6 — IntelliJ parity M19/M20

Statut : **VALIDÉ le 27 juillet 2026** sur `8dff78af7cfbdab1c1d056e3b46b0fd9e5c75ee6`.

Architecture conservée :

```text
IntelliJ IDEA / Java 21
        │
        │ processus local + JSON / minos-ide v1
        ▼
MINOS Java 24 / MinosApplication
        ├── ProgramGraphService
        ├── AdvancedImpactService
        ├── SecurityAnalysisService
        ├── SemanticIndexService
        ├── SemanticSearchService
        ├── HybridSearchService
        └── HybridContextBuilder
```

Le protocole `minos-ide` reste en version `1`. Les huit capabilities additives sont :

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

Le client vérifie la capability avant chaque appel et ne réimplémente aucun métier M19/M20. L'ancien Impact M8 reste exposé comme baseline.

Qualification Windows exact-head :

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules=12, sources=264)
Maven reactor: 13/13 SUCCESS
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
M21 INTELLIJ PARITY CONSISTENCY SUCCESS (capabilities=8, actions=8, ideBranch=261)
Plugin com.minos.codeintelligence:0.2.0-SNAPSHOT against IU-261.26222.65: Compatible
Plugin com.minos.codeintelligence:0.2.0-SNAPSHOT against IU-261.22158.277: Compatible
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
Validated HEAD: 8dff78af7cfbdab1c1d056e3b46b0fd9e5c75ee6
```

## M21-S7 — Advanced provider productionization

Statut : **VALIDÉ le 27 juillet 2026** sur `57243384286ed623de2d9499c9ae6729f77f6845`.

M19 avait défini `ProgramGraphProvider` et le modèle avancé mais le runtime standard ne disposait pas encore d'un provider local capable d'injecter des facts CFG/def-use/interproc/security explicites. S7 productionise le sidecar local v1 prévu par l'architecture M19.

Architecture :

```text
snapshot structuré actif
    ├── relations CALLS / READS / WRITES
    │      ↓ RelationshipProgramGraphProvider
    │
    └── <project>/.minos/program-graph-v1/
           ├── metadata.properties
           ├── nodes.tsv
           └── edges.tsv
                   ↓ FileProgramGraphProvider

          ProgramGraphComposer
                   ↓
          ProgramGraph capability-honest
```

Invariants renforcés :

- `snapshotId` du sidecar doit correspondre exactement au snapshot actif ;
- un sidecar stale contribue zéro capability et expose `ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT` ;
- chaque capability déclarée doit être prouvée par les kinds correspondants ;
- une arête avancée sans capability correspondante est rejetée ;
- `CPG` reste composé par MINOS et n'est pas déclaré par le sidecar ;
- `CALL_GRAPH + LOCAL_DATA_FLOW` **ne produit plus implicitement** `INTERPROCEDURAL_DATA_FLOW` ;
- l'interprocédural exige `ARGUMENT_FLOW` ou `RETURN_FLOW` explicite ;
- la sécurité exige `TAINT_FLOW` et des nœuds `SOURCE`/`SINK` ;
- les sidecars sont exclus du fingerprint source via `.minos/` ;
- le cache du provider inclut le SHA-256 des trois fichiers ;
- le provider est ajouté au scope JaCoCo `program-graph-analysis`.

Vérité terrain versionnée :

```text
fixtures/m21/advanced-program-sidecar/project/.minos/program-graph-v1/
CONTROL_FLOW     2
DEF_USE          1
ARGUMENT_FLOW    1
RETURN_FLOW      1
TAINT_FLOW       2
SOURCE/SANITIZER/SINK présents
```

Qualification :

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules=12, sources=265)
Maven reactor: 13/13 SUCCESS
program-graph-analysis: line=0.886667 branch=0.621053 classes=14
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
M21 ADVANCED PROVIDER CONSISTENCY SUCCESS (capabilities=4, nodes=12, edges=7)
M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS
Validated HEAD: 57243384286ed623de2d9499c9ae6729f77f6845
```

Documentation : [`../developer/advanced-program-provider.md`](../developer/advanced-program-provider.md).

Cette étape ne prétend pas que SCIP fournit désormais CFG/def-use/taint. SCIP conserve son profil réel. S7 fournit un contrat opérationnel local pour un analyseur avancé explicite ; M22 reste le jalon d'intégration de providers d'analyse spécialisés supplémentaires.

## M21-S8 — Semantic scale qualification

Statut : **EN COURS — campagne STANDARD locale à mesurer avant toute optimisation.**

La référence reste la philosophie M16 : **mesurer avant d'industrialiser**.

Dataset :

```text
seed                 16000031
fichiers logiques       10 000
symboles               100 000
occurrences            500 000
relations              250 000
semantic documents     210 000
vector dimensions          384
```

La campagne mesure au minimum :

```text
semantic index build/rebuild
semantic incremental reuse
vector-store-load p50/p95/p99
semantic search p50/p95/p99
hybrid search p50/p95/p99
hybrid context p50/p95/p99
peak/retained heap
process RSS
vector store disk size
```

La mutation contrôlée change un symbole et sa ligne source. Exactement trois documents doivent être ré-embeddés (`SYMBOL`, `CHUNK`, `FILE`) ; les autres doivent être réutilisés.

Règle de décision :

```text
INVALID_MEASUREMENT
OPTIMIZE_MEASURED_BOTTLENECK
KEEP_CURRENT_M20_BACKEND
```

Le scan linéaire ou le format vectoriel ne seront remplacés que si les mesures démontrent un goulot produit. Aucune dépendance vectorielle/backend alternative n'est ajoutée avant cette preuve.

Harness :

```text
scripts/m21/M21SemanticScaleProbe.java
scripts/m21/run-s8-benchmark.ps1
scripts/m21/check-s8-results.py
scripts/m21/run-s8.ps1
```

Documentation : [`../developer/semantic-scale-qualification.md`](../developer/semantic-scale-qualification.md).

Gate local S8 :

```powershell
.\scripts\m21\run-s8.ps1 -ExpectedHead <sha> -Repetitions 5
```

Verdict de fermeture attendu :

```text
M21 S8 SEMANTIC SCALE DECISION SUCCESS
M21-S8 SEMANTIC SCALE VALIDATION SUCCESS
Validated HEAD: <sha>
```

## M21-S9 — Final production integrity gate

Le gate final devra vérifier au minimum :

1. worktree propre et HEAD exact ;
2. facts documentaires et docs courantes cohérents ;
3. Maven Java 24 `clean verify` ;
4. quality gates M21 ;
5. tests/plugin verifier IntelliJ ;
6. replays providers et surfaces publiques ;
7. campagne performance M19/M20 retenue ;
8. packaging Windows et preuves supply-chain retenues ;
9. HEAD inchangé à la fin de la qualification.

Verdict attendu :

```text
M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS
Validated HEAD: <sha>
```

## Validation locale

Gate général :

```powershell
.\scripts\m21\run-local.ps1 -ExpectedHead <sha>
```

Gate S5 :

```powershell
.\scripts\m21\run-s5.ps1 -ExpectedHead <sha>
```

Gate S6 :

```powershell
.\scripts\m21\run-s6.ps1 -ExpectedHead <sha>
```

Gate S7 :

```powershell
.\scripts\m21\run-s7.ps1 -ExpectedHead <sha>
```

Gate S8 :

```powershell
.\scripts\m21\run-s8.ps1 -ExpectedHead <sha> -Repetitions 5
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

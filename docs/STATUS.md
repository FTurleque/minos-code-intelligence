# État courant — MINOS

Dernière mise à jour documentaire : **27 juillet 2026**

Ce fichier distingue l'état **livré sur `main`** du jalon de consolidation actuellement en cours. Les preuves détaillées sont conservées dans [`history/milestones/`](history/milestones/) et dans les PR/issues de qualification ; les décisions durables sont indexées dans [`adr/`](adr/README.md).

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
M21 — Production Integrity           EN COURS — consolidation post-M20
```

**État produit livré : C0→M20.** M21 est ouvert sur la branche `m21-production-integrity` et n'est pas encore livré sur `main`.

## M21 — Production Integrity & Surface Convergence

Question produit :

> MINOS peut-il devenir un produit continuellement qualifié, cohérent sur toutes ses surfaces et distribuable avec un niveau de confiance production, sans affaiblir ses invariants local-first, capability-honest et measurement-gated ?

État courant M21 :

```text
S1   governance + docs + runner local                 VALIDÉ — b4403921bfe0e2a7fe5eef9380a122982f275e0e
S2   CI recovery + readiness branch protection        EN PAUSE jusqu’en août 2026 — aucun run CI
S3   quality gates M19/M20                            VALIDÉ — 27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94
S4   Maven module-boundary hardening                  VALIDÉ — 0699d06d6138dd77008b8ea31578a334468eec75
S5   supply-chain + release hardening                 VALIDÉ — bcc44ea5e7a5c354c1df25bb7d295ee57347629c
S6   IntelliJ parity M19/M20                          EN COURS — candidat local à qualifier
S7   advanced provider productionization              PLANIFIÉ
S8   semantic scale qualification                     PLANIFIÉ
S9   final production integrity gate                  PLANIFIÉ
```

Issue : **#73**. Roadmap opérationnelle : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md).

### Qualification S1

```text
Maven reactor: 13/13 SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M15 JACOCO GATE SUCCESS
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: b4403921bfe0e2a7fe5eef9380a122982f275e0e
```

### Qualification S3

```text
program-graph-analysis      90.46 % lignes / 57.86 % branches
advanced-impact-security    93.18 % lignes / 57.69 % branches
semantic-vector-store       94.23 % lignes / 61.54 % branches
semantic-hybrid-retrieval   89.42 % lignes / 64.69 % branches
advanced-public-api         70.59 % lignes / 55.26 % branches
m19-m20-mcp-catalogue       93.49 % lignes / 60.00 % branches
M21 JACOCO GATE SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: 27b4bafb35eadfdb9827b4d4cfccf7073b1e5e94
```

### Qualification S4

```text
M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules=12, sources=263)
Maven reactor: 13/13 SUCCESS
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
Validated HEAD: 0699d06d6138dd77008b8ea31578a334468eec75
```

S4 supprime les allowlists/denylists `maven-compiler-plugin` d'ownership devenues obsolètes après la relocation physique M15. Chaque module compile désormais son arbre `src/main/java` naturel et `scripts/architecture/check-module-boundaries.py` empêche la réintroduction de filtres compiler, sources dupliquées ou packages incohérents.

### Qualification S5

```text
M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools=23)
M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules=12, sources=263)
Maven reactor: 13/13 SUCCESS
M21 JACOCO GATE SUCCESS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
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

S5 fournit un SBOM CycloneDX 1.6 agrégé depuis la racine Maven, des notices tierces strictes sans licence inventée, un manifest SHA-256 de distribution, des sidecars de release et un helper Authenticode optionnel. Les workflows GitHub Actions restent hors scope jusqu'en août.

### Candidat S6

S6 conserve le plugin comme client externe Java 21 du moteur Java 24. Le protocole `minos-ide` v1 est étendu de façon additive et capability-negotiated avec huit surfaces :

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

L'ancien Impact M8 reste disponible comme baseline. Le plugin ne calcule aucun Program Graph, chemin de sécurité, embedding ou ranking : les nouvelles commandes `minos ide … --format json` appellent les services M19/M20 de `MinosApplication`. `scripts/intellij/check-m21-parity.py` verrouille cette frontière, et le Plugin Verifier cible la branche IntelliJ 2026.1 / build 261 sur ses releases stables résolues.

Toute modification après un SHA qualifié exige une nouvelle qualification exact-head avant promotion.

## M20 — Semantic & Hybrid Code Intelligence

M20 ajoute une couche sémantique **locale et optionnelle** au-dessus des facts structurés MINOS. Les embeddings restent un signal de ranking/rappel et ne deviennent jamais une preuve de relation de code.

Acquis :

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

Autorité :

```text
facts structurés MINOS   = autoritatifs
semantic vector score    = HEURISTIC
hybrid ranking           = sélection dérivée
NEXUS global ranking     = responsabilité NEXUS
```

Surfaces M20 :

- API Java : `SemanticCodeIntelligenceApi` v1 additive ;
- MCP : **23 tools read-only** au total ;
- nouveaux tools : `minos_semantic_index_status`, `minos_semantic_search`, `minos_hybrid_search`, `minos_hybrid_context` ;
- NEXUS : contrat de signaux sémantiques v2 sans transfert du ranking global, de la sélection finale ni du budget multi-source.

Qualification Windows exact-head :

```text
Validated HEAD: 8d882e67649667898d55f0be97982b2f217027ba
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
```

PR #72 mergée via `2d095dd2c9f0d362ee54a9840b2b3e1d217579c1`. Issue #71 fermée comme `completed`.

## M19 — Advanced Code Intelligence

M19 fournit un `ProgramGraph` provider-independent et capability-honest, call graph v2, CFG, data-flow/def-use, propagation interprocédurale bornée, CPG, Impact v2 et primitives de sécurité.

```text
Validated HEAD: 859138cbfdd4e0722a6366efd97fa62ad95c2443
M19 FINAL ADVANCED CODE INTELLIGENCE VALIDATION SUCCESS
```

PR #70 mergée via `3630ebd0f229e1bc028e92444bfa34c3e7609596`.

## M18 — MINOS for IntelliJ

M18 livre un plugin IntelliJ autonome Java 21 consommant MINOS Java 24 par protocole local JSON `minos-ide` v1, sans réimplémenter l'intelligence métier. M21-S6 étend désormais ce même protocole de façon additive vers les surfaces M19/M20.

```text
Validated HEAD: 0186146668c12027f44b55d0511a45e89e6dee61
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
```

## M17 → M15

- M17 transforme discovery et providers en plateforme d'extensions explicites avec profils de capacité et conformance kit ;
- M16 impose une campagne reproductible de performance et gouverne le backend par mesures ;
- M15 fournit le reactor Maven multi-module, `MinosApplication`, le découplage MCP, la persistance décomposée, JaCoCo et les facts documentaires calculables.

## Contrats publics courants

- CLI : stable, codes de sortie `0/1/2`, diagnostics provider et protocole IDE `minos-ide` v1 ;
- API Java : `MinosApi` v1 stable + `ProviderPlatformApi` v1 + `AdvancedCodeIntelligenceApi` v1 + `SemanticCodeIntelligenceApi` v1 additives ;
- MCP : STDIO read-only, **23 tools** ;
- NEXUS : export local versionné + signaux sémantiques v2, responsabilité globale NEXUS préservée ;
- IntelliJ : plugin optionnel Java 21 consommant le moteur via protocole externe ;
- installation PROD Windows : setup.exe + ZIP versionnés, runtime Java embarqué, doctor, MCP natif et preuves supply-chain embarquées ;
- Docker MCP : mode durci optionnel.

## Frontières architecturales courantes

- MINOS reste propriétaire des faits de Code Intelligence ;
- les snapshots persistés restent la source de vérité ;
- les scores sémantiques restent heuristiques ;
- NEXUS reste propriétaire du ranking global, de la sélection et du budget de contexte multi-source ;
- le plugin IntelliJ reste un client externe ;
- les capacités provider et graphes absents ne sont jamais inventés ;
- discovery et support runtime restent des faits distincts ;
- l'analyse d'impact reste potentielle, jamais une preuve runtime exhaustive ;
- Impact v2 conserve M8 comme baseline ;
- les chemins sécurité sont des chemins statiques observés et bornés ;
- une relation cross-repository exige une identité exacte et unique ;
- toute évolution de backend reste gouvernée par des mesures reproductibles M16/M21.

## Suite

```text
M21  Production Integrity & Surface Convergence   EN COURS
M22  Advanced Provider Intelligence               PLANIFIÉ
M23  Semantic Retrieval 2.0                       PLANIFIÉ
M24  Polyglot Expansion                           PLANIFIÉ
M25  Remote & Distributed Indexing                PLANIFIÉ
M26  Runtime & Dynamic Intelligence               PLANIFIÉ
M27  Team / Hosted Mode                           PLANIFIÉ
```

M22→M27 restent des directions planifiées. Elles ne deviennent des capacités engagées qu'après création de leur roadmap opérationnelle, critères mesurables et qualification associée.

## Documentation

- portail : [`README.md`](../README.md) ;
- roadmap : [`ROADMAP.md`](ROADMAP.md) ;
- exécution M21 : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md) ;
- utilisateur : [`user/README.md`](user/README.md) ;
- développeur : [`developer/README.md`](developer/README.md) ;
- qualité : [`developer/quality-gates.md`](developer/quality-gates.md) ;
- supply-chain : [`developer/supply-chain.md`](developer/supply-chain.md) ;
- facts générés : [`generated/product-facts.md`](generated/product-facts.md) ;
- décisions : [`adr/README.md`](adr/README.md) ;
- preuves historiques : [`history/milestones/README.md`](history/milestones/README.md).

## Source de vérité

`STATUS.md` décrit l'état livré et le jalon actif. `ROADMAP.md` décrit la progression produit. Les ADR décrivent les décisions durables. Les facts calculables restent générés depuis le code. Les rapports sous `history/milestones/` restent des archives et peuvent contenir des états intermédiaires propres à leur date de validation.

# M23 — Semantic Retrieval 2.0 — exécution

Statut : **TERMINÉ, VALIDÉ EXACT-HEAD ET FUSIONNÉ DANS `develop` — 9/9.**

```text
Issue          : #78 CLOSED / completed
PR             : #79 MERGED
Branche        : m23-semantic-retrieval-2
Base M22       : develop @ 37a3c904fd92c25b343344a26991531c75ebc4b6
Qualified HEAD : 7a5fe2b96480a21e063b8ffa537009e5bdf99bc0
Merge develop  : ffe12d95ac46c25026661dca51949fb0d39626b4
Date           : 28 juillet 2026
```

M21-S2/CI reste en pause jusqu’en août 2026. M23 n’a exécuté, modifié ni contourné aucun workflow GitHub Actions en juillet ; les preuves locales exact-head sont autoritatives.

## Question produit

> MINOS peut-il fournir un retrieval réellement sémantique de qualité production tout en restant local-first, optionnel, mesuré et non autoritatif ?

## Réponse architecturale livrée

M23 conserve l’architecture M20 et ajoute une voie **réellement learned, locale et mesurée** lorsqu’elle est explicitement activée :

```text
snapshot structuré actif (autoritatif)
        │
        ↓
SemanticDocumentFactory
        │
        ├── local-hash                  référence déterministe non learned
        │
        └── OllamaEmbeddingProvider     learned / loopback-only / opt-in
                  │
                  ↓
         SemanticVectorStore v2
         float32 + lecture v1
                  │
                  ↓
         exact cosine linear scan
         + query-vector LRU 256
                  │
                  ↓
       Semantic / Hybrid / Context
                  │
       API + MCP + IntelliJ + NEXUS
```

Les scores sémantiques restent `HEURISTIC`. Aucun voisinage vectoriel n’est promu en relation de code.

## Invariants qualifiés

- facts structurés et snapshots actifs restent l’autorité ;
- `SEMANTIC` reste `HEURISTIC` ;
- aucun provider sémantique n’est activé par défaut ;
- aucun téléchargement de modèle n’est effectué par MINOS ;
- le provider learned intégré refuse les endpoints non-loopback et les redirects ;
- le transport learned loopback contourne explicitement les proxies système ;
- modèle + dimensions sont explicites et font partie de l’identité d’index ;
- changement provider/modèle/dimensions => rebuild sûr ;
- qualité learned mesurée sur le modèle réellement configuré ;
- profil de promotion figé sur `embeddinggemma`, 768 dimensions ;
- absence de modèle ou endpoint indisponible => gate M23 FAIL ;
- format v2 reconstruisible et lecture v1 conservée ;
- cache de requêtes borné, process-local et jetable ;
- ANN désactivé conformément à `KEEP_CURRENT_M20_BACKEND` ;
- aucun seuil M20/M21/M22 abaissé ;
- exact-head et worktree propre vérifiés avant promotion.

## Sous-incréments

### M23-S1 — Roadmap + ADR + learned-provider contract ✅

Roadmap opérationnelle, issue #78, ADR-0031, distinction `local-hash` / learned model et gouvernance juillet 2026.

### M23-S2 — Local learned embedding provider ✅

`OllamaEmbeddingProvider` :

- provider id `minos-local-ollama` ;
- endpoint canonique `http://127.0.0.1:11434/api/embed` ;
- localhost, IPv4 loopback et IPv6 loopback uniquement ;
- faux hostnames loopback refusés ;
- `Proxy.NO_PROXY` ;
- redirects refusés ;
- timeout et réponse bornés ;
- exactement un vecteur accepté pour une entrée ;
- dimensions et valeurs finies vérifiées ;
- modèle explicite ;
- aucun téléchargement automatique.

Activation native :

```text
MINOS_SEMANTIC_PROVIDER=ollama
MINOS_SEMANTIC_MODEL=<model-local>
MINOS_SEMANTIC_DIMENSIONS=<dimensions>
MINOS_SEMANTIC_ENDPOINT=http://127.0.0.1:11434/api/embed
MINOS_SEMANTIC_TIMEOUT_SECONDS=30
```

### M23-S3 — Compact vector-store v2 ✅

- lecture `index-v1.bin` float64 ;
- écriture `index-v2.bin` float32 ;
- migration atomique ;
- provider/model/dimensions/snapshot conservés dans les métadonnées ;
- cache mémoire quantifié exactement comme le float32 persisté ;
- valeurs non représentables refusées.

### M23-S4 — Bounded semantic query/view caches ✅

`SemanticSearchService` conserve au maximum **256 query vectors** en mémoire, clé :

```text
providerId + modelId + dimensions + query
```

Cache LRU, synchronisé, process-local et jetable.

### M23-S5 — Learned-model quality qualification ✅

Corpus versionné :

```text
fixtures/m23/semantic-quality-v1.json
```

Seuils bloquants :

```text
Recall@3 >= 0.75
MRR      >= 0.70
nDCG@3   >= 0.72
```

Profil canonique qualifié :

```text
provider   ollama
model      embeddinggemma
dimensions 768
endpoint   http://127.0.0.1:11434/api/embed
```

Résultats finaux :

```text
Recall@3 = 1.000000
MRR      = 0.944444
nDCG@3   = 0.965936
```

### M23-S6 — Backend retrieval decision hardening ✅

Le scan cosine exact est conservé :

```text
VECTOR_SEARCH_LINEAR_SCAN
ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND
```

Aucun HNSW, Lucene, vector DB ou approximate search n’est introduit sans nouvelle mesure prouvant un bottleneck.

### M23-S7 — Public surfaces + diagnostics ✅

Surfaces conservées :

```text
Java API SemanticCodeIntelligenceApi v1
MCP minos_semantic_index_status / minos_semantic_search / minos_hybrid_search / minos_hybrid_context
IntelliJ minos-ide v1 semantic-* / hybrid-*
NEXUS semantic signals v2
```

Diagnostics learned :

```text
LOCAL_LEARNED_EMBEDDING_LOOPBACK_ONLY
LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC
SEMANTIC_RESULTS_REMAIN_HEURISTIC
VECTOR_SEARCH_LINEAR_SCAN
ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND
SEMANTIC_QUERY_VECTOR_CACHE_BOUNDED_256
```

### M23-S8 — Documentation & operations ✅

Guide : [`../developer/semantic-retrieval-2.md`](../developer/semantic-retrieval-2.md).

Documentation livrée pour la configuration locale, le modèle non téléchargé, la qualité spécifique au modèle, la migration v1→v2, le cache borné, le scan exact et le dépannage endpoint/modèle/dimensions.

### M23-S9 — Final exact-head qualification runner ✅

Runner Windows :

```powershell
.\scripts\m23\run-final.ps1 -ExpectedHead 7a5fe2b96480a21e063b8ffa537009e5bdf99bc0
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

Plugin IntelliJ : parité M19/M20 PASS et Plugin Verifier compatible sur les deux IDE cibles qualifiés.

## Promotion finale

M23 a été promu après succès du runner sur le HEAD exact final avec worktree propre.

```text
Qualified HEAD : 7a5fe2b96480a21e063b8ffa537009e5bdf99bc0
PR             : #79 MERGED
Merge develop  : ffe12d95ac46c25026661dca51949fb0d39626b4
Issue          : #78 CLOSED / completed
```

L’intégration cible `develop` est terminée. La promotion vers `main` reste indépendante et soumise à M21-S2/CI lorsqu’il reprendra en août 2026.

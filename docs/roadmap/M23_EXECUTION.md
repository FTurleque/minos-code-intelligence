# M23 — Semantic Retrieval 2.0 — exécution

Statut : **9/9 IMPLÉMENTÉS — qualification locale exact-head en attente.**

Issue : **#78 — M23 — Semantic Retrieval 2.0**.

Branche : `m23-semantic-retrieval-2`.

Base : `develop @ 37a3c904fd92c25b343344a26991531c75ebc4b6` (merge M22).

M21-S2/CI reste en pause jusqu’en août 2026. M23 n’exécute, ne modifie et ne contourne aucun workflow GitHub Actions en juillet.

## Question produit

> MINOS peut-il fournir un retrieval réellement sémantique de qualité production tout en restant local-first, optionnel, mesuré et non autoritatif ?

## Réponse architecturale M23

M23 conserve l’architecture M20 mais remplace la seule preuve de plomberie `local-hash` par une voie **réellement learned, locale et mesurée** lorsqu’elle est explicitement activée :

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

## Invariants non négociables

- facts structurés et snapshots actifs restent l’autorité ;
- `SEMANTIC` reste `HEURISTIC` ;
- aucun provider sémantique n’est activé par défaut ;
- aucun téléchargement de modèle n’est effectué par MINOS ;
- le provider learned intégré refuse tout endpoint non-loopback et les redirects ;
- le transport learned loopback contourne explicitement les proxies système ;
- modèle + dimensions sont explicites et font partie de l’identité d’index ;
- changement provider/modèle/dimensions => rebuild sûr ;
- qualité learned mesurée sur le modèle réellement configuré ;
- le profil de **promotion M23** est figé sur `embeddinggemma`, 768 dimensions ;
- absence de modèle ou endpoint indisponible => gate M23 FAIL ;
- format v2 est reconstruisible et lecture v1 conservée ;
- cache de requêtes borné, process-local et jetable ;
- ANN reste désactivé tant qu’une nouvelle mesure ne renverse pas `KEEP_CURRENT_M20_BACKEND` ;
- aucun seuil M20/M21/M22 n’est abaissé ;
- exact-head + worktree propre avant promotion.

## Sous-incréments

### M23-S1 — Roadmap + ADR + learned-provider contract ✅

- issue #78 ;
- roadmap opérationnelle M23 ;
- ADR-0031 ;
- distinction explicite `local-hash` / learned model ;
- M21-S2/CI hors scope jusqu’en août.

### M23-S2 — Local learned embedding provider ✅

`OllamaEmbeddingProvider` :

- provider id : `minos-local-ollama` ;
- endpoint par défaut : `http://127.0.0.1:11434/api/embed` ;
- `localhost`, IPv4 loopback et IPv6 loopback uniquement ;
- faux hostnames loopback (`127.example.com`) refusés ;
- `Proxy.NO_PROXY` impose un chemin direct vers le loopback ;
- aucun redirect ;
- timeout borné ;
- réponse bornée ;
- exactement un vecteur accepté pour une entrée ;
- dimensions vérifiées sur chaque embedding ;
- modèle explicite ;
- aucun `pull` ou téléchargement automatique.

Activation native :

```text
MINOS_SEMANTIC_PROVIDER=ollama
MINOS_SEMANTIC_MODEL=<model-local>
MINOS_SEMANTIC_DIMENSIONS=<dimensions>
MINOS_SEMANTIC_ENDPOINT=http://127.0.0.1:11434/api/embed   # optionnel
MINOS_SEMANTIC_TIMEOUT_SECONDS=30                          # optionnel
```

Les propriétés JVM `minos.semantic.provider/model/dimensions/endpoint/timeoutSeconds` restent disponibles pour l’intégration embarquée.

### M23-S3 — Compact vector-store v2 ✅

`FileSemanticVectorStore` :

- lit `index-v1.bin` float64 ;
- écrit `index-v2.bin` float32 ;
- supprime le v1 seulement après remplacement v2 réussi ;
- écrit toujours atomiquement ;
- conserve provider/model/dimensions/snapshot dans les métadonnées ;
- cache disque invalidé par chemin + taille + mtime ;
- cache mémoire quantifié exactement comme le float32 persisté ;
- les valeurs non représentables en float32 sont refusées.

L’index est une vue reconstruisible. Supprimer v1/v2 ne détruit aucun fact structuré.

### M23-S4 — Bounded semantic query/view caches ✅

`SemanticSearchService` conserve au maximum **256 query vectors** en mémoire, clé :

```text
providerId + modelId + dimensions + query
```

Le cache est LRU, process-local, synchronisé et jetable. Il ne change ni le score cosine exact ni la nature des résultats.

Le cache snapshot-scoped du store M21-S8 reste également actif.

### M23-S5 — Learned-model quality qualification ✅

Corpus versionné :

```text
fixtures/m23/semantic-quality-v1.json
```

Gate :

```text
scripts/m23/evaluate-learned-quality.py
```

Mesures moyennes bloquantes sur 9 requêtes / 12 documents :

```text
Recall@3 >= 0.75
MRR      >= 0.70
nDCG@3   >= 0.72
```

Le gate calcule aussi une baseline lexicale à titre de diagnostic. Il appelle le modèle learned local réellement configuré. Les proxies système sont explicitement désactivés (`ProxyHandler({})`) afin que la preuve loopback ne puisse pas transiter par un proxy. Aucun provider synthétique ne peut produire le PASS final M23.

Le **profil canonique de promotion** est :

```text
provider   ollama
model      embeddinggemma
dimensions 768
endpoint   http://127.0.0.1:11434/api/embed
```

Le produit reste capable d’utiliser d’autres modèles explicitement configurés ; seul le verdict de promotion M23 est figé sur ce profil pour rendre la preuve reproductible.

### M23-S6 — Backend retrieval decision hardening ✅

M21-S8 a qualifié le STANDARD avec :

```text
status=PASS
decision=KEEP_CURRENT_M20_BACKEND
```

M23 conserve donc le scan cosine exact et expose explicitement :

```text
VECTOR_SEARCH_LINEAR_SCAN
ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND
```

Aucun HNSW, Lucene, vector DB ou approximate search n’est ajouté sans nouvelle mesure prouvant un bottleneck.

### M23-S7 — Public surfaces + diagnostics ✅

Aucun nouveau contrat métier n’est dupliqué. Les surfaces existantes continuent d’exposer provider/model/dimensions et bénéficient du learned provider :

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
```

### M23-S8 — Documentation & operations ✅

- configuration learned locale ;
- modèle non téléchargé par MINOS ;
- qualité spécifique au modèle ;
- profil canonique de qualification ;
- migration v1→v2 ;
- cache borné ;
- exact scan retained ;
- troubleshooting endpoint/model/dimensions.

Guide : [`../developer/semantic-retrieval-2.md`](../developer/semantic-retrieval-2.md).

### M23-S9 — Final exact-head qualification runner ✅

Runner Windows :

```powershell
.\scripts\m23\run-final.ps1 -ExpectedHead <sha>
```

Variables obligatoires pour le replay de promotion :

```powershell
$env:MINOS_SEMANTIC_PROVIDER='ollama'
$env:MINOS_SEMANTIC_MODEL='embeddinggemma'
$env:MINOS_SEMANTIC_DIMENSIONS='768'
$env:MINOS_SEMANTIC_ENDPOINT='http://127.0.0.1:11434/api/embed'
```

Le runner refuse un autre provider/modèle/dimension pour le **verdict de promotion M23**. L’endpoint reste contrôlé séparément par le gate loopback.

Le runner doit :

1. vérifier exact HEAD + worktree propre ;
2. vérifier le contrat M23 statiquement ;
3. exécuter le gate learned réel Recall/MRR/nDCG sur le profil canonique ;
4. rejouer le core M21/M20 + Maven + JaCoCo incluant le scope `semantic-learned-provider` ;
5. rejouer la régression M22 ;
6. rejouer la release Windows supply-chain sous version `0.2.0-m23` ;
7. rejouer la parité IntelliJ + Plugin Verifier ;
8. revérifier learned quality, docs, exact HEAD et worktree ;
9. produire :

```text
M23 SEMANTIC RETRIEVAL CONSISTENCY SUCCESS
M23 LEARNED SEMANTIC QUALITY SUCCESS
M21 JACOCO GATE SUCCESS
M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS
M21-S6 INTELLIJ PARITY VALIDATION SUCCESS
M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS
Validated HEAD: <sha>
```

## Promotion

M23 ne sera marqué **VALIDÉ** et #78 ne sera fermé qu’après succès du runner sur le HEAD exact final avec worktree propre. Toute correction ou réconciliation documentaire créant un nouveau commit impose un nouveau replay exact-head.

L’intégration cible `develop`. La promotion vers `main` reste indépendante et soumise à M21-S2/CI lorsqu’il reprendra en août 2026.

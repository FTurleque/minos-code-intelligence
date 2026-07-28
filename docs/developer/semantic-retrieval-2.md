# Semantic Retrieval 2.0 — M23

M23 ajoute une voie d'embeddings **learned locale, explicite et mesurée** au-dessus de la couche sémantique M20. Les snapshots structurés MINOS restent la source d'autorité et tous les résultats vectoriels restent `HEURISTIC`.

## Providers

### Désactivé — défaut

Aucune variable n'est nécessaire. MINOS fonctionne sans embeddings et le fallback lexical + graph reste disponible.

### `local-hash` — référence déterministe

```powershell
$env:MINOS_SEMANTIC_PROVIDER='local-hash'
```

`local-hash` est un signed feature hashing reproductible. Il valide la plomberie mais **n'est pas un modèle learned** et ne constitue pas la preuve qualité M23.

### `ollama` — provider learned local

Préparer un serveur Ollama local et un modèle d'embeddings installé par l'opérateur, puis configurer MINOS :

```powershell
$env:MINOS_SEMANTIC_PROVIDER='ollama'
$env:MINOS_SEMANTIC_MODEL='<model-local>'
$env:MINOS_SEMANTIC_DIMENSIONS='<dimensions>'
$env:MINOS_SEMANTIC_ENDPOINT='http://127.0.0.1:11434/api/embed' # optionnel
$env:MINOS_SEMANTIC_TIMEOUT_SECONDS='30'                        # optionnel
```

Le provider utilise le contrat `/api/embed` et vérifie la dimension du vecteur retourné. MINOS ne télécharge aucun modèle.

### Frontière réseau

Le provider intégré M23 accepte uniquement :

```text
localhost
127.0.0.0/8
::1
```

Les redirects HTTP sont désactivés. Un endpoint distant est une erreur de configuration. M23 ne fournit aucun provider cloud implicite.

## Qualité learned

La qualité n'est jamais déduite du nom du modèle. Le gate final appelle le modèle configuré sur le corpus contrôlé :

```powershell
python .\scripts\m23\evaluate-learned-quality.py
```

Seuils bloquants :

```text
Recall@3 >= 0.75
MRR      >= 0.70
nDCG@3   >= 0.72
```

Le rapport machine-readable est écrit dans :

```text
target/m23-quality/learned-semantic-quality.json
```

L'endpoint inaccessible, le modèle absent, une dimension incorrecte ou un seuil manqué produisent `M23 LEARNED SEMANTIC QUALITY FAILED`.

## Vector store v2

M20 stockait les composantes en float64 dans `index-v1.bin`. M23 écrit :

```text
index-v2.bin
float32 vector components
```

Le reader conserve la compatibilité v1. Lors d'une synchronisation sémantique réussie, un ancien index v1 est remplacé atomiquement par v2 puis supprimé. Le snapshot structuré n'est jamais modifié par cette migration.

Le gain brut sur les composantes vectorielles est de 8 octets → 4 octets par dimension. Les textes et métadonnées documentaires restent inchangés.

## Cache de requêtes

`SemanticSearchService` possède un LRU borné à **256 embeddings de requêtes** :

```text
providerId + modelId + dimensions + query
```

Ce cache :

- vit uniquement dans le processus ;
- n'est jamais persisté ;
- n'est jamais une source d'autorité ;
- est invalidé naturellement par changement provider/modèle/dimensions ;
- ne modifie pas la formule cosine exacte.

## Pourquoi pas ANN ?

M21-S8 a mesuré la charge STANDARD et a conclu :

```text
PASS / KEEP_CURRENT_M20_BACKEND
```

M23 conserve donc le scan exact. Les réponses exposent encore :

```text
VECTOR_SEARCH_LINEAR_SCAN
ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND
```

Ajouter HNSW, Lucene, un vector database ou un autre index approximate sans nouvelle mesure contredirait la règle `measure before optimize`.

## Index identity et rebuild

La réutilisation exige l'égalité de :

```text
providerId
modelId
dimensions
stableKey
checksum document
```

Un changement provider/modèle/dimensions force un rebuild sûr. Un document inchangé avec le même modèle réutilise son vecteur.

## Surfaces publiques

M23 ne crée pas une seconde sémantique métier. Les surfaces M20 restent les surfaces autorisées :

```text
SemanticCodeIntelligenceApi v1
minos_semantic_index_status
minos_semantic_search
minos_hybrid_search
minos_hybrid_context
minos-ide v1 semantic/hybrid capabilities
NEXUS semantic signals v2
```

Les hits continuent de porter provider/model et `InformationNature.HEURISTIC`.

## Diagnostics learned

Un provider Ollama actif expose notamment :

```text
LOCAL_LEARNED_EMBEDDING_LOOPBACK_ONLY
LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC
SEMANTIC_RESULTS_REMAIN_HEURISTIC
```

Ces diagnostics empêchent de confondre « endpoint accessible » et « modèle qualifié ».

## Qualification finale

```powershell
$env:MINOS_SEMANTIC_MODEL='<model-local>'
$env:MINOS_SEMANTIC_DIMENSIONS='<dimensions>'
.\scripts\m23\run-final.ps1 -ExpectedHead <sha>
```

Le runner rejoue qualité learned, core Maven/JaCoCo, supply-chain release Windows et parité IntelliJ avant de revérifier exact HEAD + worktree propre. Aucune CI n'est utilisée en juillet 2026.

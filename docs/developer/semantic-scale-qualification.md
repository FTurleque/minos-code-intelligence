# M21-S8 — Qualification de scalabilité sémantique et hybride

Statut : **EN COURS — baseline STANDARD à mesurer avant toute optimisation**.

M21-S7 est validé sur `57243384286ed623de2d9499c9ae6729f77f6845`. S8 applique à M20 la règle durable M16 : **mesurer le backend courant avant de modifier le format vectoriel, l'algorithme de recherche ou le backend de stockage**.

## Baseline mesurée

La baseline est volontairement le runtime M20 courant :

```text
SemanticDocumentFactory
        ↓
LocalHashEmbeddingProvider (384 dimensions, opt-in)
        ↓
FileSemanticVectorStore v1
        ↓
SemanticSearchService      — scan vectoriel linéaire explicite
        ↓
HybridSearchService        — lexical + graph + semantic
        ↓
HybridContextBuilder       — sélection bornée documents/tokens
```

`local-hash` reste un provider de référence déterministe et local, **pas un language model**. Le benchmark mesure l'architecture de stockage/retrieval M20 ; il ne transforme pas la qualité sémantique de `local-hash` en affirmation produit.

## Dataset STANDARD dérivé de M16

S8 reprend exactement les cardinalités structurées du profil M16 `STANDARD` :

```text
seed                 16000031
fichiers logiques       10 000
symboles               100 000
occurrences            500 000
relations              250 000
```

Chaque symbole est localisé. `SemanticDocumentFactory` produit donc :

```text
SYMBOL  100 000
CHUNK   100 000
FILE     10 000
----------------
total   210 000 documents
```

Le provider benchmarké utilise les **384 dimensions** par défaut de `LocalHashEmbeddingProvider`.

## Mesures obligatoires

Le probe `scripts/m21/M21SemanticScaleProbe.java` mesure :

```text
initial_snapshot_publish_ms
changed_snapshot_publish_ms
initial_index_build_ms
incremental_index_rebuild_ms
incremental_embedded_added
incremental_embedded_changed
incremental_removed
incremental_reused
incremental_reuse_ratio
semantic_index_disk_size_bytes
peak_heap_bytes
retained_heap_bytes
max_heap_bytes
process_rss_bytes
process_elapsed_ms

vector-store-load   p50 / p95 / p99 / moyenne
semantic-search     p50 / p95 / p99 / moyenne
hybrid-search       p50 / p95 / p99 / moyenne
hybrid-context      p50 / p95 / p99 / moyenne
```

Une mutation contrôlée modifie un seul symbole et sa ligne source. Les stable keys restent inchangées ; exactement trois documents doivent être ré-embeddés : le document `SYMBOL`, son `CHUNK` et le document `FILE`. Tous les autres vecteurs doivent être réutilisés.

## Seuils STANDARD initiaux

Ces seuils sont volontairement larges. Ils servent à détecter un goulot structurel, pas à définir un SLA marketing portable entre machines.

| Opération | p95 max | p99 max |
|---|---:|---:|
| vector-store-load | 1 500 ms | 3 000 ms |
| semantic-search | 2 500 ms | 5 000 ms |
| hybrid-search | 5 000 ms | 10 000 ms |
| hybrid-context | 6 000 ms | 12 000 ms |

Autres gates :

- profil `STANDARD`, seed et cardinalités exactes ;
- 210 000 documents / 384 dimensions ;
- `incremental_embedded_changed == 3` ;
- `incremental_embedded_added == 0` ;
- `incremental_removed == 0` ;
- `incremental_reuse_ratio >= 0.999` ;
- `peak_heap_bytes < 80 %` du max heap ;
- index sémantique <= **2 GiB** ;
- scan linéaire courant explicitement observé par `VECTOR_SEARCH_LINEAR_SCAN` ;
- SHA machine identique au HEAD exact qualifié.

Les durées de build/rebuild sont reportées mais ne sont pas bloquantes avant la première campagne STANDARD : elles dépendent fortement du CPU et constituent surtout un diagnostic pour une optimisation ciblée.

## Règle de décision

`scripts/m21/check-s8-results.py` produit une décision explicite :

```text
INVALID_MEASUREMENT
    données/gates de preuve incohérents

OPTIMIZE_MEASURED_BOTTLENECK
    mesure valide mais un seuil STANDARD échoue

KEEP_CURRENT_M20_BACKEND
    tous les seuils STANDARD passent
```

En cas de `OPTIMIZE_MEASURED_BOTTLENECK`, S8 **reste ouvert**. Seul le goulot effectivement mesuré peut être corrigé, puis le même dataset, le même seed, les mêmes requêtes et les mêmes seuils doivent être rejoués.

Aucune dépendance `Lucene`, `HNSW`, `RocksDB`, SQLite JDBC, Qdrant, Milvus ou Weaviate n'est autorisée avant une telle preuve et une décision architecturale explicite.

## Commande Windows

```powershell
.\scripts\m21\run-s8.ps1 -ExpectedHead <sha> -Repetitions 5
```

Le runner :

1. rejoue le core M21 ;
2. vérifie l'absence de backend non ratifié ;
3. exécute la campagne STANDARD ;
4. produit `target/m21-s8/standard.json` ;
5. produit `target/m21-s8/decision.json` ;
6. applique la décision ;
7. revérifie documentation, HEAD exact et worktree propre.

Verdict de fermeture attendu :

```text
M21 S8 SEMANTIC SCALE DECISION SUCCESS
M21-S8 SEMANTIC SCALE VALIDATION SUCCESS
Validated HEAD: <sha>
```

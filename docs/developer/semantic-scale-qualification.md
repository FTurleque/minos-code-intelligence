# M21-S8 — Qualification de scalabilité sémantique et hybride

Statut : **VALIDÉ le 28 juillet 2026** sur `a668f0a09da08515396903fbe887ed9e70125201`.

S8 applique à M20 la règle durable M16 : **mesurer le backend courant avant de modifier le format vectoriel, l'algorithme de recherche ou le backend de stockage**.

## Runtime qualifié

```text
SemanticDocumentFactory
        ↓
LocalHashEmbeddingProvider (384 dimensions, opt-in)
        ↓
FileSemanticVectorStore v1
        ↓
SemanticSearchService      — scan vectoriel linéaire exact
        ↓
HybridSearchService        — lexical + graph + semantic
        ↓
HybridContextBuilder       — sélection bornée documents/tokens
```

`local-hash` reste un provider de référence déterministe et local, **pas un language model**. Le benchmark mesure l'architecture de stockage/retrieval ; il ne transforme pas la qualité sémantique du provider de référence en affirmation produit.

## Dataset STANDARD dérivé de M16

```text
seed                 16000031
fichiers logiques       10 000
symboles               100 000
occurrences            500 000
relations              250 000
```

Chaque symbole est localisé. `SemanticDocumentFactory` produit :

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

Une mutation contrôlée modifie un seul symbole et sa ligne source. Les stable keys restent inchangées ; exactement trois documents sont ré-embeddés : `SYMBOL`, `CHUNK`, `FILE`.

## Seuils STANDARD

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
- scan linéaire explicitement observé par `VECTOR_SEARCH_LINEAR_SCAN` ;
- SHA machine identique au HEAD exact qualifié.

Les durées de build/rebuild sont reportées mais ne sont pas bloquantes : elles restent des diagnostics CPU/IO distincts des seuils de requête.

## Première campagne STANDARD — preuve de goulot

HEAD : `37cbe22e91993e8aea040621396d2abd7e00da44`.

Invariants valides : `210000` documents, `384` dimensions, `added=0`, `changed=3`, `removed=0`, `reused=209997`, reuse `0.999986`, index `717000165` octets.

```text
initial_index_build_ms        47298.293
incremental_index_rebuild_ms  43600.689
peak_heap_bytes               11530141696
max_heap_bytes                12859736064
peak_heap_ratio               0.8966
process_rss_bytes             11839127552

vector-store-load  p95= 2910.409 ms  p99= 2910.409 ms
semantic-search    p95= 8457.386 ms  p99= 8457.386 ms
hybrid-search      p95=49412.429 ms  p99=49412.429 ms
hybrid-context     p95=48520.565 ms  p99=48520.565 ms
```

Verdict :

```text
M21 S8 STANDARD MEASUREMENT status=FAIL decision=OPTIMIZE_MEASURED_BOTTLENECK
```

Cette mesure a justifié une optimisation ciblée. Elle n'a pas justifié l'introduction spéculative d'un backend ANN/vector database.

## Optimisations ciblées

Le profil a isolé quatre coûts corrélés : représentation boxed des vecteurs, rechargements répétés du même index, tri intégral avant `limit`, reconstruction/re-tokenisation du corpus hybride.

Le correctif conserve le format disque v1, les scores cosine, le scan exact linéaire, les stable keys, les poids de ranking et les limitations M20. Il modifie uniquement :

1. représentation primitive des vecteurs derrière le contrat public `List<Double>` ;
2. norm pré-calculée et accès primitif dans la boucle cosine ;
3. cache du `FileSemanticVectorStore` vérifié par métadonnées et cohérent avec `replace/delete` ;
4. top-K exact borné dans `SemanticSearchService`, ordre `score desc / stableKey asc` inchangé ;
5. réutilisation des `SemanticDocument` de l'index READY et cache du degré de graphe par `snapshotId` ;
6. compilation de la requête lexicale une fois et token matching sans reconstruire un `Set` complet par document.

Aucune dépendance `Lucene`, `HNSW`, `RocksDB`, SQLite JDBC, Qdrant, Milvus ou Weaviate n'a été ajoutée.

## Replay STANDARD qualifié

HEAD exact : `a668f0a09da08515396903fbe887ed9e70125201`.

Core avant benchmark :

```text
Maven reactor: 13/13 SUCCESS
122 tests minos-application PASS
11/11 scopes JaCoCo PASS
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
M21 LOCAL CONSOLIDATION VALIDATION SUCCESS
```

Mesure finale :

```text
initial_index_build_ms        43029.8933
incremental_index_rebuild_ms  37863.0647
incremental_embedded_added    0
incremental_embedded_changed  3
incremental_removed           0
incremental_reused            209997
incremental_reuse_ratio       0.999986
semantic_index_disk_size      717000165
peak_heap_bytes               4381003968
max_heap_bytes                12859736064
peak_heap_ratio               0.3407
process_rss_bytes             4745732096

vector-store-load  p50=0.0515 ms   p95=0.0625 ms   p99=0.0625 ms
semantic-search    p50=102.7691 ms p95=102.8875 ms p99=102.8875 ms
hybrid-search      p50=209.6237 ms p95=210.4870 ms p99=210.4870 ms
hybrid-context     p50=183.6914 ms p95=188.5624 ms p99=188.5624 ms
```

Verdict qualifié :

```text
M21 S8 STANDARD MEASUREMENT status=PASS decision=KEEP_CURRENT_M20_BACKEND
M21 S8 SEMANTIC SCALE DECISION SUCCESS
M21-S8 SEMANTIC SCALE VALIDATION SUCCESS
Validated HEAD: a668f0a09da08515396903fbe887ed9e70125201
```

Le backend M20 optimisé reste donc retenu. La migration vers un moteur ANN ou une vector database n'est pas justifiée par le STANDARD courant.

## Robustesse Windows et supervision du harness

Le gate exact-head exécute `clean verify`. Sous Windows, un processus conservant un handle sur le JAR shaded peut bloquer Maven.

Protections :

1. `run-s8.ps1` arrête uniquement un ancien **M21SemanticScaleProbe** identifiable comme stale et vérifie les verrous du JAR ;
2. `run-s8-benchmark.ps1` exécute une copie temporaire unique du JAR hors dépôt ;
3. le JVM hérite de la console et expose `M21-S8 PROGRESS` en direct ;
4. progression persistée dans `target/m21-s8/process/semantic-scale.progress.log` ;
5. suivi PID via `WaitForExit(1000)`, `WorkingSet64` et heartbeat toutes les 15 secondes ;
6. watchdog de harness de 30 minutes par défaut ;
7. cleanup Windows PowerShell 5.1 via `Stop-Process -Force`, pas `Process.Kill(Boolean)`.

Le JSON de mesure enregistre :

```text
machine.benchmark_jar_isolated = true
machine.benchmark_watchdog_minutes = 30
```

## Règle de décision

`scripts/m21/check-s8-results.py` conserve les trois décisions :

```text
INVALID_MEASUREMENT
    données/gates incohérents

OPTIMIZE_MEASURED_BOTTLENECK
    mesure valide mais au moins un seuil STANDARD échoue

KEEP_CURRENT_M20_BACKEND
    tous les seuils STANDARD passent
```

Toute évolution future du backend doit recommencer par une mesure reproductible et ne peut pas présenter un score vectoriel comme un fait structurel.

## Commande Windows

```powershell
.\scripts\m21\run-s8.ps1 -ExpectedHead <sha> -Repetitions 5
```

Le runner :

1. vérifie processus stale et verrous ;
2. rejoue le core M21 ;
3. interdit un backend non ratifié ;
4. exécute le STANDARD sur JAR isolé ;
5. produit `target/m21-s8/standard.json` ;
6. produit `target/m21-s8/decision.json` ;
7. applique la décision ;
8. revérifie docs, HEAD exact et worktree propre.

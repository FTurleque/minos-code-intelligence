# M21-S8 — Qualification de scalabilité sémantique et hybride

Statut : **EN COURS — première baseline STANDARD mesurée ; optimisation ciblée puis replay obligatoire**.

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

## Première campagne STANDARD — preuve de goulot

La première campagne complète a été obtenue sur le HEAD exact `37cbe22e91993e8aea040621396d2abd7e00da44`. Les invariants de mesure sont valides : `210000` documents, `384` dimensions, `added=0`, `changed=3`, `removed=0`, `reused=209997`, ratio de réutilisation `0.999986`, index disque `717000165` octets.

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

Cette mesure justifie une optimisation ciblée du runtime M20 courant. Elle **ne justifie pas** l'introduction spéculative d'un backend ANN/vector database.

### Optimisations autorisées par la mesure

Le profil montre quatre coûts corrélés du même chemin exact : représentation boxed des vecteurs, rechargements répétés du même index, tri complet avant `limit`, et reconstruction/re-tokenisation du corpus hybride à chaque requête.

Le correctif S8 conserve le format disque v1, les scores cosine, le scan exact linéaire, les stable keys, les poids de ranking et les limitations M20. Il cible uniquement :

1. représentation mémoire primitive des vecteurs derrière le contrat public `List<Double>` existant ;
2. cache du `FileSemanticVectorStore` invalidé par métadonnées de l'artefact et par `replace/delete` ;
3. top-K exact borné dans `SemanticSearchService`, avec le même ordre score-descendant / stableKey-ascendant ;
4. réutilisation des `SemanticDocument` déjà alignés à l'index READY et cache du degré de graphe par `snapshotId` ;
5. compilation de la requête lexicale une seule fois et token matching sans reconstruire un `Set` complet pour chaque document.

Le même dataset, le même seed, les mêmes requêtes et les mêmes seuils doivent être rejoués après ces changements. S8 reste ouvert tant que ce replay ne retourne pas `KEEP_CURRENT_M20_BACKEND`.

## Robustesse Windows et supervision du harness

Le gate exact-head exécute `clean verify`. Sous Windows, un processus qui conserve un handle sur `target/minos-code-intelligence-*-all.jar` peut empêcher Maven de supprimer le JAR.

S8 impose donc les protections suivantes :

1. `run-s8.ps1` arrête uniquement un ancien processus **M21SemanticScaleProbe** identifiable comme stale, puis teste en accès exclusif tout JAR shaded existant avant Maven ; si un autre processus le verrouille, le runner échoue immédiatement avec PID/commande lorsque Windows permet de l'identifier ;
2. `run-s8-benchmark.ps1` ne passe jamais le JAR de `target` directement au JVM de benchmark : il crée une **copie temporaire unique hors du dépôt**, exécute le probe avec cette copie comme classpath, puis la détruit en `finally` ;
3. le JVM hérite désormais de la console : les étapes `M21-S8 PROGRESS` et les erreurs Java sont visibles immédiatement au lieu d'être retenues jusqu'à la fin ;
4. le probe persiste la progression dans `target/m21-s8/process/semantic-scale.progress.log` avec les phases materialize, génération dataset, publication snapshots, build/rebuild, proofs et chaque échantillon de mesure ;
5. le wrapper suit le PID via `WaitForExit(1000)`, échantillonne `WorkingSet64` et affiche un heartbeat toutes les 15 secondes avec elapsed/RSS/dernière étape ;
6. un watchdog de harness, **30 minutes par défaut**, empêche un processus bloqué de rester indéfiniment. Ce watchdog ne constitue pas un seuil de performance S8 : s'il expire, la mesure est incomplète et S8 reste ouvert ;
7. le cleanup est compatible **Windows PowerShell 5.1** : `Stop-Process -Force` est utilisé au lieu de `Process.Kill(Boolean)`, surcharge non fiable sous le .NET Framework de Windows PowerShell.

Cette isolation garantit que le benchmark S8 lui-même ne peut pas conserver un handle sur l'artefact Maven racine utilisé par une qualification exacte ultérieure. Elle ne masque pas un processus MINOS externe légitime : un MCP/server ou autre Java qui utilise explicitement le JAR de `target` doit être arrêté avant `clean verify`.

Le JSON de mesure enregistre :

```text
machine.benchmark_jar_isolated = true
machine.benchmark_watchdog_minutes = 30
```

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

Le watchdog peut être surchargé uniquement pour diagnostiquer le harness :

```powershell
.\scripts\m21\run-s8.ps1 -ExpectedHead <sha> -Repetitions 5 -BenchmarkTimeoutMinutes 30
```

Le runner :

1. vérifie les processus stale S8 et les verrous du JAR Maven ;
2. rejoue le core M21 ;
3. vérifie l'absence de backend non ratifié ;
4. exécute la campagne STANDARD sur une copie temporaire isolée du JAR, avec progression live, heartbeat et watchdog ;
5. produit `target/m21-s8/standard.json` ;
6. produit `target/m21-s8/decision.json` ;
7. applique la décision ;
8. revérifie documentation, HEAD exact et worktree propre.

Verdict de fermeture attendu :

```text
M21 S8 SEMANTIC SCALE DECISION SUCCESS
M21-S8 SEMANTIC SCALE VALIDATION SUCCESS
Validated HEAD: <sha>
```

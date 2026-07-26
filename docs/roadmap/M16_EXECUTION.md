# M16 — Exécution : scalabilité et performance à grande échelle

Statut : **TERMINÉ — 9/9 sous-incréments, intégration conditionnée au gate exact-head**

Issue principale : **#63**

## Objectif produit

Prouver que MINOS conserve des performances, une empreinte mémoire et une croissance disque acceptables lorsque la taille du codebase augmente, puis ne retenir que les optimisations et choix de backend justifiés par les mêmes mesures reproductibles.

## Principes de qualification

- la campagne est reproductible et versionnée ;
- les datasets synthétiques sont déterministes et générés à partir d'un seed documenté ;
- les fixtures réelles Java/TypeScript M14 restent rejouées pour vérifier que les benchmarks ne remplacent pas la non-régression ;
- les snapshots persistés restent la source de vérité ;
- les indexes mémoire restent reconstruisibles ;
- aucune capacité incrémentale fournisseur absente n'est inventée ;
- aucun backend complexe n'est promu sans goulot mesuré et comparaison objective ;
- un nouveau commit invalide toujours la qualification exacte d'un head antérieur.

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M16-S1 | Harness benchmark | campagne reproductible, machine/JVM documentées | p50/p95/p99 + mémoire + disque |
| M16-S2 | Datasets d'échelle | datasets déterministes gradués + fixtures réelles | manifest/version/seed + cardinalités |
| M16-S3 | Query benchmark | symboles/usages/relations/search/architecture/impact/tests liés | seuils p95/p99 explicites |
| M16-S4 | MCP sustained load | séquences répétées sur serveur STDIO | aucun full snapshot reload systématique |
| M16-S5 | Indexing benchmark | FULL/NONE et incrémental seulement si qualifié | durée, débit, mémoire, I/O |
| M16-S6 | Memory/disk profile | structures et tailles dominantes identifiées | peak/retained heap + RSS + disque |
| M16-S7 | Backend decision | décision basée sur le goulot réellement mesuré | ADR et comparaison objective |
| M16-S8 | Optimisations retenues | uniquement des gains prouvés | mêmes résultats + seuils atteints |
| M16-S9 | Retention/compaction | snapshots/runs anciens nettoyés sans toucher à l'actif | croissance disque bornée |

## Profils de datasets

Les profils sont générés à la demande ; aucun dataset massif n'est versionné dans Git.

| Profil | fichiers logiques | symboles | occurrences | relations | Usage |
|---|---:|---:|---:|---:|---|
| `SMOKE` | 1 000 | 10 000 | 50 000 | 20 000 | développement rapide |
| `STANDARD` | 10 000 | 100 000 | 500 000 | 250 000 | qualification M16 obligatoire |
| `EXTENDED` | 50 000 | 1 000 000 | 5 000 000 | 2 000 000 | diagnostic étendu explicite |
| `STRESS` | 100 000 | 1 000 000 | 10 000 000 | 4 000 000 | exploration manuelle/non bloquante |

Le profil `STANDARD` est la porte obligatoire de fermeture. `EXTENDED` et `STRESS` restent des campagnes explicites non bloquantes : leur coût dépend fortement du heap et de la machine, et ils ne sont jamais lancés silencieusement par le gate final.

Les fichiers logiques ne nécessitent pas tous un fichier physique. Le générateur distingue :

- cardinalité de fichiers portée par les `fileId` des faits ;
- fixture physique graduée pour discovery/architecture ;
- graphes symboles/occurrences/relations pour les requêtes structurantes.

## Mesures obligatoires

```text
cold_start_time_ms
snapshot_publish_time_ms
snapshot_load_time_ms
query_index_build_time_ms
warm_query_latency_ms p50/p95/p99
peak_heap_bytes
retained_heap_bytes
process_rss_bytes
snapshot_disk_size_bytes
index_reference_count
FULL_index_duration_ms
NONE_index_duration_ms
files_per_second
loc_per_second
MCP_sequence_latency_ms p50/p95/p99
active_snapshot_full_loads
query_view_builds
query_cache_hits
```

## Requêtes benchmarkées

```text
find-symbol
find-usages
dependencies
dependents
search
architecture
impact
related-tests
```

## Seuils produit STANDARD

Les seuils sont volontairement assez larges pour être reproductibles entre machines de développement tout en détectant une régression structurelle. Les durées d'indexation fournisseur restent reportées mais ne constituent pas un SLA portable entre machines.

| Scénario | p95 max | p99 max |
|---|---:|---:|
| find-symbol | 250 ms | 500 ms |
| find-usages | 250 ms | 500 ms |
| dependencies | 250 ms | 500 ms |
| dependents | 250 ms | 500 ms |
| related-tests | 500 ms | 1 000 ms |
| search | 500 ms | 1 000 ms |
| architecture | 2 000 ms | 5 000 ms |
| impact | 1 000 ms | 2 500 ms |
| MCP sustained call | 1 000 ms | 2 500 ms |

Autres gates STANDARD :

- `active_snapshot_full_loads == 1` après warm-up d'une vue inchangée ;
- `query_view_builds == 1` après warm-up d'une vue inchangée ;
- aucun `OutOfMemoryError` ;
- `peak_heap_bytes < 80 %` du max heap JVM ;
- après compaction, snapshot actif toujours lisible ;
- au plus **2 snapshots historiques + l'actif** par projet dans le profil de rétention par défaut ;
- au plus **20 runs réussis + 10 runs non réussis** par projet dans le profil de rétention par défaut.

## Backend decision rule

L'ordre est obligatoire :

```text
1. mesurer le backend fichier + indexes mémoire de M15
2. identifier un goulot qui fait échouer un seuil produit
3. seulement dans ce cas, prototyper une alternative ciblée
4. mesurer l'alternative sur le même dataset/seed
5. décider par ADR
```

Si le backend M15 passe tous les seuils STANDARD et qu'aucun goulot structurel n'est observé, la décision M16 est **de conserver le backend actuel** et de ne pas introduire une dépendance de stockage supplémentaire.

Le prototype comparatif SQLite fourni par M16 reste un outil de benchmark, pas une dépendance runtime MINOS. La règle durable est formalisée dans [ADR-0025](../adr/0025-measurement-gated-storage-backend-evolution.md).

## Optimisations M16

M16 n'autorise aucune optimisation de requête ou migration de backend « pour anticiper ». Le gate de décision produit deux issues possibles :

- tous les seuils STANDARD passent : S8 est fermé par **absence d'optimisation spéculative**, le backend M15 est conservé ;
- un seuil échoue : M16 reste ouvert et seule une optimisation ciblant ce goulot peut être implémentée puis remesurée.

La seule évolution de stockage livrée indépendamment de cette décision est la rétention/compaction S9, car elle ferme explicitement le risque de croissance disque non bornée mesuré par M16.

## Rétention M16

Politique produit par défaut mesurée :

```text
snapshots:
  active: toujours conservé
  historiques: 2 plus récents

indexing runs:
  succeeded: 20 plus récents
  non-succeeded: 10 plus récents
```

La compaction est explicite et déterministe. Elle ne modifie ni le snapshot actif ni l'état projet courant ; le `latestRunId` de l'état projet est toujours protégé.

## Qualification finale

Runner :

```text
scripts/m16/run-final.ps1
```

La fermeture exige :

1. worktree propre et head exact ;
2. `clean verify` Java 24 ;
3. replays M14/providers/Windows conservés ;
4. génération dataset STANDARD déterministe ;
5. query benchmark STANDARD sous seuils ;
6. MCP sustained load sous seuils et sans reload complet systématique ;
7. indexing benchmark FULL/NONE + capability incrémentale explicitement reportée ;
8. memory/disk profile complet ;
9. backend decision report cohérent avec la règle de décision ;
10. rétention snapshots/runs vérifiée ;
11. documentation/facts cohérents ;
12. head inchangé à la fin du run.

Verdict exigé :

```text
M16 FINAL SCALABILITY VALIDATION SUCCESS
```

## Hors périmètre

- ajouter un nouveau langage/provider → M17 ;
- plugin IntelliJ → M18 ;
- CFG/data-flow/CPG → M19 ;
- embeddings/recherche sémantique → M20.

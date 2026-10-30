# Décision M7 — Indexation incrémentale

Date : **23 juillet 2026**

Statut : **DÉCISION PRÉPARÉE — PORTE LOCALE FINALE EN ATTENTE**

Suivi : issue #22.

## Question de décision

> MINOS sait-il déterminer de manière sûre ce qui peut être réindexé partiellement,
> et revenir explicitement à une indexation complète lorsqu’il ne peut pas le prouver ?

## Verdict préparé

**OUI, SOUS PREUVE EXPLICITE DE CAPACITÉ FOURNISSEUR.**

M7 ne confond pas « changement borné » et « indexeur incrémental ».

Le chemin de décision est :

```text
workspace
 -> fingerprint courant
 -> comparaison à la baseline de l'index actif
 -> invalidation NONE / PARTIAL_CANDIDATE / FULL_REQUIRED
 -> négociation des indexeurs
 -> vérification INCREMENTAL_INDEXING pour chaque sélection
 -> plan NONE / INCREMENTAL / FULL
 -> exécution
 -> promotion atomique du snapshot de code
 -> nouvelle capture du workspace
 -> publication de la baseline fingerprint seulement si le workspace est stable
```

## Acquis M7.1

- empreinte SHA-256 par fichier visible ;
- empreinte projet ;
- empreinte build ;
- chemins relatifs déterministes ;
- indépendance aux timestamps et chemins absolus ;
- classification ajouté/modifié/supprimé/identique.

PR #23, merge :

```text
34b57dfadad962b98c2d5c028957595cee575400
```

## Acquis M7.2

- snapshots d’empreintes persistants ;
- association explicite `projectId + indexSnapshotId` ;
- historique immuable ;
- publication/promotion séparées ;
- pointeur actif atomique ;
- vérification de checksum et des agrégats ;
- alignement avec `ProjectIndexState.activeSnapshotId`.

PR #24, merge :

```text
379b5a28a92cb58b340dc8801d66fad1b853e4ce
```

## Acquis M7.3

- `NONE` ;
- `PARTIAL_CANDIDATE` ;
- `FULL_REQUIRED` ;
- build, ignore policy et changement non qualifié forcent `FULL_REQUIRED` ;
- seules les sources/tests M1 reconnus peuvent produire `PARTIAL_CANDIDATE` ;
- absence ou désalignement de baseline force un refresh complet.

PR #25, head validé :

```text
e41abcf999ca94b0f3cf9accc0ae8b6a22e41ffd
```

Merge :

```text
8f87a8fbb3f62361f88e38c9a8f22c2da2050ca8
```

Porte :

```text
128 sources main
65 sources test
184/184 tests PASS
BUILD SUCCESS
```

## Acquis M7.4

- capacité `INCREMENTAL_INDEXING` distincte des autres modes dégradés ;
- `IncrementalIndexingPlan` explicable ;
- atomicité projet conservée : toutes les sélections doivent être capables ;
- fallback `FULL` si une seule capacité manque ;
- `IndexingExecutionRequest` porte `mode + changedFiles` ;
- revalidation de sécurité dans `IndexingLifecycleService` ;
- `IncrementalIndexingCoordinator` end-to-end ;
- baseline fingerprint avancée uniquement après stabilité du workspace ;
- baseline illisible ou non alignée traitée conservativement.

## Qualification des fournisseurs actuels

Les versions épinglées restent :

```text
scip-java       0.13.1
scip-typescript 0.4.0
```

Aucune preuve M0 ne qualifie leur capacité `INCREMENTAL_INDEXING`.

M7 **ne leur attribue donc pas cette capacité**.

Conséquence : un changement source pouvant être borné à
`PARTIAL_CANDIDATE` retombe actuellement en `FULL` avec ces indexeurs.

Cette décision est volontaire. La sûreté prime sur un gain de performance non
mesuré.

Un fournisseur futur ou requalifié pourra annoncer `INCREMENTAL_INDEXING` et
recevoir le périmètre de fichiers sans modifier les règles métier M7.

## Cas de sûreté

### Aucun changement

```text
NONE
```

Aucun run n’est créé.

### Changement build / ignore / non qualifié

```text
FULL
```

La capacité fournisseur n’est pas consultée pour réduire cette portée.

### Sources/tests seulement, fournisseur qualifié

```text
PARTIAL_CANDIDATE
+ INCREMENTAL_INDEXING sur toutes les sélections
= INCREMENTAL
```

### Sources/tests seulement, capacité absente

```text
PARTIAL_CANDIDATE
+ capacité manquante
= FULL
```

### Workspace modifié pendant le run

Le snapshot de code déjà promu est conservé, mais aucune baseline fingerprint
n’est associée au nouvel index. Le prochain refresh impose alors un chemin complet
jusqu’à rétablissement d’un couple index/fingerprint aligné.

## Limites conservées après M7

M7 ne prétend pas fournir :

- watcher filesystem permanent ;
- granularité incrémentale propre à scip-java/scip-typescript non mesurée ;
- réutilisation de shards fournisseur sans qualification ;
- incrémentalité inter-dépôts ;
- impact sémantique des changements — réservé à M8 ;
- CLI produit dédiée — M9 ;
- MCP/API — M10/M11.

## Porte finale

Le verdict ne devient **ACQUIS** qu’après :

```powershell
.\mvnw.cmd clean verify
```

sur le head exact de la PR finale M7.

Après une porte verte et fusion :

- issue #22 → `completed` ;
- M7 → terminé et livré ;
- M8 — Analyse d’impact → prochain jalon.

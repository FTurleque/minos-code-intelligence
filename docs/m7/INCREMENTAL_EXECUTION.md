# M7.4 — Planification et exécution incrémentales sûres

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE FINALE M7 EN ATTENTE**

Suivi : issue #22.

Base : M7.3 fusionné dans `main` via PR #25 au commit
`8f87a8fbb3f62361f88e38c9a8f22c2da2050ca8`.

## Objectif

Transformer l’évaluation M7.3 en un plan d’exécution explicable, puis exécuter ce
plan sans jamais demander une indexation incrémentale à un fournisseur dont la
capacité n’a pas été qualifiée.

## Capacité fournisseur

`IndexerCapability` expose désormais :

```text
INCREMENTAL_INDEXING
```

Cette capacité est distincte de :

```text
PARTIAL_INDEX_ON_BUILD_FAILURE
```

La seconde décrit la faculté de produire un index malgré certaines erreurs de
build. Elle ne prouve pas qu’un indexeur sait mettre à jour un index existant sur
un ensemble borné de fichiers.

### SCIP épinglé

Les preuves M0 ne qualifient pas `INCREMENTAL_INDEXING` pour :

```text
scip-java       0.13.1
scip-typescript 0.4.0
```

Le catalogue ne leur attribue donc pas cette capacité. Leurs limitations rendent
cette absence explicite.

## Plan d’indexation

`IncrementalIndexingPlanner` produit `IncrementalIndexingPlan` avec :

```text
NONE
FULL
INCREMENTAL
```

Règles :

```text
M7.3 NONE
    -> NONE

M7.3 FULL_REQUIRED
    -> FULL

M7.3 PARTIAL_CANDIDATE
+ tous les indexeurs sélectionnés ont INCREMENTAL_INDEXING
    -> INCREMENTAL

M7.3 PARTIAL_CANDIDATE
+ au moins un indexeur sélectionné n'a pas la capacité
    -> FULL
```

L’atomicité reste celle de M1 : **projet complet**. Dans un projet multi-langages,
un seul indexeur non qualifié suffit donc à imposer `FULL` pour le refresh entier.

Le plan conserve séparément :

- indexeurs sélectionnés ;
- indexeurs capables d’incrémental ;
- indexeurs sans preuve de capacité ;
- fichiers changés ;
- raison structurée du choix.

## Défense en profondeur du lifecycle

`IndexingLifecycleService.executePlanned(...)` revalide le plan avant exécution.

Même si un appelant forge manuellement un plan `INCREMENTAL`, le lifecycle refuse
de l’exécuter si une sélection négociée ne contient pas
`INCREMENTAL_INDEXING`.

`IndexingExecutionRequest` expose :

```text
mode
changedFiles
```

Contraintes :

- `NONE` n’est jamais exécutable ;
- `FULL` ne transporte pas de périmètre de fichiers partiel ;
- `INCREMENTAL` exige une liste de fichiers relative, triée et non vide.

## Coordinateur M7

`IncrementalIndexingCoordinator` enchaîne :

```text
ProjectDiscovery
 -> capture fingerprint courant
 -> lecture baseline active
 -> ProjectInvalidationAssessment
 -> IndexerRegistry.negotiate
 -> IncrementalIndexingPlanner
 -> IndexingLifecycleService.executePlanned
 -> contrôle de stabilité du workspace
 -> publication/promotion de la nouvelle baseline fingerprint
```

## Avancement sûr de la baseline

Le fingerprint est capturé avant le run puis après un run réussi.

```text
before == after
    -> publier la baseline avec le nouvel indexSnapshotId
    -> promouvoir le pointeur fingerprint

before != after
    -> ne pas avancer la baseline fingerprint
```

Le snapshot de code déjà promu n’est pas annulé lorsqu’un fichier change pendant
le run. En revanche, aucun fingerprint ne lui est attribué artificiellement.

Au prochain refresh, le pointeur fingerprint est absent ou désaligné avec l’index
actif et M7.3 impose donc `FULL_REQUIRED`. Le système se rétablit par un refresh
complet sûr.

Une baseline fingerprint illisible est également traitée comme une absence de
preuve et impose une invalidation complète.

## Tests

### Planificateur

`IncrementalIndexingPlannerTest` couvre :

- `NONE` ;
- invalidation complète même avec fournisseur capable ;
- incrémental lorsque toutes les sélections sont qualifiées ;
- fallback complet lorsque l’une des sélections manque la capacité.

### Lifecycle

`IndexingLifecycleIncrementalTest` couvre :

- propagation de `INCREMENTAL + changedFiles` ;
- suppression du périmètre partiel lors d’un fallback `FULL` ;
- absence de run en mode `NONE` ;
- refus d’un plan incrémental forgé pour un fournisseur non qualifié.

### Coordinateur

`IncrementalIndexingCoordinatorTest` couvre :

- fournisseur synthétique explicitement qualifié ;
- premier run complet ;
- deuxième run incrémental sur changement source ;
- troisième appel `NONE` sans changement ;
- mutation concurrente du workspace ;
- baseline non avancée ;
- refresh complet de guérison au run suivant.

### Replay réel TypeScript

`IncrementalIndexingRealFixtureTest` utilise une copie de :

```text
fixtures/typescript/typescript-modules
```

Avec le vrai catalogue SCIP M0/M1, le comportement attendu est :

```text
première indexation   -> FULL
aucun changement      -> NONE
modification .ts      -> FULL
```

Le troisième résultat est volontaire : `scip-typescript 0.4.0` n’a pas une
capacité `INCREMENTAL_INDEXING` qualifiée.

Sortie attendue :

```text
M7.4 typescript-modules planning: initial=FULL, unchanged=NONE, source=FULL, missing-capability=[scip-typescript], baseline=snapshot-2
```

## Porte de sûreté M7

M7 ne prétend pas qu’un indexeur est incrémental sans preuve.

La porte de décision est satisfaite architecturalement lorsque :

1. MINOS détecte les changements ;
2. MINOS sait quand une portée partielle est fournisseur-indépendamment admissible ;
3. MINOS vérifie une capacité fournisseur distincte ;
4. MINOS retombe automatiquement en complet sinon ;
5. la promotion du fingerprint reste alignée avec un workspace stable.

La validation locale finale du head exact reste requise avant livraison et clôture
de l’issue #22.

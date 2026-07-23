# M7.3 — Règles d’invalidation conservatrices

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

Suivi : issue #22.

Base : M7.2 livré via PR #24 au commit
`379b5a28a92cb58b340dc8801d66fad1b853e4ce`.

## Objectif

Transformer les faits M7.1/M7.2 en une évaluation fournisseur-indépendante de
l’étendue d’invalidation, sans encore lancer d’indexeur et sans prétendre qu’un
fournisseur sait exécuter une indexation partielle.

## Contrats

- `ProjectInvalidationScope` ;
- `ProjectInvalidationReason` ;
- `ProjectInvalidationAssessment` ;
- `ProjectInvalidationService`.

## Portées

```text
NONE
PARTIAL_CANDIDATE
FULL_REQUIRED
```

### NONE

Le fingerprint courant est identique à la baseline alignée avec le snapshot
d’index actif. Aucun fichier ajouté, modifié ou supprimé n’est observé.

### PARTIAL_CANDIDATE

Tous les changements sont bornés à des fichiers source/test dont la racine et
l’extension sont actuellement reconnues par `ProjectDiscovery`.

Cette portée n’est **pas** une autorisation d’exécution incrémentale. Elle signifie
uniquement que le noyau MINOS n’a observé aucun motif fournisseur-indépendant
imposant déjà une invalidation complète.

### FULL_REQUIRED

Au moins un signal empêche de prouver une portée partielle sûre.

## Motifs explicables

```text
NO_ACTIVE_INDEX
MISSING_FINGERPRINT_BASELINE
BASELINE_INDEX_MISMATCH
BUILD_DEFINITION_CHANGED
IGNORE_POLICY_CHANGED
UNQUALIFIED_FILE_CHANGE
SOURCE_OR_TEST_CHANGED
```

Les motifs sont ordonnés et dédupliqués.

## Règles conservatrices

### Première indexation

Sans snapshot d’index actif :

```text
FULL_REQUIRED / NO_ACTIVE_INDEX
```

### Baseline absente

Un index actif sans fingerprint baseline exploitable impose :

```text
FULL_REQUIRED / MISSING_FINGERPRINT_BASELINE
```

### Baseline désalignée

Si l’`indexSnapshotId` du fingerprint ne correspond pas à
`ProjectIndexState.activeSnapshotId` :

```text
FULL_REQUIRED / BASELINE_INDEX_MISMATCH
```

Le service ne tente jamais de comparer un workspace courant à une baseline
rattachée à un autre index.

### Définition de build

Tout changement de `buildSha256` impose `FULL_REQUIRED`.

Les descripteurs restent ceux qualifiés par M7.1 :

```text
pom.xml
package.json
package-lock.json
```

### Politique d’ignore

Ajout, modification ou suppression de :

```text
.gitignore
.minosignore
```

impose `FULL_REQUIRED`, car le périmètre visible du projet peut avoir changé.

### Sources et tests

La classification réutilise exactement les racines M1 et leurs extensions :

```text
Java       .java
TypeScript .ts .tsx
```

La racine la plus spécifique est examinée en premier.

Si tous les changements restants sont des sources/tests reconnus :

```text
PARTIAL_CANDIDATE / SOURCE_OR_TEST_CHANGED
```

### Changement non qualifié

Tout fichier changé qui n’est ni un descripteur de build, ni un fichier de
politique d’ignore, ni une source/test reconnue impose :

```text
FULL_REQUIRED / UNQUALIFIED_FILE_CHANGE
```

Cela inclut volontairement les fichiers de configuration ou ressources dont
l’impact sur l’indexeur n’est pas encore qualifié.

## Suppressions

Une suppression n’est considérée comme source/test que si la découverte courante
permet encore de prouver la racine correspondante.

Exemple : si la suppression du dernier `.java` fait disparaître la racine
`src/main/java` de la découverte M1, MINOS ne reconstruit pas cette information à
partir d’une convention supposée. Le fichier devient non qualifié et impose
`FULL_REQUIRED`.

Cette règle privilégie la sûreté au gain de performance.

## Cas mixtes

Une modification source accompagnée d’un fichier non qualifié reste :

```text
FULL_REQUIRED
```

Le rapport conserve néanmoins les fichiers source/test reconnus et les fichiers
non qualifiés séparément afin d’expliquer le fallback.

## Replay réel TypeScript

`ProjectInvalidationRealFixtureTest` copie la fixture versionnée :

```text
fixtures/typescript/typescript-modules
```

Puis vérifie deux scénarios depuis la même baseline :

1. modification d’un `.ts` sous `packages/app/src` → `PARTIAL_CANDIDATE` ;
2. modification de `package-lock.json` → `FULL_REQUIRED`.

Sortie attendue :

```text
M7.3 typescript-modules invalidation: source-scope=PARTIAL_CANDIDATE, source-files=1, build-scope=FULL_REQUIRED, build-changed=true
```

## Hors périmètre M7.3

- capacité fournisseur `INCREMENTAL_INDEXING` ;
- granularité partielle propre à scip-java ou scip-typescript ;
- choix final d’un plan d’exécution `INCREMENTAL` vs `FULL` ;
- exécution partielle ;
- modification de `IndexingLifecycleService` ;
- promotion automatique du fingerprint avec un nouvel index ;
- watcher filesystem.

## Suite

M7.4 devra introduire une capacité fournisseur explicite d’indexation incrémentale
et combiner cette qualification avec `ProjectInvalidationAssessment`.

Règle de sécurité attendue :

```text
PARTIAL_CANDIDATE + capacité fournisseur prouvée -> plan incrémental possible
sinon                                         -> plan complet
```

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La PR reste Draft jusqu’à validation locale de son head exact.

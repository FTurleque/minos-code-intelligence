# M7.3 — Règles d’invalidation conservatrices

Date : **23 juillet 2026**

Statut : **TERMINÉ, VALIDÉ LOCALEMENT ET LIVRÉ**

Suivi : issue #22.

PR : **#25**.

Head validé :

```text
e41abcf999ca94b0f3cf9accc0ae8b6a22e41ffd
```

Merge dans `main` :

```text
8f87a8fbb3f62361f88e38c9a8f22c2da2050ca8
```

## Objectif

Transformer les faits M7.1/M7.2 en une évaluation fournisseur-indépendante de
l’étendue d’invalidation, sans prétendre qu’un fournisseur sait exécuter une
indexation partielle.

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

`PARTIAL_CANDIDATE` ne constitue jamais une autorisation d’exécution. Il signifie
uniquement qu’aucun signal fournisseur-indépendant n’impose déjà un refresh complet.

## Règles conservatrices validées

```text
aucun index actif                       -> FULL_REQUIRED
baseline fingerprint absente            -> FULL_REQUIRED
baseline/index désalignés                -> FULL_REQUIRED
définition de build modifiée             -> FULL_REQUIRED
.gitignore ou .minosignore modifié       -> FULL_REQUIRED
fichier changé non qualifiable           -> FULL_REQUIRED
uniquement sources/tests M1 reconnus     -> PARTIAL_CANDIDATE
aucun changement                         -> NONE
```

Les descripteurs de build qualifiés restent :

```text
pom.xml
package.json
package-lock.json
```

Les extensions source/test qualifiées restent :

```text
Java       .java
TypeScript .ts .tsx
```

Une suppression n’est partielle que si la découverte courante permet toujours de
prouver sa racine source/test. Sinon MINOS revient à `FULL_REQUIRED`.

## Validation locale acquise

```text
128 sources main
65 sources test
184/184 tests PASS
BUILD SUCCESS
```

Replay réel :

```text
M7.3 typescript-modules invalidation: source-scope=PARTIAL_CANDIDATE, source-files=1, build-scope=FULL_REQUIRED, build-changed=true
```

## Transition M7.4

M7.3 décrit jusqu’où l’invalidation peut être bornée. M7.4 combine cette évaluation
avec les capacités réellement qualifiées des indexeurs et avec le lifecycle projet.

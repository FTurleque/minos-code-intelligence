# M8 — Analyse d’impact

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #27.

Base : M7 livré via PR #26 au commit `c66382705880158b9ccac63b5662b81bf2d8d255`.

## Objectif

Estimer la propagation **potentielle** d’une modification de symbole à partir du graphe de relations déjà connu par MINOS, sans transformer les trous de couverture du fournisseur en certitudes.

M8 travaille uniquement sur `CodeKnowledgeSnapshot` et reste indépendant de SCIP, Glean ou de tout backend particulier.

## Contrats

```text
ImpactAnalysisRequest
ImpactAnalysisReport
ImpactedSymbol
ImpactPathStep
ImpactLevel
ImpactLimitation
ImpactAnalysisService
ProjectImpactQuery
LocalProjectImpactQuery
```

## Sens de propagation

Les relations MINOS sont conservées dans leur sens factuel :

```text
source -> target
```

Pour une analyse d’impact, la traversée se fait dans le sens inverse de la dépendance observée.

Exemple :

```text
caller --CALLS--> callee
```

Une modification de `callee` peut potentiellement impacter `caller`.

M8 traverse donc les relations entrantes vers le symbole modifié.

## Relations propagées

Les types actuellement admis sont :

```text
TYPE_DEFINITION
IMPORTS
REFERENCES
EXTENDS
IMPLEMENTS
CALLS
RETURNS
ACCEPTS
READS
WRITES
INSTANTIATES
DEPENDS_ON
INJECTS
RELATED_TEST
```

Les relations structurelles telles que `DECLARES`, `CONTAINS`, `DEFINITION`, `CENTRALITY` ou `ARCHITECTURAL_ROLE` ne sont pas utilisées pour inventer une propagation métier.

Seules les relations résolues sont traversées, avec une exception contrôlée : un `RELATED_TEST` heuristique M5 est conservé comme **test potentiellement impacté** avec sa confiance explicite.

## Impact direct et indirect

```text
depth = 1 -> DIRECT
depth > 1 -> INDIRECT
```

La racine modifiée n’est jamais retournée comme son propre impact, y compris en présence d’un cycle.

La traversée est cycle-safe et bornée par `ImpactAnalysisRequest.maxDepth`.

Valeurs admises :

```text
1 <= maxDepth <= 32
1 <= maxResults <= 10 000
```

Les valeurs par défaut sont :

```text
maxDepth   = 4
maxResults = 200
```

## Chemin explicatif

Chaque `ImpactedSymbol` conserve le chemin complet depuis le symbole modifié.

Une `ImpactPathStep` contient :

- symbole considéré comme modifié à cette étape ;
- symbole potentiellement impacté suivant ;
- ID de la relation ;
- `RelationshipKind` ;
- nature de l’information ;
- confiance de la relation.

Lorsque plusieurs chemins atteignent le même symbole, MINOS choisit de manière déterministe :

1. le chemin le plus court ;
2. à profondeur égale, la confiance la plus élevée ;
3. à égalité, l’ordre lexical stable des IDs de relations.

## Score de confiance

Un fait fournisseur sans score explicite vaut `1.0` pour le calcul du chemin.

Pour un chemin :

```text
pathConfidence = min(edgeConfidence...)
```

Le score ne peut donc jamais être supérieur au maillon le moins fiable du chemin.

Aucun seuil sémantique arbitraire (`LOW`, `MEDIUM`, `HIGH`) n’est ajouté par M8.

## Tests potentiellement impactés

M5 persiste des relations :

```text
test --RELATED_TEST--> production
```

M8 les réutilise dans le sens d’impact :

```text
production -> test potentiel
```

Le meilleur chemin général d’un test peut être un `CALLS` factuel tandis qu’un chemin `RELATED_TEST` séparé porte la preuve spécifique de test lié. Le rapport conserve donc :

- le meilleur chemin d’impact général dans `impacts` ;
- le meilleur chemin se terminant par `RELATED_TEST` dans `potentiallyImpactedTests`.

Un test heuristique M5 reste explicitement heuristique et conserve sa confiance ; il n’est jamais présenté comme une garantie de couverture.

## Limites explicites

Le rapport expose systématiquement les limites de preuve dynamique suivantes :

```text
DYNAMIC_DISPATCH_NOT_PROVEN
REFLECTION_NOT_PROVEN
RUNTIME_CONFIGURATION_NOT_PROVEN
```

Il ajoute selon le graphe ou les bornes :

```text
UNRESOLVED_RELATIONSHIPS_IGNORED
EXTERNAL_TARGETS_NOT_TRAVERSED
GENERATED_SYMBOLS_NOT_TRAVERSED
MAX_DEPTH_REACHED
MAX_RESULTS_REACHED
```

Ainsi, l’absence d’un symbole dans le rapport signifie uniquement :

> aucun chemin admissible n’a été observé dans le snapshot et les bornes demandées.

Elle ne prouve pas l’absence d’impact runtime.

## Déterminisme

Les relations entrantes sont ordonnées par :

```text
source symbol id
relationship kind
relationship id
```

Les candidats sont ordonnés par :

```text
depth asc
confidence desc
path signature asc
symbol id asc
```

Les résultats sont donc reproductibles pour un même snapshot et une même requête.

## Replay réel TypeScript

`ImpactAnalysisRealFixtureTest` réutilise l’index versionné :

```text
fixtures/typescript/typescript-modules/.minos-m0/scip-typescript/index.scip
```

Le replay respecte la qualification M0 de `scip-typescript 0.4.0` : les cibles d’appels sont observables via les occurrences, mais les relations `CALLS` explicites ne sont pas publiées sur cette fixture. Il s’appuie donc sur une relation fournisseur réellement persistée et qualifiée :

```text
DefaultGreetingPort --IMPLEMENTS--> GreetingPort
```

Racine :

```text
GreetingPort
```

Le replay vérifie notamment :

- `DefaultGreetingPort` comme impact direct observé via `IMPLEMENTS` ;
- au moins un test potentiel issu des relations M5 ;
- un chemin non vide pour chaque preuve de test.

Sortie attendue :

```text
M8 typescript-modules impact: root=GreetingPort, impacts=..., tests=..., max-depth=..., limitations=[...]
```

## Hors périmètre

M8 ne prétend pas résoudre :

- réflexion non matérialisée dans le graphe ;
- dispatch dynamique non publié par le fournisseur ;
- chargement de classes/modules runtime ;
- configuration externe ;
- injection de dépendances non résolue ;
- code généré ou externe non traversable ;
- analyse de flux de données ;
- probabilité métier d’une régression.

Ces sujets nécessitent des faits supplémentaires, pas des suppositions.

## Porte finale

```powershell
.\mvnw.cmd clean verify
```

La PR M8 doit rester Draft jusqu’à validation locale de son head exact.

# M3 — Requêtes relationnelles normalisées

Statut : **premier incrément validé localement**
Date : **23 juillet 2026**

## Objectif

Ce premier incrément M3 définit comment MINOS interroge des relations déjà
normalisées, indépendamment du fournisseur qui les a produites et du backend
qui les conserve.

Il fournit le socle de `find_implementations`, `find_callers`, `find_callees`,
`dependencies` et `dependents`, désormais tous exposés par le service et la
CLI de clôture M3.

## Contrat de relation

Une `Relationship` contient :

- un identifiant stable dans son projet ;
- un `projectId` obligatoire ;
- une source normalisée ;
- une cible résolue ou un nom de cible non résolu, jamais les deux ;
- un `RelationshipKind` ;
- un emplacement source optionnel ;
- un statut de résolution ;
- une nature factuelle, dérivée ou heuristique ;
- une confiance entre 0 et 1, obligatoire pour une dérivation ou heuristique ;
- une origine et une liste de preuves structurées.

Une cible absente ne peut pas être déclarée `RESOLVED`. Une cible présente ne
peut pas être déclarée `UNRESOLVED`. Une relation dérivée ou heuristique sans
confiance ou sans preuve est refusée.

## Critères de recherche

`RelationshipSearchCriteria` combine :

| Critère | Sémantique |
|---|---|
| `anchor` | entité de code au centre de la requête |
| `direction` | `OUTGOING`, `INCOMING` ou `ANY` |
| `kinds` | ensemble de types admis ; vide signifie tous |
| `resolutionStatus` | filtre optionnel de résolution |
| `nature` | filtre optionnel factuel/dérivé/heuristique |
| `limit` | limite strictement positive appliquée après les filtres et le tri |

Une relation non résolue peut être retrouvée en sortie depuis sa source. Elle
ne peut pas apparaître comme relation entrante, puisqu'aucune cible MINOS
résolue n'existe.

## Isolation et ordre

`CodeKnowledgeStore.findRelationships` prend toujours un `projectId`. Le store
mémoire utilise la paire projet/ID comme clé : deux projets peuvent donc
posséder une relation de même identifiant sans collision.

L'ordre est reproductible :

1. pour `ANY`, relations sortantes avant entrantes ;
2. ordre canonique de `RelationshipKind` ;
3. cibles résolues avant cibles non résolues ;
4. références source et cible ;
5. nom de cible non résolue ;
6. emplacement source ;
7. identifiant de relation.

Les résultats et leurs collections de preuves sont immuables.

## Service de requête

`RelationshipQueryService` expose :

- `findRelationships` pour le contrat de domaine complet ;
- `findRelationshipResults` pour le DTO compact ;
- `findOutgoing` et `findIncoming` ;
- `findImplementations`, défini comme les relations `IMPLEMENTS` entrantes sur
  le symbole demandé ;
- `findCallers` et `findCallees` pour les faits `CALLS` disponibles ;
- `findDependencies` et `findDependents` pour la vue `DEPENDS_ON`.

`RelationshipResult` conserve les éléments nécessaires à l'explication :
résolution, nature, confiance, origine et preuves. Il n'expose aucun type SCIP,
Glean ou spécifique à un indexeur.

## Limites maintenues

Cet incrément n'invente aucune relation depuis une simple occurrence. En
particulier :

- une référence résolue n'est pas automatiquement un appel ;
- `is_implementation` est exposé comme `IMPLEMENTS` au sens navigation SCIP,
  mais n'est jamais promu en `EXTENDS` ni présenté comme preuve d'un mot-clé de
  langage ;
- les snapshots M2 v1 restent des vues symboles et donnent donc des collections
  M3 vides ; le format v2 est nécessaire pour les données relationnelles ;
- une capacité absente du fournisseur, notamment `CALLS`, produit une réponse
  vide réussie au lieu d'une relation inventée.

Le normaliseur SCIP factuel est décrit dans
[`SCIP_RELATIONSHIPS.md`](SCIP_RELATIONSHIPS.md). Le snapshot v2 et la CLI sont
décrits dans [`CODE_KNOWLEDGE_SNAPSHOTS.md`](CODE_KNOWLEDGE_SNAPSHOTS.md) et
[`CLI_RELATIONSHIPS.md`](CLI_RELATIONSHIPS.md).

## Validation

Commande exécutée :

```text
.\mvnw.cmd clean verify
```

Résultat :

```text
73 sources main compilées
31 sources test compilées
95 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

Les tests ciblent l'isolation multi-projets, les directions, les cibles non
résolues, les filtres combinés, la limite, l'ordre, l'immutabilité,
`findImplementations`, la provenance, la confiance, les preuves et la frontière
fournisseur.

---
id: ADR-0024
title: "Mettre en cache une vue de snapshot actif et reconstruire ses indexes en mémoire"
domain: data
rules: false
---

# ADR-0024 — Mettre en cache une vue de snapshot actif et reconstruire ses indexes en mémoire

Date : 26 juillet 2026

Statut : **Accepted — implémenté par M15-S7/S8**

Origine : M15-S7 / M15-S8

Relations : complété par [ADR 0047](archive/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md) (snapshot en mémoire et table de chaînes) et [ADR 0056](0056-ports-de-lecture-des-snapshots.md) (ports de lecture des snapshots).

## Context

Après M15-S6, la persistance locale est correctement séparée, mais les surfaces de lecture peuvent encore désérialiser le snapshot actif et reconstruire un store mémoire à chaque requête. Sur une séquence de requêtes portant sur le même snapshot, ce coût est répétitif et croît avec le volume de symboles, occurrences et relations.

Deux contraintes de correction dominent :

1. une promotion de snapshot doit devenir visible immédiatement pour les nouvelles requêtes ;
2. le snapshot persisté doit rester la source de vérité, afin qu'un index mémoire puisse toujours être détruit et reconstruit.

## Decision

MINOS construit une `SnapshotQueryView` immuable contenant :

```text
SnapshotDescriptor
CodeKnowledgeSnapshot
InMemoryCodeKnowledgeStore indexé
coût de construction
```

`FileSymbolSnapshotStore` conserve un cache borné de ces vues dont l'identité logique est :

```text
(projectId, snapshotId)
```

Le descriptor complet (nom du fichier, checksum, version et cardinalités) est également comparé avant un cache hit. Cela protège le cas historique où un même `snapshotId` peut être republié avec un contenu différent.

### Publication sûre

Sur cache miss :

```text
read active descriptor A
        ↓
load + verify snapshot A
        ↓
build indexes
        ↓
read active descriptor again
        ↓
A still active ? ── no ──> discard and retry
        │
       yes
        ↓
publish immutable query view
```

Une invalidation explicite après une promotion effectuée par la même instance est utilisée pour l'hygiène mémoire, mais **la correction ne dépend pas de ce callback**. Une promotion effectuée par une autre instance est détectée par la relecture du pointeur actif.

### Indexes reconstruisibles

Le store mémoire reconstruit notamment :

```text
symbolId          -> Symbol
normalizedName    -> List<Symbol>
qualifiedName     -> List<Symbol>
fileId            -> List<Symbol>
resolvedSymbolId  -> List<Occurrence>
sourceEntity      -> List<Relationship>
targetEntity      -> List<Relationship>
relationshipKind  -> List<Relationship>
```

Les entités normalisées restent canoniques. Les indexes ne sont jamais persistés comme nouvelle source de vérité en M15.

## Do's and Don'ts

### Do

- Identifier une vue en cache par `(projectId, snapshotId)` et comparer le descriptor complet avant un cache hit.
- Relire le descriptor actif après construction et écarter la vue si le snapshot n’est plus actif.
- Garder les indexes entièrement reconstruisibles depuis `CodeKnowledgeSnapshot`.

### Don't

- Ne pas faire dépendre la correction du cache du callback d’invalidation.
- Ne jamais laisser un cache hit masquer une promotion.
- Ne pas persister les indexes comme nouvelle source de vérité.
- Ne pas modifier les résultats, tris, filtres et limites des contrats existants.

## Consequences

### Positives

- les requêtes répétées sur le même snapshot réutilisent une seule désérialisation ;
- les indexes sont construits une fois par vue et partagés entre CLI/API/MCP/NEXUS lorsqu'ils utilisent le même store ;
- les promotions concurrentes ne publient pas de vue obsolète ;
- la compatibilité du format disque S6 reste inchangée ;
- le coût de construction et la cardinalité des indexes deviennent mesurables pour M16.

### Limites

- le cache est borné en nombre d'entrées, pas encore en octets ; M16 doit mesurer la mémoire réelle avant d'adopter une politique plus fine ;
- les recherches lexicales de type préfixe/contains peuvent encore parcourir la liste d'un projet lorsque les indexes exacts ne suffisent pas à préserver le ranking historique ;
- aucune persistence des indexes secondaires n'est décidée ;
- aucune politique automatique de rétention des anciens snapshots n'est ajoutée.

## Compliance and Enforcement

- Test `FileSymbolSnapshotStoreCacheTest` (`minos-storage-local`, lancé par `mvnw verify`) — réutilisation de la vue, promotion externe visible sans invalidation explicite, republication d’un même `snapshotId` sans contenu périmé, construction unique en cas de cache miss concurrent.
- Revue : toute évolution du cache garde l’identité `(projectId, snapshotId)`, la comparaison du descriptor et la relecture du pointeur actif.

## Invariants

- `(projectId, snapshotId)` reste l'identité logique de cache ;
- le descriptor actif est la preuve de la version réellement sélectionnée ;
- un cache hit ne doit jamais masquer une promotion ;
- les résultats, tris, filtres et limites des contrats existants restent inchangés ;
- les indexes doivent être entièrement reconstruisibles depuis `CodeKnowledgeSnapshot`.

## Validation

Les tests M15-S7/S8 couvrent cache hit, promotion externe, republication du même identifiant logique, concurrence, indexes de fichier/usages/relations et construction directe depuis un snapshot.

La qualification exacte et les mesures avant/après sont enregistrées dans la PR #62 et l'issue #55.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Le cache de vues est borné aussi en octets, alors que « Limites » le dit borné en nombre d’entrées seulement — preuve : `minos-storage-local/src/main/java/com/minos/storage/local/store/FileSymbolSnapshotStore.java:59-60`.

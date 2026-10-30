---
id: ARCH-0056
title: "Exposer un port de lecture de snapshot indépendant du store mémoire"
domain: architecture
rules: false
---

# Exposer un port de lecture de snapshot indépendant du store mémoire

Date : 2026-10-05. Statut : **Accepted**.

## Context

Sur `develop` (`c9a3390`), `CodeKnowledgeSnapshotStore`, `SnapshotQueryView` et `InMemoryCodeKnowledgeStore` sont déjà dans `minos-engine`. Leur déplacement depuis le module local a donc été réalisé. Le couplage résiduel est dans le contrat : `SnapshotQueryView.queryStore()` expose la classe concrète et `indexMetrics()` son type imbriqué. Le port `CodeKnowledgeStore` réunit écritures et lectures.

## Decision

Introduire dans le cœur un port de lecture minimal (nom de travail `CodeKnowledgeReader`) et des métriques neutres, définis par les consommateurs réels. L'index mémoire implémente ce port ; il reste une optimisation interne réutilisable. Les cas d'usage ne doivent plus appeler de mutations via une vue de lecture. Conserver une voie d'écriture explicite pour l'ingestion et la construction de l'index.

La séparation commandes/requêtes reste locale. Elle n'exige ni broker, ni Event Sourcing, ni deux bases. Le snapshot structuré reste autoritatif ; les index secondaires sont reconstruisibles. Préserver une identité de snapshot cohérente sur toute requête, les budgets et les limites de cache. La fusion de stockage (ADR 0055) ne modifie pas ce principe.

Ne pas remplacer directement le type d'un composant de record public sans analyse : cela change son constructeur et les descripteurs binaires. SH-04 doit choisir une migration additive (nouvelle vue/accessor neutre, ancien contrat déprécié et conservé) ou consigner une rupture explicitement versionnée avant retrait. Aucun changement silencieux du contrat public n'est accepté. Le nom final du nouveau type dépendra de cette analyse.

## Do's and Don'ts

### Do

- Introduire dans le cœur un port de lecture minimal et des métriques neutres, définis par les consommateurs réels.
- Conserver une voie d'écriture explicite pour l'ingestion et la construction de l'index.
- Migrer un record public de façon additive (nouvel accesseur neutre, ancien contrat déprécié et conservé) ou consigner une rupture explicitement versionnée avant retrait.
- Préserver résultats, classement, ordre et métriques.

### Don't

- Les cas d'usage n'appellent pas de mutations via une vue de lecture.
- Ni broker, ni Event Sourcing, ni deux bases.
- Aucun changement silencieux du contrat public.
- Pas d'interface géante recopiant toute l'implémentation.

## Alternatives et conséquences

Conserver l'exposition de la classe mémoire est plus simple à court terme mais lie tous les consommateurs à ses détails et à ses mutateurs. Une interface géante recopiant toute l'implémentation ne résout pas le problème. Le port sera limité aux lectures nécessaires, avec une voie de compatibilité temporaire si nécessaire.

Le gain attendu est la substituabilité et la testabilité, pas un gain de performance annoncé. Préserver résultats, classement, ordre et métriques ; toute évolution algorithmique doit être traitée séparément.

## Relations et mise en œuvre

Complète l'[ADR 0024](DATA-0024-active-snapshot-query-view-and-rebuildable-indexes.md), préserve l'[ADR 0042](ARCH-0042-racine-de-composition.md). [Tâches SH-04 et SH-12](../../project/roadmap/storage-hexagonal-2026-10/README.md).

Validation : consommateur compilé avant/après si contrat publié, double de lecture non mémoire, absence d'accès aux mutations depuis les cas d'usage de lecture, cohérence pendant promotion concurrente, résultats identiques sur fixtures et golden existants.

**Mise en œuvre partielle, 2026-10-13** (changement OpenSpec `lire-l-identite-du-snapshot-actif-sans-le-charger`, constat AUD-PERF-01 de l'audit du 10 octobre 2026, PR #445) : le port `CodeKnowledgeSnapshotStore` gagne la lecture `activeSnapshotId` en méthode `default` (chargement complet puis identifiant), redéfinie par lecture du seul pointeur actif dans `FileSymbolSnapshotStore` et `PostgresCodeKnowledgeSnapshotStore` ; `SemanticVectorStore.documents` lit documents et métadonnées sans vecteurs (redéfinie sous PostgreSQL). Ajouts seuls, aucun record public modifié. Une requête hybride prête charge le snapshot une fois au lieu de cinq (décompte par faux stores). Trois autres appelants de `loadActiveKnowledge` ne lisent que l'identifiant et ne sont pas encore passés au port (relevé dans le `design.md` archivé du changement).

**Mise en œuvre complémentaire, 2026-10-15** (changement OpenSpec `lire-l-identifiant-du-snapshot-a-la-reconciliation`) : les trois appelants passent à `activeSnapshotId`. Premier cas de **migration additive d'un record public** selon la règle ci-dessus : `ProjectIndexStateReconciler` gagne le record `ReconciledState` (identifiant du snapshot actif au lieu du snapshot chargé) et les lectures `reconcileState`, `observeState`, `observeStatusState` ; le record `Reconciliation` et `reconcile`, `observe`, `observeStatus` sont conservés, dépréciés (`@Deprecated(since = "1.3.0")`, sans retrait programmé) et rendent le même résultat au prix d'un seul chargement vérifié. Décompte par faux store : une lecture de statut passe de 2 chargements complets à 0, une réparation de 5 à 0, l'ancien contrat à 1. Le retrait de l'ancien contrat reste une rupture à versionner explicitement.

**Mise en œuvre, 2026-10-23** (changement OpenSpec `exposer-un-port-de-lecture-de-snapshot`, tâche SH-04, constat AUD-ARC-07 de l'audit du 10 octobre 2026) : noms retenus `CodeKnowledgeReader` (port de lecture, sept lectures), `SnapshotReadView` (vue neutre du snapshot actif, classe finale qui mémorise son index dérivé) et `IncomingRelationships` (index des arêtes entrantes, implémenté par `InMemoryCodeKnowledgeStore.SymbolEdgeIndex`). `CodeKnowledgeStore` étend `CodeKnowledgeReader` et ne déclare plus que la voie d'écriture de l'ingestion ; `SymbolQueryService`, `RelationshipQueryService`, `CodeSearchService` et `ModuleFilterGuard.require` reçoivent `CodeKnowledgeReader` (rupture binaire de ces classes internes, listée dans `docs/user/java-api.md` § Ruptures). Migration additive du record public : `CodeKnowledgeSnapshotStore.loadActiveReadView` est une méthode `default` dérivée de `loadActiveQueryView`, qui reste abstraite ; `SnapshotQueryView.readView()` et la fabrique `SnapshotQueryView.of` relient les deux contrats ; `SnapshotQueryView`, `queryStore()`, `indexMetrics()`, `loadActiveQueryView` et la surcharge `ImpactAnalysisService.analyze(SnapshotQueryView, …)` sont dépréciés (`@Deprecated(since = "1.3.0")`, sans retrait programmé) et rendent le même résultat. Les deux stores mettent en cache la vue neutre, construite par la fabrique du cœur `SnapshotReadView.indexed`, et servent l'ancien contrat depuis elle : une vue et au plus un index des arêtes par snapshot actif pour les deux contrats (décompte par tests à compteurs, fichier et PostgreSQL). Métriques neutres : aucune ; `IndexMetrics` reste sur la classe mémoire, faute de consommateur de production (décision D3 du changement). Le coût de l'index est inchangé : un index mémoire complet est construit une fois par vue, sur défaut de cache (ADR 0024). Contrôle automatique : règle A18 de `scripts/architecture/check-module-boundaries.py` (seul `com.minos.store` de `minos-engine` nomme `InMemoryCodeKnowledgeStore` ; l'ancienne vue est bornée à une liste fermée ; `minos-application` ne nomme pas `CodeKnowledgeStore`). Un double de lecture non mémoire sert recherche, usages, relations, impact et rapport runtime dans les tests de `minos-engine` et de `minos-application`.

## Compliance and Enforcement

- Règle A18 de `scripts/architecture/check-module-boundaries.py` (job `invariants`) : seul le package `com.minos.store` de `minos-engine` nomme `InMemoryCodeKnowledgeStore` ; `SnapshotQueryView`, `loadActiveQueryView(` et `queryStore()` ne sont utilisés que par la liste fermée `LEGACY_SNAPSHOT_VIEW_USERS` ; `minos-application` ne nomme pas `CodeKnowledgeStore`.
- Revue : aucun cas d'usage de lecture n'accède aux mutations du store ; toute modification d'un record public est additive ou consignée comme rupture versionnée.

## Écarts constatés

Aucun écart ouvert.

## References

- [Cockburn — Ports and Adapters](https://alistair.cockburn.us/hexagonal-architecture)
- [Microsoft — CQRS, modèles séparés avec stockage commun](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs)

# ADR 0056 — Exposer un port de lecture de snapshot indépendant du store mémoire

Date : 2026-10-05. Statut : **Accepted — direction validée ; implémentation à réaliser**.

## Contexte

Sur `develop` (`c9a3390`), `CodeKnowledgeSnapshotStore`, `SnapshotQueryView` et `InMemoryCodeKnowledgeStore` sont déjà dans `minos-engine`. Leur déplacement depuis le module local a donc été réalisé. Le couplage résiduel est dans le contrat : `SnapshotQueryView.queryStore()` expose la classe concrète et `indexMetrics()` son type imbriqué. Le port `CodeKnowledgeStore` réunit écritures et lectures.

## Décision

Introduire dans le cœur un port de lecture minimal (nom de travail `CodeKnowledgeReader`) et des métriques neutres, définis par les consommateurs réels. L'index mémoire implémente ce port ; il reste une optimisation interne réutilisable. Les cas d'usage ne doivent plus appeler de mutations via une vue de lecture. Conserver une voie d'écriture explicite pour l'ingestion et la construction de l'index.

La séparation commandes/requêtes reste locale. Elle n'exige ni broker, ni Event Sourcing, ni deux bases. Le snapshot structuré reste autoritatif ; les index secondaires sont reconstruisibles. Préserver une identité de snapshot cohérente sur toute requête, les budgets et les limites de cache. La fusion de stockage (ADR 0055) ne modifie pas ce principe.

Ne pas remplacer directement le type d'un composant de record public sans analyse : cela change son constructeur et les descripteurs binaires. SH-04 doit choisir une migration additive (nouvelle vue/accessor neutre, ancien contrat déprécié et conservé) ou consigner une rupture explicitement versionnée avant retrait. Aucun changement silencieux du contrat public n'est accepté. Le nom final du nouveau type dépendra de cette analyse.

## Alternatives et conséquences

Conserver l'exposition de la classe mémoire est plus simple à court terme mais lie tous les consommateurs à ses détails et à ses mutateurs. Une interface géante recopiant toute l'implémentation ne résout pas le problème. Le port sera limité aux lectures nécessaires, avec une voie de compatibilité temporaire si nécessaire.

Le gain attendu est la substituabilité et la testabilité, pas un gain de performance annoncé. Préserver résultats, classement, ordre et métriques ; toute évolution algorithmique doit être traitée séparément.

## Relations et mise en œuvre

Complète l'[ADR 0024](0024-active-snapshot-query-view-and-rebuildable-indexes.md), préserve l'[ADR 0042](0042-racine-de-composition.md). [Tâches SH-04 et SH-12](../../project/roadmap/storage-hexagonal-2026-10/README.md).

Validation : consommateur compilé avant/après si contrat publié, double de lecture non mémoire, absence d'accès aux mutations depuis les cas d'usage de lecture, cohérence pendant promotion concurrente, résultats identiques sur fixtures et golden existants.

**Mise en œuvre partielle, 2026-10-13** (changement OpenSpec `lire-l-identite-du-snapshot-actif-sans-le-charger`, constat AUD-PERF-01 de l'audit du 10 octobre 2026, PR #445) : le port `CodeKnowledgeSnapshotStore` gagne la lecture `activeSnapshotId` en méthode `default` (chargement complet puis identifiant), redéfinie par lecture du seul pointeur actif dans `FileSymbolSnapshotStore` et `PostgresCodeKnowledgeSnapshotStore` ; `SemanticVectorStore.documents` lit documents et métadonnées sans vecteurs (redéfinie sous PostgreSQL). Ajouts seuls, aucun record public modifié. Une requête hybride prête charge le snapshot une fois au lieu de cinq (décompte par faux stores). Trois autres appelants de `loadActiveKnowledge` ne lisent que l'identifiant et ne sont pas encore passés au port (relevé dans le `design.md` archivé du changement).

**Mise en œuvre complémentaire, 2026-10-15** (changement OpenSpec `lire-l-identifiant-du-snapshot-a-la-reconciliation`) : les trois appelants passent à `activeSnapshotId`. Premier cas de **migration additive d'un record public** selon la règle ci-dessus : `ProjectIndexStateReconciler` gagne le record `ReconciledState` (identifiant du snapshot actif au lieu du snapshot chargé) et les lectures `reconcileState`, `observeState`, `observeStatusState` ; le record `Reconciliation` et `reconcile`, `observe`, `observeStatus` sont conservés, dépréciés (`@Deprecated(since = "1.3.0")`, sans retrait programmé) et rendent le même résultat au prix d'un seul chargement vérifié. Décompte par faux store : une lecture de statut passe de 2 chargements complets à 0, une réparation de 5 à 0, l'ancien contrat à 1. Le retrait de l'ancien contrat reste une rupture à versionner explicitement.

## Références

- [Cockburn — Ports and Adapters](https://alistair.cockburn.us/hexagonal-architecture)
- [Microsoft — CQRS, modèles séparés avec stockage commun](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs)

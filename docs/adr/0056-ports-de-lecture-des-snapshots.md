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

Complète l'[ADR 0024](0024-active-snapshot-query-view-and-rebuildable-indexes.md), préserve l'[ADR 0042](0042-racine-de-composition.md). [Tâches SH-04 et SH-12](../roadmap/storage-hexagonal-2026-10/README.md).

Validation : consommateur compilé avant/après si contrat publié, double de lecture non mémoire, absence d'accès aux mutations depuis les cas d'usage de lecture, cohérence pendant promotion concurrente, résultats identiques sur fixtures et golden existants.

## Références

- [Cockburn — Ports and Adapters](https://alistair.cockburn.us/hexagonal-architecture)
- [Microsoft — CQRS, modèles séparés avec stockage commun](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs)

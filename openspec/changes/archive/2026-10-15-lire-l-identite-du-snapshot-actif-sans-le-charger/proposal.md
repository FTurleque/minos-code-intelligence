# Proposal

## Why

**AUD-PERF-01** (sévérité élevée, sprint 4 de l'audit du 10 octobre 2026, sur le chemin critique : il débloque AUD-PERF-06). Une recherche hybride avec un index sémantique `READY` fait, **par requête** :

- **5** chargements du snapshot actif (`loadActiveKnowledge`), dont quatre pour ne lire que son identifiant ;
- **4** lectures de métadonnées et **4** calculs de taille de l'index sémantique ;
- **1** chargement **complet** de l'index vectoriel (`activeIndex`), qui ne sert qu'à obtenir la liste des documents et la date de construction, la recherche vectorielle étant faite ensuite côté store.

Sous PostgreSQL, ce chargement complet rapatrie tous les vecteurs sérialisés en texte (`embedding::text`) et les analyse composante par composante ; au budget par défaut l'audit l'évalue à environ 300 Mo de texte par requête, sans cache. Sous le store fichier, au-delà de la limite d'admission du cache de vues (AUD-PERF-03), les cinq chargements sont cinq décodages complets.

C'est le chemin interactif de `minos_hybrid_search`, `minos_hybrid_context` et `ide hybrid-context`, dans la configuration « connected » documentée (PostgreSQL + Ollama).

## What Changes

- **Lecture légère de l'identité du snapshot actif** sur le port des snapshots, utilisée partout où seul l'identifiant est lu.
- **Une requête hybride ne charge plus aucun vecteur** : l'identité du corpus vient des métadonnées de l'index, et les documents se lisent sans leurs embeddings.
- **La taille de l'index n'est plus calculée sur le chemin de requête** ; elle reste rendue par l'outil de statut.
- **La garde de cohérence est conservée** : le contrôle « l'index n'a pas changé pendant la requête » reste, sur la lecture légère.

## Capabilities

### New Capabilities

- `recherche-semantique` : aucune capacité existante ne porte les exigences de la recherche hybride et de l'index sémantique (coût par requête, cohérence, synchronisation). Elle reçoit ici le coût borné d'une requête ; les changements `synchroniser-l-index-semantique-par-differentiel` et `lire-chaque-source-une-fois-par-fichier` la complètent.

### Modified Capabilities

(aucune)

## Hors périmètre

- La synchronisation de l'index à l'indexation (AUD-PERF-06) et la construction des documents (AUD-PERF-02) : changements suivants, qui dépendent de celui-ci.
- Le poids du cache de vues (AUD-PERF-03) et le double aller-retour du pool JDBC (AUD-PERF-12).
- L'ADR 0056 (port de lecture neutre des snapshots) : ce changement ajoute une lecture étroite sans préempter cette refonte.
- Les autres appelants de `loadActiveKnowledge` hors du chemin sémantique (`WorkspaceIntelligenceService`, `MinosApplicationAssembler`, `ProjectIndexStateReconciler`, `LocalAutonomousIndexOperations`) : non relus ; la tâche 1.1 dit s'ils relèvent du même motif.
- Le dimensionnement du tas du conteneur de requête.

## Impact

- **Modules du reactor** : `minos-domain` ou `minos-engine` (port `CodeKnowledgeSnapshotStore`, port `SemanticVectorStore`), `minos-application` (`application/semantic/HybridSearchService`, `SemanticSearchService`, `SemanticIndexService`), `minos-storage-local`, `minos-storage-postgresql` (`semantic/PostgresSemanticReadQueries`, `snapshot/PostgresCodeKnowledgeSnapshotStore`). Direction ADR 0022 respectée : le port s'élargit, les adaptateurs l'implémentent.
- **Surfaces publiques** : aucune sortie CLI, MCP ou API ne change. Le record public de statut garde son champ de taille. Les ports gagnent des méthodes `default` (additif).
- **ADR** : aucun nouvel ADR ; cohérent avec l'ADR 0056 (à citer dans sa « Mise en œuvre »).
- **Gates à rejouer** : `python scripts/remediation/check-mne.py` (`:123-124` exige `activeSemanticIndex.builtAtEpochMilli()`, `corpusIdentity`, `identity().equals(corpusIdentity)`), `check-mnd.py` (`:105-106`), `scripts/quality/check-semantic-retrieval-consistency.py` (`:121-138`), scopes JaCoCo `semantic-hybrid-retrieval` et `m30-postgresql-pgvector`, `python scripts/architecture/check-module-boundaries.py`.
- **Plateformes** : Windows et Linux ; PostgreSQL réel pour la part store.

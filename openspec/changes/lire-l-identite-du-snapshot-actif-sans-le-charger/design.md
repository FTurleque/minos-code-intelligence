# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-01**. HEAD analysé : `2d1cc214` (`develop`). Abréviations : `APP` = `minos-application/src/main/java/com/minos`, `PG` = `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql`.

## État vérifié au HEAD

### AUD-PERF-01 — confirmé (décompte refait en suivant les appels)

- `APP/application/semantic/HybridSearchService.java:59` : `snapshots.loadActiveKnowledge` (1) ; `:61` `semanticIndex.status` ; `:64` `semanticIndex.activeIndex` ; `:74` appel de `SemanticSearchService`.
- `SemanticIndexService.java:59-64` : `activeIndex` = `status(project)` puis `store.load(...)`. `:66-104` : chaque `status` fait `loadActiveKnowledge` (`:68`), `store.metadata` (`:69`) et un `sizeBytes`.
- `SemanticSearchService.java:41` et `:53` : `status` avant et après la recherche.
- **Total par requête** : 5 `loadActiveKnowledge`, 4 `metadata`, 4 `sizeBytes`, 1 `load` complet, 1 `search`, plus 5 résolutions de projet. Identique au constat.
- PostgreSQL, vue en cache : 15 `withConnection` par requête (`PG/snapshot/PostgresCodeKnowledgeSnapshotStore.java:119` ; `PG/semantic/PostgresSemanticReadQueries.java:36-54` `metadata`, `:56-83` `load`, `:114-125` `sizeBytes`).
- La lecture légère **existe déjà** : `PostgresSemanticReadQueries.java:26` (`activeKnowledgeSnapshotId`, appelée par des tests seulement) et `FileSymbolSnapshotStore.java:181`. Elle n'est pas sur le port.
- Autre site du même motif : `SemanticIndexService.java:173`, `loadActiveKnowledge` pour le seul identifiant, **dans la transaction sous verrou de mutation**.

**Non vérifié** : les chiffres de volume de l'audit (9 498 octets par vecteur, 311 Mo au budget par défaut, tamponnage intégral du résultat par le pilote), `SemanticIndexBudget.java:15-18`, et toute durée. Aucun build ni benchmark lancé. La tâche 4.1 mesure.

### Écarts avec l'action proposée par l'audit

1. **« Calculer `status` une seule fois » supprimerait une garde.** Les deux `status` de `SemanticSearchService` (`:41`, `:53`) encadrent la recherche : s'ils diffèrent, la requête est rejouée ou signale `SEMANTIC_INDEX_CHANGED_DURING_QUERY`. Le contrôle **après** recherche est conservé ; il passe sur la lecture légère (identifiant actif + métadonnées), sans rechargement du snapshot.
2. **`sizeBytes` alimente un champ public.** `Status.indexSizeBytes` appartient à un record public : il n'est pas retiré. Le chemin de requête utilise un statut interne sans taille ; l'outil de statut continue de la calculer.
3. **L'action oublie `SemanticIndexService.java:173`** (sous verrou de mutation) : même correction.
4. **Trois gates littéraux encodent le chemin actuel** : `check-mne.py:123-124` exige `activeSemanticIndex.builtAtEpochMilli()`, `corpusIdentity` et `identity().equals(corpusIdentity)` ; `check-mnd.py:105-106` ; `check-semantic-retrieval-consistency.py:121-138`. Remplacer `activeIndex` par les métadonnées impose de les mettre à jour dans la même tâche.
5. **Le cache de corpus peut éviter jusqu'à la lecture des documents.** L'identité du corpus se calcule depuis les métadonnées ; si le corpus structuré est déjà en cache pour cette identité, aucun document n'est lu.

### Autres appelants de `loadActiveKnowledge` (tâche 1.1, relus au HEAD `9e4a6e61` le 2026-10-14)

| Site | Ce qui est lu du snapshot | Identifiant seul ? |
|---|---|---|
| `APP/workspace/WorkspaceIntelligenceService.java:113` | symboles et relations de chaque projet (résolution inter-dépôts : relations `:122`, symboles `:155`) | Non : contenu requis |
| `APP/application/MinosApplicationAssembler.java:164` | `active.snapshotId()`, comparé à l'identifiant lié (`:167`) | **Oui** |
| `APP/application/ProjectIndexStateReconciler.java:182` (`loadActive`, appelé `:79`, `:81`, `:144`) | `snapshotId()` (`:90`) et comparaison de snapshots ; le snapshot est rendu dans `Reconciliation.activeSnapshot`, dont le seul consommateur de production (`APP/application/ProjectInspectionService.java:181-182`) ne lit que `snapshotId` | **Oui** |
| `minos-cli/.../operations/LocalAutonomousIndexOperations.java:238` | `snapshotId()` (`:240`, `:244`) | **Oui** |

Trois sites sur quatre ne lisent que l'identifiant. Le plus coûteux est le réconciliateur : toute lecture de statut (`observeStatus`, donc `minos_index_status` et `project inspect`) charge le snapshot actif complet **deux fois** (`:79` et `:81`), trois en cas de réparation (`:144`). Ces sites sont hors du chemin de requête hybride visé par AUD-PERF-01 et ne sont pas modifiés par ce changement ; les faire passer à `activeSnapshotId` (désormais sur le port) est une suite à part, qui touche le record public `Reconciliation`.

## Goals / Non-Goals

**Goals** : une requête hybride `READY` charge la vue du snapshot au plus une fois, ne lit aucun vecteur, ne calcule aucune taille d'index ; la garde de cohérence et les sorties sont inchangées.

**Non-Goals** : changer le classement hybride ; mettre en cache l'index vectoriel ; refondre les ports de lecture (ADR 0056).

## Decisions

### D1. Lecture de l'identité active sur le port, en méthode `default`

`CodeKnowledgeSnapshotStore` gagne une lecture de l'identifiant du snapshot actif. Implémentation `default` : celle d'aujourd'hui (chargement puis `snapshotId()`), pour ne casser ni les faux de test ni un adaptateur tiers ; les deux stores la **redéfinissent** par leur lecture légère existante. Un test de contrat commun vérifie que la lecture légère et le chargement complet rendent le même identifiant, y compris sans snapshot actif.

### D2. Statut interne sans taille

`SemanticIndexService` sépare le calcul du statut de celui de la taille. Le chemin de requête demande le statut sans taille ; `minos_semantic_index_status` et la CLI gardent la taille. Le record public est inchangé.

### D3. Documents sans vecteurs

`SemanticVectorStore` gagne une lecture des documents de l'index **sans embeddings** (méthode `default` qui projette le chargement complet ; PostgreSQL la redéfinit par une requête sans la colonne `embedding`, le store fichier par ce que son format permet). `HybridSearchService` n'appelle plus `activeIndex` : identité de corpus depuis les métadonnées (snapshot, provider, modèle, dimensions, date de construction), documents par la nouvelle lecture, et seulement si le corpus n'est pas déjà en cache pour cette identité.

### D4. La garde de cohérence est conservée et testée

Un test force un changement d'index entre la recherche et le contrôle de fin : le résultat est celui d'aujourd'hui (nouvel essai, puis signalement). La garde ne coûte plus qu'une lecture d'identifiant et une lecture de métadonnées.

### D5. La preuve est un décompte, pas une durée

Faux `CodeKnowledgeSnapshotStore` et `SemanticVectorStore` à compteurs : une requête hybride `READY` → 1 chargement de vue au plus, 0 `load`, 0 `sizeBytes`, et un nombre de lectures de métadonnées borné et écrit dans le test. La mesure de durée (tâche 4.1) est indicative.

## Direction des dépendances

Les ports s'élargissent dans leur module ; `minos-application` ne dépend d'aucun adaptateur (`application ↛ adaptateurs`). Aucune arête nouvelle : `check-module-boundaries.py` reste vert.

## Qualification des capacités

- Requête hybride sans chargement de vecteurs, store PostgreSQL : **non qualifiée** au HEAD ; qualifiée par D3 et D5.
- Store fichier : lecture des documents sans vecteurs **partielle** si le format impose de lire le fichier entier (à établir en tâche 1.1) ; le gain d'identité (D1) vaut dans tous les cas.

## Windows et Linux

Aucun comportement propre à une plateforme. Tests du store PostgreSQL sur base réelle (CI Linux).

## Risks / Trade-offs

- Une méthode `default` coûteuse peut masquer un adaptateur non optimisé : le test de contrat compte les chargements des deux stores réels.
- Divergence entre lecture légère et pointeur actif en cas d'écriture concurrente : la lecture légère lit le même pointeur que le chargement ; la garde D4 couvre le changement pendant la requête.
- Mise à jour de trois gates littéraux : faite dans la tâche qui change le code, jamais après.

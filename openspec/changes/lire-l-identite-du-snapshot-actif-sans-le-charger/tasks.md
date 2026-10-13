# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. À fusionner avant `synchroniser-l-index-semantique-par-differentiel` (AUD-PERF-06).

## 1. Relevé (AUD-PERF-01)

- [ ] 1.1 [AUD-PERF-01] Relire les autres appelants de `loadActiveKnowledge` (`WorkspaceIntelligenceService.java:113`, `MinosApplicationAssembler.java:164`, `ProjectIndexStateReconciler.java:182`, `LocalAutonomousIndexOperations.java:238`) : dire lesquels ne lisent que l'identifiant. Établir si le store fichier peut lire les documents sémantiques sans les vecteurs. Relire les littéraux de `check-mne.py:123-124`, `check-mnd.py:105-106`, `check-semantic-retrieval-consistency.py:121-138`. Consigner dans `design.md`.

## 2. Identité du snapshot actif (AUD-PERF-01)

- [ ] 2.1 [AUD-PERF-01] Rouge : test de `minos-application` avec faux `CodeKnowledgeSnapshotStore` et `SemanticVectorStore` à compteurs — une requête hybride `READY`, attendu 1 chargement de snapshot au plus. **Preuve du rouge : 5 au HEAD.** Test de contrat des deux stores : lecture légère = chargement complet, avec et sans snapshot actif.
- [ ] 2.2 [AUD-PERF-01] Port `CodeKnowledgeSnapshotStore` : lecture de l'identifiant actif en `default` (D1), redéfinie dans `FileSymbolSnapshotStore` et `PostgresCodeKnowledgeSnapshotStore` ; `SemanticIndexService.status` et `:173` l'utilisent. Preuve : tests verts ; `python scripts/architecture/check-module-boundaries.py` ; scopes JaCoCo `semantic-hybrid-retrieval`, `persistence-cache-indexes`, `m30-postgresql-pgvector`.

## 3. Ni vecteurs ni taille sur le chemin de requête (AUD-PERF-01)

- [ ] 3.1 [AUD-PERF-01] Rouge : même test à compteurs — attendu 0 `load`, 0 `sizeBytes`, lectures de métadonnées bornées, aucun document relu à la seconde requête. Test de la garde de cohérence (D4) : index remplacé pendant la recherche → nouvel essai puis `SEMANTIC_INDEX_CHANGED_DURING_QUERY`. **Preuve du rouge : 1 `load` et 4 `sizeBytes` au HEAD.**
- [ ] 3.2 [AUD-PERF-01] Statut interne sans taille (D2) ; lecture des documents sans vecteurs sur `SemanticVectorStore` en `default`, redéfinie sous PostgreSQL (requête sans `embedding`) (D3) ; `HybridSearchService` sans `activeIndex`, identité de corpus depuis les métadonnées. Mettre à jour dans cette tâche les littéraux des trois gates de 1.1. Preuve : tests verts, `python scripts/remediation/check-mne.py`, `check-mnd.py`, `check-semantic-retrieval-consistency.py` verts ; résultats hybrides identiques sur les fixtures de qualification ; `./mvnw -B -ntp -pl minos-app -am verify` (goldens).

## 4. Mesure et clôture

- [ ] 4.1 [AUD-PERF-01] Mesure indicative avant/après, sur ce dépôt et les deux stores si PostgreSQL et un provider d'embeddings sont disponibles : nombre de connexions et durée d'une requête hybride. Dire ce qui n'a pas pu être mesuré.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-PERF-01 : statut, décomptes avant/après, écarts) ; signaler AUD-PERF-06 comme débloqué ; note de mise en œuvre de l'ADR 0056.
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/user/mcp.md` si le coût d'une requête hybride y est décrit.

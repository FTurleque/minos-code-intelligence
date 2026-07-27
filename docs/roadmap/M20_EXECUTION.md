# M20 — Semantic & Hybrid Code Intelligence — exécution

Statut de branche : **9/9 implémentés ; qualification exact-head finale en attente**.

Issue : #71.

## Question produit

> MINOS peut-il retrouver du code par intention ou concept tout en conservant ses faits déterministes comme source d'autorité ?

## Architecture retenue

```text
snapshot actif MINOS
   │
   ├── symboles / relations / graphes structurés
   │        ↓
   │   lexical + graph signals
   │
   └── SemanticDocumentFactory
            ↓ stableKey + checksum
       EmbeddingProvider (OPTIONNEL)
            ↓
       SemanticVectorStore reconstruisible
            ↓
       SemanticSearchService
            ↓
       HybridSearchService
            ↓
       HybridContextBuilder
            ├── API Java v1
            ├── MCP read-only
            └── NEXUS semantic signals v2
```

La couche vectorielle n'est jamais la source d'autorité. Les snapshots et graphes structurés MINOS restent les facts de référence. Le sémantique augmente le rappel et le ranking seulement lorsqu'un provider est explicitement configuré.

## Invariants

- `MinosApplication.open(...)` fonctionne sans embeddings ;
- aucun provider cloud ou téléchargement de modèle n'est obligatoire ;
- `SEMANTIC` reste `HEURISTIC` ;
- un voisinage vectoriel n'est jamais transformé en relation de code ;
- les documents ont une `stableKey` indépendante du snapshot et un checksum de contenu ;
- le vector store porte snapshot/provider/model/dimensions et reste supprimable/reconstruisible ;
- le changement de modèle force un rebuild ;
- les documents inchangés réutilisent leur vecteur lors d'une synchronisation incrémentale ;
- les budgets résultats/documents/tokens sont explicites ;
- le MCP reste read-only ;
- NEXUS conserve ranking global, sélection finale et budget multi-source.

## Sous-incréments

### M20-S1 — Semantic document model ✅ IMPLÉMENTÉ

`SemanticDocument` et `SemanticDocumentKind` définissent des unités `SYMBOL`, `FILE`, `CHUNK`. `SemanticDocumentFactory` produit des `stableKey` reproductibles, des checksums SHA-256 et des identifiants snapshot-scoped.

### M20-S2 — Embedding provider SPI ✅ IMPLÉMENTÉ

`EmbeddingProvider` abstrait provider, modèle et dimensions. L'absence de provider produit `SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE`. `LocalHashEmbeddingProvider` fournit une implémentation locale opt-in de référence, explicitement documentée comme non-LLM.

### M20-S3 — Vector store abstraction ✅ IMPLÉMENTÉ

`SemanticVectorStore` est provider-independent. `FileSemanticVectorStore` fournit un format binaire v1 local, borné et écrit atomiquement avec métadonnées snapshot/modèle. Le store est une vue reconstruisible et peut être supprimé sans perte de facts structurés.

### M20-S4 — Semantic search ✅ IMPLÉMENTÉ

`SemanticSearchService` calcule un embedding de requête et une similarité cosinus bornée. Les résultats sont marqués `HEURISTIC`, portent provider/modèle et exposent `VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT`.

### M20-S5 — Hybrid ranking ✅ IMPLÉMENTÉ

`HybridSearchService` conserve séparément `LEXICAL`, `GRAPH` et `SEMANTIC`. Sans embeddings, le fallback lexical+graph reste opérationnel. Avec embeddings, le score sémantique complète le ranking sans augmenter la nature des facts. `SemanticSearchEvaluator` mesure Recall@K, MRR et nDCG@K et compare le hybride à la baseline lexicale.

### M20-S6 — Context builder v2 ✅ IMPLÉMENTÉ

`HybridContextBuilder` borne le nombre de documents, les tokens globaux et les tokens par document. Les troncatures sont explicites et `usedTokens` ne peut pas dépasser `maxTokens`.

### M20-S7 — Incremental semantic index ✅ IMPLÉMENTÉ

`SemanticIndexService.synchronize` aligne l'index sur le snapshot actif, réutilise les vecteurs dont `stableKey + checksum` n'a pas changé, ré-embed les ajouts/modifications et supprime les documents disparus. Un changement provider/modèle/dimensions force un rebuild sûr. Taille disque et temps de rebuild sont rapportés.

### M20-S8 — API / MCP ✅ IMPLÉMENTÉ

`SemanticCodeIntelligenceApi` v1 est additive et laisse `MinosApi`/M19 inchangées. Le MCP passe de 19 à 23 tools avec :

- `minos_semantic_index_status` ;
- `minos_semantic_search` ;
- `minos_hybrid_search` ;
- `minos_hybrid_context`.

Les tools restent read-only : la synchronisation de l'index est une opération explicite de l'API/application, jamais un effet de bord de recherche MCP.

### M20-S9 — NEXUS integration v2 ✅ IMPLÉMENTÉ

`NexusSemanticSignalContract` v2 et `NexusSemanticSignalService` exportent des candidats code-local et leurs signaux. Le contrat encode explicitement que MINOS ne réalise ni ranking global multi-source, ni sélection finale, ni allocation du budget global de contexte.

## Qualification finale

Runner :

```text
scripts/m20/run-final.ps1
```

Le runner doit prouver sur un SHA exact :

1. worktree propre et HEAD stable ;
2. couche sémantique désactivée par défaut ;
3. vector store reconstruisible/versionné et aligné au snapshot ;
4. nature `HEURISTIC` et limitation explicite des scores vectoriels ;
5. Recall@K / MRR / nDCG@K mesurés sur vérité terrain contrôlée ;
6. gain hybride réel face au lexical seul ;
7. contexte v2 strictement borné ;
8. ré-embedding incrémental ciblé ;
9. 23 tools MCP et contrats API versionnés ;
10. frontière NEXUS v2 explicite ;
11. product facts, Maven Java 24, JaCoCo et non-régression historiques verts.

Verdict unique :

```text
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
```

La branche ne sera pas marquée qualifiée, Ready ou mergée avant obtention réelle de ce verdict sur son HEAD exact.

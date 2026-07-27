# M20 — Semantic & Hybrid Code Intelligence — exécution

Statut : **TERMINÉ, VALIDÉ, QUALIFIÉ EXACT-HEAD, LIVRÉ ET INTÉGRÉ — 9/9**.

Issue : #71 — `CLOSED / completed`.

PR : #72 — `MERGED`.

HEAD exact qualifié : `8d882e67649667898d55f0be97982b2f217027ba`.

Merge commit : `2d095dd2c9f0d362ee54a9840b2b3e1d217579c1`.

## Question produit

> MINOS peut-il retrouver du code par intention ou concept tout en conservant ses faits déterministes comme source d'autorité ?

**Réponse livrée : oui**, avec une couche sémantique locale et optionnelle qui améliore le rappel/ranking sans remplacer les faits structurés MINOS.

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

La couche vectorielle n'est jamais la source d'autorité. Les snapshots et graphes structurés MINOS restent les facts de référence. Le sémantique augmente le rappel et le ranking uniquement lorsqu'un provider est explicitement configuré.

## Invariants livrés

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

### M20-S1 — Semantic document model ✅

`SemanticDocument` et `SemanticDocumentKind` définissent des unités `SYMBOL`, `FILE`, `CHUNK`. `SemanticDocumentFactory` produit des `stableKey` reproductibles, des checksums SHA-256 et des identifiants snapshot-scoped.

### M20-S2 — Embedding provider SPI ✅

`EmbeddingProvider` abstrait provider, modèle et dimensions. L'absence de provider produit `SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE`. `LocalHashEmbeddingProvider` fournit une implémentation locale opt-in de référence, explicitement non-LLM.

Activation native de référence :

```text
MINOS_SEMANTIC_PROVIDER=local-hash
```

### M20-S3 — Vector store abstraction ✅

`SemanticVectorStore` est provider-independent. `FileSemanticVectorStore` fournit un format binaire v1 local, borné et écrit atomiquement avec métadonnées snapshot/modèle. Le store est une vue reconstruisible et peut être supprimé sans perte de facts structurés.

### M20-S4 — Semantic search ✅

`SemanticSearchService` calcule un embedding de requête et une similarité cosinus bornée. Les résultats sont marqués `HEURISTIC`, portent provider/modèle et exposent `VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT`.

### M20-S5 — Hybrid ranking ✅

`HybridSearchService` conserve séparément `LEXICAL`, `GRAPH` et `SEMANTIC`. Sans embeddings, le fallback lexical+graph reste opérationnel. Avec embeddings, le score sémantique complète le ranking sans augmenter la nature des facts. `SemanticSearchEvaluator` mesure Recall@K, MRR et nDCG@K et compare l'hybride à la baseline lexicale.

### M20-S6 — Context builder v2 ✅

`HybridContextBuilder` borne le nombre de documents, les tokens globaux et les tokens par document. Les troncatures sont explicites et `usedTokens` ne peut pas dépasser `maxTokens`.

### M20-S7 — Incremental semantic index ✅

`SemanticIndexService.synchronize` aligne l'index sur le snapshot actif, réutilise les vecteurs dont `stableKey + checksum` n'a pas changé, ré-embed les ajouts/modifications et supprime les documents disparus. Un changement provider/modèle/dimensions force un rebuild sûr. Taille disque et temps de rebuild sont rapportés.

Lorsque le provider sémantique est activé, `minos index` réaligne l'index sémantique après la promotion structurée. Un échec sémantique reste un diagnostic et n'invalide pas un snapshot structuré réussi.

### M20-S8 — API / MCP ✅

`SemanticCodeIntelligenceApi` v1 est additive et laisse `MinosApi`/M19 inchangées. Le MCP passe de 19 à 23 tools avec :

- `minos_semantic_index_status` ;
- `minos_semantic_search` ;
- `minos_hybrid_search` ;
- `minos_hybrid_context`.

Les tools restent read-only : la synchronisation de l'index n'est jamais un effet de bord d'une recherche MCP.

### M20-S9 — NEXUS integration v2 ✅

`NexusSemanticSignalContract` v2 et `NexusSemanticSignalService` exportent des candidats code-local et leurs signaux. Le contrat encode explicitement que MINOS ne réalise ni ranking global multi-source, ni sélection finale, ni allocation du budget global de contexte.

## Qualification finale exact-head

Runner :

```powershell
.\scripts\m20\run-final.ps1 -ExpectedHead 8d882e67649667898d55f0be97982b2f217027ba
```

Résultat obtenu sur Windows PowerShell :

```text
HEAD: 8d882e67649667898d55f0be97982b2f217027ba
[1/6] structure / invariants M20                         PASS
[2/6] product facts                                      PASS
[3/6] Maven Java 24                                      PASS
       reactor                                           13/13 SUCCESS
       BUILD SUCCESS
[4/6] JaCoCo                                             PASS
[5/6] exact HEAD + worktree propre                       PASS
[6/6] Qualification complete.
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
Validated HEAD: 8d882e67649667898d55f0be97982b2f217027ba
```

Preuves complémentaires :

- `minos-application` : **116 tests**, 0 failure/error/skipped ;
- `minos-api` : **12 tests**, 0 failure/error/skipped ;
- `minos-mcp` : **6 tests**, 0 failure/error/skipped ;
- agrégateur `minos-code-intelligence` : **50 tests**, 0 failure/error/skipped ;
- shaded JAR smoke IT : **1/1 PASS** ;
- MCP STDIO : **23 tools** ;
- JaCoCo : tous les quality gates ciblés PASS.

Les warnings protobuf `sun.misc.Unsafe`, SLF4J NOP et Maven Shade observés pendant la qualification sont non bloquants et n'ont provoqué aucun échec.

## Livraison

PR #72 fusionnée sur `main` avec verrouillage du HEAD qualifié.

```text
Qualified head: 8d882e67649667898d55f0be97982b2f217027ba
Merge commit:   2d095dd2c9f0d362ee54a9840b2b3e1d217579c1
Issue #71:      CLOSED / completed
```

M20 clôt la phase d'évolution M15→M20. Toute évolution ultérieure doit être cadrée comme une nouvelle phase/jalon et conserver les invariants d'autorité structurée, d'optionnalité sémantique, de bornes et de qualification exact-head.
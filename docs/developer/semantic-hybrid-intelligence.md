# Intelligence sémantique et hybride — M20

M20 ajoute une couche de retrieval conceptuel **optionnelle** au-dessus des facts structurés MINOS. Cette couche améliore le rappel et le ranking ; elle ne remplace jamais les identités, relations, graphes, preuves ou snapshots.

## Autorité des données

```text
facts structurés MINOS                 autoritatifs
  symboles / relations / ProgramGraph
                 ↓
        SemanticDocumentFactory        reconstruisible
                 ↓
        EmbeddingProvider              optionnel
                 ↓
        SemanticVectorStore            cache reconstruisible
                 ↓
        semantic / hybrid ranking      HEURISTIC / DERIVED
```

Un résultat vectoriel ne doit jamais créer ou renforcer artificiellement une relation `CALLS`, `DEPENDS_ON`, `DATA_FLOW`, `IMPLEMENTS` ou autre.

## Documents sémantiques

`SemanticDocumentFactory` produit trois unités :

- `SYMBOL` — identité et métadonnées d'un symbole ;
- `CHUNK` — extrait de source autour d'un symbole localisé ;
- `FILE` — représentation bornée du contenu symbolique d'un fichier.

Chaque unité possède :

- un `stableKey` indépendant du snapshot ;
- un `checksum` de contenu ;
- un `id` lié au snapshot ;
- le projet/snapshot source ;
- le type, la source et la plage de lignes.

Le couple `stableKey + checksum` est la base de l'invalidation incrémentale.

## EmbeddingProvider

```java
String id();
String modelId();
int dimensions();
SemanticVector embed(String stableKey, String text);
```

Le provider est absent par défaut. Un runtime natif peut activer explicitement le provider local de référence :

```text
MINOS_SEMANTIC_PROVIDER=local-hash
```

`LocalHashEmbeddingProvider` est déterministe, local et sans réseau. Il sert de provider de référence pour valider le pipeline et **n'est pas un modèle de langage**.

Un provider inconnu doit échouer explicitement ; il ne peut pas provoquer un fallback silencieux vers un service distant.

## Vector store

`SemanticVectorStore` conserve un `IndexSnapshot` avec :

```text
projectId
snapshotId
providerId
modelId
dimensions
builtAtEpochMilli
documents + vectors
```

`FileSemanticVectorStore` utilise un format binaire local v1 et des remplacements atomiques. Le store peut être supprimé puis reconstruit depuis le snapshot actif.

Un changement de provider, modèle ou dimensions invalide la réutilisation des anciens vecteurs.

## Synchronisation incrémentale

`SemanticIndexService.synchronize(...)` :

1. construit les documents du snapshot actif ;
2. compare le modèle courant au précédent index ;
3. compare `stableKey + checksum` ;
4. réutilise les vecteurs inchangés ;
5. embed uniquement ajouts/modifications ;
6. retire les documents disparus ;
7. remplace atomiquement l'index ;
8. rapporte nombre d'embeddings, réutilisations, suppressions, durée et taille disque.

Lorsqu'un provider est activé dans le runtime natif, `minos index` déclenche cette synchronisation après la publication structurée. Une erreur sémantique est un diagnostic : elle ne rétrograde pas un snapshot structuré déjà publié avec succès.

## Semantic search

`SemanticSearchService` exige un index `READY` et calcule la similarité cosinus avec des bornes de résultats.

Chaque hit porte :

```text
score
nature = HEURISTIC
providerId
modelId
limitations
```

La limitation `VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT` est contractuelle.

## Hybrid ranking

`HybridSearchService` conserve les signaux séparés :

| Signal | Nature | Rôle |
|---|---|---|
| `LEXICAL` | `DERIVED` | correspondance de termes/phrase |
| `GRAPH` | `DERIVED` | centralité locale simple dans le snapshot |
| `SEMANTIC` | `HEURISTIC` | rappel conceptuel vectoriel |

Sans index sémantique READY, la recherche reste fonctionnelle en `LEXICAL_GRAPH`.

Avec index READY, le mode devient `LEXICAL_GRAPH_SEMANTIC`. La combinaison reste une décision de ranking, jamais un fait de code.

## Mesures de pertinence

`SemanticSearchEvaluator` fournit :

- Recall@K ;
- MRR ;
- nDCG@K ;
- différence hybride vs baseline lexicale.

Les tests M20 imposent un cas où une requête conceptuelle sans correspondance lexicale récupère le document pertinent grâce au signal sémantique et démontre un gain mesurable.

## Context builder v2

`HybridContextBuilder` applique avant retour :

- `maxDocuments <= 100` ;
- `maxTokens <= 65 536` ;
- `maxTokensPerDocument <= maxTokens` ;
- troncature explicite par document ;
- invariant `usedTokens <= maxTokens`.

Le contexte n'invente aucun score supplémentaire : il consomme le ranking hybride déjà calculé.

## API et MCP

API Java additive : `SemanticCodeIntelligenceApi` v1.

Le MCP expose 23 tools au total. M20 ajoute :

```text
minos_semantic_index_status
minos_semantic_search
minos_hybrid_search
minos_hybrid_context
```

Le MCP reste read-only : aucune synchronisation d'index n'est déclenchée par une requête MCP.

## NEXUS v2

`NexusSemanticSignalService` exporte uniquement des candidats code-local et leurs signaux.

Frontière contractuelle :

```text
MINOS : facts de code + candidats/signaux code-local
NEXUS : ranking global multi-source + sélection finale + budget global
```

Cette séparation évite que M20 transforme MINOS en orchestrateur de contexte général.

## Qualification

Le runner final est :

```text
scripts/m20/run-final.ps1
```

Il vérifie les invariants structurels, les facts générés, le reactor Java 24 complet, JaCoCo, les métriques contrôlées, l'invalidation ciblée, les 23 tools MCP et la frontière NEXUS avant de produire le verdict exact-head M20.

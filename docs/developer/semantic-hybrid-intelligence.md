# Intelligence sémantique et hybride — M20 / M23

M20 a ajouté une couche de retrieval conceptuel **optionnelle** au-dessus des facts structurés MINOS. M23 ajoute une voie d'embeddings learned locale et mesurée, sans changer l'autorité du système. Cette couche améliore le rappel et le ranking ; elle ne remplace jamais les identités, relations, graphes, preuves ou snapshots.

## Autorité des données

```text
facts structurés MINOS                 autoritatifs
  symboles / relations / ProgramGraph
                 ↓
        SemanticDocumentFactory        reconstruisible
                 ↓
        EmbeddingProvider              optionnel
          ├─ local-hash                référence non learned
          └─ local-ollama              learned, loopback-only
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

Le provider est absent par défaut.

### Provider de référence M20

```text
MINOS_SEMANTIC_PROVIDER=local-hash
```

`LocalHashEmbeddingProvider` est déterministe, local et sans réseau. Il sert de provider de référence pour valider le pipeline et **n'est pas un modèle de langage ni un modèle learned**.

### Provider learned local M23

```text
MINOS_SEMANTIC_PROVIDER=ollama
MINOS_SEMANTIC_MODEL=<model-local>
MINOS_SEMANTIC_DIMENSIONS=<dimensions>
MINOS_SEMANTIC_ENDPOINT=http://127.0.0.1:11434/api/embed   # optionnel
```

`OllamaEmbeddingProvider` accepte uniquement un endpoint loopback, ne suit pas les redirects, borne timeout/réponse et vérifie les dimensions retournées. MINOS ne télécharge aucun modèle. La qualité du modèle est validée séparément par Recall@3/MRR/nDCG@3 ; un endpoint fonctionnel ne suffit pas à qualifier un modèle.

Un provider inconnu doit échouer explicitement ; il ne peut pas provoquer un fallback silencieux vers un service distant.

Voir [Semantic Retrieval 2.0 — M23](semantic-retrieval-2.md).

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

M20 a introduit `index-v1.bin` avec composantes float64. M23 conserve sa lecture et écrit `index-v2.bin` en float32. Le remplacement reste atomique et le cache mémoire utilise exactement la même quantification float32 que le disque, afin qu'un redémarrage ne change pas le ranking à cause d'une divergence cache/persistance.

Le store peut être supprimé puis reconstruit depuis le snapshot actif. Un changement de provider, modèle ou dimensions invalide la réutilisation des anciens vecteurs.

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

`SemanticSearchService` exige un index `READY` et calcule la similarité cosinus exacte avec des bornes de résultats.

Chaque hit porte :

```text
score
nature = HEURISTIC
providerId
modelId
limitations
```

Les limitations contractuelles incluent :

```text
VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT
VECTOR_SEARCH_LINEAR_SCAN
ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND
```

M23 ajoute un cache LRU process-local de 256 embeddings de requêtes maximum, clé par provider/modèle/dimensions/requête. Ce cache est jetable et n'est jamais autoritatif.

## Pourquoi le scan exact reste actif

M21-S8 a mesuré le STANDARD déterministe et conclu :

```text
status=PASS
decision=KEEP_CURRENT_M20_BACKEND
```

M23 n'introduit donc ni HNSW, ni Lucene, ni vector database. Une nouvelle structure ANN exige d'abord une nouvelle mesure démontrant un bottleneck réel.

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

M20 possède une preuve contrôlée du pipeline. M23 ajoute une preuve bloquante contre le **modèle learned local réellement configuré** :

```text
Recall@3 >= 0.75
MRR      >= 0.70
nDCG@3   >= 0.72
```

Le corpus est versionné dans `fixtures/m23/semantic-quality-v1.json` et le rapport est écrit dans `target/m23-quality/learned-semantic-quality.json`.

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

Le MCP expose 23 tools au total. La couche sémantique expose :

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

Cette séparation évite que la couche sémantique transforme MINOS en orchestrateur de contexte général.

## Qualification

M20 reste qualifié par :

```text
scripts/m20/run-final.ps1
```

M23 ajoute :

```text
scripts/m23/run-final.ps1
```

Le runner M23 vérifie le modèle learned local, le corpus Recall/MRR/nDCG, le reactor Java 24 complet, JaCoCo incluant `semantic-learned-provider`, la release Windows, la parité IntelliJ, les invariants M22 et l'exact-head final.

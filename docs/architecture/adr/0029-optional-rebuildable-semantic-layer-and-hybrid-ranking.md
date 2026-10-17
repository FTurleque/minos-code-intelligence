# ADR-0029 — Optional rebuildable semantic layer and hybrid ranking

- Statut : **Accepted**
- Origine : **M20**
- Date : **2026-07-27**

## Contexte

MINOS possède déjà des identités de symboles, relations, graphes, provenance et preuves structurées. Une recherche sémantique peut améliorer le rappel sur des formulations conceptuelles, mais un voisinage vectoriel ne prouve ni une relation de code, ni un flux d'exécution, ni une dépendance.

Une intégration directe à un service cloud rendrait en outre la capacité obligatoire, introduirait des coûts et déplacerait une partie de la confidentialité hors du modèle local-first de MINOS.

## Décision

1. **La couche sémantique est optionnelle.** `MinosApplication.open(...)` démarre sans provider d'embeddings. Un provider doit être configuré explicitement.
2. **Les embeddings sont derrière `EmbeddingProvider`.** Le contrat expose identité provider, modèle et dimensions. Aucune API métier ne dépend d'un SDK cloud.
3. **L'index vectoriel est reconstruisible.** Il est aligné sur un snapshot actif, stocke provider/modèle/dimensions et peut être supprimé puis reconstruit depuis les facts MINOS.
4. **Les documents possèdent une `stableKey` indépendante du snapshot.** Le checksum permet de réutiliser les vecteurs inchangés et de ré-embed uniquement les unités modifiées.
5. **Un score vectoriel est `HEURISTIC`.** Il peut participer au rappel/ranking mais n'est jamais promu en relation, preuve structurelle ou vérité runtime.
6. **Le ranking hybride conserve ses signaux.** Lexical et graph sont des dérivations structurées ; le signal sémantique reste heuristique. La combinaison est exposée comme décision de ranking, pas comme fait de code.
7. **Le contexte hybride est borné avant retour.** Nombre de documents, tokens globaux et tokens par document sont limités explicitement.
8. **Le MCP reste read-only.** Les tools M20 consultent état/recherche/contexte et ne déclenchent pas silencieusement de construction d'index.
9. **NEXUS garde sa responsabilité globale.** MINOS peut exporter des candidats code-local et leurs signaux ; NEXUS reste propriétaire du ranking multi-source, de la sélection finale et du budget de contexte global.

## Provider local de référence

`LocalHashEmbeddingProvider` est fourni comme implémentation locale, déterministe et sans réseau permettant de prouver le SPI, le stockage, l'invalidation et les surfaces. Il n'est pas activé par défaut et **n'est pas présenté comme un modèle de langage**.

Un véritable modèle local peut remplacer ce provider sans modifier les services de recherche.

## Conséquences

- MINOS reste pleinement utilisable sans embeddings ;
- les snapshots structurés restent la source d'autorité ;
- un changement de modèle force un rebuild sûr ;
- les coûts de rebuild et taille d'index sont mesurables ;
- le backend vectoriel peut évoluer sous mesures sans modifier le contrat sémantique ;
- la recherche sémantique n'augmente pas artificiellement la certitude des analyses M19.

## Limites

Le backend M20 initial effectue une recherche vectorielle linéaire. C'est volontaire : une structure ANN plus complexe ne sera introduite que si des mesures d'échelle le justifient. Le provider de hashing local ne constitue pas une preuve de qualité d'un modèle d'embeddings réel ; les métriques de qualification contrôlent surtout les invariants de retrieval/ranking et la capacité à mesurer un gain.

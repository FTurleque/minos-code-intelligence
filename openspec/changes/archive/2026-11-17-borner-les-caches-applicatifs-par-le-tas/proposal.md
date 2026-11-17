# Proposal

## Why

Le constat **AUD-PERF-04** (Moyenne, Performance, confirmé) de l'audit du 2026-11-15
(`docs/project/audits/2026-11-15/findings.json`, sprint 5) relève que le budget de mémoire des vues (ADR DATA-0081,
constat AUD-PERF-15 de l'audit du 2026-10-10, fermé avec la réserve « budget borné pour les vues seulement ») ne
compte que les vues des deux stores de snapshots. Les caches applicatifs voisins gardent un plafond constant qui
ignore le tas : corpus hybride (256 Mio), index vectoriel local (256 Mio), graphe de programme (256 Mio), projection
décodée (fichier jusqu'à 512 Mio, cache sans poids), et les symboles que l'explorateur retient hors de la vue pour
quatre projets. Dans une JVM du plan de requête Docker, la somme du budget et de ces plafonds dépasse le tas :
l'`OutOfMemoryError` qu'AUD-PERF-15 voulait éliminer reste atteignable.

**Statut des chiffres.** Toutes les sommes de l'audit et de ce changement sont **calculées sur les constantes du
code**, non mesurées : aucun tas retenu n'a été relevé pour ces caches. Ce changement commence par cette mesure
(tâche 1.1) et ne fixe aucune part du tas avant elle.

## What Changes

- **Plafond effectif dérivé du tas** : chaque cache applicatif à plafond de poids (corpus hybride, vecteurs de
  requête, index vectoriel local, graphe de programme, empreintes du provider Java) applique le plus petit de son
  plafond configuré et d'une part déclarée du tas maximal. Les parts sont écrites dans l'ADR ; un grand tas garde les
  plafonds actuels.
- **Symboles de l'explorateur** : l'explorateur ne retient plus de table de symboles hors de la vue du snapshot ; la
  résolution des symboles d'une page passe par la vue active du store, comptée par le budget, sans rien garder entre
  deux requêtes.
- **Index vectoriel local** : avant un décodage complet, le poids en mémoire de l'index est estimé ; au-delà de la
  part du tas déclarée, la lecture complète est refusée par une erreur explicite ; les lectures paginées restent
  servies.
- **Projection** : le poids décodé d'une projection est estimé depuis son en-tête avant le décodage ; une projection
  plus lourde que sa part du tas est refusée par une erreur explicite au lieu d'être décodée ; la projection gardée en
  cache est pesée.
- **Contrat Docker** : le test de contrat de l'image de requête vérifie que le budget des vues et les plafonds
  effectifs des caches tiennent ensemble sous le tas par défaut.
- **ADR** : la table des parts du tas entre dans l'ADR consolidé qui remplace DATA-0081 (`design.md`, D6).

Aucune rupture de contrat. Effet observable : sous un petit tas, un corpus, un graphe ou un index plus lourd que sa
part n'est plus gardé en cache (il est reconstruit à la requête) ; une projection trop lourde est refusée
explicitement.

## Capabilities

### New Capabilities

Aucune : chaque cache relève de la capacité de son service.

### Modified Capabilities

- `recherche-semantique` : exigences ajoutées — les caches de la recherche sémantique sont plafonnés par une part du
  tas ; un index vectoriel local plus lourd que sa part du tas n'est pas décodé en entier.
- `program-graph-et-architecture` : exigence ajoutée — les caches du graphe de programme sont plafonnés par une part
  du tas.
- `exploration-semantique` : exigences ajoutées — l'explorateur ne retient aucun symbole hors de la vue du snapshot ;
  une projection plus lourde que sa part du tas est refusée avant décodage.
- `distribution-docker` : exigence ajoutée — le budget des vues et les caches applicatifs tiennent sous le tas par
  défaut du plan de requête.

## Hors périmètre

- Inscrire ces caches comme locataires du budget des vues (première branche de l'action de l'audit) : écarté
  (`design.md`, D1) ; le budget vit dans `minos-storage`, que `minos-application` ne peut pas nommer.
- La décision renvoyée par le sprint 4 sur les vecteurs en `float[]` (type du domaine `SemanticVector`) :
  recommandation dans `design.md` (D7), non spécifiée ici. La mémoire de travail de la préparation d'une projection
  (256 Mio, `--max-memory-mb`) n'est pas un cache et reste hors des parts du tas (`design.md`, D8).
- La recherche sémantique par parcours en flux d'un index trop lourd pour être décodé : à proposer si la mesure
  montre que le refus touche un index d'usage courant (`design.md`, D9).
- La pagination de l'index local, le comptage par page et l'ajustement de la projection : sprint 4 (changements
  `paginer-l-index-semantique-local-sans-le-charger`, `retirer-le-comptage-par-page-des-lectures-postgresql`,
  `ajuster-la-projection-sur-l-echantillon-en-memoire`), **prérequis d'ordre** : ils modifient avant ce changement
  `FileSemanticVectorStore`, les lectures sémantiques PostgreSQL et la préparation de la projection. Aucune migration
  de schéma n'est prévue ici.
- AUD-PERF-05, AUD-PERF-06 et AUD-PERF-09 (autres changements du sprint 5).
- La fraction du budget des vues (50 %), `MaxRAMPercentage`, les plafonds de persistance.
- Les fichiers de l'audit et les index partagés des ADR : tenus par le propriétaire.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Corpus hybride : 64 entrées, 256 Mio, constantes | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/HybridSearchService.java:32-33`, `:41-42` |
| Index vectoriel local : 256 Mio | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:60`, `:79` |
| Graphe de programme : 16 entrées, 256 Mio | [Existant vérifié] | `minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java:36-37`, `:89` |
| Empreintes du provider Java : 128 Mio (non cité par l'audit) | [Existant vérifié] | `minos-application/src/main/java/com/minos/program/analysis/FingerprintConstrainedJavaProgramGraphProvider.java:38-39`, `:44-45` |
| Vecteurs de requête : 8 Mio (non cité par l'audit) | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticSearchService.java:20`, `:25-26` |
| Projection : fichier jusqu'à 512 Mio, un million de points, une projection décodée gardée sans poids | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticProjectionStore.java:55`, `:59`, `:68`, `:94-97` |
| `lookups` : 4 entrées de poids 1, deux tables de tous les symboles du snapshot | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java:68`, `:73-74`, `:400-419`, `:722-733` |
| Seuls les deux stores de snapshots sont locataires du budget | [Existant vérifié] | `FileSymbolSnapshotStore.java:457` ; `PostgresCodeKnowledgeSnapshotStore.java:565` ; aucune autre occurrence de `implements QueryViewMemoryBudget.Tenant` |
| Le store sémantique PostgreSQL n'a pas de cache d'index | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticVectorStore.java` : aucune occurrence de `cache` |
| Un index constructible (250 000 documents, 192 Mio de contenu, 192 Mio de vecteurs en `double[]`) peut être estimé au-delà du plafond de son cache ; il est alors décodé sans être gardé | [Existant vérifié] par lecture ; ordre de grandeur de 600 à 700 Mio calculé, non mesuré | `SemanticIndexBudget.java:15-19`, `:56-61` ; `FileSemanticVectorStore.java:57-59`, `:434-437`, `:711-729` |
| Somme sous le tas par défaut (1 075,2 Mio) : 1 185,6 Mio (PostgreSQL) ou 1 441,6 Mio (stockage local) | [Question] non bloquante | calculé, non mesuré ; mesure en tâche 1.1 |
| Parts du tas par cache | [Question] bloquante pour l'implémentation | `design.md`, D2 : fixées par le propriétaire après la mesure 1.1 |
| Plafond effectif = min(plafond configuré, part du tas) | [Proposition] | `design.md`, D1 |

## Impact

- **Modules du reactor** : `minos-engine` (primitive de part du tas à côté de `WeightedLruCache`),
  `minos-application` (`HybridSearchService`, `SemanticSearchService`, `SemanticCorpusQueryService`,
  `ProgramGraphService`, `FingerprintConstrainedJavaProgramGraphProvider`, avec leurs tests à créer dans ce module),
  `minos-storage` (`FileSemanticVectorStore`, `FileSemanticProjectionStore`), `minos-bootstrap` (tests existants à
  rejouer), `minos-app` (test de contrat et de recensement). Aucune dépendance Maven nouvelle.
- **Surfaces publiques** : aucun contrat ne change ; une projection ou un index vectoriel trop lourd pour le tas
  donne un refus explicite (additif).
- **ADR** : ADR consolidé du domaine `DATA` qui remplace DATA-0081 (prochain numéro libre à l'écriture, non réservé),
  commun aux changements du sprint 5.
- **Gates à rejouer** : `scripts/remediation/check-mnd.py`, `scripts/remediation/check-mne.py`,
  `scripts/remediation/check-p0-p2.py`, `scripts/quality/check-bounded-helpers.py`,
  `scripts/quality/check-semantic-retrieval-consistency.py`, `scripts/quality/check-advanced-provider-consistency.py`,
  `scripts/architecture/check-module-boundaries.py` (A2, A8, A11, A14), `scripts/quality/check-comment-language.py`,
  `scripts/architecture/check-private-io.py` ; scopes JaCoCo `semantic-hybrid-retrieval`, `semantic-vector-store`,
  `program-graph-analysis`, `java-advanced-provider` ; SonarCloud : nouveau code ≥ 80 %
  par module.
- **Plateformes** : Windows et Linux, même code ; le contrat Docker est une lecture de fichiers.
- **Documentation durable et diagrammes** : `docs/developer/indexing-and-storage.md`, `docs/user/docker-runtime.md`
  (« Limites de ressources »), `docs/user/semantic-explorer.md` (refus d'une projection), dossiers de capacité des
  quatre capacités touchées.

## Questions ouvertes

- **Bloquante pour l'implémentation, pas pour les specs** : les parts du tas (D2), fixées par le propriétaire sur la
  mesure 1.1.
- Non bloquantes : D1 (plafond dérivé du tas), D3 (symboles lus dans la vue), D7 (vecteurs en `float[]`, renvoyée
  par le sprint 4) et D9 (refus d'un index trop lourd), avec recommandation dans `design.md`.

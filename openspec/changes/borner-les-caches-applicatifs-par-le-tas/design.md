# Design

## Contexte

L'ADR DATA-0081 donne aux vues de snapshots un budget de 50 % du tas ; les caches applicatifs voisins gardent des
plafonds constants. Motivation : `proposal.md` (Why), constat AUD-PERF-04. Modules touchés : `minos-engine`,
`minos-application`, `minos-storage`, un test de `minos-app`. Les surfaces (CLI, MCP, explorateur, API Java, NEXUS)
gardent leurs contrats.

**Prérequis d'ordre.** Le sprint 4 passe avant : `paginer-l-index-semantique-local-sans-le-charger` réécrit les
lectures de `FileSemanticVectorStore` et introduit un chemin « index que le cache ne peut pas tenir »,
`retirer-le-comptage-par-page-des-lectures-postgresql` modifie les lectures sémantiques PostgreSQL (migration de
schéma 6 ; ce changement n'en prévoit aucune), `ajuster-la-projection-sur-l-echantillon-en-memoire` modifie la
préparation de la projection. Ce changement s'implémente sur `main` après leur fusion, et après les deux autres
changements du sprint 5 qui touchent les stores de snapshots. Les `fichier:ligne` ci-dessous sont à relire alors.

## État vérifié au HEAD

HEAD `89be3fb4`. Aucun build lancé : la vérification est une lecture.

| Fait | Preuve |
|---|---|
| Corpus hybride : `WeightedLruCache` de 64 entrées et 256 Mio, construit en champ, sans paramètre | `minos-application/src/main/java/com/minos/application/semantic/HybridSearchService.java:32-33`, `:41-42` |
| Vecteurs de requête : 256 entrées, 8 Mio | `minos-application/src/main/java/com/minos/application/semantic/SemanticSearchService.java:18`, `:20`, `:25-26` |
| Index vectoriel local : 256 Mio par défaut, plafond injectable par un constructeur de paquet | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:60`, `:71-80` |
| La synchronisation construit un index jusqu'à 250 000 documents, 192 Mio de contenu et 192 Mio de vecteurs comptés en `double[]` (le format persisté est en `float32`) | `minos-application/src/main/java/com/minos/application/semantic/SemanticIndexBudget.java:15-19`, `:56-61` |
| Le décodage applique les mêmes bornes (fichier 384 Mio, 192 Mio de chaînes, 192 Mio de vecteurs) | `FileSemanticVectorStore.java:57-59` |
| Le poids en cache d'un index compte 256 octets par document, 8 octets par dimension et 40 octets plus 2 par caractère pour chaque chaîne | même fichier `:711-729` |
| Le résultat de `put` est ignoré : un index plus lourd que le plafond est décodé puis rendu sans être gardé | même fichier `:434-437` |
| Graphe de programme : 16 entrées, 256 Mio, plafond passé au constructeur | `minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java:36-37`, `:84-90` |
| Empreintes du provider Java : 64 entrées, 128 Mio | `minos-application/src/main/java/com/minos/program/analysis/FingerprintConstrainedJavaProgramGraphProvider.java:38-39`, `:44-45` |
| Projection : fichier borné à 512 Mio et un million de points ; une projection décodée gardée dans une `HashMap`, sans poids | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticProjectionStore.java:55`, `:59`, `:68`, `:88-99`, `:209`, `:248` |
| `lookups` : `WeightedLruCache(4, 4)`, poids 1 par entrée | `minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java:68`, `:73-74`, `:416` |
| `SymbolLookup.of` indexe tous les symboles du snapshot dans deux `HashMap` ; le snapshot vient de `loadActiveKnowledge` | même fichier `:409`, `:722-733` |
| La table par identifiant ne sert qu'aux documents qui ne sont pas des fichiers, la table par fichier aux autres | même fichier `:744-746` |
| Le port de lecture offre déjà la recherche d'un symbole par identifiant | `minos-engine/src/main/java/com/minos/store/CodeKnowledgeReader.java:19` |
| Une vue porte déjà un index dérivé dont le poids est compté d'avance | `minos-engine/src/main/java/com/minos/store/SnapshotReadView.java:53-55`, `:75` |
| Seuls les deux stores de snapshots sont locataires du budget | `FileSymbolSnapshotStore.java:457` ; `PostgresCodeKnowledgeSnapshotStore.java:565` |
| Le budget et son contrat `Tenant` vivent dans `minos-storage` (`com.minos.storage.codec`) | `minos-storage/src/main/java/com/minos/storage/codec/QueryViewMemoryBudget.java:41`, `:281-287` |
| Les services applicatifs sont construits par `MinosApplication` ; les stores par `minos-storage` et `minos-bootstrap` | `minos-application/src/main/java/com/minos/application/MinosApplication.java:192`, `:199`, `:210` ; `minos-storage/src/main/java/com/minos/storage/local/LocalStorageBackend.java:53` ; `minos-bootstrap/src/main/java/com/minos/bootstrap/DefaultMinosApplicationComposer.java:168` |
| Le store sémantique PostgreSQL n'a pas de cache d'index | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticVectorStore.java` : aucune occurrence de `cache` |
| DATA-0081 ne couvre, hors budget, que la vue tenue par une requête en cours | `docs/architecture/adr/DATA-0081-port-de-connaissance-et-persistance-des-snapshots-budget-memoire-des-vues.md:118` |

**Calculé, non mesuré.** Tas par défaut du plan de requête : 3 Gio × 35 % = 1 075,2 Mio ; budget des vues :
537,6 Mio. Plafonds constants : corpus 256 + graphe 256 + empreintes 128 + vecteurs de requête 8 = 648 Mio
(PostgreSQL), 904 Mio avec l'index vectoriel local. Sommes avec le budget : 1 185,6 Mio et 1 441,6 Mio, au-dessus du
tas. DATA-0081 mesure par ailleurs un pic de 0,84 du tas pour une vue qui remplit le budget : le décodage d'une vue
emploie déjà 0,34 du tas hors budget, ce qui laisse de l'ordre de 0,16 du tas à tout le reste. Un index vectoriel local conforme au budget de construction peut être
estimé bien au-delà de son plafond de cache : jusqu'à 384 Mio de chaînes (192 Mio de contenu à 2 octets par
caractère), 192 Mio de vecteurs et 256 octets par document, soit de l'ordre de 600 à 700 Mio ; il n'est alors jamais
gardé et son décodage complet n'est borné par aucun plafond lié au tas. Le tas réellement
retenu par chaque cache, le poids décodé d'une projection par point et la part d'une vue que `lookups` maintient en
vie n'ont pas été mesurés.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| « Enregistrer ces caches comme Tenant du budget » : le contrat `Tenant` est dans `minos-storage`, adaptateur que `minos-application` ne peut pas nommer (`application ↛ adaptateurs`) | AUD-PERF-04, action | `QueryViewMemoryBudget.java:281-287` ; ARCH-0068, règle A2 | D1 : plafond dérivé du tas, primitive dans `minos-engine` |
| L'audit cite trois caches de 256 Mio ; deux autres caches à plafond de poids échappent aussi au budget : empreintes du provider Java (128 Mio) et vecteurs de requête (8 Mio) | AUD-PERF-04, preuve | `FingerprintConstrainedJavaProgramGraphProvider.java:38-39` ; `SemanticSearchService.java:20` | Inclus ; un test recense tout cache à plafond de poids |
| « (+ 256 de vecteurs en stockage local) » : exact ; sous PostgreSQL aucun cache d'index vectoriel n'existe | AUD-PERF-04, impact | `PostgresSemanticVectorStore.java` | Le contrat Docker distingue les deux stockages |
| « Pondérer `lookups` par le nombre de symboles et le vider sur `evictAll` » : le vidage exige un signal du budget que l'application ne reçoit pas ; pondérer sans vider laisse les symboles d'une vue évincée en vie | AUD-PERF-04, action | `SemanticCorpusQueryService.java:400-419` | D3 : supprimer la table, résoudre chaque page par la vue active sans rien retenir |
| L'audit cite le plafond de cache de l'index vectoriel (256 Mio), pas son décodage : un index constructible plus lourd que le plafond est décodé en entier à chaque lecture complète, hors de tout plafond | AUD-PERF-04, preuve | `SemanticIndexBudget.java:15-19` ; `FileSemanticVectorStore.java:57-59`, `:434-437`, `:711-729` | D9 : estimation avant décodage complet et refus au-delà de la part du tas ; les lectures paginées du sprint 4 restent servies |
| « Projection jusqu'à 512 Mio de fichier » : la borne porte sur le fichier, le poids décodé n'est pas estimé ; refuser de mettre en cache ne suffit pas, le décodage lui-même alloue | AUD-PERF-04, preuve | `FileSemanticProjectionStore.java:88-99`, `:209` | D4 : estimation avant décodage et refus explicite |
| Sous un petit tas, un plafond dérivé peut passer sous le poids d'un corpus ou d'un graphe aujourd'hui gardé : il est alors reconstruit à chaque requête | action de l'audit (première branche) | `HybridSearchService.java:157-161` | D2 : parts fixées sur mesure ; régression de latence mesurée (tâche 1.1) et documentée |
| Le sprint 4 fait dépendre un chemin de lecture de « l'index tient-il dans le cache » : abaisser le plafond de l'index vectoriel élargit ce chemin | `paginer-l-index-semantique-local-sans-le-charger` | `FileSemanticVectorStore.java:60` | Rejouer les tests et la mesure du sprint 4 sous le plafond dérivé (tâche 3.3) |
| Le gate `check-mnd.py` exige les chaînes des caches (`DEFAULT_MAX_CORPUS_CACHE_WEIGHT_BYTES`, `WeightedLruCache<UUID, CachedCorpus> corpusCache`, `WeightedLruCache<CacheKey, GraphEntry> cache`, `maximumWeight`, `DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES`, `WeightedLruCache<UUID, CachedFingerprint> immutableFingerprintCache`) | action de l'audit | `scripts/remediation/check-mnd.py:102-109` | Garder ces déclarations ; seul l'argument de plafond change |

## Exigences couvertes

- recherche-semantique › Les caches de la recherche sémantique sont plafonnés par une part du tas — D1, D2.
- program-graph-et-architecture › Les caches du graphe de programme sont plafonnés par une part du tas — D1, D2.
- recherche-semantique › Un index vectoriel local plus lourd que sa part du tas n'est pas décodé en entier — D9.
- exploration-semantique › L'explorateur ne retient aucun symbole hors de la vue du snapshot — D3.
- exploration-semantique › Une projection plus lourde que sa part du tas est refusée avant décodage — D4.
- distribution-docker › Le budget des vues et les caches applicatifs tiennent sous le tas par défaut du plan de
  requête — D5.

## Objectifs et non-objectifs

Objectifs : aucun cache à plafond de poids sans rapport au tas ; aucune rétention de symboles hors du compte ; une
somme vérifiée par test. Non-objectifs : un budget unique partagé entre vues et caches ; l'éviction des caches
applicatifs sous pression ; changer la fraction du budget des vues.

## Responsabilités et frontières des composants

- `minos-engine` (`com.minos.store.cache`, paquet existant) : primitive qui rend un plafond effectif à partir d'un
  plafond configuré, d'une part en pour cent et d'un tas maximal (celui de la JVM par défaut, injectable en test).
  Aucune dépendance nouvelle ; les parts y sont déclarées en un seul endroit, lisible par le test de contrat.
  `minos-engine` ne dépend que de `minos-domain` (`minos-engine/pom.xml:19`) : il ne connaît pas la fraction du budget
  des vues (`QueryViewMemoryBudget.DEFAULT_PERCENT`, `minos-storage`) ; la somme « parts + budget » est donc vérifiée
  dans `minos-app`, qui voit les deux.
- `minos-application` et `minos-storage` : chaque cache demande son plafond effectif à la primitive.
  `SemanticCorpusQueryService` ne garde plus de table de symboles.
- Direction ARCH-0068 respectée (`engine` ← `storage`, `engine` ← `application`). Règle A11 : aucune surface ne nomme
  le nouveau type. Règle A14 : aucun paquet nouveau.

## Modèle de domaine et invariants

- Plafond effectif d'un cache = min(plafond configuré, part × tas maximal), au moins 1.
- Somme des parts des caches + fraction du budget des vues + marge de décodage déclarée ≤ 100 % (vérifiée par le
  test de contrat de `minos-app` pour les valeurs par défaut).
- Aucune table de symboles ne survit à la requête qui l'a construite.
- Un décodage complet d'index vectoriel n'a lieu que si son poids estimé tient dans la part du tas déclarée.

## Traitements et interactions

Explorateur, cible : `lookup` demande la vue active au store (`loadActiveReadView`, servie par le cache du store),
résout un symbole par identifiant avec le lecteur de la vue (`findSymbolById`) et le premier symbole de chaque
fichier de la page par un parcours des symboles de la vue limité aux fichiers de cette page ; il ne garde rien entre
deux requêtes. Voir le diagramme.

## États et transitions

Non applicable : aucun cycle de vie nouveau.

## Contrats d'interface

- Aucun contrat public ne change. Statistiques de cache existantes (`corpusCacheStats()`, `cacheStats()`,
  `fingerprintCacheStats()`) : le plafond rapporté est le plafond effectif.
- Constructeurs : les points d'entrée existants gardent leur signature ; un point d'entrée de paquet reçoit le tas
  maximal pour les tests (pas de constructeur télescopique public, ADR 0045).
- Erreur de projection : `semantic projection is too large for this process: an estimated <octets> bytes exceed the
  <octets>-byte limit; prepare a smaller projection (--max-points) or raise the maximum heap`. Sans chemin.
- Erreur d'index vectoriel : `semantic index is too large to load in this process: an estimated <octets> bytes
  exceed the <octets>-byte limit; raise the maximum heap`. Sans chemin.

## Persistance et intégrité

Aucun format ni schéma ne change ; aucune migration.

## Transactions, concurrence et idempotence

La résolution des symboles d'une page n'a pas d'état partagé. Les verrous des caches sont inchangés.

## Sécurité et isolation

Fail-closed pour la projection : au-delà de sa part, refus, pas de décodage « au mieux ». Messages sans chemin. E/S
privées inchangées.

## Erreurs et reprise

Refus de projection : erreur d'usage actionnable. Un cache qui ne garde pas une entrée n'est pas une erreur.

## Observabilité

Statistiques de cache (plafond effectif, poids, évictions) ; `QueryViewMemoryBudget.stats()` pour la vue.

## Performance et mesure

Aucun objectif chiffré : les parts du tas sont une [Question] tranchée sur mesure. Stratégie (tâche 1.1, manuelle),
sur le plus gros snapshot persistable disponible (`morpheus-engine` ou corpus `QueryViewWeightFixtures`), sous
`-Xmx1076m` puis sous un grand tas, stockage local et PostgreSQL :

- tas retenu après une requête hybride, un graphe de programme, une page de l'explorateur et la lecture d'une
  projection de 100 000 puis d'un million de points ;
- poids estimé contre tas retenu pour chaque cache ; octets par point d'une projection décodée ;
- latence de la deuxième requête quand l'entrée n'est pas gardée (coût d'un plafond trop bas).

La mesure est refaite après correction (tâche 7.2). Le banc `benchmarks/scalability` étendu par AUD-PERF-13
(sprint 1) est réutilisé s'il est disponible.

## Compatibilité, migration et retour arrière

Aucune migration. Sous un grand tas, comportement inchangé. Sous un petit tas, un cache garde moins : le dire dans
`docs/user/docker-runtime.md`. Retour arrière : revenir au commit précédent.

## Diagrammes

### Qui borne quoi dans le tas d'une JVM de requête ?

Type : flowchart (vue de composants, pas un diagramme UML) · Portée : cible · Exigences : recherche-semantique › Les caches de la recherche sémantique sont plafonnés par une part du tas ; program-graph-et-architecture › Les caches du graphe de programme sont plafonnés par une part du tas ; distribution-docker › Le budget des vues et les caches applicatifs tiennent sous le tas par défaut du plan de requête

```mermaid
flowchart TB
    heap["Tas maximal (Runtime.maxMemory)"]
    budget["QueryViewMemoryBudget : 50 % (minos-storage)"]
    share["Part du tas par cache (minos-engine)"]
    views["Vues des stores de snapshots"]
    corpus["HybridSearchService : corpus"]
    qvec["SemanticSearchService : vecteurs de requête"]
    vec["FileSemanticVectorStore : index"]
    graph["ProgramGraphService : graphes"]
    fp["Provider Java : empreintes"]
    proj["FileSemanticProjectionStore : projection"]
    heap --> budget --> views
    heap --> share
    share --> corpus
    share --> qvec
    share --> vec
    share --> graph
    share --> fp
    share --> proj
```

Les vues restent sous le budget ; chaque autre cache reçoit un plafond qui suit le tas. Les symboles de l'explorateur
quittent la colonne des caches : ils sont lus dans la vue à chaque page.

### Comment l'explorateur résout-il les symboles d'une page ?

Type : sequenceDiagram · Portée : cible · Exigences : exploration-semantique › L'explorateur ne retient aucun symbole hors de la vue du snapshot

```mermaid
sequenceDiagram
    participant Q as SemanticCorpusQueryService
    participant S as CodeKnowledgeSnapshotStore
    participant V as SnapshotReadView
    Q->>S: activeSnapshotId(projet)
    alt snapshot actif différent de celui de l'index
        Q-->>Q: symboles non résolus
    else même snapshot
        Q->>S: loadActiveReadView(projet)
        S-->>Q: vue (cache du store, comptée par le budget)
        Q->>V: symbole par identifiant ; parcours des symboles pour les fichiers de la page
        V-->>Q: symboles de la page (rien n'est gardé)
    end
```

## Décisions

### D1 — Plafond dérivé du tas plutôt que locataire du budget

[Proposition] — décision du propriétaire attendue. Chaque cache applique min(plafond configuré, part du tas).
Alternative écartée : en faire des locataires du budget. Elle exige de remonter le contrat `Tenant` dans
`minos-engine` et de câbler le budget par `minos-bootstrap`, et fait vider corpus et graphes à chaque chargement de
vue sous pression (reconstructions en cascade). Compromis : les caches ne sont pas évincés sous pression ; leur somme
est bornée par construction.

### D2 — Parts du tas fixées sur mesure

[Question] bloquante pour l'implémentation. Contrainte calculée : sous le tas par défaut, le budget (0,50) et le pic
de décodage d'une vue (jusqu'à 0,84 du tas, DATA-0081) laissent de l'ordre de 0,16 du tas à tous les caches et au
travail des requêtes. Recommandation : le propriétaire fixe les parts après la mesure 1.1, avec une somme des parts
des caches qui ne dépasse pas cette marge sur le plan de requête par défaut ; à défaut de marge suffisante, la
décision à prendre est celle de la fraction du budget des vues ou de `MaxRAMPercentage`, hors de ce changement. Les
plafonds configurés actuels restent le maximum sous un grand tas.

### D3 — Les symboles d'une page sont lus dans la vue, sans table retenue

[Proposition]. `lookups` et `SymbolLookup` sont supprimés. Un symbole est résolu par identifiant avec le lecteur de
la vue ; le premier symbole de chaque fichier de la page est trouvé par un parcours des symboles de la vue, limité
aux fichiers de la page, dont rien n'est gardé. Le poids estimé des vues ne change pas.

Alternatives écartées :

- **Index dérivé porté par `SnapshotReadView`** (premier symbole par fichier, compté dans le poids de la vue) : il
  augmenterait le poids estimé de toute vue d'une borne par symbole. La marge de l'exigence en vigueur
  distribution-docker › Le plan de requête Docker fixe le tas de sa JVM en proportion de sa limite mémoire est de
  37,6 Mio sous le budget et de 12 Mio sous le plafond du cache pour la vue V3 la plus lourde (calculé) : sans
  constante mesurée, rien ne garantit qu'elle tienne, et les assertions de poids exact des tests d'admission
  (`PostgresCodeKnowledgeSnapshotStoreViewAdmissionTest.java:60-61`, cas du store fichier) changeraient. À reprendre
  seulement si la latence mesurée du parcours est inacceptable, avec une constante mesurée et la preuve que cette
  marge tient.
- **Pondérer `lookups` et le vider sur un signal du budget** : exige le câblage écarté en D1.

Compromis : chaque page parcourt les symboles du snapshot (coût linéaire, mesuré en 1.1 et 7.2).

### D4 — Une projection est estimée avant décodage et refusée au-delà de sa part

[Proposition]. Le nombre de points et la taille du fichier, lus avant le décodage, donnent une borne du poids
décodé ; au-delà de la part du tas, refus explicite. Alternative écartée : décoder sans mettre en cache (l'allocation
du décodage reste hors borne).

### D5 — Le test de contrat somme le budget et les caches

[Proposition]. `M29DockerQueryImageContractTest` calcule le tas par défaut, le budget, la marge de décodage déclarée
et les plafonds effectifs (stockage local et PostgreSQL) depuis les fichiers compose, `QueryViewMemoryBudget` et la
primitive, et vérifie aussi « somme des parts + fraction du budget + marge de décodage ≤ 100 % » : `minos-app` est le
premier module qui voit à la fois `minos-engine` et `minos-storage`. La marge de décodage (part du tas qu'emploie le
chargement d'une vue au-delà du budget, 0,34 d'après le pic de 0,84 mesuré par DATA-0081) est une valeur déclarée
avec les parts, décidée en D2. Un test de `minos-app` recense les détenteurs de données pondérées en production :
toute construction de `WeightedLruCache`, plus une liste fermée et nommée des caches qui n'en sont pas
(`FileSemanticProjectionStore`, dont le cache est une `HashMap`, `:68`) ; il échoue pour une classe qui ne passe pas
par la part du tas. Les caches bornés par leur seul nombre d'entrées (empreintes de `SemanticCorpusQueryService`)
sont listés avec leur raison.

### D6 — ADR consolidé commun au sprint 5

[Proposition]. La table des parts du tas, la suppression de la rétention des symboles et le refus d'une projection
entrent dans l'ADR consolidé `DATA-<NNNN>` (prochain numéro libre à l'écriture) qui remplace DATA-0081 et porte les
décisions des trois changements du sprint 5 ; le premier changement implémenté l'écrit, les suivants complètent sa
« Mise en œuvre ». Le risque « vue encore tenue par une requête : la moitié du tas hors budget la couvre »
(DATA-0081 `:118`) y est réécrit avec la marge réelle.

### D7 — Vecteurs en `float[]` (renvoyé par le sprint 4) : ne pas changer le type du domaine

[Proposition] — décision du propriétaire attendue (reportée ici par
`paginer-l-index-semantique-local-sans-le-charger`, D4). `SemanticVector` (domaine) reste en `double[]`. Le format
persisté local est déjà en `float32` et le budget de construction compte les vecteurs en `double[]`
(`SemanticIndexBudget.java:56-61`) : garder en cache les valeurs `float` lues sur disque ne perdrait aucune
précision. La mesure 1.1 relève le tas retenu par le cache de l'index vectoriel ; si sa part du tas se révèle
contraignante, la suite proposée est un changement distinct qui compacte la seule représentation en cache du store
local, sans toucher au type du domaine, avec la preuve que scores et goldens ne bougent pas. Non spécifié ici.

### D8 — Mémoire de travail de la préparation d'une projection : hors des caches, sans décision attendue

[Existant vérifié] dans `ajuster-la-projection-sur-l-echantillon-en-memoire` (design, « Le budget par défaut
(256 Mio) contient l'échantillon de tout index constructible : aucun relèvement n'est nécessaire »), qui laisse à ce
sprint le compte de cette mémoire. [Proposition] : elle n'est pas un cache mais la mémoire de travail d'une commande
bornée par `--max-memory-mb` ; elle n'entre ni dans les parts du tas ni dans le contrat du plan de requête, et l'ADR
consolidé la nomme comme telle. Aucune exigence ici.

### D9 — Un index vectoriel local est estimé avant son décodage complet

[Proposition] — décision du propriétaire attendue. Avant un décodage complet, le store estime une borne du poids en
mémoire depuis les métadonnées de l'index (nombre de documents, dimensions) et la taille du fichier ; au-delà de la
part du tas déclarée pour ce décodage, il refuse par une erreur explicite. Les lectures qui ne chargent pas l'index
(pages, document, voisins en flux, livrées par `paginer-l-index-semantique-local-sans-le-charger`) restent servies ;
ce changement en dépend. Alternatives : laisser le décodage hors plafond (l'issue reste une `OutOfMemoryError` sous
un petit tas) — écartée ; faire passer la recherche sémantique elle-même par un parcours en flux — hors de ce lot,
à proposer si la mesure montre que le refus touche un index d'usage courant. Compromis : sous le tas par défaut du
plan de requête, un index proche du budget de construction est refusé pour la recherche complète au lieu d'être
tenté.

## Qualification des capacités

- `recherche-semantique`, `program-graph-et-architecture` : **partielles** jusqu'à la décision D2 et la mesure 7.2
  (mécanisme prouvé par tests à tas injecté ; valeurs de production non mesurées).
- `exploration-semantique` : **partielle** (rétention prouvée par test de joignabilité ; refus de projection prouvé à
  tas injecté ; octets par point non mesurés).
- `distribution-docker` : **qualifiée** par test de contrat (lecture de fichiers), Windows et Linux.

## Risques et compromis

- [Risque] Parts trop basses : corpus ou graphe reconstruit à chaque requête → mesure de latence 1.1 et 7.2,
  documentation, plafonds réglables par la taille du tas.
- [Risque] Le parcours des symboles par page (D3) coûte en latence sur un gros snapshot → mesuré ; repli nommé en D3.
- [Risque] Le refus d'un index trop lourd (D9) touche un index constructible sous un petit tas → message
  actionnable, mesure 1.1 sur un index au budget de construction.
- [Risque] Test de joignabilité dépendant du ramasse-miettes → boucle bornée sur une référence faible, pas de délai
  fixe (AUD-TST-06, sprint 6).
- [Risque] Collision de fichiers avec le sprint 4 → ordre d'implémentation, relecture des lignes.

## Vérification

- Tests à créer : test de la primitive (`minos-engine`) ; dans `minos-application`, avec des doublures des ports,
  les tests des plafonds de `HybridSearchService`, `SemanticSearchService`, `ProgramGraphService` et
  `FingerprintConstrainedJavaProgramGraphProvider` (aucun test de ces classes n'existe dans ce module au HEAD ;
  SonarCloud juge la couverture par module) ; test de recensement (`minos-app`).
- Tests existants à étendre ou à rejouer : `FileSemanticVectorStoreTest`, `FileSemanticProjectionStoreTest`
  (`minos-storage`) ; `SemanticCorpusQueryServiceTest` (`minos-application` ; sa doublure `FixedSnapshotStore`,
  `:676-684`, ne redéfinit que `loadActiveKnowledge` alors que `loadActiveReadView` est une méthode par défaut du
  port, `CodeKnowledgeSnapshotStore.java:45` : l'adapter) ; `HybridCorpusRefreshTest`, `HybridCorpusWeightTest`,
  `ProgramGraphServiceConcurrencyTest` (`minos-bootstrap`) ; `M29DockerQueryImageContractTest` (`minos-app`).
- Gates : `check-mnd.py`, `check-mne.py`, `check-p0-p2.py`, `check-bounded-helpers.py`,
  `check-semantic-retrieval-consistency.py`, `check-advanced-provider-consistency.py`,
  `check-module-boundaries.py`, `check-comment-language.py`, `check-private-io.py`,
  `check-capability-dossiers.py`.
- JaCoCo : `semantic-hybrid-retrieval`, `semantic-vector-store`, `program-graph-analysis`, `java-advanced-provider`.
- Non vérifiable sans mesure : les parts de production (D2).

## Documentation durable à mettre à jour

`docs/developer/indexing-and-storage.md` (table des parts) ; `docs/user/docker-runtime.md` (« Limites de
ressources ») ; `docs/user/semantic-explorer.md` (refus d'une projection) ; dossiers de capacité
`recherche-semantique.md`, `program-graph-et-architecture.md`, `exploration-semantique.md`,
`distribution-docker.md` ; ADR consolidé et ses index.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| recherche-semantique › Les caches de la recherche sémantique sont plafonnés par une part du tas | Petit tas ; Grand tas ; Part invalide | D1, D2, composants | 2.1, 3.1, 3.2 | à écrire |
| idem | Corpus plus lourd que le plafond effectif | D1 | 3.1, 3.2 | à écrire |
| program-graph-et-architecture › Les caches du graphe de programme sont plafonnés par une part du tas | Petit tas ; Grand tas ; Graphe plus lourd | D1, D2 | 4.1, 4.2 | à écrire |
| recherche-semantique › Un index vectoriel local plus lourd que sa part du tas n'est pas décodé en entier | Tient dans sa part ; Trop lourd ; Lecture paginée | D9 | 3.4, 3.5 | à écrire |
| exploration-semantique › L'explorateur ne retient aucun symbole hors de la vue du snapshot | Deux pages ; Vue évincée ; Snapshot différent | D3, séquence | 5.1, 5.2 | à écrire |
| exploration-semantique › Une projection plus lourde que sa part du tas est refusée avant décodage | Tient dans sa part ; Trop lourde ; En-tête incohérent | D4 | 6.1, 6.2 | à écrire |
| distribution-docker › Le budget des vues et les caches applicatifs tiennent sous le tas par défaut du plan de requête | Somme sous le tas ; Part relevée ; Cache sans part | D5 | 7.1 | à écrire |
| mesures (chiffres calculés de l'audit) | — | Performance et mesure | 1.1, 7.2 | manquante (manuelle) |

## Questions ouvertes

- D2 : parts du tas, fixées par le propriétaire après la mesure 1.1 (bloque les tâches 2 à 7, pas les exigences).
- D1, D3, D7, D9 : décisions du propriétaire attendues, recommandations ci-dessus. D8 n'attend pas de décision.
- Numéro de l'ADR consolidé : prochain numéro libre à l'écriture.

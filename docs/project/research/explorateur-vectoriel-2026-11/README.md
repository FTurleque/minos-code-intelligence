# Étude — explorer l'index sémantique dans un navigateur (pgAdmin et explorateur vectoriel)

- **Date** : 2026-11-13
- **Base analysée** : branche `develop`, commit `63e0c4ac5288ebd6d52b3462e40e7067d06b36ce` (2026-11-11). Les modifications locales non commitées du poste (`.claude/agent-memory/`, `docs/project/audits/2026-10-10/`, `Claude outputs/`) n'ont été ni lues comme preuves ni modifiées.
- **Nature** : étude et conception. **Aucune fonctionnalité décrite ici n'est livrée.** Le code de production n'est pas modifié.
- **Statuts employés** : `[Existant vérifié]` (relu au HEAD, `fichier:ligne`), `[Proposition]`, `[Hypothèse]`, `[À mesurer]`.

Documents de l'étude :

| Document | Contenu |
|---|---|
| ce fichier | état vérifié, matrice des besoins, options, recommandation, projections, sécurité, risques, questions |
| [CONCEPTION-FONCTIONNELLE.md](CONCEPTION-FONCTIONNELLE.md) | écrans, interactions, contrat des filtres, scores, états |
| [PGADMIN.md](PGADMIN.md) | parcours pgAdmin, rôle de lecture, requêtes SQL de diagnostic |
| [maquette/index.html](maquette/index.html) | maquette autonome à données fictives (ouvrir le fichier dans un navigateur) |
| [plan de réalisation](../../roadmap/explorateur-vectoriel-2026-11/README.md) | lots, dépendances, critères de fin |
| `openspec/changes/` : `inspecter-postgresql-avec-pgadmin`, `explorer-l-index-semantique-dans-un-navigateur`, `projeter-l-index-semantique-en-2d` | propositions, designs (diagrammes Mermaid dans le deuxième), exigences, tâches |
| [ARCH-0082](../../../architecture/adr/ARCH-0082-surface-http-locale-optionnelle-de-consultation.md) | décision **acceptée le 2026-11-13** : surface HTTP locale |

## 1. Constats déterminants `[Existant vérifié]`

### 1.1 Schéma PostgreSQL

Le schéma est créé par code, sans fichier de migration SQL : `minos-storage/src/main/java/com/minos/storage/postgresql/support/PostgresSchemaMigrator.java` (version courante 5, ligne 10).

| Fait | Preuve |
|---|---|
| `semantic_index_meta(project_id uuid PK, snapshot_id, provider_id, model_id, dimensions CHECK 1..16384, built_at bigint)` : **une ligne par projet**, donc un seul index, un seul modèle et un seul snapshot par projet | `PostgresSchemaMigrator.java:96` |
| `semantic_documents(project_id, stable_key, document_id, snapshot_id, kind, source_id, file_id NULL, start_line, end_line, content, checksum, embedding vector)`, clé `(project_id, stable_key)` | `PostgresSchemaMigrator.java:97` |
| La colonne `embedding` est de type `vector` **sans dimension déclarée** : la cohérence des dimensions est tenue par l'application, pas par le schéma | `PostgresSchemaMigrator.java:97` ; contrôle applicatif `SemanticVectorStore.java:227-229` |
| Seul index secondaire : `semantic_documents(project_id)` ; aucun index vectoriel (HNSW, IVFFlat) | `PostgresSchemaMigrator.java:98` |
| Aucune clé étrangère entre les tables sémantiques, `projects` et `knowledge_active` | `PostgresSchemaMigrator.java:80-98` |
| `knowledge_active(project_id PK, snapshot_id)` désigne le snapshot structurel actif | `PostgresSchemaMigrator.java:84` |
| Le snapshot structurel est un **binaire opaque** (`knowledge_snapshots.payload bytea`) : symboles, modules et langages ne sont pas interrogeables en SQL | `PostgresSchemaMigrator.java:83` |
| `projects.root_value` contient la racine du projet (chemin) | `PostgresSchemaMigrator.java:80` |
| Aucune colonne de tenant, aucun `GRANT`, aucune sécurité de ligne dans le schéma | `PostgresSchemaMigrator.java:78-137` |
| La table `code_chunks` de la maquette d'origine n'existe pas | recherche `git grep` sur le dépôt : aucune occurrence |

### 1.2 Granularité et identités d'un document

Un point de la carte est un `SemanticDocument` (`minos-domain/src/main/java/com/minos/semantic/SemanticDocument.java:8-20`). Trois natures existent (`SemanticDocumentKind.java:4-8`), construites par `SemanticDocumentFactory` (`minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java`) :

| Nature | `stable_key` | `source_id` | `file_id` | lignes | `content` indexé | Preuve |
|---|---|---|---|---|---|---|
| `SYMBOL` | `symbol:<symbolKey>` | identifiant du symbole | fichier du symbole | celles du symbole, `0-0` sans emplacement | **texte de synthèse** (`kind`, `name`, `qualified`, `signature`, `language`, `module`), borné à 768 tokens | `:80-83`, `:179-187` |
| `CHUNK` | `chunk:<symbolKey>:<début>:<fin>` | identifiant du symbole | fichier du symbole | celles du symbole | **extrait du fichier** lu à l'indexation (2 lignes de contexte, 768 tokens), ou le texte de synthèse si le fichier est illisible | `:100-108` |
| `FILE` | `file:<fileId>` | `fileId` | `fileId` | `0-0` | **résumé** : liste des symboles du fichier, 2 048 tokens | `:110-112`, `:152-163` |

Conséquences pour l'interface :

- **Fichier** : `file_id` est une référence relative à la racine du projet, résolue et confinée par `LocalSourceReader` (`minos-application/src/main/java/com/minos/context/LocalSourceReader.java:235-253` : refus de `..`, contrôle `startsWith(projectRoot)`, `NOFOLLOW_LINKS`, plafond de 16 Mio ligne 29). Il s'affiche tel quel.
- **Symbole** : `source_id` est un identifiant ; le nom qualifié, le **module** et le **langage** se lisent dans le snapshot structurel (`Symbol.language()`, `Symbol.moduleId()`, `SemanticDocumentFactory.java:185-186`). Ils ne sont pas des colonnes : ils se résolvent par jointure applicative avec le snapshot actif, jamais en SQL.
- **Contenu indexé ≠ source actuelle** : `content` est ce qui a été vectorisé. Aucun contenu de source n'est conservé après la construction (exigence courante `recherche-semantique` › « Aucun contenu de source n'est conservé après la construction », `openspec/specs/recherche-semantique/spec.md:75`). Un extrait « actuel » est une seconde lecture du disque, à présenter comme telle.
- **Identité de version** : `checksum = sha256(kind ␟ stable_key ␟ content)` et `document_id = "semantic:" + sha256(snapshot_id ␟ stable_key ␟ checksum)` (`SemanticDocumentFactory.java:146-147`). `stable_key` est stable d'un snapshot à l'autre ; `document_id` change à chaque snapshot.

### 1.3 Services, états et scores

| Fait | Preuve |
|---|---|
| États de l'index : `DISABLED`, `NO_ACTIVE_SNAPSHOT`, `MISSING`, `STALE`, `READY` ; `STALE` distingue `SEMANTIC_INDEX_SNAPSHOT_STALE` et `SEMANTIC_INDEX_MODEL_STALE` | `SemanticIndexService.java:266`, `:106-109` |
| La recherche sémantique exige `READY`, sinon elle rend une réponse vide avec `SEMANTIC_SEARCH_REQUIRES_READY_INDEX` | `SemanticSearchService.java:41-43` |
| Un index remplacé pendant la requête est détecté (trois tentatives), limite `SEMANTIC_INDEX_CHANGED_DURING_QUERY` | `SemanticSearchService.java:38-69` |
| Score sémantique = **similarité cosinus** `1 − (embedding <=> requête)`, bornée à `[-1, 1]`, classement exact, départage par `stable_key` | `PostgresSemanticReadQueries.java:153-159`, `:171` ; moteur `pgvector-exact-cosine` `PostgresSemanticVectorStore.java:24` |
| Chaque résultat porte `InformationNature.HEURISTIC`, le provider et le modèle ; la limite `VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT` accompagne toute réponse | `SemanticSearchService.java:58-61`, `:83` |
| Score hybride = `0,50 × sémantique + 0,35 × lexical + 0,15 × graphe`, borné à `[0, 1]`, avec les trois signaux exposés | `HybridSearchService.java:85`, `:331-332` |
| Aucun reranker n'existe au HEAD | recherche `git grep -i rerank` sur `minos-*/src/main` : aucune occurrence |
| Bornes : 1 000 résultats (sémantique), 500 (hybride), requête de 64 Kio | `SemanticSearchService.java:17-19`, `HybridSearchService.java:30-31` |
| Budget d'index par défaut : 250 000 documents | `SemanticIndexBudget.java:15-16` |
| `local-hash` : provider `minos-local-hash`, modèle `signed-token-chargram-v1-<dimensions>`, limite `LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL` | `LocalHashEmbeddingProvider.java:31-42` |
| Ollama : boucle locale ou service géré `minos-ollama`, limites `LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC`, `SEMANTIC_RESULTS_REMAIN_HEURISTIC` | `OllamaEmbeddingProvider.java:40-41`, `:85-89`, `:144` |
| Sémantique désactivée par défaut (`minos.semantic.provider` absent → `disabled`) | `MinosApplicationRuntimeConfiguration.java:25-29` |

### 1.4 Surfaces et composition

| Fait | Preuve |
|---|---|
| API Java sémantique v1 : statut, synchronisation, recherche sémantique, recherche hybride, contexte hybride | `minos-api/src/main/java/com/minos/api/SemanticCodeIntelligenceApi.java:11-25` |
| Le DTO public d'un document **ne porte pas le contenu** (seulement `contentChecksum`) | `SemanticCodeIntelligenceApi.java:56-65` |
| CLI : `semantic-index-status`, `semantic-index-sync`, `semantic-search` | `minos-cli/src/main/java/com/minos/cli/command/IdeIntelligenceCommand.java:44-46` |
| MCP : `minos_semantic_index_status`, `minos_semantic_search`, `minos_hybrid_search`, `minos_hybrid_context` | `minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java:129-135` |
| Les services sont assemblés dans `MinosApplication` ; le stockage est choisi par `StorageBackends` et `minos-bootstrap` | `MinosApplication.java:192-195`, `minos-bootstrap/src/main/java/com/minos/bootstrap/StorageBackendSelection.java:14` |
| Magasin local : fichier `index-v2.bin` par projet, plafond de 192 Mio de vecteurs décodés | `FileSemanticVectorStore.java:50`, `:56` |
| **Aucun serveur HTTP** dans le produit ; aucune étude ni interface web antérieure | `git grep` sur `HttpServer`, `jetty`, `javalin`, `undertow`, `netty` dans `*.java` et `pom.xml` : aucune occurrence ; `git grep -i` sur `pgadmin`, `umap`, `t-sne` : aucune occurrence |

### 1.5 Permissions et isolation réellement applicables

- La recherche sémantique du MCP appelle directement le service, **sans contrôle de permission** (`MinosApplicationMcpBackend.java:250-251`). Le jeton d'équipe n'est exigé que pour les outils `minos_team_*` (`:323`).
- Les permissions du plan de contrôle d'équipe portent sur l'administration du tenant (`HostedPermission.java:4-14` : membres, espaces, audit, rétention). **Aucune ne régit la lecture du code ou de l'index d'un projet.**
- L'isolation effective est celle du poste : un `MINOS_HOME`, ses projets inscrits, et pour Docker les seuls projets montés (SEC-0075).

Il n'existe donc pas de mécanisme projet/tenant à réutiliser pour une interface distante. Ce point borne le périmètre du MVP (§ 6).

### 1.6 Déploiement Docker

| Fait | Preuve |
|---|---|
| `minos-postgres` (profil `postgresql`, image `pgvector/pgvector:0.8.7-pg17` épinglée par digest) n'est attaché qu'au réseau `minos-runtime`, **interne**, et ne publie aucun port | `docker/compose-mcp.connected.yaml:281-300`, `:393-394` ; aucun bloc `ports:` dans le fichier |
| Le mot de passe PostgreSQL est un fichier monté en lecture seule | `compose-mcp.connected.yaml:291`, `:296-299` |
| Le volume de données PostgreSQL est externe au projet Compose | `compose-mcp.connected.yaml:408-410` |
| Le plan de requête `minos-mcp` monte les données MINOS **en lecture seule** ; les écritures passent par le plan `minos-admin` | `compose-mcp.connected.yaml:63-67`, `:103-132` |
| Chaque service porte un bloc de limites et une image épinglée, contrôlés par des gates | `docker/.env.example:9` (`check-compose-limits.py`), `compose-mcp.connected.yaml:284-286` (`check-image-pins.py`) |
| Un `MINOS_HOME` natif ne peut pas joindre le PostgreSQL géré | `docs/user/docker-runtime.md:275-286` |

### 1.7 Décisions en vigueur qui contraignent la conception

| ADR | Contrainte |
|---|---|
| ARCH-0017 | MCP en STDIO, lecture seule ; « ne pas rendre obligatoire un serveur HTTP » |
| GEN-0077 § 7 | aucun framework serveur dans le domaine et les services ; une couche d'exposition réseau reste un adaptateur périphérique |
| ARCH-0068 | direction des dépendances ; pas de module créé pour reproduire un package |
| ARCH-0069 | backend Docker : pas d'ouverture de `MinosApplication` côté hôte ; MCP Docker sur données en lecture seule |
| SEC-0071 | pas de serveur HTTP propre au plan de contrôle ; aucun secret en argument |
| BE-0029 | couche sémantique optionnelle ; aucun index construit silencieusement ; un score vectoriel n'est jamais un fait |
| BE-0031 | cosinus exact comme référence ; pas d'ANN sans goulot mesuré ni décision séparée |

## 2. Matrice besoin → capacité → manque

| Besoin | Capacité existante | Manque | Composants concernés | Preuve |
|---|---|---|---|---|
| Lister les projets et l'état de leur index | statut par projet | aucun, hormis l'exposition HTTP | `SemanticIndexService`, registre de projets | `SemanticIndexService.java:56-64` |
| Lister les documents, paginés | lecture de **tous** les documents sans vecteurs | pagination par clé, filtres, comptage | `SemanticVectorStore`, deux adaptateurs | `SemanticVectorStore.java:60-62`, `PostgresSemanticReadQueries.java:64-69` |
| Afficher fichier, lignes, nature | champs du document | aucun | `SemanticDocument` | `SemanticDocument.java:8-20` |
| Afficher symbole, module, langage | présents dans le snapshot structurel | jointure applicative document → symbole | port de lecture des snapshots | `SemanticDocumentFactory.java:179-187` |
| Afficher le contenu indexé | présent en stockage | absent des DTO publics | `minos-api` | `SemanticCodeIntelligenceApi.java:56-65` |
| Afficher l'extrait actuel et signaler un écart | lecteur de source confiné | comparaison contenu indexé / source | `LocalSourceReader` | `LocalSourceReader.java:235-253` |
| Recherche en langage naturel | recherche sémantique et hybride | filtres côté serveur | `SemanticSearchService`, `HybridSearchService` | `SemanticSearchService.java:32` |
| Nature des scores | cosinus et signaux hybrides exposés | libellés d'interface | DTO existants | `SemanticCodeIntelligenceApi.java:68-92` |
| Documents similaires à un document | classement par vecteur de requête | lecture du vecteur **d'un** document | `SemanticVectorStore` | `SemanticVectorStore.java:80` |
| Carte 2D | aucune | projection, cache dérivé, statut, points | nouveaux service et port | — |
| Lire les vecteurs par lots | chargement complet seulement | lecture bornée par lots | `SemanticVectorStore` | `PostgresSemanticReadQueries.java:121-127` |
| Interface navigateur | aucune | surface HTTP locale, ressources statiques | nouvelle surface | § 1.4 |
| Inspection SQL | PostgreSQL géré non publié | service pgAdmin optionnel, rôle de lecture | `docker/`, scripts de cycle de vie | § 1.6 |
| Permissions de lecture par projet | aucune | hors périmètre du MVP | — | § 1.5 |

## 3. Objectifs et périmètre

**Objectifs.** (1) Inspecter les tables réelles par pgAdmin, sans élargir l'exposition de PostgreSQL. (2) Parcourir, rechercher et comprendre les documents sémantiques d'un projet depuis un navigateur local. (3) Visualiser les embeddings sur une carte 2D honnête sur ce qu'elle montre.

**Hors périmètre.** Accès distant et multi-utilisateur ; édition de code ; déclenchement d'indexation ou de synchronisation depuis le navigateur ; vue ProgramGraph ; index vectoriel approché ; remise en cause du classement exact ; nouveaux providers d'embeddings ; plugin IntelliJ.

## 4. Options d'intégration web

| Option | Description | Pour | Contre | Verdict |
|---|---|---|---|---|
| **A. Surface HTTP locale dans MINOS** | serveur du JDK (`jdk.httpserver`), ressources statiques embarquées, démarré par une commande explicite | aucune dépendance nouvelle côté serveur ; réutilise les services ; conforme à GEN-0077 § 7 | nouvelle surface à sécuriser et à qualifier | **recommandée** |
| B. Application web séparée (Node ou Python) lisant PostgreSQL | pile distincte | liberté d'outillage (UMAP Python) | credentials PostgreSQL hors de MINOS, logique de recherche dupliquée, nouvelle toolchain, stockage local non couvert | écartée |
| C. Export statique (`minos … export` → fichier HTML autonome) | aucune écoute réseau | surface d'attaque minimale | pas de recherche en langage naturel (elle exige le provider), fichier volumineux contenant du code | repli possible pour la seule carte |
| D. Framework serveur (Spring, Javalin…) | productivité | contraire à l'esprit de GEN-0077, dépendances à auditer | écartée |
| E. Outil du plugin IntelliJ | intégré à l'IDE | le plugin est un client externe sans dépendance `com.minos:*` ; hors demande | écartée |

### 4.1 Recommandation `[Proposition]`

1. **Surface** : une surface HTTP locale, optionnelle, en lecture seule, lancée par `minos explorer serve`. Elle sert des ressources statiques embarquées dans le jar (HTML, CSS, JavaScript sans chaîne de build) et une API JSON versionnée sous `/api/v1/`. Décision structurante : ADR proposé ARCH-0082.
2. **Emplacement du code** : un module de surface `minos-explorer`, au même rang que `minos-mcp`. Justification : responsabilité propre (transport HTTP, modèle de menace réseau, ressources web) et frontière vérifiable par `check-module-boundaries.py` (la surface ne dépend d'aucun adaptateur ; le MCP ne dépend pas d'elle). Repli si le propriétaire refuse un module : un package `com.minos.cli.explorer` dans `minos-cli`, au prix d'un mélange de deux transports dans un module.
3. **Services** : les lectures nouvelles (liste paginée, détail, voisins, statut et points de projection) sont des services de `minos-application`, package existant `com.minos.application.semantic`. La recherche réutilise `SemanticSearchService` et `HybridSearchService` sans modification de leur classement.
4. **Ports** : méthodes additives à défaut dérivé sur `SemanticVectorStore` (même procédé que `documents` et `checksums`, `SemanticVectorStore.java:60-71`) pour la pagination, le vecteur d'un document et la lecture par lots ; un port `SemanticProjectionStore` pour le cache dérivé.
5. **API Java publique** : une interface additive `SemanticExplorationApi` (contrat v1) dans `minos-api` ; `SemanticCodeIntelligenceApi` reste inchangée.
6. **Préparation de projection** : commande distincte `minos semantic projection prepare <projet>`, jamais déclenchée par une consultation ; en Docker, exécutée par le plan `minos-admin`.
7. **pgAdmin** : service Compose optionnel (profil `pgadmin`) et rôle PostgreSQL `minos_inspect` en lecture seule ; détail dans [PGADMIN.md](PGADMIN.md).

Le navigateur ne reçoit ni credentials PostgreSQL ni adresse d'Ollama : il ne parle qu'à l'API locale.

### 4.2 Matrice de support

| Stockage | Runtime | Liste, recherche, détail | Carte 2D | Statut |
|---|---|---|---|---|
| local | natif | lot 2 | lot 3 | **MVP** `[Proposition]` |
| PostgreSQL externe | natif | lot 2 | lot 3 | MVP, sous réserve de qualification |
| PostgreSQL géré | Docker | lot 2b (service `minos-explorer`) | lot 3 | cible, **non couverte tant que non qualifiée** |
| PostgreSQL géré | natif | — | — | **non supporté** : le PostgreSQL géré n'est pas joignable de l'hôte (§ 1.6) |
| local | Docker | lot 2b | lot 3 | cible, non couverte tant que non qualifiée |
| tous | accès distant | — | — | **non supporté** ; exige une décision séparée (§ 6) |

En backend Docker, l'explorateur s'exécute dans un conteneur : ouvrir `MinosApplication` côté hôte est interdit par ARCH-0069.

## 5. Projections et performances

### 5.1 Méthodes

Sources lues le 2026-11-13 : scikit-learn (`sklearn.manifold.TSNE`), documentation umap-learn (reproductibilité), README de Smile. Liste en § 9.

| Critère | PCA | UMAP | t-SNE |
|---|---|---|---|
| Qualité exploratoire | linéaire ; conserve la structure globale, sépare mal des groupes locaux `[Hypothèse à mesurer sur des embeddings de code]` | non linéaire ; bons voisinages locaux, structure globale partielle | non linéaire ; voisinages locaux, distances entre groupes non interprétables |
| Reproductibilité | déterministe à ordre et initialisation fixés | stochastique ; exacte avec `random_state`, qui désactive une partie du parallélisme | fonction de coût non convexe ; résultat dépendant de l'initialisation |
| Coût | un passage par itération sur les vecteurs, mémoire proportionnelle à la dimension | graphe de voisins approché puis optimisation ; plus coûteux | Barnes-Hut en `O(N log N)`, la méthode exacte ne passe pas à l'échelle |
| Petits corpus | stable | sensible à `n_neighbors` | la perplexité doit rester inférieure au nombre de points |
| Nouveaux points | projection directe (produit matriciel) | `transform` disponible, qualité dépendante de la distribution | **aucune méthode `transform`** |
| Packaging MINOS | implémentable en Java pur, sans dépendance | exige une bibliothèque ou un processus externe | idem |
| Dépendances et licences | aucune | Smile : double licence (GPL ou commerciale), versions 4.x pour Java 21, BLAS natif pour l'apprentissage de variétés ; autre implémentation Java `[à vérifier]` | idem Smile |

**Recommandation `[Proposition]`.**

- **Lot 3 : PCA à deux composantes**, calculée en Java sans dépendance (itération de puissance avec déflation sur la matrice de covariance, par lots, graine fixée). Elle est retenue pour ses propriétés d'exploitation — déterministe, bornée en mémoire, projection de nouveaux points, aucune dépendance — et non pour son rendu. La carte affiche la **part de variance expliquée** ; si elle est faible, l'interface le dit.
- **Lot 4 : UMAP sous décision mesurée.** Critères : préservation des k plus proches voisins de l'espace d'origine, temps, mémoire. La licence de Smile est incompatible avec un produit propriétaire sans licence commerciale : toute adoption passe par une décision écrite (règle « pas de nouvelle dépendance sans raison écrite », `.claude/rules/securite.md` point 10).
- **t-SNE : non retenu** — pas de projection de nouveaux points, résultat instable d'une exécution à l'autre, paramétrage sensible.

Quelle que soit la méthode : la carte est une aide à l'exploration. **Le classement reste calculé sur les vecteurs d'origine** ; la distance à l'écran n'est jamais une mesure de pertinence, et aucun lien n'est tracé entre deux points.

### 5.2 Vue 3D

Non recommandée pour le MVP : occlusion, sélection imprécise, coût de rendu et d'accessibilité, pour un gain d'information non démontré. La PCA donne la troisième composante sans surcoût : la question se rouvre au lot 4 avec une mesure (variance expliquée par la troisième composante).

### 5.3 Rendu navigateur

| Option | Capacité vérifiée | Hypothèse | Verdict |
|---|---|---|---|
| Canvas 2D natif | aucune dépendance (constat) | fluide jusqu'à quelques dizaines de milliers de points `[À mesurer]` | **lot 3** |
| regl-scatterplot (WebGL) | sa documentation annonce jusqu'à 20 millions de points, zoom, déplacement et sélection au lasso | licence MIT et poids du bundle `[à vérifier avant adoption]` | lot 4 si la mesure l'exige |
| deck.gl | non vérifié dans cette étude | adapté à la 3D `[Hypothèse]` | à étudier seulement si la 3D est retenue |

Toute bibliothèque est **embarquée** dans le jar, avec son empreinte : aucun chargement depuis un CDN.

### 5.4 Calcul, cache et invalidation `[Proposition]`

- **Hors du chemin de recherche** : la préparation est une commande ; ni la liste, ni la recherche, ni la lecture des points ne calculent.
- **Lecture par lots** : nouvelle lecture des vecteurs par pages ordonnées par `stable_key` ; `load()` (qui matérialise tout l'index, `PostgresSemanticReadQueries.java:121-147`) n'est pas utilisée.
- **Échantillonnage reproductible** : rang d'un document = `sha256(graine ␟ stable_key)` ; les `N` premiers rangs forment l'échantillon. L'ajout d'un document ne redistribue pas les autres. L'ajustement de la PCA peut porter sur un échantillon ; la projection porte sur tous les points retenus.
- **Budgets (cibles, non mesurées)** : 100 000 points affichables ; 512 Mio de tas pour la préparation ; charge utile des points en binaire (`float32` x, y et un octet de catégorie par axe de couleur) ≈ 1,1 Mo pour 100 000 points ; les métadonnées se lisent à la demande.
- **Annulation et progression** : la commande publie sa progression sur `stderr` et s'interrompt proprement (aucune publication partielle).
- **Cache dérivé et reconstruisible** : supprimable sans perte ; jamais source de vérité.
- **Publication atomique** : écriture dans un fichier temporaire puis remplacement atomique par les primitives d'E/S privées (`check-private-io.py`).
- **Clé de projection** : `sha256` de `project_id`, `snapshot_id` indexé, `provider_id`, `model_id`, `dimensions`, **empreinte documentaire** (`sha256` des couples `stable_key`/`checksum` triés), méthode et sa version, paramètres, graine, sélection du corpus (natures retenues, plafond et graine d'échantillonnage).
- **Invalidation** : à la lecture, l'identité courante de l'index est comparée à celle de la projection ; un écart rend l'état `PERIMEE`, jamais une carte silencieusement fausse.
- **Espaces incompatibles** : une projection ne couvre qu'un projet, donc un seul modèle (`semantic_index_meta` a une ligne par projet). Aucune carte multi-projets tant que l'égalité provider/modèle/dimensions n'est pas vérifiée.

### 5.5 Protocole de mesure `[À mesurer]`

| Palier | Corpus | Remarque |
|---|---|---|
| 1 000 | synthétique, `local-hash` 256 dimensions | déterministe, rejouable en CI |
| 10 000 | synthétique, `local-hash` 256 dimensions | |
| ≈ 25 000 | index réel de ce dépôt (25 210 documents `embeddinggemma`, mesure du 2026-10-15, `docs/project/governance/STATUS.md:61`) | seul corpus appris disponible |
| 100 000 | synthétique ; PostgreSQL | à 768 dimensions, 100 000 vecteurs `float32` pèsent ≈ 307 Mo et dépassent le plafond de 192 Mio du magasin local (`FileSemanticVectorStore.java:56`) : palier réservé à PostgreSQL ou à une dimension réduite |

Relevés par palier : durée et tas maximal de la préparation ; taille du cache ; latence d'une page de liste ; latence de recherche (déjà mesurable) ; taille de la charge utile des points ; délai du premier rendu ; fluidité du déplacement. Plateformes : Windows et Linux. **Aucun seuil n'est fixé avant la première mesure** ; les valeurs du § 5.4 sont des cibles.

## 6. Frontières de sécurité de la surface HTTP `[Proposition]`

« Écouter sur localhost » ne protège ni d'une page web tierce ouverte dans le même navigateur, ni d'un autre processus du poste, ni d'une résolution DNS détournée. Contrôles retenus :

| Risque | Contrôle |
|---|---|
| Exposition réseau | écoute sur `127.0.0.1` (et `::1`) uniquement ; aucune option d'écoute élargie dans le MVP ; en Docker, publication `127.0.0.1:<port>` seulement |
| Autre processus ou page tierce | jeton de session aléatoire de 256 bits, généré au démarrage, exigé sur toute requête `/api/` dans l'en-tête `Authorization` ; transmis au navigateur par le fragment d'URL (jamais envoyé au serveur ni journalisé) ; aucun cookie |
| Rebond DNS | liste d'autorisation de l'en-tête `Host` (`127.0.0.1:<port>`, `localhost:<port>`, `[::1]:<port>`) ; tout autre hôte est refusé |
| Requête inter-origines | refus de toute requête dont `Origin` n'est pas l'origine servie ; aucun en-tête CORS émis ; recherche en `POST` avec `Content-Type: application/json` |
| Exécution de contenu indexé | le code et les extraits sont insérés comme **texte** (`textContent`) ; `Content-Security-Policy: default-src 'self'` sans script en ligne ; `X-Content-Type-Options: nosniff` |
| Lecture de fichiers | uniquement par `LocalSourceReader` (confinement vérifié, § 1.2) ; jamais de chemin fourni par le navigateur, seulement un identifiant de document |
| Fuite de chemins ou de secrets | réponses sans racine absolue (même règle que `surfaces-publiques` › « Le MCP n'expose pas la racine absolue d'un projet ») ; erreurs internes opaques |
| Abus de ressources | bornes existantes reprises (1 000 / 500 résultats, 64 Kio) ; pages de 200 documents au plus ; délai d'exécution par requête ; nombre de requêtes simultanées plafonné |
| Transmission à un tiers | ressources embarquées, aucun appel sortant du navigateur ; la requête de recherche n'est envoyée qu'au provider configuré (boucle locale ou `minos-ollama`) |
| Écriture | aucune route ne modifie index, projection ou configuration |

**Mode distant : non supporté.** Il n'existe aucune permission de lecture par projet (§ 1.5) et SEC-0071 interdit un serveur HTTP propre au plan de contrôle. `[Proposition]` : quand `MINOS_HOSTED_MODE=enabled`, `minos explorer serve` refuse de démarrer (fail-closed) tant qu'une décision séparée n'a pas défini authentification et autorisation.

pgAdmin est traité à part : c'est un outil d'administration dont les accès SQL **ne reproduisent pas** les règles applicatives de MINOS ([PGADMIN.md](PGADMIN.md) § 1).

## 7. Risques

| Risque | Atténuation |
|---|---|
| La PCA produit une carte peu lisible | variance expliquée affichée ; UMAP étudié au lot 4 sur mesure |
| La surface HTTP élargit la surface d'attaque d'un produit jusque-là sans écoute réseau | optionnelle, boucle locale, jeton, contrôles `Host`/`Origin`, revue de sécurité dédiée (skill `minos-security-checklist`) |
| Module `jdk.httpserver` absent du runtime Windows embarqué | la liste des modules est calculée par `jdeps` (`scripts/release/build-windows-distribution.ps1:255-256`) `[à vérifier par un build]` |
| pgAdmin attaché à un réseau non interne obtient un accès sortant | réseau d'entrée dédié, vérification de mises à jour désactivée, service hors profil par défaut |
| Lecture de `projects.root_value` par pgAdmin | droits `SELECT` par colonne, sans `root_value` |
| Résolution module/langage impossible quand l'index est `STALE` | valeur « non résolu » explicite, jamais devinée |
| Corpus au-delà des budgets | état « corpus trop volumineux », échantillonnage annoncé |
| Dérive entre maquette et données réelles | la maquette n'emploie que les champs du § 1.2 |

## 8. Questions ouvertes

**Décision du propriétaire, 2026-11-13 : toutes les recommandations ci-dessous sont acceptées** (Q1 à Q7). Le tableau est conservé comme trace de l'étude.

| # | Question | Recommandation |
|---|---|---|
| Q1 | Module `minos-explorer` ou package dans `minos-cli` ? | module (§ 4.1 point 2) |
| Q2 | L'ADR ARCH-0082 est créé au statut `Proposed` pendant la spécification, à la demande de cette étude ; `.claude/rules/openspec.md` prévoit de le planifier en tâche. Le garder au registre ou le retirer jusqu'à l'implémentation ? | le garder : `.claude/rules/adr.md` admet une proposition portée par un changement OpenSpec en cours |
| Q3 | Cache de projection : fichier sous `MINOS_HOME` pour les deux backends, ou tables PostgreSQL (schéma v6) ? | fichier au lot 3 ; tables seulement si une mesure l'exige |
| Q4 | Rôle `minos_inspect` : accès à `knowledge_snapshots` (binaire) et `indexing_runs` ? | non |
| Q5 | Docker (lot 2b) dans le premier incrément, puisque l'index appris du poste vit dans le PostgreSQL géré ? | oui, dès que le lot 2 natif est qualifié |
| Q6 | Refus de démarrage en mode équipe (§ 6) ? | oui |
| Q7 | Port par défaut et ouverture automatique du navigateur ? | port éphémère, URL imprimée sur `stderr`, pas d'ouverture automatique |

## 9. Sources externes consultées (2026-11-13)

- pgAdmin, *Container Deployment* (9.x) : <https://www.pgadmin.org/docs/pgadmin4/latest/container_deployment.html>
- umap-learn, *UMAP Reproducibility* : <https://umap-learn.readthedocs.io/en/latest/reproducibility.html>
- scikit-learn, `sklearn.manifold.TSNE` : <https://scikit-learn.org/stable/modules/generated/sklearn.manifold.TSNE.html>
- regl-scatterplot : <https://github.com/flekschas/regl-scatterplot> et l'article JOSS 10.21105/joss.05275
- Smile : <https://github.com/haifengl/smile>

Non relus dans cette étude, à confirmer sur la documentation officielle avant implémentation : rôles prédéfinis et droits par colonne de PostgreSQL 17, opérateurs et fonctions de pgvector 0.8 (`<=>`, `vector_dims`), deck.gl, comportement des réseaux Docker `internal` vis-à-vis de la publication de ports.

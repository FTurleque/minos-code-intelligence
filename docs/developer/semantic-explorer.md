# Surface d'exploration sémantique

Comment la consultation de l'index sémantique est construite : lectures du port vectoriel, service applicatif,
API Java, surface HTTP locale. Usage : [Explorer l'index sémantique](../user/semantic-explorer.md). Décision :
[ARCH-0082](../architecture/adr/ARCH-0082-surface-http-locale-optionnelle-de-consultation.md).

## Où vit quoi

| Élément | Module | Rôle |
|---|---|---|
| `SemanticVectorStore.documentPage`, `storedDocument` | `minos-domain` | lectures additives du port, à défaut dérivé de `documents()` et de `load()` |
| `PostgresSemanticReadQueries.documentPage`, `storedDocument` | `minos-storage` | une requête par lecture ; la page ne lit pas la colonne `embedding` |
| `ProjectMutationSemanticVectorStore` | `minos-storage` | délègue les deux lectures au magasin local, dont l'index chargé est en cache |
| `SemanticCorpusQueryService` | `minos-application` | projets, page de documents, détail, voisins, recherche déléguée |
| `SemanticExplorationApi`, `LocalSemanticExplorationApi` | `minos-api` | contrat Java v1, DTO propres |
| `ExplorerLaunchRoute`, `ExplorerCommandLine` | `minos-cli` | commande, options, codes de sortie |
| `ExplorerLaunchRouteProvider` | `minos-app` | route enregistrée par `META-INF/services`, refus avant ouverture |
| `ExplorerSession`, `ExplorerServer`, `RequestGuard`, `ExplorerApi` | `minos-explorer` | écoute, contrôles, routes, page embarquée |

`minos-cli` ne dépend pas de `minos-explorer` : comme pour `minos mcp`, le lanceur appelle une interface que
l'assemblage fournit par `ServiceLoader`. Le test ArchUnit `ModuleArchitectureTest` impose que seule
`ExplorerLaunchRouteProvider` atteigne la surface, que `minos-explorer` n'utilise ni adaptateur de stockage ni
JDBC, et qu'aucun autre module n'ouvre d'écoute réseau.

## Identité d'index et curseur

Une lecture nomme l'index qu'elle a lu : `(projectId, snapshotId, providerId, modelId, dimensions,
documentsFingerprint)`, où l'empreinte est le SHA-256 des couples clé stable / checksum triés. L'empreinte est
calculée une fois par index (les métadonnées, date de construction comprise, servent de clé de cache) à partir
de `checksums()`, qui ne lit ni contenu ni vecteur.

Le curseur d'une page est opaque : il porte l'empreinte de cette identité et la dernière clé stable rendue. Un
curseur présenté pour une autre identité, ou une page dont les métadonnées diffèrent de la première lue dans la
même requête, lève `IndexChangedException` (`409` en HTTP, `UNAVAILABLE` dans l'API Java).

L'ordre des clés stables est celui du magasin : comparaison de chaînes Java pour le magasin local,
`ORDER BY stable_key` (collation de la base) pour PostgreSQL. Il est cohérent d'une page à l'autre dans un même
magasin ; il n'est pas garanti identique entre les deux pour des clés non ASCII.

## Filtres

Les filtres s'appliquent dans le service, après la lecture d'une page du magasin, sur la description de chaque
document. Une page filtrée lit au plus 2 000 documents ; au-delà, elle est rendue courte avec un curseur et la
limite `SEMANTIC_DOCUMENT_FILTER_SCAN_BOUNDED`. Module et langage sont résolus par le snapshot actif, lu par
`CodeKnowledgeSnapshotStore.loadActiveKnowledge` et gardé pour le dernier couple projet / snapshot ; si l'index
ne décrit pas le snapshot actif, ils sont absents et la limite
`SEMANTIC_DOCUMENT_SYMBOLS_UNRESOLVED_INDEX_NOT_ALIGNED` est rendue.

Pour une recherche, le classement est celui de `SemanticSearchService` ou de `HybridSearchService`, bornes
comprises ; un filtre retire des résultats et chaque résultat garde le rang (`rank`) que le service lui a donné.

## Détail et source

`detail` lit le document et son vecteur par `storedDocument`, puis relit la source par `LocalSourceReader`, à
partir du `fileId` **stocké dans l'index** : aucun chemin ne vient de l'appelant. Un identifiant qui sort de la
racine du projet, un fichier absent ou illisible donnent `SOURCE_INTROUVABLE`, sans rien lire. Le contenu d'un
`CHUNK` est comparé à ce que l'indexation produirait aujourd'hui (`SemanticDocumentFactory.chunkContent`, la
fonction que la construction de l'index utilise) : `IDENTIQUE` ou `DIFFERENTE`. `SYMBOL` et `FILE` sont
`NON_COMPARABLE`.

## API HTTP, contrat 1

Préfixe `/api/v1/`. Toutes les réponses sont du JSON (`DeterministicJson`), avec `X-Content-Type-Options:
nosniff`. Autorisation de chaque route : `Authorization: Bearer <jeton>`, `Host` local, `Origin` absent ou égal
à l'origine servie, `Sec-Fetch-Site` absent, `same-origin` ou `none`.

Hors de l'API, `RequestGuard` classe la requête : le `GET` de `/`, `/app.css` ou `/app.js` (`Target.PAGE_READ`)
est servi quel que soit `Sec-Fetch-Site`, pour qu'un lien suivi depuis une autre page ouvre la surface ; `Host`
et `Origin` y restent contrôlés. Toute autre requête hors API (`Target.OTHER`) garde le refus des requêtes
venues d'un autre site. Un refus hors API répond par un texte fixe (`text/plain`), identique quel que soit le
motif.

| Route | Entrées | Sortie |
|---|---|---|
| `GET projects` | — | `contractVersion`, `projects[]` : `projectId`, `name`, `indexState`, `providerId`, `modelId`, `dimensions`, `activeSnapshotId`, `indexedSnapshotId`, `documentCount`, `limitations` |
| `GET projects/{p}/documents` | `cursor`, `limit` 1–200 (défaut 50), `kind`, `module`, `language`, `filePrefix` | `index`, `totalDocuments`, `documents[]`, `nextCursor`, `limitations` |
| `GET projects/{p}/documents/{stableKey}` | — | `index`, `document`, `indexedContent`, `currentSource`, `divergence`, `limitations` |
| `GET projects/{p}/documents/{stableKey}/neighbors` | `limit` 1–200 (défaut 20) | `index`, `stableKey`, `neighbors[]` : `document`, `score`, `scoreKind`, `limitations` |
| `POST projects/{p}/search` | corps JSON plat : `query` (≤ 64 Kio), `mode` `semantic`\|`hybrid`, `limit` (≤ 1 000 / 500, défaut 20), `minimumScore`, filtres | `index`, `snapshotId`, `mode`, `rankedCount`, `hits[]` : `rank`, `document`, `score`, `scoreKind`, `signals[]`, `limitations` |

`{p}` est l'identifiant ou le nom du projet ; `{stableKey}` est encodé en un seul segment (`%2F` pour `/`).
Un `document` porte `stableKey`, `kind`, `sourceId`, `fileId`, `startLine`, `endLine`, `contentChecksum`,
`contentLength`, `symbol` (`name`, `qualifiedName`, `kind`, ou `null` s'il n'est pas résolu), `language`,
`moduleId`. `scoreKind` vaut `COSINE_SIMILARITY` ou `HYBRID_SCORE`.

Erreurs : `{"error":{"code","message"}}`. `400` usage (borne nommée), `401` jeton, `403` hôte ou origine (corps
vide pour ces deux refus), `404` projet, document ou route, `405` méthode (en-tête `Allow`), `409`
`INDEX_CHANGED`, `415` corps non JSON, `503` `BUSY` ou `TIMEOUT`, `500` opaque (la sortie d'erreur de la
commande reçoit le seul nom de la classe de l'exception).

Le contrat évolue de façon additive sous ce préfixe ; une rupture publie `/api/v2/` à côté.

## Bornes de la surface

| Borne | Valeur | Où |
|---|---|---|
| Corps d'une requête | 512 Kio | `ExplorerServer.MAX_BODY_BYTES` |
| Appels d'API simultanés | 4, le suivant reçoit `503` | `ExplorerServer.MAX_CONCURRENT_API_CALLS` |
| Durée d'un appel | 30 s, puis abandon et `503` | `ExplorerServer.DEFAULT_REQUEST_TIMEOUT` |
| Fils de connexion | 8, file de 64 | `ExplorerServer` |
| Page de documents, voisins | 200 | `SemanticCorpusQueryService` |
| Lecture d'une requête, écriture d'une réponse | 10 s et 60 s | propriétés `sun.net.httpserver.maxReqTime` et `maxRspTime`, posées par `ExplorerServer` si elles sont absentes |
| Empreintes d'index, tables de symboles gardées | 64 projets, 4 projets | `SemanticCorpusQueryService` |

Le serveur HTTP du JDK lit ces deux propriétés une fois par JVM : une valeur déjà posée par l'hôte est gardée.
Des connexions qui n'achèvent pas leur requête occupent les fils de connexion jusqu'à ce délai ; une requête
arrivée entre-temps peut être coupée avec elles (`ExplorerServerTest`, test des connexions bloquées). Un appel
abandonné après 30 s garde son jeton de concurrence tant que la lecture sous-jacente n'a pas rendu la main.
Aucun seuil de performance n'est fixé : la mesure est planifiée avec le changement de projection.

## Écoute

`ListeningPolicy` ne connaît que deux portées. Sur un poste : `127.0.0.1`, et `::1` si la machine a cette
adresse ; si elle l'a et que le port y est déjà pris, la surface ne démarre pas.
Dans le runtime Docker : l'interface du conteneur, parce qu'un port publié n'atteint pas la boucle locale du
conteneur ; elle n'est accordée que si la variable interne `MINOS_EXPLORER_CONTAINER_LISTEN=enabled` est posée
(par le Compose), que `MINOS_RUNTIME_LOCATION=docker`, **et** que le processus tourne sous Linux avec un
marqueur de conteneur (`/.dockerenv` ou `/run/.containerenv`). Toute autre combinaison est un refus de
démarrage, jamais un repli silencieux. Il n'existe pas d'option de ligne de commande pour l'écoute.

## Page embarquée

`minos-explorer/src/main/resources/com/minos/explorer/web/` : `index.html`, `app.js`, `app.css`, sans chaîne de
build ni dépendance. `WebResources` sert cet ensemble fermé ; aucun chemin de requête n'atteint le chargeur de
classes. La page est servie avec `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src
'self'; connect-src 'self'; …`. `WebResourcesContentTest` refuse `innerHTML` et ses équivalents, toute URL
externe, tout script ou style en ligne.

## Tests

| Sujet | Test |
|---|---|
| Lectures du port, défaut dérivé | `minos-domain/.../SemanticVectorStorePagedReadsFallbackTest` |
| Contrat commun aux deux magasins | `minos-engine/src/test/.../testsupport/SemanticDocumentReadsContract`, exécuté par `FileSemanticDocumentReadsContractTest` et `PostgresSemanticDocumentReadsContractTest` |
| Service applicatif | `minos-application/.../SemanticCorpusQueryServiceTest` |
| API Java | `minos-api/.../SemanticExplorationApiContractTest` |
| Écoute, refus, en-têtes, bornes (sockets réels) | `minos-explorer/.../ExplorerServerTest` |
| Routes sur un `MINOS_HOME` réel, absence d'écriture et de chemin absolu | `minos-explorer/.../ExplorerRoutesTest` |
| Démarrage, refus en mode équipe et hors conteneur | `minos-explorer/.../ExplorerSessionTest`, `ExplorerUnitsTest` |
| Commande et route | `minos-cli/.../ExplorerCommandLineTest`, `minos-app/.../ExplorerLaunchRouteProviderTest` |
| JAR distribué, service Compose | `minos-app/.../ShadedJarExplorerEntryPointIT`, `ExplorerComposeServiceContractTest` |

Les tests PostgreSQL exigent le serveur de test (`scripts/dev/start-test-postgres.sh` ou `.ps1`).

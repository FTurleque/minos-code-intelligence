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
| `SemanticVectorStore.vectorPage` | `minos-domain` | lecture additive des vecteurs par lots (1 000 au plus), à défaut dérivé de `load()` |
| `SemanticProjection`, `SemanticProjectionStore` | `minos-domain` | modèle d'une projection et port de son cache dérivé |
| `FileSemanticVectorStore.vectorPage`, `checksums` ; `PostgresSemanticReadQueries.vectorPage` | `minos-storage` | lots lus par parcours du fichier, ou par une requête sans le contenu ; jamais par `load()` |
| `FileSemanticProjectionStore` | `minos-storage` | cache `semantic-projections/<projet>/projection-v1.bin`, pour les deux backends |
| `SemanticProjectionService`, `ProjectionPreparation`, `PrincipalAxes` | `minos-application` | préparation (échantillon, PCA), statut, points |
| `SemanticProjectionCommand` | `minos-cli` | `minos semantic projection prepare\|status` |
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
| `GET projects/{p}/projection` | — | `projectId`, `state`, `cause`, `prepareCommand`, `indexDocumentCount`, `projection` (`index`, `method`, `methodVersion`, `parameters`, `seed`, `kinds`, `maxPoints`, `pointCount`, `candidateCount`, `documentCount`, `sampled`, `explainedVariance`) ou `null` |
| `GET projects/{p}/projection/points` | `offset` (défaut 0), `limit` 1–5 000 (défaut 5 000) | les champs du statut, puis `offset`, `total`, `points[]` : `stableKey`, `kind`, `x`, `y` |
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

## Projection 2D

Une projection est un **cache dérivé** : elle se recalcule à volonté depuis l'index. Elle est préparée par la
commande `minos semantic projection prepare` et par elle seule ; les deux routes de projection sont des `GET`,
toute autre méthode rend `405`, et aucune lecture ne lit de vecteur ni ne calcule (`ExplorerProjectionRoutesTest`,
`SemanticProjectionServiceTest`).

**Préparation** (`ProjectionPreparation`). Chaque étape est un parcours de l'index par `vectorPage` :

1. *select* : parmi les documents des natures demandées, les `maxPoints` de plus petit rang sont retenus ; le rang
   d'un document est `sha256(graine ␟ stableKey)`, donc la même graine tire le même échantillon du même index ;
2. *center* : moyenne des vecteurs retenus ;
3. *fit* : itération de sous-espace à deux vecteurs, une passe par itération, 40 passes au plus, arrêt quand la
   base ne bouge plus (`PrincipalAxes.TOLERANCE`) ;
4. *project* : coordonnées des points retenus, rotation sur les deux axes principaux de la base, signe de chaque
   axe fixé (première valeur non nulle de sa direction positive), mise à l'échelle commune dans `[-1, 1]`.

Les sommes sont faites dans l'ordre des clés stables et les fonctions trigonométriques par `StrictMath` : deux
préparations identiques publient deux fichiers identiques octet pour octet (`SemanticProjectionCommandLineTest`).
La mémoire de travail est comptée (`ProjectionPreparation.WorkingMemory`) : un lot de vecteurs, six tableaux de
la taille d'un vecteur, et par point retenu sa clé, son rang et ses coordonnées ; au-delà du budget
`--max-memory-mb`, la préparation échoue sans publier. L'index n'est jamais chargé entier. Non compté : la table
des empreintes des documents, lue une fois au début et une fois avant publication pour l'identité de l'index.

**Identité et états.** La projection porte l'identité de son index (projet, snapshot, provider, modèle,
dimensions, empreinte SHA-256 des clés stables et des empreintes de documents), la méthode, sa version, ses
paramètres, la graine et la sélection. L'identité est relue avant publication : un index modifié pendant la
préparation la fait échouer. À la lecture, `SemanticProjectionService` rend `ABSENTE` (aucun fichier), `PRETE`
(identités égales), `PERIMEE` (identités différentes, calculé, jamais stocké) ou `ECHOUEE` (fichier illisible).
Une préparation en cours ou en échec n'est pas un état lu : une lecture n'écrit rien et ne sonde aucun verrou, de
sorte qu'elle fonctionne sur des données montées en lecture seule ; l'échec est rendu par le code de sortie de la
commande.

**Fichier** `projection-v1.bin` (`FileSemanticProjectionStore`) : magie `MSPJ`, version, identité, méthode,
sélection, effectifs, variance expliquée, puis par point sa clé stable, sa nature et deux `float32`, enfin le
SHA-256 de tout ce qui précède. Un fichier tronqué, altéré, d'une autre version ou d'un autre projet est refusé
à la lecture. Publication par fichier temporaire du même dossier puis remplacement atomique
(`DurableAtomicFile.replace`), sous un bail de fichier par projet (`prepare.lock`) ; le fichier temporaire est
supprimé dans tous les cas. E/S par les primitives privées uniquement (`check-private-io.py`).

**Lots de vecteurs.** `FileSemanticVectorStore.vectorPage` parcourt `index-v2.bin` sans décoder les contenus et
reprend à l'octet où la page précédente s'est arrêtée, tant que le fichier n'a pas été remplacé ; un index au
format hérité `index-v1.bin` passe par le chargement complet. `PostgresSemanticReadQueries.vectorPage` lit en une
requête les métadonnées de l'index, son effectif et une page `stable_key`, `kind`, `embedding`.

**Carte.** `app.js` dessine sur un `<canvas>` 2D, sans dépendance : couleur par nature, filtre de nature appliqué
à l'affichage, résultats de recherche mis en évidence, colonne « Carte » de la table. Les points sont lus par
pages de 5 000. Une projection `PERIMEE` n'est dessinée que sur demande.

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
Aucun seuil de performance n'est fixé. Mesure rejouable de la préparation et de la lecture d'une projection :
`./mvnw -B -ntp -pl minos-bootstrap -am test "-Dtest=SemanticProjectionMeasurementTest" "-Dminos.projection.measure=true" "-Dsurefire.failIfNoSpecifiedTests=false"`
(sautée sans la propriété ; relevé écrit dans `minos-bootstrap/target/projection-measurements.md`).

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
| Lots de vecteurs, défaut dérivé et contrat commun | `SemanticVectorStorePagedReadsFallbackTest`, `SemanticDocumentReadsContract`, `FileSemanticVectorStoreStreamedReadsTest` |
| Modèle et cache de projection | `minos-domain/.../SemanticProjectionTest`, `minos-storage/.../FileSemanticProjectionStoreTest` |
| Préparation, statut, points | `minos-application/.../SemanticProjectionServiceTest` |
| Commande et reproductibilité du fichier | `minos-cli/.../SemanticProjectionCommandLineTest`, `ExplorerCitedCommandsTest` |
| Routes de projection, carte | `minos-explorer/.../ExplorerProjectionRoutesTest`, `WebResourcesContentTest` |
| API Java | `minos-api/.../SemanticExplorationApiContractTest` |
| Écoute, refus, en-têtes, bornes (sockets réels) | `minos-explorer/.../ExplorerServerTest` |
| Routes sur un `MINOS_HOME` réel, absence d'écriture et de chemin absolu | `minos-explorer/.../ExplorerRoutesTest` |
| Démarrage, refus en mode équipe et hors conteneur | `minos-explorer/.../ExplorerSessionTest`, `ExplorerUnitsTest` |
| Commande et route | `minos-cli/.../ExplorerCommandLineTest`, `minos-app/.../ExplorerLaunchRouteProviderTest` |
| JAR distribué, service Compose | `minos-app/.../ShadedJarExplorerEntryPointIT`, `ExplorerComposeServiceContractTest` |

Les tests PostgreSQL exigent le serveur de test (`scripts/dev/start-test-postgres.sh` ou `.ps1`).

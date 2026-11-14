# Design

## Contexte

Motivation : `proposal.md`. Comparaison des méthodes, budgets et protocole de mesure : `docs/project/research/explorateur-vectoriel-2026-11/README.md` § 5. Conception commune du chantier : `openspec/changes/archive/2026-11-14-explorer-l-index-semantique-dans-un-navigateur/design.md`, dont ce design reprend le modèle, les états et les contrats de projection sans les recopier. Modules touchés : domaine, stockage, application, API, CLI, surface d'exploration.

## État vérifié au HEAD

Commit `8d3f8fe1dcdfee8604e532ef6d1991ded888701a` (`main`, 2026-11-14), relu au démarrage de l'implémentation, après l'archivage de `explorer-l-index-semantique-dans-un-navigateur`.

| Fait | Preuve |
|---|---|
| `load()` matérialise tout l'index, vecteurs compris, en texte puis en `double[]` | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticReadQueries.java:202-229`, `:280-294` |
| Les lectures additives du port ont un défaut dérivé de `load()` | `minos-domain/src/main/java/com/minos/semantic/SemanticVectorStore.java:51-71` |
| Le port lit une page de documents sans vecteurs (`documentPage`, 1 000 au plus) et un document avec son vecteur (`storedDocument`) ; aucune lecture de vecteurs par lots | `minos-domain/src/main/java/com/minos/semantic/SemanticVectorStore.java:73-128` |
| Le magasin local n'a pas de lecture propre des pages ni des empreintes : `documentPage`, `storedDocument` et `checksums` y dérivent de `load()` | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:79-176` (seuls `load` et `metadata` sont redéfinis) |
| Identité d'un document : `stable_key`, `checksum` | `minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java:154-155` |
| Identité d'index et empreinte des documents déjà calculées par le service de corpus | `minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java:355-371`, `:383-390`, `:454-474` |
| Format local `index-v2.bin`, vecteurs `float32`, plafond de 192 Mio décodés | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:48-56` |
| Remplacement conditionnel de l'index sous verrou de projet | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticVectorStore.java:106-135` |
| Index réel de 25 210 documents mesuré le 2026-10-15 | `docs/project/governance/STATUS.md:61` |
| Une commande `minos semantic status <projet>` existe ; `semantic projection …` s'y ajoute | `minos-cli/src/main/java/com/minos/cli/MinosCliRunner.java:42`, `minos-cli/src/main/java/com/minos/cli/command/RetrievalStatusCommand.java:83-85` |
| La surface HTTP ne rend que du JSON ; toute méthode autre que celle de la route rend `405` | `minos-explorer/src/main/java/com/minos/explorer/ExplorerApi.java:65-74`, `:106-109` |
| `SemanticProjectionStore` et `SemanticProjectionService` n'existent pas | recherche dans le dépôt, aucun résultat |

Non mesuré : tout ce qui concerne la PCA, le cache et le rendu.

## Écarts

Les noms de ports livrés par le changement précédent sont ceux de la conception commune : aucun écart de structure.

| Écart | Source A | Source B | Suite |
|---|---|---|---|
| Points rendus en binaire, table des clés paginée à part | conception commune, « Contrats d'interface » | la surface ne rend que du JSON (`ExplorerApi.java:65-74`) et aucune mesure ne justifie un second format | une seule route `projection/points`, en JSON, paginée par `offset` et `limit` (5 000 points au plus par page) ; le format binaire relève de la décision mesurée 7.1 |
| États `EN_PREPARATION` et `ECHOUEE` après une préparation en échec | conception commune, « États et transitions » | une lecture n'écrit rien et le plan de requête Docker monte les données en lecture seule : elle ne peut ni sonder un verrou ni lire un marqueur fiable après un arrêt brutal | une lecture rend `ABSENTE`, `PRETE`, `PERIMEE` ou `ECHOUEE` (fichier illisible) ; la préparation en cours n'est vue que par sa commande (progression sur `stderr`) et par le refus d'une seconde préparation ; un échec est rendu par le code de sortie, la projection précédente gardant son état |
| Le magasin local dérive `checksums` de `load()` | exigence « La lecture d'une projection ne calcule rien » (aucun vecteur lu) | `FileSemanticVectorStore.java:79-176` | lecture propre des empreintes et des lots de vecteurs dans l'adaptateur local, par parcours du fichier sans décoder ce qui n'est pas demandé |
| Budget de mémoire « configuré » sans option pour le configurer | delta, scénario « Budget dépassé » | table des commandes de ce design | option `--max-memory-mb N` de `prepare` (défaut 256) |
| Le « Purpose » de la spec de référence ne cite plus la carte 2D | `openspec/specs/exploration-semantique/spec.md:3-4` (retirée à l'archivage, la carte n'étant pas livrée) | ce changement la livre | point de clôture : le « Purpose » est complété à l'archivage de ce changement, pas avant |

## Exigences couvertes

| Exigence | Section |
|---|---|
| `exploration-semantique` › La préparation d'une projection est une commande explicite et bornée | Traitements ; D1, D2 |
| `exploration-semantique` › Une projection est publiée atomiquement et liée à l'identité de son index | Persistance ; Transactions |
| `exploration-semantique` › Les points d'une projection annoncent méthode, sélection et échantillonnage | Contrats d'interface |
| `exploration-semantique` › La lecture d'une projection ne calcule rien | Responsabilités ; Contrats d'interface |

## Objectifs et non-objectifs

Objectifs : calcul reproductible, borné en mémoire, hors du chemin de recherche ; carte jamais silencieusement fausse. Non-objectif : la meilleure séparation visuelle possible.

## Responsabilités et frontières des composants

| Composant | Module | Responsabilité |
|---|---|---|
| `SemanticVectorStore` (lecture par lots, additive) | `minos-domain` | vecteurs après une clé stable, par lots bornés |
| `SemanticProjectionStore` (port) | `minos-domain` | lire et publier une projection |
| Adaptateurs de lots ; cache fichier | `minos-storage` | lecture sans `load()` ; E/S privées |
| `SemanticProjectionService` | `minos-application` | sélection, PCA, identité, statut, points |
| Commandes `semantic projection prepare\|status` | `minos-cli` | lancement, progression sur `stderr`, codes de sortie |
| Routes de statut et de points ; carte | `minos-explorer` | lecture seule, rendu Canvas 2D |

Direction ARCH-0068 respectée, sans exception. Le calcul ne lance aucun processus.

## Modèle de domaine et invariants

Repris de la conception commune (« Modèle de domaine et invariants ») : identité d'index, sélection par rang `sha256(graine ␟ stableKey)`, projection = identité + méthode + paramètres + graine + sélection. Invariants ajoutés : la PCA centre les données ; le signe de chaque composante est fixé (première coordonnée non nulle positive) pour que deux préparations donnent les mêmes coordonnées ; les coordonnées sont normalisées dans `[-1, 1]`.

## Traitements et interactions

Séquence : conception commune, « Comment une projection est-elle préparée sans toucher au chemin de recherche ? ». PCA : sélection en un passage ; moyenne en un passage ; deux composantes par itération de sous-espace (décision D5), chaque itération étant un passage par lots sur l'échantillon d'ajustement ; projection de tous les points retenus en un dernier passage. Mémoire : proportionnelle à la dimension et à la taille d'un lot, pas au corpus.

## États et transitions

`ABSENTE`, `EN_PREPARATION`, `PRETE`, `ECHOUEE`, `PERIMEE` : conception commune, « Par quels états passe une projection ? ».

## Contrats d'interface

Routes `GET /api/v1/projects/{p}/projection` et `…/projection/points` : conception commune, « Contrats d'interface ». Toute autre méthode sur ces routes rend `405`.

| Commande | Options | Sortie | Codes |
|---|---|---|---|
| `minos semantic projection prepare <projet>` | `--max-points N` (défaut provisoire 100 000, question 1), `--seed N` (défaut 42), `--kinds CHUNK[,SYMBOL,FILE]` (défaut `CHUNK`), `--max-memory-mb N` (défaut 256), `--format text\|json` | rapport : points, documents, variance expliquée, durée | 0 en cas de succès ; non nul sinon (usage, index non `READY`, index modifié pendant la préparation, erreur interne), valeurs à aligner sur la convention de `docs/user/cli.md` |
| `minos semantic projection status <projet>` | `--format text\|json` | état, identité, méthode, sélection | 0 ; non nul pour une erreur d'usage |

La préparation exige un index `READY` : projeter un index `STALE` produirait une carte périmée dès sa publication.

## Persistance et intégrité

Fichier `semantic-projections/<projet>/projection-v1.bin` sous `MINOS_HOME`, pour les deux backends : magie, version de format, identité complète, méthode et paramètres, sélection, variance expliquée, nombre de points, puis par point sa clé stable, sa nature et ses coordonnées `float32`, empreinte SHA-256 finale vérifiée à la lecture. Une version de format inconnue est refusée.

## Transactions, concurrence et idempotence

Une préparation à la fois par projet (verrou de fichier) ; identité relue avant publication ; remplacement atomique d'un fichier temporaire du même dossier ; fichier temporaire supprimé en cas d'échec ou d'interruption. Deux préparations identiques produisent des fichiers identiques octet pour octet.

## Sécurité et isolation

E/S par les primitives privées (`scripts/architecture/check-private-io.py`) ; aucune route d'écriture (ARCH-0082) ; aucun chemin absolu dans le rapport ni les erreurs ; les points ne portent que des clés stables, le contenu se lit par le détail.

## Erreurs et reprise

Budget dépassé, index non prêt, index modifié pendant la préparation : erreurs actionnables, projection précédente intacte. Fichier corrompu : état `ECHOUEE` à la lecture, cause publique « projection illisible », relance possible.

## Observabilité

Progression par lots, durée et tas maximal sur `stderr` ; rapport final sur `stdout`.

## Performance et mesure

Aucun seuil : [Question]. Protocole : étude § 5.5 (1 000 et 10 000 documents synthétiques `local-hash`, index réel d'environ 25 000 documents, 100 000 documents sur PostgreSQL). Relevés : durée et tas de la préparation, taille du cache, taille de la charge des points, délai du premier rendu. Windows et Linux.

## Compatibilité, migration et retour arrière

Additif ; aucune migration. Retour arrière : supprimer le dossier `semantic-projections`.

## Diagrammes

Séquence de préparation, états et modèle d'identités : conception commune. À reporter dans le dossier de capacité par la tâche 6.9.

## Décisions

### D1 — PCA à deux composantes, en Java, sans dépendance
[Décision — propriétaire, 2026-11-13] Écartés à ce stade : UMAP (dépendance et licence), t-SNE (pas de projection de nouveaux points). Compromis : lisibilité non garantie, variance expliquée affichée.

### D2 — Préparation par commande, jamais par consultation
[Décision — propriétaire, 2026-11-13] Aligné sur BE-0029 et ARCH-0082.

### D3 — Cache dérivé en fichier pour les deux backends
[Décision — propriétaire, 2026-11-13] Écarté : tables PostgreSQL (migration de schéma sans mesure qui la justifie).

### D4 — Rendu Canvas 2D
[Proposition] Écarté à ce stade : WebGL (dépendance embarquée à justifier par une mesure, tâche 7.1).

### D5 — Ajustement par itération de sous-espace, 40 passes au plus
[Proposition] Les deux composantes sont cherchées ensemble (une passe par itération) plutôt que l'une après l'autre avec déflation : deux fois moins de passes sur l'index pour le même résultat. La base finale est tournée sur ses deux axes principaux par le calcul exact d'une matrice 2 × 2. Le nombre de passes et la tolérance font partie des paramètres publiés avec la projection.

### D6 — Points en JSON paginé, nature portée par chaque point
[Proposition] Voir « Écarts ». La nature du document est écrite avec chaque point pour que la carte se colore et se filtre sans relire l'index.

## Qualification des capacités

| Capacité | Qualification |
|---|---|
| Carte 2D, stockage local, natif | **partielle** : tests automatisés verts sous Windows (poste), Linux par la CI ; parcours navigateur du propriétaire (6.7) et seuils (6.8) non faits |
| Carte 2D, PostgreSQL externe, natif | **partielle** : lecture par lots prouvée par le contrat commun, exécuté contre PostgreSQL par la CI seulement ; préparation complète non exercée sur ce stockage |
| Carte 2D, runtime Docker | non supportée ; dépend de la qualification du service `minos-explorer` |
| UMAP, WebGL, 3D | non supportées, sans engagement |

## Risques et compromis

- [Risque] Carte PCA peu lisible → variance affichée, décision 7.1.
- [Risque] Itération de puissance lente à converger quand deux valeurs propres sont proches → nombre d'itérations borné, résultat déterministe quand même.
- [Risque] Remplacement atomique différent sous Windows → test sur les deux plateformes.

## Vérification

Tests de reproductibilité, de budget, d'atomicité et de péremption ; test de contrat de la lecture par lots sur les deux adaptateurs ; test « aucun appel au provider ni à la recherche » ; gates cités dans la proposition. Manuel : rendu de la carte dans un navigateur.

Rouge constaté avant correction (2026-11-14) : `SemanticProjectionServiceTest`, 16 tests sur 17 en échec sur un service dont les trois opérations levaient `UnsupportedOperationException` (tâches 6.1, 6.3 et 6.5, relevés en une exécution) ; `ExplorerProjectionRoutesTest`, 5 tests sur 6 en échec avec `404` au lieu de `200`, `400` ou `405` avant l'ajout des routes (6.5). Les tests des adaptateurs de stockage et du cache fichier ont été écrits après leur code : leur rouge n'a pas été observé.

Commandes exécutées réellement sur un `MINOS_HOME` jetable (`target/projection-demo/home`, index `local-hash` de la fixture `fixtures/typescript/typescript-modules`, 42 documents) : `minos semantic projection status` (`ABSENTE`, puis `PRETE`, `PERIMEE` après réimport sous `--snapshot demo-2`, `ECHOUEE` après troncature du fichier), `minos semantic projection prepare` (publication, échantillon de 10 sur 42, deux exécutions au fichier identique, refus sur index `DISABLED` avec code 1, erreur d'usage avec code 2).

### Revue de sécurité (2026-11-14)

Agent `minos-security-reviewer` exécuté en lecture seule sur `git diff origin/main` (aucun test lancé par l'agent). Aucun constat critique ni élevé. Vérifié sans constat : contrôles `RequestGuard` avant toute route, `405` et corps jamais lu sur les routes de projection, aucune écriture ni verrou à la lecture, E/S par les primitives privées (`check-private-io.py` vert), bornes et empreinte du fichier de projection, reprise par offset gardée par la taille, la date et l'ordre strict, erreurs HTTP opaques, page sans `innerHTML` ni ressource tierce, requête SQL paramétrée.

| # | Constat | Gravité | Traitement |
|---|---|---|---|
| 1 | L'échantillon était retrouvé par avance séquentielle dans l'ordre Java des clés ; un magasin qui trie par une collation linguistique (PostgreSQL hors collation `C`) faisait échouer toute préparation avec « index modifié » | moyenne | corrigé : chaque document de l'échantillon est retrouvé par sa clé, quel que soit l'ordre des pages ; rouge puis vert : `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#aStoreThatSortsItsPagesByALinguisticCollationIsProjectedLikeAnyOther` (magasin de test à ordre insensible à la casse). **Non reproduit contre un PostgreSQL en collation linguistique** : aucun serveur sur le poste |
| 2 | Le budget de mémoire ne couvre ni un index au format hérité `index-v1.bin` (lu par chargement complet), ni le texte des vecteurs d'une page PostgreSQL | faible | non corrigé ; limite écrite dans `docs/developer/semantic-explorer.md` |
| 3 | À la lecture, le dossier du projet n'était pas contrôlé : un lien à sa place faisait lire un autre arbre | faible | corrigé : un lien ou un non-dossier est refusé (`FileSemanticProjectionStoreTest#aProjectEntryThatIsALinkToAnotherTreeIsNeverRead`) |
| 4 | Tout échec du bail était rendu comme « autre préparation en cours » | faible | corrigé : seule l'expiration du délai l'est (`#aLeaseThatCannotBeTakenForAnotherReasonThanABusyOneIsNotReportedAsARunningPreparation`) |
| 5 | La position de reprise avançait de la longueur ré-encodée d'une chaîne, pas de la longueur lue | faible | corrigé (`FileSemanticVectorStoreStreamedReadsTest#aWalkResumedAfterAKeyThatIsNotValidUtf8ReadsTheNextEntryWhereItStarts`) |
| 6 | Bornes du lecteur de projection au-delà de ce que la préparation écrit ; décodages simultanés possibles à cache froid | faible | corrigé : 1 000 000 points, chaînes de 16 Kio, un décodage à la fois ; sans test dédié |
| 7 | En format texte, `status` écrivait sans filtrage des valeurs lues du fichier | faible | corrigé : valeurs passées par `CliCommandSupport.publicDiagnostic` ; sans test dédié |
| 8 | Une `IOException` de la commande sortait en « bootstrap failed » | information | corrigé : rendue avec le préfixe de la commande ; sans test dédié |
| 9 | La ligne de la dernière passe était écrite après la publication | information | corrigé : la publication est annoncée avant d'avoir lieu (`minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#theLastPassIsReportedBeforeTheProjectionIsPublished`) ; la ligne finale « published in » reste écrite après |
| 10 | Fichier temporaire laissé par un processus tué ; sous Windows, remplacement réessayé environ 1 s si un lecteur tient la cible | information | fichiers temporaires abandonnés supprimés par la préparation suivante (`FileSemanticProjectionStoreTest#whatAKilledPreparationLeftBehindIsRemovedByTheNextOne`) ; limite Windows non reproduite, laissée |
| 11 | `checksums()` n'exigeait pas l'unicité des clés | information | corrigé : une clé en double est refusée ; sans test dédié |

Décision laissée au propriétaire : un `MINOS_HOME` inscriptible par un tiers n'est pas dans le modèle de menace retenu pour ce cache dérivé ; les constats 3, 6 et 7 sont traités comme des durcissements.

## Documentation durable à mettre à jour

État à la clôture de l'implémentation (tâche 8.4) :

| Document | État |
|---|---|
| `docs/architecture/diagrams/capacites/exploration-semantique.md` | fait : vues V5 et V6, quatre lignes en « Exigences en attente d'archivage », écarts E6 à E9 |
| `docs/user/semantic-explorer.md`, `docs/user/cli.md` | fait : section « Carte 2D », commandes, états, dépannage, matrice de support (carte livrée, non qualifiée) |
| `docs/developer/semantic-explorer.md` | fait : section « Projection 2D », routes, tests, limites |
| `docs/project/governance/STATUS.md`, `ROADMAP.md` | fait |
| ADR ARCH-0082, « Mise en œuvre » | fait |
| arc42 | aucun changement : la surface et son module y figurent déjà, la projection n'ajoute ni module ni dépendance |
| `openspec/specs/exploration-semantique/spec.md`, « Purpose » | **reste, à l'archivage** : y citer la carte 2D ; déplacer alors les quatre lignes du dossier dans sa table de traçabilité |
| `docs/user/docker-runtime.md` | **reste** : rien n'y est écrit sur la préparation par `minos-admin`, non exercée |

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| exploration › préparation explicite et bornée | Préparation reproductible | Traitements | 6.1–6.2 | `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#twoPreparationsOfTheSameIndexWithTheSameSeedGiveTheSameCoordinates` ; fichier identique octet pour octet par la commande réelle : `minos-cli/src/test/java/com/minos/cli/SemanticProjectionCommandLineTest.java#thePreparationPublishesTheSameFileTwiceAndTheStatusReadsItBack` |
| idem | Budget dépassé | Erreurs | 6.1–6.2 | `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#aPreparationThatExceedsItsMemoryBudgetFailsAndLeavesThePublishedProjectionIntact` (refus et projection précédente intacte, sur le service) ; code de sortie non nul d'un refus prouvé sur un autre refus : `minos-cli/src/test/java/com/minos/cli/SemanticProjectionCommandLineTest.java#anIndexThatIsNotReadyOrAnUnknownProjectIsAnExecutionErrorThatSaysWhatToDo` |
| idem | (exigence) lecture par lots bornés, commande seule | Traitements | 6.1–6.2 | `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#aPreparationReadsTheVectorsByBoundedPagesAndNeverLoadsTheWholeIndex`, `#aPreparationCallsNeitherTheProviderNorTheSearch` ; adaptateur local sans `load()` : `minos-storage/src/test/java/com/minos/storage/local/store/FileSemanticVectorStoreStreamedReadsTest.java#vectorPagesAndChecksumsAreReadWithoutLoadingTheIndex` ; contrat des deux adaptateurs : `minos-engine/src/test/java/com/minos/testsupport/SemanticDocumentReadsContract.java#aFullWalkOfTheVectorsReturnsEachVectorOnceInStableKeyOrderWithItsKind` (PostgreSQL : sauté sur le poste, exécuté par la CI) |
| exploration › publication atomique | Index resynchronisé | États | 6.3–6.4 | `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#aProjectionWhoseIndexChangedIsStale` ; par HTTP : `minos-explorer/src/test/java/com/minos/explorer/ExplorerProjectionRoutesTest.java#aProjectionWhoseIndexWasReplacedIsStaleAndAnAlteredOneIsFailed` |
| idem | Préparation interrompue | Transactions | 6.3–6.4 | partielle : `minos-storage/src/test/java/com/minos/storage/local/store/FileSemanticProjectionStoreTest.java#aPreparationThatEndsWithoutPublishingLeavesThePublishedProjectionAndNoPartialFile`, `#aPreparationInterruptedBeforeItsFirstPublicationLeavesNoReadableProjection`, `#anAlteredCutOrForeignFileIsRefusedWithoutNamingItsLocation` ; l'arrêt brutal d'un processus pendant l'écriture n'est pas exercé |
| exploration › points annoncés | Corpus échantillonné | Contrats | 6.5–6.6 | `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#aSampleOfTwoHundredPointsOutOfAThousandDocumentsIsAnnouncedWithItsSeed` (200 sur 1 000) ; par HTTP : `minos-explorer/src/test/java/com/minos/explorer/ExplorerProjectionRoutesTest.java#aSampledProjectionAnnouncesItsMethodItsSeedAndItsCountsWithItsPoints` |
| exploration › lecture sans calcul | Projection absente | Contrats | 6.5–6.6 | `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java#aProjectWithoutProjectionIsAbsentNamesTheCommandAndReadsNoVector` ; par HTTP : `minos-explorer/src/test/java/com/minos/explorer/ExplorerProjectionRoutesTest.java#aProjectWithoutProjectionIsAbsentAndTheAnswerNamesTheCommandThatPreparesIt` |
| idem | Préparation demandée par HTTP | Contrats | 6.5–6.6 | `minos-explorer/src/test/java/com/minos/explorer/ExplorerProjectionRoutesTest.java#anyMethodButGetOnAProjectionRouteIsRefusedAndNoProjectionIsCreatedOrChanged` |

Preuves exécutées le 2026-11-14 sur le poste Windows (tests Maven ciblés par module) ; Linux et PostgreSQL sont
couverts par la CI de la PR. Preuves manquantes : parcours des états dans un navigateur par le propriétaire
(`parcours-navigateur-6.7.md`, tâche 6.7) ; seuils de performance et paliers non mesurés (`mesures-6.8.md`, tâche
6.8) ; arrêt brutal d'un processus pendant la publication ; dépassement de budget observé par la commande elle-même
(le plus petit budget acceptable, 1 Mio, suffit aux corpus de test).

## Questions ouvertes

| # | Question | Recommandation |
|---|---|---|
| 1 | Plafond de points par défaut | 100 000 gardé à titre provisoire ; proposition motivée dans `mesures-6.8.md` |
| 2 | Seuils de performance | propositions dans `mesures-6.8.md` ; aucune n'est appliquée |
| 3 | Options UMAP, WebGL, 3D (tâche 7.1) | décision du propriétaire sur le relevé ; aucune n'est implémentée |

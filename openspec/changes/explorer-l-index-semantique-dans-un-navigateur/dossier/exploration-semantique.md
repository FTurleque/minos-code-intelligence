# Capacité `exploration-semantique` — dossier durable

Spec de référence : [openspec/specs/exploration-semantique/spec.md](../../../../openspec/specs/exploration-semantique/spec.md)
· Relevé au 2026-11-13, changement `explorer-l-index-semantique-dans-un-navigateur`.

## Objet et périmètre

[Exigence] L'utilisateur d'un poste consulte, depuis un navigateur local et en lecture seule, les documents
sémantiques d'un projet : liste paginée, détail, recherche, voisins. Acteurs : l'utilisateur du poste, son
navigateur. Hors périmètre : carte 2D (changement `projeter-l-index-semantique-en-2d`), accès distant, toute
écriture. Décision : ARCH-0082.

## Vues

### Par où passe une requête du navigateur, et où est-elle refusée ?

Type : sequenceDiagram (UML, séquence) · Portée : existant vérifié · Exigences : « Toute requête d'API exige le
jeton de session et une origine locale », « La surface d'exploration ne modifie aucun état »

```mermaid
sequenceDiagram
  autonumber
  participant N as "Navigateur local"
  participant G as "RequestGuard"
  participant A as "ExplorerApi"
  participant S as "SemanticCorpusQueryService"
  participant V as "SemanticVectorStore"
  N->>G: requête /api/v1/…
  alt Host hors des trois noms de boucle locale
    G-->>N: 403, corps vide
  else Origin ou Sec-Fetch-Site étranger
    G-->>N: 403, corps vide
  else jeton absent ou erroné
    G-->>N: 401, corps vide
  else requête acceptée
    G->>A: méthode, segments, paramètres
    alt méthode autre que celle de la route
      A-->>N: 405 + Allow
    else route de lecture
      A->>S: projets, page, détail, voisins ou recherche
      S->>V: documentPage, storedDocument, search, checksums
      V-->>S: documents et métadonnées de l'index lu
      S-->>A: réponse et identité de l'index
      A-->>N: 200 JSON, nosniff
    end
  end
```

Aucun service n'est appelé avant le garde ; aucune route n'appelle `replace`, `synchronize` ni un provider
d'embeddings pour autre chose que le vecteur de la requête de recherche.

### Qui démarre la surface, et sur quelles adresses écoute-t-elle ?

Type : flowchart (organigramme de décision, non UML) · Portée : existant vérifié · Exigences : « La surface
d'exploration est optionnelle et n'écoute que sur la boucle locale »

```mermaid
flowchart TD
  C["minos explorer serve"] --> U{"options valides ?"}
  U -- non --> E2["code 2, usage, rien n'est ouvert"]
  U -- oui --> R["ExplorerLaunchRoute fournie par minos-app"]
  R --> B{"poste dont le backend est Docker ?"}
  B -- oui --> E2
  B -- non --> T{"mode équipe activé ?"}
  T -- oui --> E2
  T -- non --> L{"MINOS_EXPLORER_CONTAINER_LISTEN posée ?"}
  L -- non --> LO["écoute 127.0.0.1 et ::1"]
  L -- oui --> K{"runtime docker, Linux et marqueur de conteneur ?"}
  K -- non --> E2
  K -- oui --> CI["écoute sur l'interface du conteneur, port publié sur 127.0.0.1 de l'hôte"]
```

Aucune option de ligne de commande ne choisit l'adresse d'écoute.

### Quels composants, et dans quel sens dépendent-ils ?

Type : flowchart (vue de composants, non UML) · Portée : existant vérifié · Exigences : « La surface
d'exploration ne modifie aucun état », « Les voisins d'un document sont classés depuis son vecteur stocké »

```mermaid
flowchart LR
  subgraph ASM["minos-app (assemblage)"]
    RP["ExplorerLaunchRouteProvider"]
  end
  subgraph SURF["surfaces"]
    CLI["minos-cli : ExplorerCommandLine, ExplorerLaunchRoute"]
    HTTP["minos-explorer : ExplorerSession, ExplorerServer, ExplorerApi"]
    API["minos-api : SemanticExplorationApi"]
  end
  subgraph APP["minos-application"]
    CQ["SemanticCorpusQueryService"]
    SS["SemanticSearchService"]
    HS["HybridSearchService"]
    SR["LocalSourceReader"]
  end
  subgraph DOM["minos-domain"]
    VS["SemanticVectorStore : documentPage, storedDocument"]
  end
  subgraph STO["minos-storage"]
    L["magasin local"]
    P["magasin PostgreSQL"]
  end
  CLI -. "ServiceLoader" .-> RP
  RP --> HTTP
  HTTP --> CQ
  API --> CQ
  CQ --> SS
  CQ --> HS
  CQ --> SR
  CQ --> VS
  L -. implémente .-> VS
  P -. implémente .-> VS
```

`minos-cli` ne dépend pas de `minos-explorer` : il déclare une interface que l'assemblage fournit. Les surfaces ne
voient que le service applicatif ; aucune ne nomme un adaptateur de stockage (`ModuleArchitectureTest`).

### Comment le service optionnel du runtime Docker est-il exposé ?

Type : flowchart (vue de conteneurs, non UML) · Portée : existant vérifié, non qualifié sur une installation
réelle · Exigences : « La surface d'exploration est optionnelle et n'écoute que sur la boucle locale »

```mermaid
flowchart TB
  H["Hôte : 127.0.0.1:port"]
  subgraph ING["minos-explorer-ingress (non interne)"]
    EX["minos-explorer (profil explorer, lecture seule)"]
  end
  subgraph RT["minos-runtime (interne)"]
    PG[("minos-postgres")]
    OL["minos-ollama"]
    MCP["minos-mcp (STDIO)"]
  end
  H -- "port publié sur la boucle locale, jeton exigé" --> EX
  EX --> PG
  EX --> OL
  MCP --> PG
```

Dans le conteneur, la surface écoute sur l'interface du conteneur : un port publié n'atteint pas sa boucle
locale. Cette écoute n'est accordée que par la variable interne posée par le Compose, dans un conteneur. Le
réseau d'entrée donne au conteneur une route sortante, acceptée en l'état (écart E3).

## Traçabilité

| Exigence | Scénarios couverts | Vue | Preuves | Statut |
|---|---|---|---|---|
| La surface d'exploration est optionnelle et n'écoute que sur la boucle locale | 3/3, le premier par preuve structurelle | V2, V4 | `minos-explorer/src/test/java/com/minos/explorer/ExplorerServerTest.java#theSurfaceIsBoundToLoopbackAddressesOnlyAndItsUrlNamesTheIpv4Loopback`, `minos-explorer/src/test/java/com/minos/explorer/ExplorerSessionTest.java#theSurfaceRefusesToStartWhenTheTeamModeIsEnabledAndOpensNoPort`, `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java#onlyTheExplorerRouteOfTheAssemblyStartsTheExplorationSurface` | partielle |
| Toute requête d'API exige le jeton de session et une origine locale | 3/3 | V1 | `minos-explorer/src/test/java/com/minos/explorer/ExplorerServerTest.java#anApiRequestWithoutTheTokenIsRefusedWithoutDetailAndReachesNoService`, `minos-explorer/src/test/java/com/minos/explorer/ExplorerServerTest.java#aRequestWhoseHostIsNotALoopbackNameOfThisPortIsRefusedEvenWithTheToken`, `minos-explorer/src/test/java/com/minos/explorer/ExplorerServerTest.java#aRequestFromAnotherOriginIsRefusedAndNoCorsHeaderIsEverSent` | couverte |
| La surface d'exploration ne modifie aucun état | 1/1 hors statut et points de projection (non livrés) | V1 | `minos-explorer/src/test/java/com/minos/explorer/ExplorerRoutesTest.java#consultingEveryRouteLeavesTheHomeAndTheIndexByteForByteUnchanged` | partielle |
| La liste des documents est paginée et lue sur un seul index | 3/3 | V1 | `minos-engine/src/test/java/com/minos/testsupport/SemanticDocumentReadsContract.java#aFullWalkReturnsEveryDocumentOnceInStableKeyOrder`, `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#aPageSizeOutOfBoundsIsAUsageErrorThatNamesTheBound`, `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#aCursorIssuedForAnIndexIsRefusedOnceThatIndexIsReplaced` | couverte |
| Le détail distingue le contenu indexé de la source actuelle | 2/2 | V1 | `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#theDetailOfAChunkWhoseSourceChangedShowsBothContentsAndTheDivergence`, `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#theDetailOfADocumentWhoseFileWasDeletedSaysTheSourceIsMissingAndShowsNoAbsolutePath` | couverte |
| La recherche de l'explorateur rend le classement des services existants et nomme chaque score | 2/2, égalité avec le service et non avec la sortie de la commande | V1 | `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#aSemanticSearchKeepsTheRanksAndScoresOfTheSearchServiceAndNamesTheScore`, `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#aSearchOnAnIndexThatIsNotReadyIsEmptyAndSaysWhy` | partielle |
| Les voisins d'un document sont classés depuis son vecteur stocké | 2/2 | V1 | `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#neighboursAreRankedFromTheStoredVectorWithoutAnyCallToTheProvider`, `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java#anUnknownStableKeyIsNotFoundForNeighboursAndForTheDetail` | couverte |
| Aucune réponse de l'explorateur n'expose de chemin absolu ni de secret | 2/2 | V1 | `minos-explorer/src/test/java/com/minos/explorer/ExplorerRoutesTest.java#noAnswerErrorOrLogLineNamesAnAbsoluteRootOrAProviderAddress`, `minos-explorer/src/test/java/com/minos/explorer/ExplorerServerTest.java#everyAnswerCarriesTheSecurityHeadersAndNoCookie`, `minos-explorer/src/test/java/com/minos/explorer/WebResourcesContentTest.java#indexedContentCanOnlyBeInsertedAsText` | couverte |
| Le provider local-hash est signalé comme référence déterministe | 1/1 | V1 | `minos-explorer/src/test/java/com/minos/explorer/ExplorerRoutesTest.java#theProjectListDescribesTheIndexAndNeverTheRootOfAProject`, `minos-api/src/test/java/com/minos/api/SemanticExplorationApiContractTest.java#theFiveReadingsDescribeOneIndexAndNeverItsRoot` | couverte |

## Écarts

| Id | Écart | Preuve | Suite proposée |
|---|---|---|---|
| E1 | Le scénario « Aucune écoute sans la commande » n'est prouvé que structurellement (aucun autre code n'atteint la surface ni n'ouvre d'écoute) ; aucun test n'observe les ports d'un processus CLI ou MCP | `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java` | [Proposition] Test de processus qui relève les ports à l'écoute |
| E2 | Le « Purpose » et le scénario « Données inchangées » citent la carte 2D, le statut et les points de projection, portés par un autre changement | `openspec/changes/projeter-l-index-semantique-en-2d` | Aucun avant l'archivage de ce changement |
| E3 | En runtime Docker, la surface écoute sur l'interface du conteneur et le réseau d'entrée n'est pas interne (route sortante) | `docker/compose-mcp.connected.yaml` | Accepté par le propriétaire le 2026-11-13 (ARCH-0082, « Écarts constatés ») |
| E4 | « Même classement que la CLI » est prouvé contre `SemanticSearchService`, que la commande appelle, pas contre la sortie de `semantic-search` | `minos-application/src/test/java/com/minos/application/semantic/SemanticCorpusQueryServiceTest.java` | [Proposition] Test de bout en bout CLI contre HTTP |
| E5 | Le parcours dans un navigateur réel n'est pas automatisé | — | Parcours manuel du propriétaire (tâche 5.6) |

## Questions ouvertes

Seuils de performance : à fixer après la mesure planifiée avec le changement de projection.

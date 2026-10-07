# Proposal

## Why

Les outils de statut sont ceux qu'un agent ou un IDE interroge en premier ; l'audit 2026-10
(annexe C et D) montre qu'ils diagnostiquent mal et ne tiennent pas leur contrat de lecture
seule, au HEAD `bc1d3421` :

- une référence de projet de type chemin (ce que les agents passent le plus souvent) donne une
  erreur opaque sur les trois surfaces : CLI `error: index-status failed: ResolutionException`
  (reproduit sur le jar construit), MCP « MINOS tool execution failed », API
  `ResolutionException`. Un simple nom inconnu, lui, donne `unknown project: <nom>` ;
- le MCP « lecture seule » (ADR-0017) inspecte les runtimes providers à chaque appel de
  `minos_index_status` et `minos_project_structure` : extraction et écriture sous `MINOS_HOME`,
  SHA-256 de tout l'arbre des outils, script et sonde AppContainer sous Windows ;
- le statut d'index paie une découverte complète du dépôt qu'il n'utilise pas, et échoue quand
  la découverte échoue alors que l'état d'index est lisible ;
- `maxTokens` inférieur à 800 est refusé par `minos_hybrid_context` et `ide hybrid-context` alors
  que le schéma publié l'autorise ;
- l'ouverture de l'API Java ne traduit que `IOException` : une `RuntimeException` d'ouverture sort
  brute et non redactée, contrairement au contrat v1 ;
- une erreur d'exécution d'une opération `ide` est rapportée « MINOS bootstrap failed ».

Ces six défauts touchent la même couche (traduction des erreurs publiques et lecture du statut) :
les traiter ensemble garde une seule politique d'erreur publique entre CLI, API Java et MCP.

## What Changes

- **Erreurs de référence de projet actionnables** (CLI, API Java, MCP) : une référence inconnue,
  ambiguë ou invalide est rapportée avec sa cause et l'action corrective (nom enregistré ou
  UUID, `minos project list`), sans recopier la valeur de l'appelant lorsqu'elle ressemble à un
  chemin ou à un secret. La politique d'écho est portée par le résolveur de projets et partagée
  par les trois surfaces. Les erreurs internes restent opaques et sans fuite.
- **Erreurs typées du MCP** : registre partiellement illisible, mode équipe désactivé, jeton
  d'équipe absent sont rapportés avec leur message fixe et sûr ; le journal d'opérateur nomme
  l'outil et la classe de la cause racine, jamais le message.
- **Statut MCP sans effet de bord** : `minos_index_status` et `minos_project_structure` ne
  touchent plus aux runtimes providers (aucune écriture, aucun hachage, aucun processus). Le champ
  existant `providerProfiles` est conservé (contrat additif) avec le profil statique des
  providers ; l'état d'exécution y est déclaré explicitement « non inspecté » et renvoie vers
  `minos providers` / `minos doctor`.
- **Statut d'index sans découverte** : l'état d'index d'un projet (CLI `index-status`, MCP
  `minos_index_status`) est calculé sans parcours de l'arbre du dépôt ; une découverte qui
  échoue ou dépasse son budget ne fait plus échouer le statut.
- **`maxTokens` bas accepté** : le plafond par document par défaut est borné par `maxTokens`
  quand le client n'a pas fourni `maxTokensPerDocument` (MCP et CLI `ide hybrid-context`).
- **Ouverture de l'API Java** : toute exception d'ouverture est traduite en `MinosApiException`
  classée et redactée.
- **`ide <op>`** : un `IOException` d'exécution est rapporté comme erreur d'exécution de la
  commande, plus comme échec de démarrage.

Aucun **BREAKING** : les messages d'erreur pour une référence « sûre » (par exemple
`unknown project: demo`) sont conservés à l'identique (épinglés par les goldens de
caractérisation) ; les nouveaux textes ne remplacent que des messages opaques. La valeur du
champ `runtimeState` de `providerProfiles` dans le statut MCP change de sens (voir design,
décision D3 et question ouverte 1).

## Capabilities

### New Capabilities
- `surfaces-publiques`: garanties observables des surfaces publiques versionnées (CLI, API
  Java, MCP) pour les erreurs publiques diagnostiquables et sans fuite, la lecture seule et la
  légèreté des outils de statut, la cohérence des bornes entre défauts et schéma publié.

### Modified Capabilities
<!-- Aucune : openspec/specs/ est vide, la capacité est créée par ce changement. -->

## Constats d'audit couverts

Sources : `docs/audit/annexes/C-surfaces.md` (C-NN = `MINOS-AUD-CNN`) et
`docs/audit/annexes/D-cycle-de-vie.md` (D-02 = `MINOS-AUD-D02`).

| Constat | Qualification | Priorité | Traité par |
|---|---|---|---|
| MINOS-AUD-C01 — erreurs opaques CLI/API/MCP, y compris référence de type chemin | DÉFAUT CONFIRMÉ (CLI reproduite ; MCP et API par lecture) | P1 | exigences « Une référence de projet inconnue, ambiguë ou invalide reçoit une erreur actionnable sans fuite », « Les échecs d'usage et de configuration connus du MCP sont diagnostiquables », « Les échecs internes restent opaques et sans fuite », « Un échec d'outil MCP est diagnostiquable par l'opérateur sans exposer son message » ; tâches 1.1, 2.1, 2.2, 3.1 |
| MINOS-AUD-C02 — le MCP « lecture seule » inspecte les runtimes providers | DÉFAUT CONFIRMÉ (chemin d'appel) ; RISQUE (durée, échec) | P1 | exigences « Le statut MCP n'a aucun effet de bord » et « L'état d'exécution des runtimes n'est jamais présenté comme inspecté par le statut MCP » ; tâches 1.2, 4.1 |
| MINOS-AUD-C03 — le statut paie une découverte complète du dépôt | DÉFAUT CONFIRMÉ (couplage) ; RISQUE (coût) | P2 | exigence « Le statut d'index ne dépend pas de la découverte du dépôt » ; tâches 1.3, 5.1 |
| MINOS-AUD-D02 — le statut d'index dépend d'une découverte complète (et d'un décodage complet du snapshot) | RISQUE ; AMÉLIORATION | P2 | même exigence que C03 pour la découverte ; le décodage du snapshot est hors périmètre (D-03) |
| MINOS-AUD-C05 — `maxTokens` < 800 refusé alors que le schéma l'autorise | DÉFAUT CONFIRMÉ | P2 | exigence « Une combinaison de bornes acceptée par le schéma publié n'est pas refusée par les défauts » ; tâche 6.1 |
| MINOS-AUD-C06 — l'ouverture de l'API ne traduit que `IOException` | DÉFAUT CONFIRMÉ | P2 | exigence « Tout échec d'ouverture de l'API Java est une erreur publique classée et redactée » ; tâche 7.1 |
| MINOS-AUD-C16 — `ide <op>` : `IOException` d'exécution rapportée « bootstrap failed » | AMÉLIORATION | P3 | exigence « Une erreur d'exécution d'une opération IDE n'est pas rapportée comme un échec de démarrage » ; tâche 8.1 |

## Impact

**Modules du reactor touchés**

- `minos-application` : résolveur de projets (politique de message public), service
  d'inspection de projets (vue de statut sans découverte), `ProjectOperations`.
- `minos-mcp` : traduction des erreurs d'outils, backend (statut passif, erreurs typées, défauts
  de contexte hybride).
- `minos-api` : traduction des erreurs publiques et ouverture de l'application.
- `minos-cli` : message d'échec d'une commande, `index-status`, `ide <op>`.
- `minos-app` : goldens de caractérisation du MCP (`providerProfiles`, voir D3).
- `minos-bootstrap` : tests du résolveur et de l'inspection (le module porte les tests
  d'application).
- Aucun changement dans `minos-engine`, `minos-nexus`, `minos-intellij`.

**Surfaces publiques impactées**

- CLI : messages d'erreur de toutes les commandes qui résolvent une référence de projet ;
  `index-status` ; `ide hybrid-context` ; `ide <op>`. Codes de sortie inchangés.
- API Java v1 : message et classement de `MinosApiException` pour référence inconnue et pour
  échec d'ouverture (aucun nouveau `ErrorCode` : `INVALID_REQUEST` / `UNAVAILABLE` /
  `IO_FAILURE`).
- MCP : texte des erreurs d'outils ; `minos_index_status` et `minos_project_structure` (contenu
  de `providerProfiles.runtimeState`) ; `minos_hybrid_context`. Le catalogue (31 outils) et les
  schémas ne changent pas.
- IntelliJ : indirectement (le plugin affiche les `error:` de la CLI) ; aucun changement de
  code ni de protocole. NEXUS : non concerné.

**ADR** : aucun nouvel ADR requis ; aucun ADR amendé. Le changement applique ADR-0017 (MCP en
lecture seule), l'alinéa (l) d'ADR-0039 (lecture d'état légère) et la forme additive des
contrats (ADR-0016, ADR-0018). ADR-0052 (statut Proposed) n'est pas modifié.

**Gates à rejouer** : `python scripts/remediation/check-p0-p2.py` (affirme
`error: MINOS tool execution failed` et interdit `effective.getMessage()` dans
`MinosMcpTools.java`), `python scripts/remediation/check-post-mne.py` (affirme `MAX_RESULT_BYTES`,
`MCP_RESULT_BUDGET_EXCEEDED`, `exceedsUtf8Budget`, `renderProgramGraph`),
`python scripts/quality/check-hosted-control-plane-consistency.py` (affirme dans le backend MCP
`System.getenv("MINOS_TEAM_TOKEN")`, `MINOS team mode is disabled`), et
`python scripts/quality/check-runtime-dynamic-consistency.py`. Scopes JaCoCo de
`scripts/quality/check-jacoco.py` : `mcp-mapping`, `m19-m20-mcp-catalogue`, `public-api`,
`project-resolution`. Si le catalogue d'outils MCP devait changer (ce n'est pas prévu),
rejouer `python scripts/docs/product-facts.py --check`. `scripts/quality/check-tools-manifest.py`
n'est pas concerné (il garde le catalogue des outils embarqués, pas les outils MCP).

## Hors périmètre

- MINOS-AUD-C13 (validation des arguments MCP dépendante du validateur de schéma du SDK) et
  MINOS-AUD-C15 (bornes de réponse et de durée, annulation, contenu non fiable) : améliorations.
- MINOS-AUD-C11 et MINOS-AUD-C12 (dérive documentaire du catalogue et de l'inventaire CLI) :
  traitées par un autre changement.
- MINOS-AUD-D01 (un répertoire illisible fait échouer la découverte, l'empreinte et le statut)
  et la tolérance du parcours de découverte lui-même : autre changement
  (`tolerer-repertoires-illisibles-a-la-decouverte`). Le présent changement découple seulement le
  statut d'index de la découverte ; `project list` et `project inspect` continuent de découvrir.
- MINOS-AUD-D03 (décodage complet du snapshot actif par l'observation d'état).
- Plugin IntelliJ : MINOS-AUD-C04 (lanceur `minos.cmd`), C07 à C10 ; la résolution de projet du
  plugin par `project list` (coût signalé dans C-03) n'est pas modifiée.
- MINOS-AUD-R12 (l'ouverture de `MINOS_HOME` crée le squelette de stockage) : le test de
  lecture seule du MCP se place après l'ouverture de l'application.
- Garde de lecture seule sur l'ensemble des 31 outils MCP (voir questions ouvertes du design).
- Tout nouveau `ErrorCode` public, tout changement de schéma d'outil MCP.

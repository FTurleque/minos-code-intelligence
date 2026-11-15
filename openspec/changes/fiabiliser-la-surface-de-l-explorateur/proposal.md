# Proposal

## Why

Cinq constats du sprint 3 de l'audit `docs/project/audits/2026-11-15/` touchent les deux mêmes fichiers de
`minos-explorer` (`ExplorerServer.java`, `ExplorerApi.java`) ; ils sont traités d'un bloc, avec **un seul critère de
journalisation** pour la surface :

- **AUD-QUA-02** (part explorateur) : un échec interne n'écrit que le nom simple de la classe, sans cause racine
  (`ExplorerServer.java:205`, `:276`, `:295`).
- **AUD-QUA-01** (part explorateur) : toute `IllegalArgumentException` est rendue `400 INVALID_REQUEST` sans trace
  (`ExplorerApi.java:133-135`).
- **AUD-SEC-02** : chaque requête refusée écrit une ligne, sans limitation (`ExplorerServer.java:195-197`) ; en Docker
  le journal du conteneur n'a pas de rotation (`docker/compose-mcp.connected.yaml:108-163`, aucune clé `logging`).
- **AUD-SEC-03** : une requête abandonnée au délai garde son créneau tant que la lecture ne rend pas la main
  (`ExplorerServer.java:268-280`, `:286-289`) ; quatre lectures bloquées rendent l'API indisponible.
- **AUD-SEC-04** : `segments` annonce refuser les segments « . » et « .. » et les accepte
  (`ExplorerServer.java:310-321`).

La surface est neuve (ADR 0082) et les sprints 4 et 8 vont en changer les lectures : elle doit d'abord dire ce qui
échoue, sans se laisser saturer ni noyer son journal.

## What Changes

- **Critère de journalisation unique** : la surface écrit sur stderr trois sortes de lignes seulement — démarrage,
  échec interne, refus agrégés. Aucune ne porte de chemin de requête, de valeur de paramètre ni de jeton.
- **Échec interne** (AUD-QUA-02) : ligne avec la route (son motif, pas son adresse), la classe, la classe de la cause
  racine, l'origine et une référence, par le composant de `tracer-les-echecs-internes-des-surfaces` ; la réponse
  `500 INTERNAL_ERROR` porte la référence ; pile au journal privé quand il est actif.
- **Erreur d'usage** (AUD-QUA-01) : seule une erreur d'usage explicite (`UsageException`) donne `400` ; toute autre
  erreur d'argument donne `500` et la ligne d'échec interne.
- **Refus limités** (AUD-SEC-02) : la première occurrence d'une raison de refus est écrite aussitôt ; les suivantes
  sont comptées et résumées par une ligne par raison et par fenêtre de temps.
- **Rotation en Docker** (AUD-SEC-02) : les services `minos-explorer` et `minos-pgadmin` bornent leur journal
  `json-file` ; le gate des plafonds Compose le tient.
- **Créneau libéré** (AUD-SEC-03) : une requête abandonnée au délai libère son créneau ; les lectures abandonnées
  encore en cours sont comptées, bornées, et dites dans le journal.
- **Segments** (AUD-SEC-04) : « . » et « .. » sont refusés en `400` avant tout appel de service ; la javadoc dit ce
  que le code fait.

## Capabilities

### New Capabilities

Aucune : la surface relève de `exploration-semantique`, le Compose de `distribution-docker`.

### Modified Capabilities

- `exploration-semantique` : exigences **ajoutées** seulement (échec interne tracé ; `400` réservé à l'erreur
  d'usage ; refus journalisés avec limitation ; créneau libéré à l'abandon ; lectures abandonnées bornées ; segments
  « . » et « .. » refusés). Les sprints 4 et 8 touchent la même capacité : aucune exigence existante n'est modifiée.
- `distribution-docker` : exigence **ajoutée** (journal borné des services publiés sur la boucle locale).

## Hors périmètre

- Le composant de trace et le journal privé eux-mêmes (`tracer-les-echecs-internes-des-surfaces`) et le type
  `UsageException` (`distinguer-l-erreur-d-usage-de-l-echec-interne`) : prérequis.
- Un délai de requête PostgreSQL ou d'embeddings propre à l'explorateur, et la coalescence ou le cache des lectures
  (AUD-PERF-03, sprint 4) : design, D4.
- Le jeton visible dans `docker compose logs` : limite déjà documentée (`docs/user/docker-runtime.md:381-383`), non
  comptée par l'audit.
- La rotation du journal des autres services Docker (`minos-mcp`, `minos-postgres`, `minos-ollama`) : design, D6.
- Le refus d'un `/` décodé dans un segment : design, D5.
- La page web embarquée et le contrat des routes (aucune route ajoutée ni retirée).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Trois sites écrivent `request failed: <nom simple>` | [Existant vérifié] | `ExplorerServer.java:205`, `:276`, `:295` |
| `ExplorerApi` rend toute IAE en `400` sans appeler `failures` | [Existant vérifié] | `ExplorerApi.java:133-135` |
| Un refus = une ligne, avant la réponse | [Existant vérifié] | `ExplorerServer.java:195-197` |
| Le journal de la surface est le stderr du processus | [Existant vérifié] | `ExplorerSession.java:82-84`, `:102-108` |
| Ni `minos-explorer` ni `minos-pgadmin` n'ont de clé `logging` | [Existant vérifié] | `docker/compose-mcp.connected.yaml:108-163`, `:469-…` ; recherche `logging\|max-size` dans `docker/*.yaml` : 0 résultat |
| Le créneau n'est rendu que dans le `finally` de la tâche ; le délai annule sans libérer | [Existant vérifié] | `ExplorerServer.java:268-289` ; `:45` (4 créneaux), `:98-100` (4 fils, file de 4) |
| `segments` ne refuse que le segment vide et le caractère nul | [Existant vérifié] | `ExplorerServer.java:310-321` |
| Une clé de document contient `/` et voyage encodée dans un segment | [Existant vérifié] | `minos-explorer/src/test/java/com/minos/explorer/ExplorerServerTest.java:271-272` ; `minos-explorer/src/main/resources/com/minos/explorer/web/app.js:367`, `:431` |
| `minos-explorer` ne compile pas contre `minos-engine` (portée de test seulement) et sa table A11 est vide | [Existant vérifié] | `minos-explorer/pom.xml:34-47` ; `scripts/architecture/check-module-boundaries.py:590-591` |
| Les lectures sous-jacentes ont un délai fini : PostgreSQL 120 s, embeddings 30 s par défaut | [Existant vérifié] | `PostgresConnectionFactory.java:25` ; `OllamaEmbeddingProvider.java:42` |
| Fenêtre d'agrégation des refus, borne des lectures abandonnées, taille de rotation | [Proposition] | `design.md`, D2, D3, D6 |

## Impact

- **Modules du reactor** : `minos-explorer` (pom inchangé : la trace passe par la façade `InternalFailureReporter` de
  `minos-application`, aucun type de `minos-engine` nommé) ; `minos-application` (validations de requête de
  `SemanticCorpusQueryService` converties en erreur d'usage) ; `docker/compose-mcp.connected.yaml` ;
  `scripts/quality/check-compose-limits.py` et son test.
- **Surfaces publiques** : API HTTP de l'explorateur (contrat v1), **additif** : référence dans le message d'une
  erreur `500` ; `400` nouveau pour « . » et « .. » ; des erreurs d'argument internes passent de `400` à `500`.
  CLI, API Java, MCP, IntelliJ, NEXUS : aucun.
- **ADR** : aucun à écrire ; 0082 (surface d'exploration) reste en vigueur, `SEC-<prochain numéro libre>` (journal privé) est porté par
  `tracer-les-echecs-internes-des-surfaces`.
- **Gates à rejouer** : `scripts/quality/check-compose-limits.py` (+ `test_check_compose_limits.py`),
  `scripts/quality/check-image-pins.py`, `scripts/install/test_mcp_lifecycle_pgadmin.py`,
  `scripts/architecture/check-module-boundaries.py` (A2, A8, A11), `scripts/architecture/check-private-io.py`,
  `scripts/quality/check-comment-language.py` ; `M29Docker*ContractTest` ; SonarCloud : nouveau code de
  `minos-explorer` ≥ 80 %.
- **Plateformes** : Windows et Linux, même code Java ; la rotation concerne Docker (Linux).
- **Documentation durable et diagrammes** : dossiers `exploration-semantique.md` et `distribution-docker.md` ;
  `docs/user/semantic-explorer.md` (lire le journal, référence d'erreur) ; `docs/user/docker-runtime.md` (rotation,
  conséquence pour l'adresse à jeton).

## Questions ouvertes

Aucune bloquante. D4, D5 et D6 du design attendent le propriétaire, avec une recommandation.

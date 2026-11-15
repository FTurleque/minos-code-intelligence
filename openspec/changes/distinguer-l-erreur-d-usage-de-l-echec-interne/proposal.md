# Proposal

## Why

Le constat **AUD-QUA-01** (Moyenne, Qualité) de l'audit `docs/project/audits/2026-11-15/` (sprint 3) relève que les
surfaces traduisent **toute** `IllegalArgumentException` en erreur d'usage, sans rien journaliser :

- le MCP rend le message de l'exception au client (`minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java:195-196`,
  `:240-256`), alors que les autres échecs passent par `logInternalFailure` (`:207`) ;
- l'API Java classe toute `IllegalArgumentException` en `INVALID_REQUEST` et toute `IllegalStateException` en
  `UNAVAILABLE` (`minos-api/src/main/java/com/minos/api/MinosApiSupport.java:70-73`).

Les sous-classes du JDK (`NumberFormatException`, `InvalidPathException`, `PatternSyntaxException`,
`IllegalFormatException`) et les préconditions violées dans un service suivent la même voie : un bogue interne
s'affiche « corrigez votre requête » et ne laisse aucune trace. C'est le schéma de requalification d'AUD-QUA-03 (10/10),
étendu aux surfaces. Le sprint 3 le corrige après la trace des échecs internes (AUD-QUA-02,
`tracer-les-echecs-internes-des-surfaces`), pour que l'erreur requalifiée en interne soit aussitôt diagnostiquable.

## What Changes

- **Type d'erreur d'usage explicite** : `UsageException`, sous-classe de `IllegalArgumentException`, dans
  `minos-domain`. Elle est levée par la validation des entrées d'une surface et par les validations de requête des
  services appelés. Le sous-typage garde tout `catch (IllegalArgumentException …)` existant valide.
- **MCP** : seule une `UsageException` est rendue comme erreur d'argument. Toute autre `IllegalArgumentException`
  suit la voie interne : erreur générique et ligne de diagnostic.
- **API Java** : seule une `UsageException` (ou une référence de projet non résolue) donne `INVALID_REQUEST`. Toute
  autre `IllegalArgumentException` donne `EXECUTION_FAILURE` et une ligne de diagnostic. Une `IllegalStateException`
  reste `UNAVAILABLE` mais devient tracée.
- **Erreurs typées conservées** : « document inconnu » (`DocumentNotFoundException`) devient une erreur d'usage
  explicite et reste `INVALID_REQUEST` ; « index remplacé » et « préparation refusée », indisponibilités attendues,
  ne sont pas tracées.
- **Genre de symbole inconnu** (MCP) : aujourd'hui rendu par l'erreur générique parce que la validation attache une
  cause, il devient une erreur d'usage au message fixe, qui nomme l'argument sans recopier la valeur envoyée.
- **Migration bornée** : les validations atteignables depuis le MCP et l'API Java passent à `UsageException` ; un
  test par surface fixe la table des erreurs d'usage attendues.
- **Changement de classification** de l'API Java (signalé, non additif au sens strict) : un appelant qui recevait
  `INVALID_REQUEST` pour un bogue interne reçoit `EXECUTION_FAILURE`. Note de version exigée.

## Capabilities

### New Capabilities

Aucune : la classification des erreurs des surfaces relève de `surfaces-publiques`.

### Modified Capabilities

- `surfaces-publiques` : exigences **ajoutées** (erreur d'argument du MCP réservée à l'erreur d'usage explicite ;
  `INVALID_REQUEST` de l'API Java réservé à l'erreur d'usage explicite ; indisponibilité non classée tracée ; table des
  erreurs d'usage tenue par test). Aucune exigence existante n'est modifiée : « Les échecs internes restent opaques et
  sans fuite » et « Tout échec d'ouverture de l'API Java est une erreur publique classée et redactée » restent
  tenues telles quelles.

## Hors périmètre

- L'explorateur (`ExplorerApi.java:133-135`) : changement `fiabiliser-la-surface-de-l-explorateur`, qui réutilise
  `UsageException`.
- L'**ouverture** de l'API Java (`MinosApiSupport.java:104-112`) : l'exigence existante y classe l'erreur de
  configuration en requête invalide ; la changer serait un `MODIFIED` (design, D4).
- La CLI : une erreur d'usage n'y vient que de l'analyse des options (`CliCommandSupport.java:93-98`, code 2) ; une
  exception du corps de la commande est déjà un échec d'exécution (`:104-107`, code 1).
- La conversion des autres `new IllegalArgumentException` du dépôt (plusieurs centaines dans `minos-application`,
  `minos-engine`, `minos-domain`) hors des chemins atteints par le MCP et l'API.
- NEXUS et le plugin IntelliJ.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le MCP rend le message de toute `IllegalArgumentException` sans cause ni contenu sensible | [Existant vérifié] | `MinosMcpTools.java:195-196`, `:240-256` |
| L'API Java classe IAE en `INVALID_REQUEST`, ISE en `UNAVAILABLE` | [Existant vérifié] | `MinosApiSupport.java:70-73` |
| Un test existant attend le message d'une IAE brute du backend | [Existant vérifié] | `minos-mcp/src/test/java/com/minos/mcp/MinosMcpToolsTest.java:239-244` |
| Décompte des `new IllegalArgumentException` de production : application 245, engine 320, domain 105, runtime-local 89, cli 74, storage 59, provider-scip 41, api 23, explorer 23, mcp 20, integration-git 10, app 8, nexus 4 | [Existant vérifié] | décompte `grep` sur `minos-*/src/main` au HEAD `89be3fb4` |
| `ISE → UNAVAILABLE` est un contrat documenté de l'API (index remplacé pendant la lecture) | [Existant vérifié] | `minos-api/src/main/java/com/minos/api/SemanticExplorationApi.java:12-13` |
| Le genre de symbole inconnu est rendu par l'erreur générique (IAE avec cause, valeur recopiée dans le message) | [Existant vérifié] | `MinosApplicationMcpBackend.java:362-368` ; `MinosMcpTools.java:241-243` |
| `DocumentNotFoundException` est une IAE et le contrat publie `INVALID_REQUEST` pour un document inconnu | [Existant vérifié] | `SemanticCorpusQueryService.java:447` ; `SemanticExplorationApi.java:11-12` |
| `IndexChangedException` et `RefusedException` sont des ISE attendues | [Existant vérifié] | `SemanticCorpusQueryService.java:438` ; `SemanticProjectionService.java:189` |
| `TeamCommand` déclare une `UsageException` privée homonyme | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/command/TeamCommand.java:362` |
| `UsageException` sous-classe non finale d'`IllegalArgumentException`, dans `minos-domain` | [Proposition] | `design.md`, D1, D6, D8 |
| Reclassement `INVALID_REQUEST → EXECUTION_FAILURE` accepté pour l'API Java | [Proposition] | `design.md`, D3 (décision du propriétaire) |

## Impact

- **Modules du reactor** : `minos-domain` (type), `minos-mcp`, `minos-api`, et les validations de requête atteintes
  dans `minos-application`, `minos-engine`, `minos-domain` (liste arrêtée par la tâche 1.2).
- **Surfaces publiques** : MCP — messages d'usage inchangés sauf le genre de symbole inconnu (de l'erreur générique à
  une erreur d'usage sans valeur recopiée), bogues internes désormais opaques ; API Java —
  **reclassement** d'un code d'erreur pour les bogues internes (voir What Changes) ; CLI, IntelliJ, NEXUS : aucun.
- **ADR** : aucun (pas de décision structurante : une convention de type et une correction de classification).
- **Gates à rejouer** : `scripts/remediation/check-p0-p2.py` (`error: MINOS tool execution failed`,
  interdiction de `effective.getMessage()`), `check-post-mne.py`, `scripts/architecture/check-module-boundaries.py`
  (A2, A8, A11), `scripts/architecture/check-private-io.py`, `scripts/quality/check-comment-language.py`,
  `check-runtime-dynamic-consistency.py`, `check-hosted-control-plane-consistency.py`,
  `scripts/docs/product-facts.py --check` ; scopes JaCoCo `mcp-mapping`, `m19-m20-mcp-catalogue`.
- **Plateformes** : Windows et Linux, même code, aucun comportement propre.
- **Documentation durable et diagrammes** : dossier `surfaces-publiques.md` ; `docs/developer/` (convention
  `UsageException`) ; note de version de l'API Java.

## Questions ouvertes

Aucune bloquante. D3 (reclassement de l'API Java) et D4 (ouverture de l'API) attendent le propriétaire, avec une
recommandation dans le design.

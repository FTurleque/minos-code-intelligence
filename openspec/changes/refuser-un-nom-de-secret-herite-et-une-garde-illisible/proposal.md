# Proposal

## Why

Deux constats de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`), confirmés au HEAD `89be3fb4`, portent sur deux contrôles de `minos-runtime-local` qui tiennent par les données du moment et non par le code :

- **AUD-SEC-05** (Faible) : l'invariant « le motif de secret l'emporte sur toute liste » (règle 3 de `.claude/rules/securite.md`, javadoc de `ProviderProcessEnvironment.isDeniedName`) n'est appliqué qu'aux variables déclarées. Les variables héritées passent par les seules listes d'autorisation ; aucune entrée actuelle ne correspond au motif, mais ni le code ni un test n'empêche d'en ajouter une.
- **AUD-QUA-05** (Faible) : sous Windows, la garde des octrois trop larges ignore une variable d'emplacement large qu'elle ne sait pas analyser, et conclut que l'octroi n'est pas trop large. C'est contraire à la règle 1 (« un contrôle qui ne peut pas être fait échoue »).

## What Changes

- L'environnement d'un provider et celui d'un lanceur de confiance retirent toute variable **héritée** dont le nom correspond au motif de secret, même si elle figure dans une liste d'autorisation.
- Un test parcourt les listes d'autorisation héritées et échoue si une entrée correspond au motif.
- Sous Windows, une variable d'emplacement large présente mais non analysable fait juger l'octroi **trop large** : la racine d'outillage n'est pas accordée. Seule l'erreur d'analyse de chemin est interceptée ; aucun message ne reprend la valeur de la variable.
- Le même traitement s'applique au contrôle de la racine système Windows, qui lit `SystemRoot` sans protection.

Aucune rupture de contrat. Aucun comportement ne change sur un poste dont les variables d'emplacement sont des chemins valides et dont les listes n'ont pas d'entrée à motif de secret (état du HEAD).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `installation-des-providers` : une exigence ajoutée (variable héritée à motif de secret). Les exigences existantes « Un motif de secret l'emporte sur toute liste d'autorisation » (commandes d'installation) et « Une variable déclarée par un plan d'indexation n'échappe pas au motif de secret » ne sont pas modifiées.
- `confinement-code-non-fiable` : une exigence ajoutée (garde Windows fail-closed sur une variable illisible).

## Hors périmètre

- Le contenu des listes d'autorisation et le motif lui-même : inchangés.
- `InstallationProcessEnvironment` : il applique déjà le motif à tout le résultat (`InstallationProcessEnvironment.java:48`).
- Le relèvement du plancher JaCoCo de branches de `ProviderProcessEnvironment` (AUD-TST-03, autre sprint) : changement `relever-les-planchers-de-la-frontiere-des-providers`. Ce changement-ci se fusionne d'abord (même classe) et ne modifie pas le plancher.
- La garde Linux (`LinuxBubblewrapWorkerSandboxBackend.isOverBroadToolchainRoot`), qui refuse déjà le plan.
- AUD-SEC-01 (changement `couvrir-le-clone-distant-par-l-ouverture-de-confiance`).

## Découpage

Les deux constats touchent le même module (`minos-runtime-local`), le même build et la même revue de sécurité, sans décision d'architecture ; chacun tient en quelques lignes et un test. Ils sont regroupés pour une seule PR `sec/`. Leurs fichiers sont disjoints : les groupes 1 et 2 des tâches sont indépendants.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les variables héritées d'un provider sont filtrées par `isSafeInheritedKey` seul | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:178-182` |
| Celles d'un lanceur de confiance par `TRUSTED_LAUNCHER_KEYS` seul | [Existant vérifié] | `ProviderProcessEnvironment.java:152-157` |
| Le motif n'est appliqué qu'aux variables déclarées | [Existant vérifié] | `ProviderProcessEnvironment.java:188-194` |
| Aucune des 71 entrées des trois listes ne correspond au motif | [Existant vérifié] | décompte par script en lecture seule sur `ProviderProcessEnvironment.java:21-115`, 2026-11-15 |
| La garde Windows intercepte toute `RuntimeException` et rend « non trop large » | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java:763-775` |
| `isWindowsSystemRoot` analyse `SystemRoot` sans interception | [Existant vérifié] | `WindowsAppContainerWorkerSandboxBackend.java:777-782` |
| Une variable illisible fait juger l'octroi trop large | [Proposition] | décision D2 du design, arbitrage de l'axe Sécurité demandé par l'audit |

## Impact

- **Modules du reactor** : `minos-runtime-local` (code et tests).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé ; application des règles 1 et 3 existantes.
- **Gates à rejouer** : `scripts/quality/check-jacoco.py` (scopes qui nomment `ProviderProcessEnvironment`, plancher `:182`, et `WindowsAppContainerWorkerSandboxBackend`, `:208`), `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast` ; chercher `isOverBroadGrant`, `isDeniedName` et `isSafeInheritedKey` dans `scripts/` avant tout renommage (skill `minos-literal-gates`).
- **Plateformes** : AUD-SEC-05 vaut sur Windows et Linux ; AUD-QUA-05 est propre à Windows (le test de la décision pure s'exécute sur les deux).
- **Documentation durable** : `docs/architecture/diagrams/capacites/installation-des-providers.md`, `docs/architecture/diagrams/capacites/confinement-code-non-fiable.md`, `.claude/rules/securite.md` (règle 3 : le motif s'applique aussi aux variables héritées).

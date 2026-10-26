# Proposal

## Why

Le constat **AUD-ARC-01** de l'audit du 10 octobre 2026 relève que les surfaces utilisent directement des types de `minos-engine` au lieu de passer par des cas d'usage de `minos-application` (ADR 0058 § 3, ADR 0057 § 6). La règle A11 tient cette dette. Le design archivé du lot 1 (`2026-10-24-faire-consommer-l-indexation-aux-surfaces`) inventorie six lots. Le lot 3, le **contrôle hébergé**, n'a pas de prérequis : l'ADR 0068 § 3 garde `com.minos.hosted` dans le moteur, et la cartographie de SH-08 le confirme (mise en œuvre de l'ADR 0068, « § 3, `hosted` »).

Au sommet du lot 6 (`50134955`), le package `com.minos.hosted` représente 13 entrées de `ENGINE_TYPES_NAMED_BY_SURFACE` :

- **API (9)** : `LocalMinosTeamApi` importe `HostedAuditEvent`, `HostedControlPlaneService`, `HostedPrincipal`, `HostedProjectBinding`, `HostedRetentionPlan`, `HostedRetentionPolicy`, `HostedRole`, `HostedTenantState` et `SharedWorkspace`.
- **CLI (3)** : `TeamCommand` nomme `HostedControlPlaneService`, `HostedRetentionPolicy` et `HostedRole`. `MinosCli` nomme le service, et `MinosCliRunner` appelle l'accesseur `hostedControlPlaneService()`.
- **MCP (1)** : `MinosApplicationMcpBackend` nomme le service par son nom qualifié.

La table bytecode compte 15 entrées `hosted` : API 9, CLI 5, MCP 1.

## What Changes

- **Façade applicative de l'équipe**, `com.minos.application.team.TeamControlPlane`. Elle se place au-dessus de `HostedControlPlaneService`, qui reste dans le moteur. Elle expose les mêmes opérations, en types applicatifs et JDK :
  - modèles de lecture : `TeamTenant`, `TeamWorkspace`, `TeamBinding`, `TeamMember`, `TeamAuditEvent`, `TeamRetentionPlan` ;
  - résultats : `TeamBootstrap`, `TeamKeyRotation`, `TeamRetentionApply` ;
  - entrées : `TeamRole` et `TeamRetentionPolicy`.

  `MinosApplication.teamControlPlane()` la rend, vide quand le mode équipe est désactivé.
- **Surfaces** : `LocalMinosTeamApi`, `TeamCommand`, `MinosCli`, `MinosCliRunner` et `MinosApplicationMcpBackend` passent par la façade. Leurs sources de production ne nomment plus aucun type de `com.minos.hosted`.
- **`HostedControlPlaneRenderer`** (application) rend les modèles de la façade. Clés, ordre et valeurs JSON sont inchangés.
- **Cliquets A11** :
  - garde Python : **33 → 20 types** (API 15 → 6, CLI 11 → 8, MCP 2 → 1, NEXUS 5) ;
  - table bytecode : **28 → 13 types** (API 13 → 4, CLI 8 → 3, MCP 2 → 1, NEXUS 5).
- **Gates littéraux** : `check-hosted-control-plane-consistency.py` attend `TeamControlPlane` dans `LocalMinosTeamApi`, et non plus `HostedControlPlaneService` ; la portée JaCoCo `m27-team-hosted-control-plane` couvre `com/minos/application/team/`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : une exigence est ajoutée (le contrôle hébergé est consommé par une façade applicative).
- `controle-tenant-heberge` : une exigence est ajoutée (la façade ne prend aucune décision de sécurité et ne révèle aucun jeton).

## Hors périmètre

- Les autres lots d'AUD-ARC-01 : le lot 4 (Git et résultats de requête : entrées `git.*`, `query.*`, et `ProjectRegistry` et `CodeKnowledgeSnapshotStore` de l'API) et le lot 5 (export NEXUS : les 5 entrées NEXUS, et `ProjectRegistry` et `CodeKnowledgeSnapshotStore` de la CLI) sont traités en parallèle.
- Le déplacement de `com.minos.hosted` hors du moteur, écarté par l'ADR 0068 § 3.
- Le retrait de l'accesseur `MinosApplication.hostedControlPlaneService()`. Plus aucune surface ne l'appelle, mais la racine de composition et ses tests s'en servent.
- Toute modification des invariants, des messages, de l'autorisation, de l'audit ou du stockage du plan de contrôle.
- Toute sortie, tout code de sortie et tout outil MCP : `minos team`, `MinosTeamApi` et les outils `minos_team_*` restent identiques.

## Impact

- **Modules** :
  - `minos-application` : façade, modèles, rendu, tests ;
  - `minos-api`, `minos-cli` et `minos-mcp` : consommation de la façade, et fixtures de test adaptées ;
  - `minos-app` : table bytecode.

  Aucune dépendance Maven ajoutée.
- **Surfaces publiques** : aucune rupture.
  - CLI : `minos team` garde le même texte, le même JSON et les mêmes codes de sortie.
  - API Java : `MinosTeamApi` et ses DTO restent inchangés ; seule l'implémentation `LocalMinosTeamApi` change.
  - MCP : les 5 outils `minos_team_*` sont inchangés.
  - `A2SurfaceCharacterizationTest` garde les 12 goldens identiques à l'octet, dont `team.golden`, `retention.golden`, `api.golden` et `mcp-all-tools.golden`.
  - IntelliJ et NEXUS ne sont pas touchés.
- **ADR** : aucun nouvel ADR. Une note de mise en œuvre est ajoutée à l'ADR 0058 § 3 (lot 3). L'ADR 0068 § 3 (`hosted` dans le moteur) est appliqué tel quel.
- **Gates** :
  - `check-module-boundaries.py` et son auto-test ;
  - `ModuleArchitectureTest` ;
  - `check-hosted-control-plane-consistency.py`, appelé par `check-post-mne.py` ;
  - `check-jacoco.py`, portée `m27-team-hosted-control-plane`.
- **Plateformes** : aucun code propre à un système. Les tests tournent à l'identique sous Windows et sous Linux.
- **Constat** : AUD-ARC-01, lot 3 sur 6.

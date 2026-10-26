# Design

## Context

Constat **AUD-ARC-01**, lot 3 sur 6. L'inventaire des lots est dans le design archivé `openspec/changes/archive/2026-10-24-faire-consommer-l-indexation-aux-surfaces/design.md` (« Lots suivants », ligne du lot 3). Ce lot n'a pas de prérequis.

ADR applicables :

- 0058 § 3 : un usage du moteur par une surface est une dette à résorber ;
- 0057 § 6 : les surfaces consomment les cas d'usage ;
- 0068 § 3 : `com.minos.hosted` reste dans `minos-engine`, ce que confirme la cartographie de SH-08 (mise en œuvre de l'ADR 0068, « § 3, `hosted` ») ;
- 0042 : racine de composition.

Les lots 4 (Git et résultats de requête) et 5 (export NEXUS) sont traités en parallèle, sur d'autres branches. Ce lot ne touche que ses propres lignes des tables A11.

## État vérifié au HEAD

HEAD : `refactor/descendre-le-noyau-transverse` `50134955` (lot 6, PR #566, pas encore fusionnée). La branche sera rebasée sur `develop` après la fusion de #566.

### Surfaces

- **API** : `minos-api/src/main/java/com/minos/api/LocalMinosTeamApi.java`, 147 lignes.
  - Imports `:4-12` : les 9 types de `com.minos.hosted`.
  - Champ `Optional<HostedControlPlaneService>` (`:24`), lu par l'accesseur `application.hostedControlPlaneService()` (`:27`).
  - `setRetention` construit un `HostedRetentionPolicy` (`:84-85`).
  - `role(String)` (`:109-113`) refuse une valeur vide (« role must not be blank ») et lève « unsupported hosted role: … » pour une valeur inconnue.
  - Les DTO sont projetés `:114-143`. `hosted()` (`:106-108`) lève « MINOS team mode is disabled ».
- **CLI** : `minos-cli/src/main/java/com/minos/cli/command/TeamCommand.java`, 372 lignes.
  - Imports `:5-8`.
  - Constructeurs publics à `HostedControlPlaneService` et `Supplier<HostedControlPlaneService>` (`:78-86`).
  - Bornes de `retention-set` lues sur `HostedRetentionPolicy.MIN_*`/`MAX_*` (`:227-230`), et bornes d'`audit` sur `HostedControlPlaneService.MIN_AUDIT_LIMIT`/`MAX_AUDIT_LIMIT` (`:245`).
  - `retentionPolicy(CliOptions)` (`:276-285`) transforme l'`IllegalArgumentException` du record en erreur d'usage. `role(String)` (`:324-330`) lève « unsupported hosted role: … ».
  - Le type de l'invocation est `Invocation.run(HostedControlPlaneService, …)` (`:358`).
- **CLI** : `minos-cli/src/main/java/com/minos/cli/MinosCli.java`.
  - Import `:33`, champ du constructeur `:139`, construction de `TeamCommand` `:161-162`.
  - Méthodes du builder `hostedControlPlaneService` et `hostedControlPlaneServiceSupplier` (`:297`, `:366-377`), privées au package. `MinosCliSurfaceTest.java:75-76` fige leurs noms et leur visibilité, et `MinosCliBuilderTest.java:83` leur refus du null.
- **CLI** : `minos-cli/src/main/java/com/minos/cli/MinosCliRunner.java:127` chaîne `opened.hostedControlPlaneService().orElseThrow(MinosCli.ServiceNotConfigured::new)`.
- **MCP** : `minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java`.
  - Import du rendu `:24`, et cinq outils `teamTenant`… `teamAudit` (`:289-312`).
  - `hosted()` (`:314-317`) rend le type qualifié `com.minos.hosted.HostedControlPlaneService` et lève `TeamModeDisabled`.

### Application

- `MinosApplication.java:313` : `Optional<HostedControlPlaneService> hostedControlPlaneService()`. Le gate `check-hosted-control-plane-consistency.py:97-99` exige ce nom dans `MinosApplication.java`. `MinosApplicationTest.java:79` (bootstrap) et les fixtures des tests de surface l'appellent aussi.
- `minos-application/src/main/java/com/minos/output/HostedControlPlaneRenderer.java`, 167 lignes.
  - Imports `:3-10` (8 types du moteur).
  - Signatures `:22-83` sur les types du moteur.
  - `RendererKeyOrderTest` fige l'ordre des clés.
- Le moteur garde les invariants : bornes de `HostedRetentionPolicy` (`MIN_AUDIT_EVENTS = 100`, `MAX_AUDIT_EVENTS = 100_000`, `MIN_RETENTION_DAYS = 1`, `MAX_RETENTION_DAYS = 3_650`), messages de validation, et `HostedRole` (5 valeurs) et ses permissions.
- `BootstrapResult` et `RotateKeyResult` (`HostedControlPlaneService.java:209-232`) masquent le jeton dans `toString()` (`***`).

### Cliquets A11

- **Garde Python** (`scripts/architecture/check-module-boundaries.py:515-559`) :
  - API : 9 entrées `hosted.*` (`:518-526`), chacune à 1 fichier ;
  - CLI : `hosted.HostedControlPlaneService: 3` (`TeamCommand`, `MinosCli`, et l'accesseur dans `MinosCliRunner`), `hosted.HostedRetentionPolicy: 1` et `hosted.HostedRole: 1` (`:537-539`) ;
  - MCP : `hosted.HostedControlPlaneService: 1` (`:556`).

  Total : 33 types, dont 13 `hosted`.
- **Bytecode** (`minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java:92-125`) :
  - API : 9 `hosted.*` (`:95-103`) ;
  - CLI : `HostedControlPlaneService: 2`, `HostedProjectBinding`, `HostedRetentionPolicy`, `HostedRole` et `HostedTenantState` (`:109-113`). Le bytecode voit `HostedProjectBinding` et `HostedTenantState`, que la CLI utilise par chaînage (`binding.projectId()`, `service.tenant(…).retentionPolicy()`) sans les nommer ;
  - MCP : `HostedControlPlaneService: 1` (`:118`).

  Total : 28 types, dont 15 `hosted`.
- **Auto-tests** : `test_check_module_boundaries.py` n'affirme aucune entrée `hosted` de la liste réelle. Les cas `:990-1054` écrivent `HostedRole` dans des arbres temporaires, avec leur propre liste. `ModuleArchitectureTest.java:285-300` fait de même dans sa liste synthétique.

### Sorties figées

`A2SurfaceCharacterizationTest.teamControlPlaneOutputsAreUnchanged` (`:377-422`) compare `team.golden` : API team, CLI `team tenant` avec et sans application hébergée, MCP team. `retention.golden` (`:454`), `api.golden` et `mcp-all-tools.golden` couvrent le reste. 12 goldens en tout.

### Gates littéraux (recherche de chaque nom dans `scripts/`)

- `scripts/quality/check-hosted-control-plane-consistency.py`, appelé par `scripts/remediation/check-post-mne.py:21` :
  - `:116` exige `HostedControlPlaneService` dans `LocalMinosTeamApi.java` ;
  - `:111-113` exige les noms d'opérations de `TeamCommand` ;
  - `:130-132` exige `hosted().tenant(hostedToken())`, `hosted().audit(hostedToken(), limit)` et « MINOS team mode is disabled » dans le backend MCP ;
  - `:97-99` exige `hostedControlPlaneService` dans `MinosApplication.java`.
- `scripts/quality/check-jacoco.py:212-217` : portée `m27-team-hosted-control-plane`, avec les préfixes `com/minos/hosted/`, `TeamCommand`, `LocalMinosTeamApi` et `HostedControlPlaneRenderer`. Le gate M27 exige `"m27-team-hosted-control-plane"`, `FileHostedControlPlaneStore`, `TeamCommand` et `LocalMinosTeamApi` dans `check-jacoco.py`.
- `scripts/m27/run-final.ps1` et `run-final.sh` n'appellent que le gate M27. `scripts/history/` est figé, et rien n'y cite les noms changés.
- Aucun `scripts/**/check-*.py` ne cite `hostedControlPlaneServiceSupplier`, `ServiceNotConfigured`, `MIN_AUDIT_LIMIT` ni les méthodes `render*`.

## Écarts avec l'action proposée par l'audit

L'inventaire du lot 1 proposait une « façade applicative de l'équipe rendant des résumés ». Le lot la réalise ainsi, avec deux précisions :

- les **entrées** ont aussi un pendant applicatif (`TeamRole`, `TeamRetentionPolicy`), sans quoi la CLI et l'API continueraient de nommer `HostedRole` et `HostedRetentionPolicy` ;
- l'accesseur `hostedControlPlaneService()` reste, faute d'autre consommateur à servir que la composition et ses tests (non-goal du lot 1 : ne retirer aucun accesseur).

## Goals / Non-Goals

**Goals** :

- que `minos-api`, `minos-cli` et `minos-mcp` ne nomment ni n'utilisent plus aucun type de `com.minos.hosted`, ni dans les sources ni en bytecode ;
- que le contrôle hébergé soit consommé par une façade de `minos-application`, testée dans son module ;
- que les sorties restent identiques à l'octet.

**Non-Goals** :

- déplacer `com.minos.hosted` ;
- modifier une règle de sécurité, un message, un format persisté, un DTO de `MinosTeamApi` ou un outil MCP ;
- retirer un accesseur de `MinosApplication`.

## Decisions

### D1 — Une façade dans `com.minos.application.team`, rendue par `MinosApplication`

`TeamControlPlane` (classe finale) enveloppe un `HostedControlPlaneService` et expose ses 17 opérations, avec les mêmes paramètres et les mêmes exceptions. Elle est rendue par `MinosApplication.teamControlPlane()` → `Optional<TeamControlPlane>`, construite une fois à partir de `hostedControlPlaneService`.

Le package `com.minos.application.team` est sous la racine `application` (A14). Il ne dépend que de `com.minos.hosted` et du JDK. `com.minos.application` (l'accesseur) et `com.minos.output` (le rendu) en dépendent, et il ne dépend d'aucun des deux : pas de cycle (A8).

Alternatives écartées :

- **(B)** Construire la façade dans chaque surface à partir de `application.hostedControlPlaneService()`. Le chaînage de l'accesseur compte en A11 : la dette ne descend pas.
- **(C)** Déplacer `hosted` dans l'application. L'ADR 0068 § 3 l'écarte.

### D2 — Modèles de lecture en types JDK, entrées à pendant applicatif

Les résultats sont des records applicatifs :

- `TeamTenant` : identifiant, nom, `keyId`, version, dates, nombres de membres et d'espaces, séquence d'audit, politique ;
- `TeamWorkspace` (statut en chaîne) et `TeamBinding` ;
- `TeamMember` : rôle `TeamRole`, et permissions sous forme de noms triés ;
- `TeamAuditEvent` (issue en chaîne) et `TeamRetentionPlan` ;
- `TeamBootstrap`, `TeamKeyRotation` et `TeamRetentionApply` : version, événements et espaces restants.

Les valeurs en chaîne sont les `name()` que les rendus et les DTO publient aujourd'hui. Les listes sont copiées et immuables.

Côté entrées :

- **`TeamRole`** est une énumération aux mêmes valeurs que `HostedRole`, convertie par nom à l'entrée de la façade. Un test d'exhaustivité échoue si une valeur n'a pas de pendant, dans un sens ou dans l'autre (précédent : D2 du lot 1).
- **`TeamRetentionPolicy`** est un record dont les bornes sont les constantes de `HostedRetentionPolicy` et dont la validation délègue au record du moteur. Les messages et les bornes restent donc ceux du moteur, qui demeure la seule source.
- **`TeamControlPlane.MIN_AUDIT_LIMIT` et `MAX_AUDIT_LIMIT`** reprennent les constantes du service.

Alternative écartée : rendre aux surfaces des `Map` déjà projetées. L'API a besoin de valeurs typées pour ses DTO, et le rendu JSON y perdrait son contrôle d'ordre.

### D3 — Le rendu suit la façade, les sorties ne bougent pas

`HostedControlPlaneRenderer` prend les modèles de D2, avec les mêmes clés, le même ordre et les mêmes valeurs. `RendererKeyOrderTest` et les goldens le prouvent. `LocalMinosTeamApi` projette ses DTO depuis les mêmes modèles.

Les surfaces gardent leurs messages propres : « role must not be blank » pour l'API, l'erreur d'usage de la CLI, `TeamModeDisabled` pour le MCP, `ServiceNotConfigured` pour la CLI.

Les méthodes du builder de `MinosCli` deviennent `teamControlPlane` et `teamControlPlaneSupplier`, toujours privées au package. `MinosCliSurfaceTest` suit le changement de nom.

### D4 — La façade ne décide rien

La façade n'ajoute ni contrôle, ni rattrapage, ni journal :

- chaque exception du service (`SecurityException`, `IllegalArgumentException`, `IOException`, `IllegalStateException`) se propage telle quelle ;
- l'autorisation précède toujours la validation (B03), car elle reste dans le moteur ;
- l'audit des refus est inchangé (B01, B02) ;
- un jeton à clé retirée reste un échec d'authentification (B04).
- une entrée manquante (`null`) est transmise au moteur, qui autorise avant de valider : la façade ne la refuse pas avant l'autorisation.

`TeamBootstrap` et `TeamKeyRotation` masquent leur jeton dans `toString()`, comme les résultats du moteur.

### D5 — Tests dans le module de la façade

SonarCloud juge le nouveau code module par module. `TeamControlPlaneTest` (`minos-application`) construit un vrai `HostedControlPlaneService` sur un magasin en mémoire et des clés fixes, avec les classes publiques du moteur, sans racine de composition. Il couvre :

- chaque opération et sa projection ;
- le refus d'un jeton invalide, propagé tel quel ;
- le refus d'une politique hors bornes, avec le message du moteur ;
- l'exhaustivité de `TeamRole` ;
- le masquage des jetons ;
- le refus d'autorisation d'une entrée nulle présentée par un membre non autorisé.

`HostedControlPlaneRendererTest` couvre le rendu dans le même module. Les tests de surface ne changent pas d'attente. Leurs fixtures enveloppent le service dans la façade.

## Direction des dépendances (ADR 0068)

`minos-api`, `minos-cli` et `minos-mcp` dépendent de `minos-application`, qui dépend de `minos-engine` : aucune arête n'est ajoutée. La façade ne nomme aucun adaptateur (A2) et ne crée pas de racine (A14).

Les surfaces ne gardent que des dépendances vers l'application pour ce domaine. Les tests de surface peuvent nommer le moteur : A11 ne compte que les sources de production.

## Capacités

Aucune capacité n'est ajoutée ni requalifiée. Le mode équipe reste **qualifié avec contraintes** (M27, opt-in, sans service réseau), avec les mêmes opérations et les mêmes refus.

## Windows et Linux

Aucun code propre à un système. Le magasin réel (`FileHostedControlPlaneStore`) n'est pas touché. Les tests de la façade utilisent un magasin en mémoire, et `team.golden` passe par le magasin réel sur les deux systèmes en CI.

## Coût

| Poste | Estimation au HEAD |
|---|---|
| Couverture du nouveau code (SonarCloud, 80 % par module) | `minos-application` : environ 250 lignes (façade, 12 types, rendu modifié), couvertes par `TeamControlPlaneTest` et `RendererKeyOrderTest`. Surfaces : lignes modifiées de `LocalMinosTeamApi`, `TeamCommand`, `MinosCli`, `MinosCliRunner` et `MinosApplicationMcpBackend`, couvertes par `LocalMinosTeamApiTest`, `TeamCommandTest`, `TeamOperationGuardTest`, `TeamCommandsArgumentRulesTest` et `MinosApplicationMcpBackendM27Test` |
| Tests déplacés | aucun ; les fixtures `TeamFixtures` (CLI) et les constructions de `TeamCommand` dans `TeamCommandTest` enveloppent le service |
| Portées de `scripts/quality/check-jacoco.py` | `m27-team-hosted-control-plane` gagne le préfixe `com/minos/application/team/` (seuils inchangés : ligne 0,45, branche 0,25) ; `global` |
| Gates littéraux | `check-hosted-control-plane-consistency.py:116` attend `TeamControlPlane` dans `LocalMinosTeamApi.java`, et non plus `HostedControlPlaneService`, et un `forbid` de `com.minos.hosted` y est ajouté. Les littéraux du backend MCP (`hosted().tenant(hostedToken())`…) et de `MinosApplication` (`hostedControlPlaneService`) restent vrais. `check-module-boundaries.py` (A11 : 13 lignes retirées) et son auto-test (un cas ajouté) ; `ModuleArchitectureTest` (15 lignes retirées) |
| Bases SpotBugs (`quality/spotbugs-baseline`) | `minos-application.xml`, `minos-api.xml`, `minos-cli.xml` et `minos-mcp.xml` sont rejoués par le profil `audit-spotbugs`. Une alerte nouvelle sur un record à liste se corrige dans le code (copie immuable), jamais dans la base |
| `scripts/quality/comment-language.json` | nouveaux commentaires en anglais, sans jalon ; un Javadoc réécrit perd « M27 », et les maximums `milestoneCommentMaximum` concernés (`minos-api`, `minos-application`, `minos-cli`) sont abaissés si le gate le demande |
| Documentation | ADR 0058 (mise en œuvre, lot 3) ; arc42 § 5.3 et `docs/architecture/diagrams/c4-component-application.md` (la façade) ; `docs/developer/team-hosted-mode.md` (les surfaces passent par la façade) ; ligne du lot 3 dans `SUIVI.md`. Aucune page utilisateur : les sorties ne changent pas |

## Risks / Trade-offs

- **Sortie modifiée par inadvertance** : `team.golden`, `retention.golden`, `api.golden`, `mcp-all-tools.golden` et `RendererKeyOrderTest` la refusent.
- **Message de validation dupliqué et divergent** : `TeamRetentionPolicy` délègue au record du moteur, et `TeamRole` est converti par nom. Il n'existe qu'une source.
- **Retrait par chaînage** : un `var` ou un chaînage ne retire rien. La table bytecode voit `HostedTenantState` et `HostedProjectBinding`, que le garde Python ne voit pas. La clôture vérifie les deux tables.
- **Conflits avec les lots 4 et 5** : les mêmes tables A11, `test_check_module_boundaries.py`, `SUIVI.md` et la mise en œuvre de l'ADR 0058 sont modifiés en parallèle. Chaque lot ne touche que ses lignes, et le rebase recalcule les totaux.

## Recouvrements avec les changements actifs

| Changement | Fichiers communs | Effet |
|---|---|---|
| `descendre-le-noyau-transverse` (lot 6) | tables A11, `SUIVI.md`, ADR 0058 | base de cette branche ; rebase sur `develop` après la fusion de #566 |
| lots 4 et 5 (sessions parallèles) | tables A11, auto-test, `SUIVI.md`, ADR 0058 | lignes distinctes ; totaux recalculés au rebase |
| `decouper-les-classes-de-plus-de-800-lignes` | aucun fichier de ce lot | sans effet |

## Migration Plan

Une PR vers `develop`, branche `refactor/faire-consommer-le-controle-heberge`. Retour arrière : revert de la PR, sans effet sur les données (aucun format persisté touché).

## Open Questions

Aucune. Les choix ouverts sont techniques et tranchés par D1 à D5. La conservation de l'accesseur `hostedControlPlaneService()` suit le non-goal du lot 1.

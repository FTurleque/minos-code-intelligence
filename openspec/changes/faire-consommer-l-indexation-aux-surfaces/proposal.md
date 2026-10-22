# Proposal

## Why

**AUD-ARC-01** (audit du 10 octobre 2026) : les surfaces `minos-cli`, `minos-api`, `minos-mcp` et `minos-nexus` nomment des types de `minos-engine` au lieu de consommer des cas d'usage de `minos-application` (ADR 0057 § 6, ADR 0058 § 3). La dette est figée par la règle A11 de `scripts/architecture/check-module-boundaries.py` : la table `ENGINE_TYPES_NAMED_BY_SURFACE` compte **60 entrées** au HEAD `6b60790e` (CLI 33, API 16, NEXUS 6, MCP 5). Son prérequis, AUD-ARC-02 (cas d'usage `ProjectIndexingUseCase`, tâche SH-05), est fusionné : la CLI n'orchestre plus l'indexation, mais elle **projette encore elle-même** les types du moteur que le cas d'usage lui rend, compose seule l'indexation distante (bail, enregistrement, épinglage, exécution distribuée, retour arrière) et lit l'état de reprise dans le magasin d'état. L'API et le MCP lisent ce même état par `ResumableRunSummary.of(application.indexStateStore(), …)`.

Ce changement inventorie les 60 entrées par famille, fixe l'ordre des lots et livre le **premier lot : l'indexation locale et distante**. Il retire **21 entrées** (CLI 18, API 2, MCP 1) : après lui, aucune surface ne nomme plus de type de `com.minos.orchestration` ni de `com.minos.remote`.

## What Changes

- **Indexation distante** : le flux `remote materialize` / `remote index` (refus anticipé de l'ADR 0041, bail inter-JVM, enregistrement, épinglage, exécution distribuée, preuves d'artefacts vérifiées, retour arrière) devient un cas d'usage `RemoteIndexingUseCase` de `minos-application` (package `com.minos.application.remote`) ; `RemoteIndexLease` le suit. `LocalRemoteIndexOperations` (CLI) ne fait plus que convertir les options de la commande et projeter le résultat.
- **Indexation locale** : le cas d'usage d'indexation rend des résumés applicatifs (plan, exécution, reprise, runtimes de providers) en types JDK ; `LocalAutonomousIndexOperations` ne nomme plus aucun type du moteur. Les énumérations du moteur passées en entrée (politique de reprise, politique réseau du worker) ont un pendant applicatif à valeurs identiques, gardé par un test d'exhaustivité.
- **État de reprise** : lu par une requête **sans effet de bord** de `ProjectInspectionService` ; la CLI, l'API et le MCP l'utilisent, `ProjectJson` reçoit le résumé applicatif.
- **Runtimes de providers** : liste et installation passent par `ProviderPlatformService`.
- **API Java** : `ProviderPlatformApi.ProviderDto` trie ses capacités sans `ProviderConformanceKit`, à comportement identique.
- **Cliquet A11** : les 21 lignes sont retirées de `ENGINE_TYPES_NAMED_BY_SURFACE` dans la même PR que le code.
- **Lots suivants** : décrits dans `design.md` (diagnostic de l'hôte, contrôle hébergé, Git et résultats de requête, export NEXUS, noyau transverse), avec leurs prérequis et une décision du propriétaire à prendre ; non implémentés ici.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : cinq exigences ajoutées (familles retirées des surfaces, indexation distante dans l'application avec refus ADR 0041 intact, état de reprise lu sans écriture, choix applicatifs exhaustifs face aux énumérations du moteur, sorties publiques inchangées à l'octet).

## Hors périmètre

- **Les 39 entrées restantes** : diagnostic de l'hôte (`WorkerSandboxProbe`, `PrivateLocalStorage`, après SH-08), contrôle hébergé (13 entrées ; `hosted` reste dans le moteur, ADR 0062), Git et résultats de requête (8 entrées ; D4 retenue : les 6 résultats de requête restent, justifiés), export NEXUS (après SH-04, spécifié en parallèle), noyau transverse (`PublicErrorMessages`, `MinosVersion`, `DegradedEntry`, `UnreadableRegistryException`, `ProjectPathMapping(s)`, `Sha256` de NEXUS). Voir `design.md`.
- **Retirer un accesseur public de `MinosApplication`** (`indexStateStore()`, `providerRuntimeManager()`, `projectRegistry()`…) : rupture de l'API documentée ; les accesseurs restent, les surfaces cessent de les appeler pour ce lot.
- **Rouvrir `remote index`** : il reste fermé par décision (ADR 0041) ; le refus est déplacé, pas assoupli.
- **Scinder `minos-api`** en deux artefacts (ADR 0057 § 6, option non ratifiée) ; changer un format disque, un code de sortie, une sortie CLI, un schéma MCP, le contrat NEXUS ou le protocole du plugin IntelliJ.
- Supprimer `IndexingRuntimePorts`, `ResumableRunSummary` ou un autre type du moteur : ils restent utilisés par le moteur et l'application.

## Impact

- **Modules du reactor** : `minos-application` (nouveau package `com.minos.application.remote`, résumés dans `com.minos.application.indexing`, `ProjectInspectionService`, `ProviderPlatformService`, `output/ProjectJson`) ; `minos-cli` (`operations/*`, `command/IndexCommand`, `command/RemoteIndexCommand`, `command/ProjectCommand`, `MinosCliRunner`) ; `minos-api` (`LocalMinosApi`, `ProviderPlatformApi`) ; `minos-mcp` (`MinosApplicationMcpBackend`). Aucun POM ni arête de module modifiés ; `minos-engine`, `minos-bootstrap` et les adaptateurs inchangés.
- **Surfaces publiques** : aucune signature de `com.minos.api` modifiée ; `MinosApplication` n'est pas modifié ; `ProjectInspectionService`, `ProviderPlatformService` et `ProjectIndexingUseCase` reçoivent des méthodes et des types (additif), aucune n'est retirée ; sorties CLI, réponses MCP et golden inchangés à l'octet.
- **ADR** : aucun nouvel ADR pour ce lot. Notes de mise en œuvre planifiées : ADR 0057 § 6 et ADR 0058 § 3. La décision D4 (résultats de requête du moteur publiés aux surfaces, retenue le 2026-10-22) donne un amendement de l'ADR 0057 § 6, planifié dans le changement du lot 4.
- **Gates à rejouer** : `check-module-boundaries.py` et son auto-test, `check-remote-distributed-consistency.py`, `scripts/remediation/check-mnd.py`, `check-jacoco.py` (portée `m25-remote-distributed-indexing`), `check-private-io.py`, `ApplicationOwnershipGuardTest` (`minos-app`), `check-ci-wiring.py`, `run_gates.py --fast`.
- **Coût** : environ 430 lignes de production déplacées vers `minos-application`, comptées comme code neuf par SonarCloud (seuil 80 % par module) ; 7 classes de test (848 lignes) de `cli/operations` à répartir. Détail chiffré dans `design.md`.
- **Plateformes** : Ubuntu 24.04 et Windows Server 2022 ; le refus de l'ADR 0041 et le bail inter-JVM sont exercés sur les deux.

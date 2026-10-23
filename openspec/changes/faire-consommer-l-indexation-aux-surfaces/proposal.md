# Proposal

## Why

**AUD-ARC-01** (audit du 10 octobre 2026) : les surfaces `minos-cli`, `minos-api`, `minos-mcp` et `minos-nexus` nomment des types de `minos-engine` au lieu de consommer des cas d'usage de `minos-application` (ADR 0057 § 6, ADR 0058 § 3). La dette est figée par la règle A11 de `scripts/architecture/check-module-boundaries.py` : la table `ENGINE_TYPES_NAMED_BY_SURFACE` compte **60 entrées** au HEAD `5ce7b79b` (CLI 33, API 16, NEXUS 6, MCP 5 ; aucun fichier Java ni script n'a changé depuis `6b60790e`, HEAD de la rédaction). Son prérequis, AUD-ARC-02 (cas d'usage `ProjectIndexingUseCase`, tâche SH-05), est fusionné : la CLI n'orchestre plus l'indexation, mais elle **projette encore elle-même** les types du moteur que le cas d'usage lui rend, compose seule l'indexation distante (bail, enregistrement, épinglage, exécution distribuée, retour arrière) et lit l'état de reprise dans le magasin d'état. L'API et le MCP lisent ce même état par `ResumableRunSummary.of(application.indexStateStore(), …)`.

Ce changement inventorie les 60 entrées par famille, fixe l'ordre des lots et livre le **premier lot : l'indexation locale et distante**. Il retire **21 entrées** (CLI 18, API 2, MCP 1) : après lui, aucune surface ne nomme plus de type de `com.minos.orchestration` ni de `com.minos.remote`.

## What Changes

- **Indexation distante** : le flux `remote materialize` / `remote index` (refus anticipé de l'ADR 0041, bail inter-JVM, enregistrement, épinglage, exécution distribuée, preuves d'artefacts vérifiées, retour arrière) devient un cas d'usage `RemoteIndexingUseCase` de `minos-application` (package `com.minos.application.remote`) ; `RemoteIndexLease` le suit. `LocalRemoteIndexOperations` (CLI) ne fait plus que convertir les options de la commande et projeter le résultat. La requête distante reste validée par la commande **avant** l'ouverture paresseuse de l'application, avec les mêmes messages (D9).
- **Indexation locale** : le cas d'usage d'indexation rend des résumés applicatifs (plan, exécution, reprise) en types JDK ; `LocalAutonomousIndexOperations` ne nomme plus aucun type du moteur. Les énumérations du moteur passées en entrée (politique de reprise, politique réseau du worker) ont un pendant applicatif à valeurs identiques, gardé par un test d'exhaustivité.
- **État de reprise** : lu par une requête de `ProjectInspectionService` qui n'appelle aucune méthode d'écriture ni de bail du magasin d'état ; son résultat vit dans `com.minos.application` (un type de `com.minos.application.indexing` créerait un cycle de packages, règle A8). La CLI, l'API et le MCP l'utilisent ; `ProjectJson.indexStatus` prend ce résultat à la place de `ResumableRunSummary` (D8).
- **Runtimes de providers** : liste et installation passent par `ProviderPlatformService`, résumé dans `com.minos.application` ; le MCP, qui utilise déjà ce service, n'appelle ni l'une ni l'autre (ADR 0017, D7 = A).
- **API Java** : `ProviderPlatformApi.ProviderDto` trie ses capacités sans `ProviderConformanceKit`, à comportement identique.
- **Cliquet A11** : les 21 lignes sont retirées de `ENGINE_TYPES_NAMED_BY_SURFACE` dans la même PR que le code.
- **Lots suivants** : décrits dans `design.md` (diagnostic de l'hôte, contrôle hébergé, Git et résultats de requête, export NEXUS, noyau transverse), avec leurs prérequis ; non implémentés ici.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : cinq exigences ajoutées (familles retirées des surfaces, indexation distante dans l'application avec refus ADR 0041 intact, état de reprise lu sans écriture, choix applicatifs exhaustifs face aux énumérations du moteur, sorties publiques inchangées à l'octet).

## Hors périmètre

- **Les 39 entrées restantes** : diagnostic de l'hôte (`WorkerSandboxProbe`, `PrivateLocalStorage` de `DoctorCommand`, après SH-08), contrôle hébergé (13 entrées ; `hosted` reste dans le moteur, ADR 0062), Git et résultats de requête (8 entrées ; D4 retenue : les 6 résultats de requête restent, justifiés), export NEXUS (6 entrées, dont `Sha256` ; après SH-04, spécifié en parallèle), noyau transverse (10 entrées : `PublicErrorMessages`, `MinosVersion`, `DegradedEntry`, `UnreadableRegistryException` de la CLI et du MCP, `ProjectPathMapping(s)`). Voir `design.md`.
- **Retirer un accesseur public de `MinosApplication`** (`indexStateStore()`, `providerRuntimeManager()`, `projectRegistry()`…) : rupture de l'API documentée ; les accesseurs restent, les surfaces cessent d'appeler les quatre premiers (`indexStateStore`, `providerRuntimeManager`, `indexerDescriptors`, `compositionRoot`) pour ce lot. Aucun accesseur n'est **ajouté** à `MinosApplication` pour le nouveau cas d'usage (il créerait le cycle `application` ↔ `application.remote`).
- **Rouvrir `remote index`** : il reste fermé par décision (ADR 0041) ; le refus est déplacé, pas assoupli.
- **Scinder `minos-api`** en deux artefacts (ADR 0057 § 6, option non ratifiée) ; changer un format disque, un code de sortie, une sortie CLI, un schéma MCP, le contrat NEXUS ou le protocole du plugin IntelliJ.
- Supprimer `IndexingRuntimePorts`, `ResumableRunSummary` ou un autre type du moteur : ils restent utilisés par le moteur et l'application. Rendre `FileIndexStateStore` (adaptateur) entièrement passif à l'ouverture : son constructeur crée ses dossiers et migre les runs anciens, ce qui reste le comportement de `MinosApplication.open`.

## Impact

- **Modules du reactor** : `minos-application` (nouveau package `com.minos.application.remote` ; choix et résumés d'indexation dans `com.minos.application.indexing` ; résultat de reprise et résumé de runtime dans `com.minos.application` ; `ProjectInspectionService`, `ProviderPlatformService`, `output/ProjectJson`) ; `minos-cli` (`operations/*`, `command/IndexCommand`, `command/RemoteIndexCommand`, `command/ProjectCommand`, `MinosCliRunner`) ; `minos-api` (`LocalMinosApi`, `ProviderPlatformApi`) ; `minos-mcp` (`MinosApplicationMcpBackend`). Aucun POM ni arête de module modifiés ; `minos-engine`, `minos-bootstrap` et les adaptateurs inchangés.
- **Surfaces publiques** : aucune signature de `com.minos.api` modifiée ; `MinosApplication` n'est pas modifié ; `ProjectInspectionService`, `ProviderPlatformService` et `ProjectIndexingUseCase` reçoivent des méthodes et des types (additif), aucune n'est retirée ; `ProjectJson` (`com.minos.output`, interne à l'application) change de signature. Sorties CLI, réponses MCP, valeurs de l'API Java et golden de caractérisation (`minos-app/src/test/resources/characterization/`) inchangés à l'octet. NEXUS et le plugin IntelliJ ne sont pas touchés.
- **ADR** : aucun nouvel ADR pour ce lot. Notes de mise en œuvre planifiées : ADR 0057 § 6 et ADR 0058 § 3. La décision D4 (résultats de requête du moteur publiés aux surfaces, retenue le 2026-10-22) donne un amendement de l'ADR 0057 § 6, planifié dans le changement du lot 4.
- **Gates à rejouer** : `check-module-boundaries.py` (A8, A11, A14, A15) et son auto-test, `check-remote-distributed-consistency.py`, `scripts/remediation/check-mnd.py`, `check-jacoco.py` (portées `m25-remote-distributed-indexing`, `public-api`, `mcp-mapping`, `global`), `check-private-io.py`, `ApplicationOwnershipGuardTest` et `A2SurfaceCharacterizationTest` (`minos-app`), `check-ci-wiring.py`, `run_gates.py --fast` ; base SpotBugs `quality/spotbugs-baseline/minos-api.xml` (entrée `ProviderDto.capabilities`).
- **Coût** : environ 430 lignes de production déplacées vers `minos-application`, comptées comme code neuf par SonarCloud (seuil 80 % par module) ; 7 classes de test (848 lignes) de `cli/operations` à répartir : 4 vers l'application, 2 restent dans la CLI, 1 est scindée. Détail chiffré dans `design.md`.
- **Plateformes** : Ubuntu 24.04 et Windows Server 2022 ; le refus de l'ADR 0041 et le bail inter-JVM sont exercés sur les deux.

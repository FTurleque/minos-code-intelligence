# Proposal

## Why

Constat **AUD-ARC-14** de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouvert le 2026-11-25 au commit `75e10c68`). Le sprint 10 a retiré du public les ports de `MinosApplication` (ARCH-0087 § 2.2) : ils sont privés au package `com.minos.application`. Les cas d'usage d'indexation, de dépôt distant et d'export reçoivent leurs `Dependencies`. Trois classes du package puisent encore leurs ports dans l'application entière, comme un localisateur de services :

- `LocalProjectOperations` lit six accesseurs privés au package : `projectRegistry()`, `snapshotStore()`, `indexStateStore()`, `scipArtifactImporter()`, `retentionService()` et `indexImportHistoryStore()`.
- `ProviderPlatformService.defaults` lit `providerCatalog()` et `providerRuntimeManager()`.
- `LocalProjectGitQuery` lit `gitIntelligence()`.

Le garde ne voit rien : A11 n'inspecte que les méthodes publiques. Conséquence : un test de `LocalProjectOperations` doit ouvrir une application complète, `Builder` et doubles compris, pour exercer un import. La quatrième classe citée par l'audit, `LocalProjectSymbolQuery`, n'appelle que le service public `projectQueryService()`, et elle a déjà un constructeur public à deux ports (design, écart E1).

## What Changes

- `LocalProjectOperations` reçoit ses sept collaborateurs par un enregistrement `Dependencies` privé au package. `ProviderPlatformService` et `LocalProjectGitQuery` gardent leurs constructeurs à ports. Le seul lieu qui lit les ports de `MinosApplication` pour les assembler est `MinosApplication`, comme pour `ProjectIndexingUseCase.Dependencies` (design, D1).
- Les points d'entrée publics qui prennent l'application restent **inchangés** : `new LocalProjectOperations(Path)`, `new LocalProjectOperations(MinosApplication)`, `ProviderPlatformService.defaults(MinosApplication)`, `new LocalProjectGitQuery(MinosApplication)` et `new LocalProjectSymbolQuery(MinosApplication)`. `docs/user/java-api.md` les donne comme remplaçants des accesseurs retirés (1.3.0 et prochaine version). Ils délèguent désormais à la façade, sans lire de port. Aucune rupture (design, D2).
- Un test ArchUnit de `ModuleArchitectureTest` refuse qu'une classe de production autre que `MinosApplication` appelle une méthode non publique de `MinosApplication` qui rend un type de `minos-engine` ou `MinosApplicationComposer`. Il ne lit aucune table : un cliquet à zéro, sans liste à tenir (design, D3).
- Les accesseurs privés au package qui n'ont plus d'appelant sont supprimés : `gitIntelligence()`, `providerCatalog()`, `scipArtifactImporter()`, `indexImportHistoryStore()`, ainsi que `hostedControlPlaneService()`, déjà sans appelant au HEAD (design, D4).

Aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change. Aucune signature publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées. Les opérations locales reçoivent leurs collaborateurs, et seule la façade lit ses ports. Les points d'entrée publics des opérations locales sont conservés. Les ports sans lecteur sont retirés de la façade.

## Hors périmètre

- Les paramètres typés moteur des méthodes publiques de `MinosApplication` (`projectIndexing(UnaryOperator<IndexerExecutor>)`), l'extension d'A11 aux paramètres et le contenu du `test-jar` : constats AUD-ARC-13 et AUD-SEC-12, changement `tenir-le-contrat-de-la-facade-par-le-garde`.
- La prose de `docs/user/java-api.md`, d'ARCH-0042 et du Javadoc de classe de `MinosApplication` : constats AUD-ARC-15 et AUD-QUA-08, changement `aligner-la-prose-de-la-facade-apres-le-sprint-10`. Ce changement ne modifie pas `java-api.md` (D2).
- Les libellés du dossier `frontieres-de-modules` hors de la vue de la façade : constat AUD-ARC-16, changement `actualiser-le-dossier-des-frontieres-de-modules`.
- Les 13 accesseurs privés au package encore lus par `MinosApplicationTestAccess`, et les 232 appels de test qui passent par lui : régime d'ARCH-0087 § 2.3, inchangé.
- Le nombre de constructeurs publics de `LocalProjectOperations` et `LocalProjectSymbolQuery` (deux chacun) au regard de la convention « constructeur unique » (`.claude/rules/java-production.md`) : signatures publiques documentées, conservées par D2.
- Les services publics `ProjectInspectionService` et `ProjectQueryService` que la façade rend : déjà injectés.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `LocalProjectOperations` lit six accesseurs privés au package et le service public `projectInspectionService()` | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/LocalProjectOperations.java:50-61` |
| `ProviderPlatformService.defaults` lit `providerCatalog()` et `providerRuntimeManager()` ; un constructeur public à ports existe | [Existant vérifié] | `ProviderPlatformService.java:29-38` |
| `LocalProjectGitQuery(MinosApplication)` lit `gitIntelligence()` ; un constructeur à port privé au package existe | [Existant vérifié] | `LocalProjectGitQuery.java:16-22` |
| `LocalProjectSymbolQuery(MinosApplication)` n'appelle que `projectQueryService()`, public | [Existant vérifié] | `LocalProjectSymbolQuery.java:25-32` ; `MinosApplication.java:317` |
| 18 méthodes d'instance de `MinosApplication` sont privées au package (ports et racine de composition) | [Existant vérifié] | `MinosApplication.java:272-293`, `:312-314`, `:421` |
| Ces points d'entrée sont documentés comme remplaçants publics | [Existant vérifié] | `docs/user/java-api.md:357-361`, `:423-426` ; `docs/user/release-notes/unreleased.md:34` |
| Aucun gate littéral de `scripts/` ne cite les quatre classes ; aucun scope JaCoCo ne les couvre | [Existant vérifié] | recherche dans `scripts/**/*.py` et `*.json` ; `scripts/quality/check-jacoco.py` (aucun préfixe correspondant) |
| Dépendances par enregistrement privé au package, assemblées par la façade | [Proposition] | design, D1 |
| Points d'entrée publics conservés | [Proposition] | design, D2 |
| Cliquet ArchUnit sans table | [Proposition] | design, D3 |
| Suppression de cinq accesseurs sans appelant | [Proposition] | design, D4 |

## Impact

- **Modules du reactor** : `minos-application` (quatre classes du package de la façade, `MinosApplication`, leurs tests), `minos-app` (`ModuleArchitectureTest`). Aucun appelant de `minos-api`, `minos-cli` ou `minos-mcp` ne change (D2).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune. Les signatures publiques des quatre classes restent identiques.
- **ADR** : aucun créé. ARCH-0087 : une ligne « Mise en œuvre » et une ligne « Compliance and Enforcement » pour le test ArchUnit, à la clôture. Que ce complément suffise, ou qu'il faille un ADR consolidé, reste une question pour le propriétaire (design, D5).
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py` (A11, A17 qui lit `ModuleArchitectureTest`) et ses auto-tests, `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`. Aucun scope JaCoCo.
- **SonarCloud** : le nouveau code de `minos-application` (enregistrement `Dependencies`, méthodes d'assemblage de la façade) doit être couvert à 80 % par des tests de `minos-application`. Les tests de `minos-app` et de `minos-bootstrap` qui appellent `defaults` ne comptent pas pour ce module.
- **Plateformes** : Windows et Linux, sans branche de plateforme.
- **Documentation durable** : `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (vue de la façade, `:98`, et traçabilité des trois exigences), `docs/architecture/diagrams/capacites/analyse-git.md` si la vue de `LocalProjectGitQuery` change.

## Questions ouvertes

D5 (forme du complément à ARCH-0087) attend le propriétaire. D1 à D4 ont une recommandation.

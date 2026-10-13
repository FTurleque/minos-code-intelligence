# Proposal

## Why

`minos-cli` (38 classes, 6 024 lignes) tient dans un seul package plat, `com.minos.cli` : points d'entrée, commandes, opérations d'indexation locales et distantes, analyse d'options et rendu JSON y sont mêlés. Le propriétaire a demandé de ranger ce module, après `minos-provider-scip` (PR #411) et `minos-storage-postgresql` (PR #413), en acceptant le coût mesuré : des classes aujourd'hui package-private deviennent publiques.

## What Changes

- Trois sous-packages : `command` (les 21 commandes et `ArchitectureOutputFormat`), `operations` (opérations d'indexation autonomes et distantes, bail distant, source d'état de reprise), `support` (analyse d'options, rendu JSON, exécution commune des commandes, ouverture différée de l'application, codes de sortie).
- La racine `com.minos.cli` garde les points d'entrée et le SPI nommés hors du module : `MinosLauncher`, `MinosCli`, `MinosCliRunner`, `DockerRuntimeBootstrap`, `McpLaunchRoute`, `McpLaunchRoutes`. **Leurs noms qualifiés ne changent pas.**
- Le cycle `command → operations → support → command` mesuré sur ce découpage est levé : les trois codes de sortie (`SUCCESS`, `EXECUTION_ERROR`, `USAGE_ERROR`) sont définis dans `support.CliExitCodes` ; `FindSymbolCommand` garde ses trois constantes, qui y renvoient.
- **7 classes deviennent publiques**, avec les membres que d'autres sous-packages appellent (158 usages de membres statiques package-private mesurés).
- Les tests suivent leurs classes ; les tests transverses restent à la racine.
- Aucun comportement, aucune option, aucune sortie, aucun code de sortie ne change.

## Capabilities

### Modified Capabilities
- `frontieres-de-modules` : une exigence ajoutée sur l'organisation interne de `minos-cli`.

## Hors périmètre

- Changer une commande, une option, un message ou un code de sortie.
- Déplacer ou renommer un point d'entrée (`Main-Class` du jar ombré, lanceurs Docker, scripts de release) ou le SPI `McpLaunchRoute`.
- Introduire JPMS (`module-info`) pour rétablir une encapsulation entre sous-packages.
- Ranger `minos-runtime-local` (changement séparé) ; `minos-api` n'est pas rangé.
- Réduire la taille ou la complexité des commandes.

## Impact

- Modules : `minos-cli` ; `minos-app` pour deux imports de `FindSymbolCommand` (`DockerMcpTransport`, `McpBackendRouter`) et des tests.
- Surfaces publiques : CLI inchangée (mêmes commandes, sorties, codes) ; API Java, MCP, IntelliJ, NEXUS : aucune. Les classes de commande publiques changent de package ; elles ne font pas partie de l'API Java documentée (`docs/user/java-api.md` ne garantit que `MinosLauncher`, `DockerRuntimeBootstrap` et `McpLaunchRoute`).
- ADR : aucun créé ; ADR 0044 respecté (les nouveaux packages appartiennent à ce seul module).
- Gates à rejouer : `check-module-boundaries.py` (A3, A8), `check-jacoco.py` (scopes qui citent `RemoteIndexCommand`, `RuntimeCommand`, `TeamCommand`, `LocalRemoteIndexOperations`), `check-remote-distributed-consistency.py`, `check-runtime-dynamic-consistency.py`, `check-hosted-control-plane-consistency.py`, `check-polyglot-provider-consistency.py`, `check-mnd.py`, `check-post-mne.py`, `product-facts.py --check`, baseline SpotBugs du module.
- Couverture : SonarCloud compte un fichier déplacé comme code neuf (80 %) ; une partie des tests des commandes vit dans `minos-app`, des tests seront à ajouter dans `minos-cli`.
- Plateformes : Ubuntu et Windows, sans différence.

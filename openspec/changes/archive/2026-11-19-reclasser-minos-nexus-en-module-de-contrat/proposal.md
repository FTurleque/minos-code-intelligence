# Proposal

## Why

Constat **AUD-ARC-08** de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, sprint 8) : `minos-nexus` est classé « surface » par le garde de frontières, alors qu'il sert de bibliothèque de traduction à une autre surface. `minos-cli` en dépend en portée compile ; c'est la seule arête surface → surface du reactor. Le diagramme C4 de l'arc42 range les cinq surfaces dans un même cadre sans arête entre elles, et ArchUnit n'impose « aucune autre surface » qu'à `minos-explorer`. Le module porte en outre une classe publique sans appelant de production, `NexusSemanticSignalService`, tenue par le cliquet A15.

Le constat bloque AUD-ARC-07 (prose d'architecture) : la documentation ne peut être alignée qu'une fois la famille du module tranchée.

## What Changes

- Le garde `scripts/architecture/check-module-boundaries.py` reçoit une famille de modules **de contrat** (`CONTRACT_MODULES`), qui contient `minos-nexus` ; `SURFACE_MODULES` ne le contient plus.
- Un module de contrat ne dépend que de `minos-domain` et de `minos-application` en production, et de `minos-bootstrap` en portée test seulement ; il ne dépend d'aucune surface, d'aucun adaptateur, ni du moteur.
- Règle nouvelle : aucune surface ne dépend d'une autre surface, quelle que soit la portée. Une surface peut dépendre d'un module de contrat.
- La règle A11 (types du moteur) continue de couvrir `minos-nexus` : sa liste reste vide et gardée.
- `ModuleArchitectureTest` applique les mêmes familles sur le bytecode.
- `NexusSemanticSignalService` reste dans le module, déclarée au cliquet A15 ; la documentation la présente comme **non qualifiée par un client** (décision D2).
- Les diagrammes écrits à la main qui rangent `minos-nexus` parmi les surfaces sont corrigés (arc42 section 5, `c4-container.md`, vue de composants). Aucun fichier Java de production ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées (famille de contrat, absence d'arête entre surfaces, statut déclaré du signal sémantique NEXUS) ; deux exigences modifiées, qui énumèrent `minos-nexus` parmi les surfaces : « Les surfaces n'assemblent pas le cycle d'indexation » et « Les types du moteur nommés par une surface le sont par liste fermée ». Leur portée ne change pas : `minos-nexus` y reste nommé, comme module de contrat. L'exigence sur la vue bytecode (« par surface ») n'est pas modifiée ici (design D3).

## Hors périmètre

- L'absorption de `minos-nexus` dans un autre module (option écartée par D1, à reprendre seulement sur décision du propriétaire).
- La suppression ou le branchement de `NexusSemanticSignalService` sur une commande (D2).
- La source unique des tables du garde (AUD-ARC-04, changement `donner-une-source-unique-aux-tables-du-garde-de-frontieres`).
- Les listes « Interfaces » et « Sources » de l'arc42 et la portée des dépendances dans le document généré (AUD-ARC-07, changement `aligner-la-prose-d-architecture-sur-le-code`).
- La manière dont `NexusExportService` obtient `KnowledgeExportUseCase` (AUD-ARC-02, sprint 7).
- Le contrat JSON NEXUS v1 et la commande `minos nexus-export` : inchangés à l'octet.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `minos-cli` dépend de `minos-nexus` en portée compile | [Existant vérifié] | `minos-cli/pom.xml:32-36` |
| Trois imports de `com.minos.nexus` dans `minos-cli`, aucun ailleurs en production (un test de `minos-app` l'importe aussi) | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/MinosCliRunner.java:28`, `minos-cli/src/main/java/com/minos/cli/command/NexusExportCommand.java:7-8` |
| `minos-nexus` est listé parmi les surfaces | [Existant vérifié] | `scripts/architecture/check-module-boundaries.py:51`, `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java:83-84` |
| `NexusSemanticSignalService` est sans appelant de production | [Existant vérifié] | `scripts/architecture/check-module-boundaries.py:1222-1223` |
| Le module compte 4 classes et 347 lignes | [Existant vérifié] | `wc -l minos-nexus/src/main/java/com/minos/nexus/*.java` : 152 + 93 + 50 + 52 |
| Requalifier plutôt qu'absorber | [Décision] | design D1, propriétaire, 2026-11-17 |
| Garder `NexusSemanticSignalService`, déclarée non qualifiée | [Décision] | design D2, propriétaire, 2026-11-17 |

## Impact

- **Modules du reactor** : aucun code de production modifié. `minos-app` (test `ModuleArchitectureTest`). Aucun module créé ni supprimé.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune modification ; le package `com.minos.nexus` et l'artefact `minos-nexus` gardent leurs noms.
- **ADR** : note datée de « Mise en œuvre » à ajouter à ARCH-0068 (famille de contrat) ; aucun ADR nouveau avec l'option recommandée. L'option d'absorption exigerait un ADR (voir design D1).
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, `scripts/architecture/test_check_module_boundaries.py`, `scripts/docs/check-current-docs.py`, `scripts/docs/check-capability-dossiers.py`, `archgate check`. Aucun scope JaCoCo touché.
- **Plateformes** : Windows et Linux — aucun comportement propre à une plateforme.
- **Documentation durable et diagrammes** : `docs/architecture/arc42/05-vue-blocs.md`, `docs/architecture/diagrams/c4-container.md`, `docs/architecture/diagrams/application/component-modules-et-dependances.md`, `docs/architecture/SYNTHESE.md`, `docs/developer/public-surfaces.md`, `docs/developer/quality-gates.md`, dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md`.

## Questions ouvertes

Aucune : le propriétaire a retenu le 2026-11-17 les options recommandées de D1 (requalifier) et de D2 (garder `NexusSemanticSignalService`, déclarée non qualifiée).

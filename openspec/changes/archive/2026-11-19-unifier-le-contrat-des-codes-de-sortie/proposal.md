# Proposal

## Why

Constat **AUD-ARC-06** de l'audit `docs/project/audits/2026-11-15/` (sprint 7, sévérité faible) : le contrat stable des codes de sortie de la CLI (ARCH-0016) est défini par `CliExitCodes`, mais 28 fichiers de production (25 de `minos-cli`, 3 de `minos-app`) le lisent par les alias d'une commande particulière, `FindSymbolCommand.SUCCESS`, `EXECUTION_ERROR`, `USAGE_ERROR`, `PARTIAL_RESULT`. `ExplorerSession` redéfinit 0, 1 et 2. Renommer ou retirer `find-symbol` casserait l'assemblage, et trois définitions parallèles peuvent diverger sans qu'un test le voie.

## What Changes

- Toutes les références de production et de test aux alias `FindSymbolCommand.<code>` lisent `CliExitCodes`.
- Les quatre alias sont supprimés de `FindSymbolCommand`.
- Un test de parité, dans `minos-app` (seul module qui voit `minos-cli` et `minos-explorer`), exige que chaque constante de code de sortie d'`ExplorerSession` ait le même nom et la même valeur dans `CliExitCodes`.
- La Javadoc de `CliExitCodes` décrit le contrat sans renvoyer à une commande.

Aucun code de sortie ne change de valeur. Aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `surfaces-publiques` : exigences ajoutées sur la définition unique des codes de sortie et la parité de la surface d'exploration.

## Hors périmètre

- La constante `PARTIAL_RESULT_EXIT_CODE = 3` du plugin `minos-intellij` : client externe du protocole, sans dépendance `com.minos:*` ; aucune parité compilée n'est possible (design, écarts).
- Tout changement de valeur ou de sens d'un code de sortie.
- Le classement erreur d'usage / échec interne (sprint 2, changement `distinguer-l-erreur-d-usage-de-l-echec-interne`).
- La politique de lancement de l'explorateur (AUD-ARC-05) : changement `centraliser-la-politique-de-lancement-de-l-explorateur`.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `CliExitCodes` définit 0, 1, 2, 3 | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/support/CliExitCodes.java:9-13` |
| `FindSymbolCommand` en garde quatre alias publics | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/command/FindSymbolCommand.java:20-28` |
| 28 fichiers de production et 31 fichiers de test lisent les alias ; 67 occurrences en production | [Existant vérifié] | design, « État vérifié au HEAD » |
| `ExplorerSession` redéfinit `SUCCESS`, `EXECUTION_ERROR`, `USAGE_ERROR` | [Existant vérifié] | `minos-explorer/src/main/java/com/minos/explorer/ExplorerSession.java:18-20` |
| `minos-explorer` ne peut pas dépendre de `minos-cli` | [Existant vérifié] | `scripts/architecture/check-module-boundaries.py:83` |
| Les alias sont supprimés, sans période de dépréciation | [Proposition] | design, D1 |
| La parité de l'explorateur est tenue par un test de `minos-app` | [Proposition] | design, D2 |

## Impact

- **Modules du reactor** : `minos-cli` (25 fichiers de production, 30 de test), `minos-app` (3 fichiers de production, 1 de test, test de parité).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune sortie ni valeur ne change. Les constantes publiques `FindSymbolCommand.SUCCESS` et suivantes disparaissent d'une classe interne de la CLI, qui n'est pas une API Java publiée.
- **ADR** : aucun ; ARCH-0016 reste la décision.
- **Gates à rejouer** : `check-module-boundaries.py` (A8 : le package `cli.support` reste une feuille), `check-comment-language.py` (Javadoc française de `CliExitCodes` reprise en anglais : le maximum de `minos-cli` baisse), `python .claude/scripts/run_gates.py --fast` ; aucun gate de `scripts/` ne cite `CliExitCodes` ni `FindSymbolCommand.<code>` au HEAD.
- **Plateformes** : Windows et Linux — aucun comportement propre à une plateforme.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/surfaces-publiques.md` ; `docs/architecture/arc42/06-vue-execution.md:119` et `docs/architecture/diagrams/seq-erreur-provider.md:35`, qui citent `FindSymbolCommand.EXECUTION_ERROR` ; `docs/user/cli.md` seulement si elle cite la classe.

## Questions ouvertes

Aucune.

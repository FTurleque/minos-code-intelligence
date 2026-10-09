# ADR 0058 — Déclarer chaque module utilisé

Date : 2026-10-09. Statut : **Accepted**.

Constat : MINOS-AUD-H08 (`docs/quality/code-audit-constats.md`). Complète : [0022](0022-maven-reactor-and-module-boundaries.md), [0042](0042-racine-de-composition.md)

## Contexte

La table `ALLOWED_DEPENDENCIES` (`scripts/architecture/check-module-boundaries.py`, ADR 0022 et 0042) borne les dépendances **déclarées** dans les POM. Maven laisse pourtant une classe utiliser un module atteint par une dépendance transitive. La mesure ArchUnit stricte de l'audit du 8 octobre 2026 en a trouvé quatre :

- `minos-storage-local` → `minos-domain` (259 dépendances) ;
- `minos-nexus` → `minos-engine` (21 : `ProjectRegistry`, `RegisteredProject`, `CodeKnowledgeSnapshotStore`, `CodeKnowledgeSnapshot`, `Sha`) ;
- `minos-mcp` → `minos-domain` (14) et → `minos-engine` (13 : `HostedControlPlaneService`, `UnreadableRegistryException`, `PublicErrorMessages`, `ResumableRunSummary`) ;
- `minos-runtime-local` → `minos-domain` (5 : `Preconditions.requireText`).

Un usage transitif échappe aux deux contrôles de frontière : un module peut dépendre d'un autre sans que la table, ni une relecture de POM, ne le montre.

## Décision

1. **Tout module utilisé est déclaré.** Les quatre arêtes ci-dessus sont ajoutées aux POM et aux deux tables, celle de `check-module-boundaries.py` et celle de `ModuleArchitectureTest`, qui restent identiques.
2. **La règle stricte devient obligatoire.** `ModuleArchitectureTest.everyModuleOnlyUsesItsAllowedModules` s'exécute dans `clean verify` : une classe qui utilise un module que son module ne déclare pas fait échouer le build.
3. **Pour les surfaces, c'est un constat, pas un nouveau droit.** `minos-nexus` et `minos-mcp` reçoivent `minos-engine` comme `minos-cli` et `minos-api` l'avaient déjà. Le point 6 de l'ADR 0057 reste l'objectif : les surfaces consomment des cas d'usage de `minos-application`. Les usages listés ci-dessus sont la dette à résorber ; en ajouter un nouveau demande la même justification qu'une nouvelle arête.

## Conséquences

- Une dépendance indirecte ne peut plus servir en silence : la règle nomme la classe et la dépendance.
- Les arêtes déclarées rendent la dette des surfaces visible dans les POM, là où une relecture la verra.
- Retirer une de ces arêtes, une fois les usages migrés vers des cas d'usage, se fait dans la même table et le même test.

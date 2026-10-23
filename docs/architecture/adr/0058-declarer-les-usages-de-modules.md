---
id: ADR-0058
title: "Déclarer chaque module utilisé"
domain: architecture
rules: false
---

# ADR 0058 — Déclarer chaque module utilisé

Date : 2026-10-09. Statut : **Accepted**.

Constat : MINOS-AUD-H08 (`docs/developer/quality/code-audit-constats.md`). Complète : [0022](0022-maven-reactor-and-module-boundaries.md), [0042](0042-racine-de-composition.md)

## Context

La table `ALLOWED_DEPENDENCIES` (`scripts/architecture/check-module-boundaries.py`, ADR 0022 et 0042) borne les dépendances **déclarées** dans les POM. Maven laisse pourtant une classe utiliser un module atteint par une dépendance transitive. La mesure ArchUnit stricte de l'audit du 8 octobre 2026 en a trouvé quatre :

- `minos-storage-local` → `minos-domain` (259 dépendances) ;
- `minos-nexus` → `minos-engine` (21 : `ProjectRegistry`, `RegisteredProject`, `CodeKnowledgeSnapshotStore`, `CodeKnowledgeSnapshot`, `Sha`) ;
- `minos-mcp` → `minos-domain` (14) et → `minos-engine` (13 : `HostedControlPlaneService`, `UnreadableRegistryException`, `PublicErrorMessages`, `ResumableRunSummary`) ;
- `minos-runtime-local` → `minos-domain` (5 : `Preconditions.requireText`).

Un usage transitif échappe aux deux contrôles de frontière : un module peut dépendre d'un autre sans que la table, ni une relecture de POM, ne le montre.

## Decision

1. **Tout module utilisé est déclaré.** Les quatre arêtes ci-dessus sont ajoutées aux POM et aux deux tables, celle de `check-module-boundaries.py` et celle de `ModuleArchitectureTest`, qui restent identiques.
2. **La règle stricte devient obligatoire.** `ModuleArchitectureTest.everyModuleOnlyUsesItsAllowedModules` s'exécute dans `clean verify` : une classe qui utilise un module que son module ne déclare pas fait échouer le build.
3. **Pour les surfaces, c'est un constat, pas un nouveau droit.** `minos-nexus` et `minos-mcp` reçoivent `minos-engine` comme `minos-cli` et `minos-api` l'avaient déjà. Le point 6 de l'ADR 0057 reste l'objectif : les surfaces consomment des cas d'usage de `minos-application`. Les usages listés ci-dessus sont la dette à résorber ; en ajouter un nouveau demande la même justification qu'une nouvelle arête.

## Do's and Don'ts

### Do

- Déclarer dans le POM et dans les deux tables tout module qu'une classe utilise.
- Garder identiques la table de `check-module-boundaries.py` et celle de `ModuleArchitectureTest`.
- Exécuter `ModuleArchitectureTest.everyModuleOnlyUsesItsAllowedModules` dans `clean verify`.
- Justifier tout nouvel usage du moteur par une surface comme une nouvelle arête.

### Don't

- Ne pas utiliser un module atteint seulement par une dépendance transitive.
- Ne pas traiter l'arête surface → `minos-engine` comme un nouveau droit.

## Consequences

- Une dépendance indirecte ne peut plus servir en silence : la règle nomme la classe et la dépendance.
- Les arêtes déclarées rendent la dette des surfaces visible dans les POM, là où une relecture la verra.
- Retirer une de ces arêtes, une fois les usages migrés vers des cas d'usage, se fait dans la même table et le même test.

## Compliance and Enforcement

- Test `ModuleArchitectureTest.everyModuleOnlyUsesItsAllowedModules` (`minos-app`, build Maven des jobs `Verify`) — une classe qui utilise un module non déclaré fait échouer le build.
- Gate `scripts/architecture/check-module-boundaries.py` (job `invariants`) — table `ALLOWED_DEPENDENCIES` (règle A2) et règle A11 (types de `minos-engine` nommés par chaque surface, table `ENGINE_TYPES_NAMED_BY_SURFACE`) ; auto-test `test_check_module_boundaries.py`.
- Revue : l'identité des deux tables n'est vérifiée par aucun script ; un relecteur la contrôle à chaque modification de l'une d'elles.

## Mise en œuvre

**Point 3 gardé (2026-10-22, audit AUD-ARC-01, changement OpenSpec `figer-la-dette-de-frontieres-par-des-cliquets`)** : la règle A11 de `scripts/architecture/check-module-boundaries.py` tient, par surface, la liste fermée des types de `minos-engine` que ses sources de production nomment (import ou nom qualifié ; table `ENGINE_TYPES_NAMED_BY_SURFACE`). Un type hors liste, une entrée que la surface ne nomme plus ou qui ne désigne aucun type font échouer le garde : la « justification » d'un nouvel usage est une ligne ajoutée à la table et relue. Les usages entre adaptateurs sont tenus de la même façon (règle A13, [ADR 0062](0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md)).

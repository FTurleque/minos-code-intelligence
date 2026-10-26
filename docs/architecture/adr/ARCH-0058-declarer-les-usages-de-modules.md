---
id: ARCH-0058
title: "Déclarer chaque module utilisé"
domain: architecture
rules: true
files: ["scripts/architecture/check-module-boundaries.py", "minos-app/src/test/java/**/ModuleArchitectureTest.java"]
---

# Déclarer chaque module utilisé

Date : 2026-10-09. Statut : **Accepted**.

Constat : MINOS-AUD-H08 (`docs/developer/quality/code-audit-constats.md`). Complète : [0022](archive/0022-maven-reactor-and-module-boundaries.md), [0042](ARCH-0042-racine-de-composition.md)

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
- Gate `scripts/architecture/check-module-boundaries.py` (job `invariants`) — table `ALLOWED_DEPENDENCIES` (règle A2) et règle A11 (types de `minos-engine` utilisés par chaque surface, avec le nombre de fichiers qui les utilisent, appels d'accesseurs de `MinosApplication` compris ; table `ENGINE_TYPES_NAMED_BY_SURFACE`) ; auto-test `test_check_module_boundaries.py`.
- Test `ModuleArchitectureTest.surfacesUseOnlyListedEngineTypes` (`minos-app`, jobs `Verify`) — règle A11 sur le bytecode (table `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE`), qui voit les appels chaînés sur un résultat du moteur.
- Règle Archgate `ARCH-0058-declarer-les-usages-de-modules.rules.ts` / `allowed-dependency-tables-identical` : refuse un module ou une arête présent dans `ALLOWED_DEPENDENCIES` (`check-module-boundaries.py`) et absent de la table `ALLOWED` de `ModuleArchitectureTest`, ou l'inverse ; contrôle propre à Archgate, aucun gate Python ne le fait (lancée par `archgate check`, pas par la CI).
- Règle A17 de `scripts/architecture/check-module-boundaries.py` (job `invariants`) : `ModuleArchitectureTest.ALLOWED` égale `ALLOWED_DEPENDENCIES`, module par module ; la règle Archgate ci-dessus en est le second exécutant.
- Revue : une arête ajoutée l'est dans les deux tables, dans le même changement.

## Mise en œuvre

**Point 3 gardé (2026-10-22, audit AUD-ARC-01, changement OpenSpec `figer-la-dette-de-frontieres-par-des-cliquets`)** : la règle A11 de `scripts/architecture/check-module-boundaries.py` tient, par surface, la liste fermée des types de `minos-engine` que ses sources de production nomment (import ou nom qualifié ; table `ENGINE_TYPES_NAMED_BY_SURFACE`). Un type hors liste, une entrée que la surface ne nomme plus ou qui ne désigne aucun type font échouer le garde : la « justification » d'un nouvel usage est une ligne ajoutée à la table et relue. Les usages entre adaptateurs sont tenus de la même façon (règle A13, [ADR 0062](archive/0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md)).

**Point 3, lot 1 résorbé (2026-10-24, audit AUD-ARC-01, changement OpenSpec `faire-consommer-l-indexation-aux-surfaces`)** : aucune surface ne nomme plus de type de `com.minos.orchestration` ni de `com.minos.remote` ; `ENGINE_TYPES_NAMED_BY_SURFACE` compte 39 entrées (CLI 15, API 14, MCP 4, NEXUS 6). Restent, par lot : diagnostic de l'hôte (2 entrées de la CLI, `WorkerSandboxProbe` et `PrivateLocalStorage` de `DoctorCommand`, après le port d'ACL de plateforme SH-08), contrôle hébergé (13), Git et résultats de requête (8, dont 6 résultats de requête justifiés par un amendement de l'ADR 0057 § 6), export NEXUS (6, sur le port de lecture des snapshots SH-04), noyau transverse (10).

**Point 3, mesure élargie (2026-10-25, audit AUD-ARC-01, changement OpenSpec `rendre-les-cliquets-d-architecture-etanches`)** : la règle A11 compte, par type, les fichiers qui l'utilisent, et un appel d'accesseur de `MinosApplication` compte comme un usage du type rendu. La table passe ainsi à 43 entrées (CLI 17, API 16, MCP 4, NEXUS 6). Les 4 entrées ajoutées ne sont pas de nouveaux usages : ce sont des usages sans nom présents au HEAD `dedb219e`. Elles portent chacune leur raison dans la table :

- `registry.ProjectRegistry` et `store.CodeKnowledgeSnapshotStore` pour l'API : `LocalMinosApi` passe `app.projectRegistry()` et `app.snapshotStore()` à `LocalProjectSymbolQuery` ;
- les mêmes types pour la CLI : `MinosCliRunner` les passe à `NexusExportService`.

Le test `ModuleArchitectureTest.surfacesUseOnlyListedEngineTypes` tient la même dette sur le bytecode (39 types), appels chaînés compris.

**Point 3, lot 2 résorbé (2026-10-25, audit AUD-ARC-01, changement OpenSpec `faire-consommer-le-diagnostic-de-l-hote`)** : `minos doctor` obtient la sélection des bacs à sable, la localisation des commandes et la confidentialité des emplacements de `MINOS_HOME` par le service applicatif `HostDiagnosticsService`, en types JDK et applicatifs. La CLI ne nomme plus `io.PrivateLocalStorage` ni `runtime.WorkerSandboxProbe`, et n'utilise plus `runtime.HostCommandLocator`. `ENGINE_TYPES_NAMED_BY_SURFACE` compte 41 entrées (CLI 15, API 16, MCP 4, NEXUS 6), et la table bytecode 36 (CLI 12, API 14, MCP 4, NEXUS 6).

**Point 3, lot 6 résorbé (2026-10-25, audit AUD-ARC-01, changement OpenSpec `descendre-le-noyau-transverse`)** : les types du noyau transverse sans E/S ni dépendance au moteur sont dans `minos-domain`. Il s'agit de `com.minos.diagnostics.PublicErrorMessages` (paquet entier, nom complet inchangé), et de `UnreadableRegistryException`, `DegradedEntry` et `ProjectPathMapping` (`com.minos.domain.registry`). Les surfaces les utilisent sans entrée A11. Deux types restent dans le moteur, avec leur raison écrite dans la table : `registry.ProjectPathMappings`, port d'E/S persisté, et `runtime.MinosVersion`, placé par l'ADR 0068 § 3. `ENGINE_TYPES_NAMED_BY_SURFACE` compte 33 entrées (API 15, CLI 11, MCP 2, NEXUS 5), et la table bytecode 28 (API 13, CLI 8, MCP 2, NEXUS 5).

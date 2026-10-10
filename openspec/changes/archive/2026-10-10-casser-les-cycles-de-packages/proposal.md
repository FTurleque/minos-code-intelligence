# Proposal

## Why

La règle A8 (changement `durcir-les-gardes-d-architecture`, PR #394) refuse tout nouveau cycle de packages, mais elle a **gelé** les quatre cycles existants (15 packages) dans `KNOWN_PACKAGE_CYCLES`. AUD-ARC-06 n'est donc fermé qu'à moitié : la garde est livrée, les cycles subsistent.

Ces cycles ont un coût réel :

- **`minos-application`** (8 packages : `application`, `application.dynamic`, `application.semantic`, `architecture`, `impact`, `output`, `program.analysis`, `workspace`) : les packages ne sont pas séparables, ce qui est le prérequis de SH-09 (découpe par capacité) et de la séparation du rendu (`output`) des cas d'usage.
- **`minos-engine`** : `discovery` ↔ `discovery.spi` et `incremental` ↔ `orchestration`.
- **`minos-intellij`** (hors reactor) : `protocol` ↔ `ui`.

L'analyse du design montre qu'une **simulation sur le graphe de classes** lève les quatre cycles en déplaçant **13 classes**, sans créer de module, sans déplacer `com.minos.output` hors de `minos-application`, et sans ADR.

## What Changes

Quatre lots indépendants, chacun vérifiable par le retrait d'une entrée de `KNOWN_PACKAGE_CYCLES` (la garde devient rouge tant que le cycle existe, verte quand il est levé) :

| Lot | Cycle levé | Classes déplacées |
|---|---|---|
| A | `minos-application` (8 packages) | `ProjectResolver` → `com.minos.application.resolution` ; `DeterministicJson` → `com.minos.output.json` |
| B | `incremental` ↔ `orchestration` | `IncrementalIndexingCoordinator`, `IncrementalIndexingPlan`, `IncrementalIndexingPlanner`, `IncrementalIndexingResult`, `ProjectFingerprintSnapshotAlignmentService`, `ProjectInvalidationService` → `com.minos.orchestration` |
| C | `discovery` ↔ `discovery.spi` | `BuildSystemDetector`, `LanguageDetector`, `ProjectDetector`, `SourceRootDetector` → `com.minos.discovery` (le package `spi` disparaît) — **option à confirmer** |
| D | `protocol` ↔ `ui` du plugin | `MinosRegistryNotice` → `com.minos.intellij.protocol` |

Les tests des classes déplacées les suivent (règle A3 : un test se déclare dans le package de sa production). Les gates qui citent un chemin déplacé sont mis à jour dans la même tâche. À la fin, `KNOWN_PACKAGE_CYCLES` est **vide** et le mécanisme reste (tout cycle futur est refusé).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` (introduite par `durcir-les-gardes-d-architecture`, pas encore archivée au moment de la rédaction) : exigences ajoutées — plus aucun cycle de packages, comportement observable inchangé à l'octet, couverture et gates suivant les classes déplacées.

## Hors périmètre

- **AUD-ARC-02** (une seule orchestration d'indexation, sprint 9) : `IncrementalIndexingCoordinator` n'a aucun appelant de production et sera supprimé ou fusionné par S9. Le lot B le **déplace** sans le supprimer, pour ne pas mêler les deux chantiers.
- **AUD-ARC-01, AUD-ARC-05, AUD-ARC-08** (sprint 10) : arêtes surfaces → moteur et adaptateur → adaptateur.
- Déplacer `com.minos.output` hors de `minos-application` (proposé par l'audit) : inutile d'après la simulation, et il demanderait un ADR.
- Toute modification de comportement : ce lot ne fait que déplacer des classes et corriger des imports.
- Les surfaces publiques versionnées (CLI, MCP, API Java `minos-api`, protocole `minos-ide`) : inchangées.

## Impact

- **Modules du reactor touchés** : `minos-application` (lots A), `minos-engine` (B, C), et les modules qui importent les classes déplacées (`minos-cli`, `minos-mcp`, `minos-api` pour `ProjectResolver` et `DeterministicJson` ; `minos-cli` pour les classes d'`incremental`). Hors reactor : `minos-intellij` (lot D).
- **Surfaces publiques impactées** : aucune en comportement. `ProjectResolver` figure dans des constructeurs publics de `minos-application` (`ProjectQueryService`, `HybridSearchService`, `LocalProjectImpactQuery`, `ProgramGraphService`), mais `minos-application` n'est pas l'API versionnée (aucune signature de `minos-api` ne l'expose) : décision documentée dans le design.
- **ADR** : aucun nouvel ADR. Les déplacements restent **dans** leur module (ADR 0044 respecté). Le lot C modifie le nom d'un package cité par l'ADR 0026 : une note de suivi y est ajoutée, sans changer la décision.
- **Gates à rejouer** : `check-module-boundaries.py` (A8 : table vide), `check-jacoco.py` (scope `project-resolution`), `check-post-mne.py` (chemin de `DeterministicJson`), `scripts/m15/run-final.ps1` (chemin de `ProjectResolver`), `check-mnd.py`, `check-mne.py`, `check-polyglot-provider-consistency.py`, `check-current-docs.py` ; `ModuleArchitectureTest` ; goldens de caractérisation identiques à l'octet.
- **Plateformes** : refactor de noms de packages, sans comportement propre à un OS ; la CI Windows et Linux le qualifie.

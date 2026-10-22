# Proposal

## Why

Reste d'**AUD-QUA-02** (moyenne, sprint 9 de l'audit du 10 octobre 2026). Sept classes publiques de `src/main` n'ont **aucune référence depuis un autre fichier de production** au HEAD `949e1569`. `IncrementalIndexingCoordinator` (220 lignes) est traité par `unifier-l-orchestration-d-indexation`. Il en reste six, 378 lignes :

| Classe | Module | Lignes | Appelée par |
|---|---|---|---|
| `ProjectFingerprintSnapshotAlignmentService` | `minos-engine` | 42 | 3 classes de test |
| `IndexerProviderRegistry` | `minos-engine` | 58 | 2 classes de test, dans 2 modules |
| `InterproceduralFlowService` | `minos-application` | 110 | 1 classe de test |
| `ProgramGraphEvaluator` | `minos-application` | 46 | 4 classes de test, dans 2 modules |
| `SemanticSearchEvaluator` | `minos-application` | 70 | 1 classe de test |
| `NexusSemanticSignalService` | `minos-nexus` | 52 | 1 classe de test |

Ce code est testé, mesuré et documenté comme s'il tournait. Deux gates le figent : `scripts/quality/check-jacoco.py:29` et `:53` (portées qui nomment trois de ces classes) et `scripts/quality/check-java-ast-provider-consistency.py:109` (exige `ProgramGraphEvaluator().evaluate`). Rien n'empêche qu'une huitième classe de ce genre apparaisse.

Les six cas ne sont pas de même nature : `NexusSemanticSignalService` est une **surface publique documentée** de `minos-nexus`, et `IndexerProviderRegistry` est nommé par l'ADR 0026 comme catalogue d'extensions. « Aucun appelant » ne suffit donc pas à décider.

## What Changes

- **Une décision écrite par classe** (brancher, déplacer en test, supprimer, ou déclarer comme point d'entrée), prise par le propriétaire sur une recommandation (`design.md`, D1).
- **Les gates suivent** : portées JaCoCo et littéraux retirés ou déplacés dans la même tâche que la classe.
- **Un gate à cliquet** : aucune classe publique de `src/main` sans référence de production hors d'une liste nominative justifiée, qui ne peut que rétrécir.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : deux exigences ajoutées (toute classe publique de production est référencée par la production ou inscrite avec sa raison ; la liste est un cliquet).

## Hors périmètre

- `IncrementalIndexingCoordinator` et `IncrementalIndexingResult` : changement `unifier-l-orchestration-d-indexation`.
- `CodeKnowledgeSnapshotBinaryCodec` et les ports hébergés non qualifiés (`HostedAvailabilityPort`, `HostedTransportSecurityPort`) : frontières déclarées, exclues par l'audit lui-même ; elles entrent dans la liste nominative avec leur raison.
- Les méthodes ou membres sans appelant à l'intérieur d'une classe utilisée.
- Le plugin `minos-intellij` (points d'extension déclarés en XML) : non couvert par le gate dans ce changement.
- Brancher réellement une capacité (flux interprocédural, signaux NEXUS) : si le propriétaire choisit « brancher », cela demande son propre changement.

## Impact

- **Modules du reactor** : `minos-engine`, `minos-application`, `minos-nexus` ; tests de `minos-bootstrap` et `minos-app`. Aucune dépendance ajoutée, aucun module créé.
- **Surfaces publiques** : selon D1. Supprimer `NexusSemanticSignalService` retirerait une classe publique documentée de la surface NEXUS (`docs/developer/public-surfaces.md`, `docs/user/java-api.md`) : **décision en attente**, recommandation de la conserver et de la déclarer. CLI, MCP, IntelliJ : aucun effet.
- **ADR** : si `IndexerProviderRegistry` est supprimé, l'ADR 0026 reçoit une note de « Mise en œuvre » ; aucun nouvel ADR attendu.
- **Gates à rejouer** : `python scripts/quality/check-jacoco.py` (portées de `:29` et `:53`, scope `nexus-export`), `python scripts/quality/check-java-ast-provider-consistency.py`, `python scripts/architecture/check-module-boundaries.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/docs/check-current-docs.py`.
- **Plateformes** : Windows et Linux, sans différence.

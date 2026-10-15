# Proposal

## Why

Deux constats de faible sévérité du sprint 4 de l'audit du 10 octobre 2026, réunis parce qu'ils ont la même cause (un service lit le snapshot brut au lieu de la vue de requête indexée) et la même correction :

- **AUD-PERF-11** : `ImpactAnalysisService.analyze` reconstruit à chaque appel la table des symboles et l'index trié des arêtes entrantes (`:76`, `:199-221`) ; `AdvancedImpactService` trie tout le graphe de programme à chaque appel (`:95`), alors que ce graphe est en cache. L'audit d'architecture de septembre a mesuré 15 à 68,5 ms par appel, entièrement en préparation.
- **AUD-PERF-13** : `RuntimeIntelligenceService.java:183` cherche un symbole par un parcours linéaire de tous les symboles du snapshot, alors que la vue de requête offre la recherche par identifiant (`InMemoryCodeKnowledgeStore.findSymbolById`).

Le coût est fixe et linéaire en taille de snapshot ; il s'ajoute à chaque `minos_impact`, `minos_impact_v2` et `minos_runtime_symbol`.

## What Changes

- **Index des arêtes entrantes porté par la vue de requête** : construit une fois avec la vue, réutilisé par chaque analyse d'impact.
- **Adjacence triée portée par l'entrée de cache du graphe de programme** : construite une fois avec le graphe.
- **Recherche de symbole par l'index de la vue** dans le rapport runtime.
- **Le poids des caches inclut ces index.**
- **Sorties identiques** : ordre et contenu des rapports d'impact inchangés.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `tests-lies-et-impact` : une exigence ajoutée (les index d'impact sont construits une fois par vue ou par graphe en cache, à sortie identique).
- `persistance-snapshots` : deux exigences ajoutées (une recherche de symbole par identifiant utilise l'index de la vue ; le poids d'une vue en cache compte ses index dérivés).

## Hors périmètre

- Un arrêt anticipé de la traversée d'impact : infirmé par la mesure (constat Info AUD-PERF-14, à ne pas « corriger »).
- Modifier les composants du record public `SnapshotQueryView` : interdit par l'ADR 0056 ; l'index est porté par un accesseur du store en mémoire.
- Le poids de base des vues (AUD-PERF-03, prérequis) et le calcul de la clé du graphe de programme (AUD-PERF-07).
- D'autres parcours linéaires : le relevé au HEAD n'en trouve pas d'autre du même motif dans `src/main`.

## Impact

- **Modules du reactor** : `minos-engine` (`store/InMemoryCodeKnowledgeStore`), `minos-application` (`impact/ImpactAnalysisService`, `impact/LocalProjectImpactQuery`, `program/analysis/AdvancedImpactService`, `program/analysis/ProgramGraphService`, `application/dynamic/RuntimeIntelligenceService`). Direction ADR 0022 respectée.
- **Surfaces publiques** : aucune sortie CLI, MCP ou API ne change (exigence existante « Les sorties d'impact restent additives et reproductibles »).
- **ADR** : aucun ; respecte l'ADR 0056.
- **Gates à rejouer** : `python scripts/remediation/check-mnd.py` (`:101-102`), `check-mne.py` (`:106-107`), `check-runtime-dynamic-consistency.py` (`:81-86`), scopes JaCoCo `persistence-cache-indexes`, `advanced-impact-security`, `m26-runtime-dynamic-intelligence`, goldens de caractérisation de `minos-app`, `python scripts/architecture/check-module-boundaries.py`.
- **Plateformes** : Windows et Linux, sans différence.

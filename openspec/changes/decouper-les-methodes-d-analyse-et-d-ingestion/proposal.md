# Proposal

## Why

**AUD-QUA-07** (faible, sprint 11 de l'audit du 10 octobre 2026). Quelques méthodes de service concentrent la longueur et les décisions, ce qui gêne la relecture et les tests de mutation. L'audit parle de dette locale, pas systémique, et exclut tout seuil bloquant.

Relevé au HEAD :

- `ingest` — `minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionAdapter.java:51`, 154 lignes ;
- `analyze` — `minos-application/src/main/java/com/minos/impact/ImpactAnalysisService.java:56`, 131 lignes, 22 points de décision ;
- `execute` — `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:47`, **14 paramètres**, alors que la classe regroupe déjà sept de ses ports dans un record privé (`:847`) ; un seul appelant (`IndexingLifecycleService.java:171`).

**Le décompte global de l'audit n'est pas reproduit** : il annonçait 40 méthodes de 80 lignes ou plus sur 4 876 ; une mesure indépendante en trouve 24 sur 4 399, par une autre méthode de comptage (le script d'origine n'est pas disponible). Aucun des deux chiffres n'est repris comme référence ; la tâche 1.1 fixe la méthode et la mesure.

## What Changes

- `ImpactAnalysisService.analyze` et `ScipIngestionAdapter.ingest` sont découpées en **étapes nommées, méthodes privées de la même classe**.
- `IndexingRunExecutor.execute` reçoit ses ports et ses politiques par **un objet requête** au lieu de quatorze paramètres.
- **Comportement constant** : aucune sortie, aucun message, aucun ordre d'effet ne change ; la preuve est faite par les tests existants, verts avant et après, et par les goldens inchangés.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

(aucune : pur remaniement à comportement constant. Aucune exigence n'est ajoutée, modifiée ni retirée ; `skip_specs: true` est posé dans `.openspec.yaml` et le changement n'a pas de dossier `specs/`. Les exigences courantes de `openspec/specs/` qui couvrent l'analyse d'impact, l'ingestion et l'indexation restent la référence et doivent rester satisfaites.)

## Hors périmètre

- Tout seuil de longueur ou de complexité, bloquant ou non, dans un gate ou dans SonarCloud (l'audit l'exclut).
- `LocalIsolatedIndexWorker.execute` (107 lignes) : la classe est citée par six gates littéraux et par la baseline SpotBugs ; à traiter à part, gates en main.
- `CodeSearchService.search` (119 lignes), `WindowsAppContainerWorkerSandboxBackend.sandboxPlan` (108 lignes), `MinosApplicationAssembler.build` (108 lignes, non citée par l'audit) : relevées, non traitées ici.
- Toute extraction vers une **classe nouvelle**, tout changement de signature publique.
- Les index d'arêtes d'`ImpactAnalysisService` (AUD-PERF-11, changement actif `indexer-les-aretes-d-impact-avec-la-vue`, qui passe avant).

## Impact

- **Modules du reactor** : `minos-application` (`impact/ImpactAnalysisService`), `minos-provider-scip` (`ingestion/ScipIngestionAdapter`), `minos-engine` (`orchestration/IndexingRunExecutor`, `orchestration/IndexingLifecycleService`). Aucune dépendance ajoutée (ADR 0022).
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- **ADR** : aucun.
- **Gates à rejouer** : scopes JaCoCo `critical-orchestration` et `resume-orchestration` (`scripts/quality/check-jacoco.py:182,194` nomment `IndexingRunExecutor`), scope `m24-polyglot-provider-platform` pour `minos-provider-scip` ; `python .claude/scripts/run_gates.py --fast` ; baseline SpotBugs (`quality/spotbugs-baseline/`). `ImpactAnalysisService` et `ScipIngestionAdapter` ne sont cités par aucun gate de `scripts/`.
- **Plateformes** : Windows et Linux, sans différence.

# Proposal

## Why

**AUD-QUA-07** (faible, sprint 11 de l'audit du 10 octobre 2026). Quelques méthodes de service concentrent la longueur et les décisions, ce qui gêne la relecture et les tests de mutation. L'audit parle de dette locale, pas systémique, et exclut tout seuil bloquant.

Relevé au HEAD `5ce7b79b` (mesure décrite dans `design.md`) :

- `ingest` — `minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionAdapter.java:51-204`, 154 lignes, appelée en production par `ScipSymbolSnapshotImporter.java:98` seulement ;
- `analyze` — `minos-application/src/main/java/com/minos/impact/ImpactAnalysisService.java:84-209`, surcharge **privée** de 126 lignes et environ 21 points de décision, derrière deux surcharges publiques courtes (`:60`, vue de requête ; `:71`, snapshot seul) ;
- `execute` — `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:48-104`, **16 paramètres** (57 lignes), alors que la classe regroupe déjà neuf de ses entrées dans un record privé `Ports` (`:858-880`) ; un seul appelant (`IndexingLifecycleService.java:209`).

**Le décompte global de l'audit n'est pas reproduit** : l'audit annonce 40 méthodes de 80 lignes ou plus sur 4 876 ; une contre-vérification antérieure en trouve 24 sur 4 399 ; une mesure approchée au HEAD `5ce7b79b` en trouve 18 sur 4 430. Les trois méthodes de comptage diffèrent et aucun script n'est versionné. Aucun chiffre n'est repris comme référence ; la tâche 1.1 fixe la méthode et la mesure.

## What Changes

- `ImpactAnalysisService.analyze` (surcharge privée) et `ScipIngestionAdapter.ingest` sont découpées en **étapes nommées, méthodes privées de la même classe**.
- `IndexingRunExecutor.execute` reçoit ses entrées par **deux records de visibilité package** (entrées du run, collaborateurs du service) au lieu de seize paramètres.
- **Comportement constant** : aucune sortie, aucun message, aucun ordre d'effet ni de validation ne change ; la preuve est faite par les tests existants et des tests de caractérisation écrits avant le découpage, verts avant et après, et par les rapports de référence `minos-application/src/test/resources/impact/*.txt` et les goldens `minos-app/src/test/resources/characterization/` inchangés.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

(aucune : pur remaniement à comportement constant. Aucune exigence n'est ajoutée, modifiée ni retirée ; `skip_specs: true` est posé dans `.openspec.yaml` et le changement n'a pas de dossier `specs/`. Les exigences courantes de `openspec/specs/` qui couvrent l'analyse d'impact, l'ingestion SCIP et l'indexation restent la référence et doivent rester satisfaites.)

## Hors périmètre

- Tout seuil de longueur ou de complexité, bloquant ou non, dans un gate ou dans SonarCloud (l'audit l'exclut).
- `LocalIsolatedIndexWorker.execute` (`minos-runtime-local/.../LocalIsolatedIndexWorker.java:153`, 107 lignes) : la classe est citée par six gates `check-*.py`, par `scripts/architecture/private-io-allowlist.json`, par le scope JaCoCo `m25-remote-distributed-indexing` et par la baseline SpotBugs de `minos-runtime-local`, et le changement actif `migrer-les-ecritures-d-artefacts-du-runtime-local` la modifie ; à traiter à part, gates en main.
- Les autres méthodes de 80 lignes ou plus relevées (liste dans `design.md`), dont `CodeSearchService.search` (119 lignes), `WindowsAppContainerWorkerSandboxBackend.sandboxPlan` (108 lignes), `MinosApplicationAssembler.build` (108 lignes, non citée par l'audit) : relevées, non traitées ici.
- Les deux surcharges publiques d'`analyze` et la signature publique d'`IndexingLifecycleService` : inchangées.
- Toute extraction vers une **classe de premier niveau nouvelle**, tout changement de signature publique.
- La migration d'`ImpactAnalysisService` vers une vue de lecture neutre (AUD-ARC-07, changement actif `exposer-un-port-de-lecture-de-snapshot`, tâche 3.2), qui passe **après** la section 2 de ce changement.

## Impact

- **Modules du reactor** : `minos-application` (`impact/ImpactAnalysisService`), `minos-provider-scip` (`ingestion/ScipIngestionAdapter`), `minos-engine` (`orchestration/IndexingRunExecutor`, `orchestration/IndexingLifecycleService`). Aucune dépendance ni aucun package ajoutés (ADR 0022, ADR 0044, ADR 0062).
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python .claude/scripts/run_gates.py --fast` ; `python scripts/quality/check-jacoco.py` — scopes `critical-orchestration` (`scripts/quality/check-jacoco.py:254-258`) et `resume-orchestration` (`:263-271`) pour `IndexingRunExecutor`, scope `global` pour les trois classes (aucun autre scope ne nomme `ImpactAnalysisService` ni `ScipIngestionAdapter`) ; comparaison SpotBugs à `quality/spotbugs-baseline/` (aucune entrée pour ces trois classes : toute alerte nouvelle est visible). Aucun `check-*.py` ne cite `ImpactAnalysisService`, `ScipIngestionAdapter`, `IndexingRunExecutor.execute` ni `IndexingLifecycleService` hors `check-jacoco.py`.
- **SonarCloud** : les lignes déplacées comptent comme nouveau code de leur module (seuil 80 %).
- **Plateformes** : Windows et Linux, sans différence.

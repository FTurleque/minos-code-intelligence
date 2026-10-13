# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-07**. HEAD analysé : `949e1569` (`develop`). `EXEC` désigne `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java`.

## État vérifié au HEAD

### AUD-QUA-07 — confirmé, décompte global non reproduit

- **Signatures relues** : `ingest` (`minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionAdapter.java:51`), `analyze` (`minos-application/src/main/java/com/minos/impact/ImpactAnalysisService.java:56`), `search` (`minos-application/src/main/java/com/minos/context/CodeSearchService.java:43`), `sandboxPlan` (`minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java:254`), `build` (`minos-application/src/main/java/com/minos/application/MinosApplicationAssembler.java:39`), `execute` (`minos-runtime-local/src/main/java/com/minos/runtime/local/LocalIsolatedIndexWorker.java:153`). Les longueurs (154, 131, 119, 108, 108, 107 lignes) et les 22 points de décision d'`analyze` sont repris de la contre-vérification ; ils n'ont pas été recomptés pendant cette rédaction.
- **`EXEC:47-53`** : `execute` prend 14 paramètres (`projectId`, `projectRoot`, `targets`, `mode`, `changedFiles`, `executors`, `stager`, `promoter`, `stateStore`, `markers`, `artifactPolicy`, `resumePolicy`, `moduleAssignment`, `clock`). `EXEC:56` construit aussitôt un `Ports` à partir de sept d'entre eux.
- **`EXEC:847`** : `private record Ports(executors, stager, promoter, stateStore, markers, moduleAssignment, clock)`. Il ne porte ni `artifactPolicy` ni `resumePolicy`.
- **Appelant unique** : `minos-engine/src/main/java/com/minos/orchestration/IndexingLifecycleService.java:171-172`.
- **JaCoCo** : `scripts/quality/check-jacoco.py:182` (scope `critical-orchestration`) et `:194` (scope `resume-orchestration`) nomment `IndexingRunExecutor`.
- **Gates** : une recherche dans `scripts/` et `quality/` ne trouve `ImpactAnalysisService` ni `ScipIngestionAdapter` dans aucun gate ; `LocalIsolatedIndexWorker` est cité par `check-jacoco.py`, `check-remote-distributed-consistency.py`, `check-vertical-decomposition-consistency.py` (`scripts/quality/`), `check-minos-01.py`, `check-p0-p2.py`, `check-post-mne.py` (`scripts/remediation/`) et par la baseline SpotBugs.

**Non mesuré / non vérifié** : le nombre total de méthodes longues (40 sur 4 876 selon l'audit, 24 sur 4 399 selon la contre-vérification : méthodes de comptage différentes) ; aucun build, test ni gate exécuté ; la couverture actuelle, par les tests, de chaque branche d'`analyze` et d'`ingest`.

### Écarts avec l'action proposée par l'audit

1. **« Le record `Ports` existant » ne suffit pas** : il est privé et ne porte pas les deux politiques. Il faut soit l'élargir et l'ouvrir au package, soit un objet requête distinct (D2).
2. **`MinosApplicationAssembler.build` (108 lignes) n'est pas citée par l'audit** ; elle est relevée, hors lot.
3. **`LocalIsolatedIndexWorker.execute` est citée par l'audit mais gardée par six gates littéraux** : la découper ici exposerait à casser des chaînes exigées ; hors lot.
4. **Extraire vers des classes nouvelles coûterait en couverture** : SonarCloud juge le code neuf module par module. Des méthodes privées dans la même classe restent couvertes par les tests existants.
5. **Le changement actif `indexer-les-aretes-d-impact-avec-la-vue` réécrit une partie d'`analyze`** : découper avant lui garantit un conflit.

## Goals / Non-Goals

**Goals** : des étapes nommées et relisibles dans `analyze` et `ingest` ; un appel d'`execute` lisible ; zéro différence observable.

**Non-Goals** : un seuil ; une nouvelle abstraction ; un gain de performance ; toucher aux classes gardées par des gates littéraux.

## Decisions

### D1. La preuve d'un remaniement est une caractérisation, pas un rouge

Aucun comportement ne change : il n'existe pas de test qui échoue avant et réussisse après. Dans chaque section, l'étape « rouge » est remplacée par : (a) relever les tests existants qui traversent la méthode et la couverture de ses branches ; (b) si une branche n'est traversée par aucun test, **écrire d'abord le test de caractérisation**, vert au HEAD ; (c) découper ; (d) rejouer, tout vert, goldens identiques. La mesure de longueur avant/après est consignée, à titre d'information.

### D2. Objet requête d'`IndexingRunExecutor.execute`

Un record de visibilité package, imbriqué dans `IndexingRunExecutor`, regroupe les entrées du run (`projectId`, `projectRoot`, `targets`, `mode`, `changedFiles`) et un second ses collaborateurs (les sept ports actuels de `Ports`, plus `artifactPolicy` et `resumePolicy`). `Ports` est soit élargi et ouvert au package, soit conservé et construit depuis le second record : le choix se fait à la lecture (tâche 4.1), sans changer une vérification de nullité ni son message. L'ordre des validations actuelles (`resumePolicy` d'abord, `EXEC:54`, puis la racine) est conservé.

### D3. Découpage en méthodes privées de la même classe

`analyze` et `ingest` deviennent une suite d'appels à des étapes nommées d'après ce qu'elles font (collecte, normalisation, dérivation, selon ce que montre la lecture). Chaque étape est `private`, dans la même classe, sans état partagé nouveau : ce qui passait par des variables locales passe par des paramètres ou par un petit record privé. Aucune classe de premier niveau n'est créée.

### D4. Ordre et isolement des lots

Une PR par classe. `ImpactAnalysisService` passe **après** `indexer-les-aretes-d-impact-avec-la-vue`. `ScipIngestionAdapter` et `IndexingRunExecutor` sont indépendants.

## Direction des dépendances

Aucun import ajouté ni retiré ; chaque remaniement reste dans sa classe (`minos-application`, `minos-provider-scip`, `minos-engine`). ADR 0022 et ADR 0044 non concernés.

## Qualification des capacités

Sans objet : aucune capacité ajoutée, modifiée ni requalifiée. L'analyse d'impact, l'ingestion SCIP et l'indexation gardent leur qualification actuelle.

## Windows et Linux

Aucune différence. Le scope JaCoCo `m24-polyglot-provider-platform` est rouge sur poste Windows hors régression : comparer à `develop` avant d'attribuer un écart au découpage de `ScipIngestionAdapter`.

## Risks / Trade-offs

- **Régression silencieuse** dans une branche non couverte : c'est l'objet de D1 (b) ; sans test qui traverse la branche, on ne la déplace pas.
- **Ordre des effets** : `ingest` et `execute` ont des effets ordonnés (lectures, écritures, marqueurs) ; une étape extraite ne doit ni les réordonner ni changer le moment où une exception sort.
- **SonarCloud** : les lignes déplacées comptent comme nouveau code dans leur module ; elles restent couvertes parce qu'elles restent dans la même classe, à vérifier sur le rapport de la PR.
- **Baseline SpotBugs** : indexée par classe et par plages de lignes ; à rejouer pour chaque module touché, sans masquer d'alerte.
- **Gain modeste** : sévérité faible ; si la lecture montre qu'un découpage obscurcit plus qu'il n'éclaire, le dire et laisser la méthode.

# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans le module de la classe qu'ils couvrent (SonarCloud juge le nouveau code module par module, seuil 80 %). Une ligne de `ENGINE_TYPES_NAMED_BY_SURFACE` disparaît dans la tâche qui retire son dernier usage (D6). Remplacer un nom par `var` ou par un chaînage d'accesseurs n'est pas un retrait. Toutes les tâches portent **AUD-ARC-01**.

## 1. État de reprise lu par l'application

- [ ] 1.1 [AUD-ARC-01] Rouge : test de `minos-application` sur la requête de reprise de `ProjectInspectionService` — run interrompu rendu avec phase et cibles, arborescence de `MINOS_HOME` identique avant et après, `pas-un-uuid` → absence ; test de la surcharge de `ProjectJson` (mêmes clés et valeurs que la forme actuelle). Preuve du rouge : la méthode n'existe pas.
- [ ] 1.2 [AUD-ARC-01] `ResumableRunStatus` et `ProjectInspectionService.resumableRun(String)` ; surcharge de `ProjectJson` ; `LocalMinosApi`, `MinosApplicationMcpBackend` et `LocalAutonomousIndexOperations` (via `IndexResumeStatusSource`, dont la signature rend le type applicatif) les utilisent ; `MinosCliRunner` n'ouvre plus les opérations d'indexation pour lire le statut. Retirer `orchestration.ResumableRunSummary` des listes CLI, API et MCP, et `orchestration.IndexStateStore` de la liste CLI (son seul usage était cette lecture). Preuve : `./mvnw -B -ntp -pl minos-cli,minos-api,minos-mcp -am test` vert (dont `MinosCliRunnerResumeStatusTest` et les tests de statut MCP sans effet de bord), `check-module-boundaries.py` vert.

## 2. Runtimes de providers

- [ ] 2.1 [AUD-ARC-01] Rouge : test de `ProviderPlatformService` — `runtimes()` et `installRuntime(String)` rendent `ProviderRuntimeSummary` (identifiant, version, état, exécutable, diagnostics, requis par défaut) sur un `ProviderRuntimeManager` de test.
- [ ] 2.2 [AUD-ARC-01] Méthodes et résumé ; `LocalAutonomousIndexOperations.providers` et `installProvider` les utilisent. Retirer `runtime.ProviderRuntimeManager` et `runtime.ProviderRuntimeStatus` de la liste CLI. Preuve : `ToolsCommandTest`, `DoctorCommandTest`, `ProviderVerdictConsistencyTest` verts sans attente modifiée.

## 3. Indexation locale : résumés et choix de reprise

- [ ] 3.1 [AUD-ARC-01] Rouge : tests de `minos-application` sur `IndexingFixture` — `planSummary` et `executeSummary` (projet, langages et systèmes de build triés, providers, mode en `name()`, raisons, fichiers modifiés, run, statut, snapshot actif, empreinte promue, diagnostic, trace de reprise) ; test d'exhaustivité `IndexingResumeChoice` ↔ `IndexingResumePolicy`. Y reporter `IndexRunFailureMessageTest`, `LocalAutonomousIndexOperationsStateTest`, `LocalAutonomousIndexOperationsRunFormatTest` et `IndexFailurePathsTest` (depuis `minos-cli/src/test/java/com/minos/cli/operations`).
- [ ] 3.2 [AUD-ARC-01] `IndexingResumeChoice`, `IndexPlanSummary`, `IndexExecutionSummary`, `ResumeTraceSummary` dans `com.minos.application.indexing` ; `AutonomousIndexOperations` et `IndexCommand` passent au choix applicatif et au mode en chaîne ; `LocalAutonomousIndexOperations` ne fait plus que projeter les résumés et perd le constructeur qui prend un décorateur d'exécuteur ; mettre à jour le fragment de `minos-app/src/test/java/com/minos/characterization/ApplicationOwnershipGuardTest.java:63-66`. Retirer `orchestration.IndexingRun`, `IndexingMode`, `IndexingResumePolicy` de la liste CLI (`IndexingRuntimePorts` part en 4.2). Preuve : `IndexCommandResumeFlagsTest`, `NoResumeIndexingTest`, `AutonomousIndexCommandTest`, `IndexPlanCaptureOrderTest` verts sans attente modifiée ; `ApplicationOwnershipGuardTest` vert.

## 4. Indexation distante dans l'application

- [ ] 4.1 [AUD-ARC-01] Rouge : tests de `minos-application` avec doublures de `RemoteIndexingRuntime` et `RemoteRepositoryMaterializer` — refus ADR 0041 avant tout effet (matérialiseur jamais appelé, aucun dossier `remote-index-leases`, registre inchangé, message sans chemin) ; chaque sortie du nettoyage (bail, enregistrement, épinglage, exécution, absence de preuve vérifiée, succès) libère la source une seule fois ; test d'exhaustivité `WorkerNetworkChoice` ↔ `WorkerNetworkPolicy` ; `RemoteIndexLeaseTest` reporté. Les cas unitaires de `LocalRemoteIndexOperationsRefusalTest` y sont réécrits.
- [ ] 4.2 [AUD-ARC-01] `RemoteIndexingUseCase`, `RemoteIndexLease`, `RemoteSourceRequest`, `WorkerNetworkChoice` et les résumés dans `com.minos.application.remote` ; le cas d'usage appelle `ProjectIndexingUseCase` avec le décorateur d'exécuteur ; `LocalRemoteIndexOperations`, `RemoteIndexOperations` et `RemoteIndexCommand` ne font plus que convertir et projeter. `LocalRemoteIndexOperationsIntegrationTest` et un cas de bout en bout du refus restent dans `minos-cli`, reciblés sur le cas d'usage. Retirer de la liste CLI les cinq types `remote.*`, `orchestration.IndexerDescriptor`, `orchestration.IndexingRuntimePorts`, `registry.ProjectRegistry`, `registry.RegisteredProject`, `io.BoundedFileLease`, `io.Sha256`. Preuve : tests des deux modules verts ; recherche de l'exigence « Les familles de l'indexation ne sont plus nommées par les surfaces » vide.
- [ ] 4.3 [AUD-ARC-01] Gates littéraux (skill `minos-literal-gates`) : `scripts/quality/check-remote-distributed-consistency.py` (chemins et faits de `:128-146`, `:180`, `:209`) et `scripts/remediation/check-mnd.py` (`:39-40`, `:64-71`) reciblés sur `RemoteIndexingUseCase.java` et `com/minos/application/remote/RemoteIndexLease.java`, sans affaiblir un fait ni un import interdit ; `scripts/quality/check-jacoco.py`, portée `m25-remote-distributed-indexing` : préfixe `com/minos/application/remote/`, plancher par classe `{line 0.65, branch 0.35}` transféré à `RemoteIndexingUseCase`. Preuve : les trois scripts verts, `python scripts/architecture/check-private-io.py` vert.

## 5. API Java

- [ ] 5.1 [AUD-ARC-01] Rouge : test de `ProviderPlatformApi.ProviderDto` — capacités triées, non modifiables, clé ou valeur nulle refusée (`NullPointerException` comme `sortedCopy`).
- [ ] 5.2 [AUD-ARC-01] Copie triée en JDK dans `ProviderDto` ; retirer `orchestration.ProviderConformanceKit` de la liste API. Preuve : `./mvnw -B -ntp -pl minos-api -am test` vert.

## 6. Preuves d'ensemble

- [ ] 6.1 [AUD-ARC-01] `python scripts/architecture/check-module-boundaries.py` affiche `minos-api=14, minos-cli=15, minos-mcp=4, minos-nexus=6` ; `python scripts/architecture/test_check_module_boundaries.py` vert.
- [ ] 6.2 [AUD-ARC-01] `./mvnw -B -ntp verify` vert sur le poste ; aucun golden modifié (`git diff --stat develop -- "*golden*"` vide) ; `python .claude/scripts/run_gates.py` vert. Sur Windows, la portée JaCoCo `m24-polyglot-provider-platform` se compare à `develop` avant toute investigation.
- [ ] 6.3 (manuelle) [AUD-ARC-01] Sur la PR : couverture SonarCloud du nouveau code ≥ 80 % pour `minos-application`, `minos-cli`, `minos-api` et `minos-mcp` ; workflow `code-audit` en mode bloquant sans alerte SpotBugs hors base (`quality/spotbugs-baseline/`, base non élargie) ; les deux jobs `Verify` verts.
- [ ] 6.4 [AUD-ARC-01] Revue d'architecture (agent `minos-architecture-reviewer`) : direction des dépendances, aucun adaptateur nommé par l'application, aucun retrait masqué par `var`.

## 7. Clôture

- [ ] 7.1 Documentation courante : `docs/developer/remote-distributed-indexing.md`, `docs/architecture/arc42/06-vue-execution.md`, `docs/architecture/diagrams/seq-indexation-nominale.md`, `docs/architecture/diagrams/seq-erreur-provider.md` ; notes de mise en œuvre des ADR 0057 § 6 et 0058 § 3 (lot 1 livré, 39 entrées restantes, ordre des lots) ; mentions de `LocalRemoteIndexOperations` des ADR 0041 et 0042 ; `docs/project/roadmap/storage-hexagonal-2026-10/README.md` (tableau des chantiers). Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 7.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-ARC-01 : lot 1 livré, décomptes, lots restants et prérequis) et `docs/project/governance/STATUS.md`.
- [ ] 7.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : celles de 7.1 et 7.2.

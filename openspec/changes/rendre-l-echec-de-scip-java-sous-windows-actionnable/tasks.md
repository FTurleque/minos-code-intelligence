# Tasks

Ordre : lot 1 (sections 1 à 3) puis lot 2 (sections 4 et 5). Dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`.

## 1. Ligne publique d'un run en échec (D1)

- [x] 1.1 Rouge : `IndexRunFailureMessageTest` (`minos-cli`, package `operations`) — cause à chemin absolu (ligne avec run, phase, `MINOS_HOME/index-state/runs`, sans le chemin, et inchangée par `CliCommandSupport.failureMessage`), cause sûre jointe telle quelle, cause à secret retirée, run interrompu. **Preuve du rouge : la ligne de `LocalAutonomousIndexOperations.java:189-191` devient `IllegalStateException` au HEAD.**
- [x] 1.2 `LocalAutonomousIndexOperations` : `failureMessage(IndexingRun)` compose identifiant, phase et cause sûre ou renvoi. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-cli -am test` ; `python scripts/remediation/check-post-mne.py`, `python scripts/quality/check-polyglot-provider-consistency.py`, `python scripts/quality/check-remote-distributed-consistency.py` verts.

## 2. Messages amont sans chemin absolu (D2)

- [x] 2.1 Rouge : dans `ProcessIndexerExecutorArtifactPreservationTest`, la sortie non nulle n'est pas jugée sensible et nomme `provider.stderr.log` ; dans `WindowsSandboxAclAndProbeRefusalTest`, le refus ne contient pas le répertoire du fichier. **Preuve du rouge : les deux messages contiennent un chemin absolu au HEAD (`ProcessIndexerExecutor.java:158`, `WindowsAppContainerWorkerSandboxBackend.java:665-667`).**
- [x] 2.2 `ProcessIndexerExecutor` : journal relatif à `MINOS_HOME` ; `requireAclGrantable` : nom de fichier seul, `without administrator privileges` conservé. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-runtime-local -am test` ; `python scripts/remediation/check-minos-01.py`, `check-mne.py`, `check-mnd.py`, `check-post228-hardening.py` verts. Reste à faire : `python scripts/quality/check-jacoco.py` (scopes `provider-sandbox-windows` et celui de `ProcessIndexerExecutor`) après un `verify`, non lancé le 2026-10-17.

## 3. PowerShell canonique pour scip-java (D3)

- [x] 3.1 Rouge : `ScipJavaPowerShellTest` (package `plan`) et `ScipJavaRuntimePowerShellTest` (package `install`), Windows — `ScipJavaProcessPlanFactory.powerShellExecutable()` et `ManagedScipProviderRuntimeManager.powerShellExecutable()` rendent `CommandLocator.windowsPowerShell()` ; dans `ScipJavaProcessPlanFactoryToolchainTest`, un `PATH` d'enfant qui ne contient qu'un faux `powershell` et un faux `pwsh` ne donne jamais la commande du plan (remplace l'ancien cas « sans PowerShell », devenu inaccessible : un faux `SystemRoot` casse le stockage privé). **Preuve du rouge : sans PowerShell 5.1 dans le `PATH` (le poste du diagnostic), les deux méthodes rendent `C:\Program Files\PowerShell\7\pwsh.exe` au HEAD (`ScipJavaProcessPlanFactory.java:351-353`, `ManagedScipProviderRuntimeManager.java:870`).**
- [x] 3.2 Les deux méthodes délèguent à `CommandLocator.windowsPowerShell()` ; les messages de prérequis nomment Windows PowerShell 5.1. Preuve : tests verts, y compris lancés sans PowerShell 5.1 dans le `PATH` ; `./mvnw -B -ntp -pl minos-provider-scip -am test` ; `python scripts/remediation/check-post-mne.py` vert.

## 4. Lot 2 : scip-java Windows non déclaré prêt (D4)

- [ ] 4.1 Rouge : `ManagedScipProviderRuntimeManagerTest` — sous Windows, scip-java installé n'est pas `READY` et son diagnostic nomme le runtime Docker ; sous Linux, inchangé ; l'indexation Java Windows est refusée avant tout lancement. **Preuve du rouge : `READY` au HEAD (`ManagedScipProviderRuntimeManager.java:256`).**
- [ ] 4.2 Prérequis « exécution sous le bac à sable non qualifiée » sous Windows. Preuve : tests verts ; goldens de `minos-app` inchangés ou mis à jour avec justification ; `./mvnw -B -ntp -pl minos-app -am verify`.

## 5. Lot 2 : décision de qualification

- [ ] 5.1 ADR (prochain numéro libre de l'index ; 0060 au HEAD du 2026-10-18) : options (a) et (b) de D4, critères (confinement inchangé, sans élévation, Windows et Linux), décision et conséquences ; index `docs/architecture/adr/README.md`.
- [ ] 5.2 Selon l'ADR : test bout en bout sous le conteneur qui exécute un appel natif depuis PowerShell 5.1 **et** une JVM (`File.createTempFile`, initialisation de `java.security`), rouge au HEAD ; puis lever le prérequis de 4.2.

## 6. Clôture

- [ ] 6.1 `docs/user/troubleshooting.md` : lire la cause d'un run en échec dans `MINOS_HOME/index-state/runs` ; scip-java sous Windows. Rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`.
- [ ] 6.2 `docs/project/governance/STATUS.md` et `docs/project/audits/2026-10-10/SUIVI.md` : remplacer « à instruire à part » par le diagnostic et l'état des lots ; marquer les points 1 et 2 de `S15-REGRESSION-RUNTIME-GRANT.md` comme repris ici.
- [ ] 6.3 `openspec validate --all --strict` ; `python .claude/scripts/run_gates.py`.

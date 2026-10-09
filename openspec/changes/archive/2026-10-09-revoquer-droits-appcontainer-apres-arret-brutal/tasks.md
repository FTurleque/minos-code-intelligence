# Tasks

## 0. Préalable (utilisateur)

- [x] 0.1 Rétablir le poste touché par H23 en administrateur (DACL du profil, entrées `S-1-15-2-…` du JDK) ; vérifier `java -XshowSettings:security -version` et `./mvnw -v`.

## 1. Test d'abord (machine jetable Windows)

- [x] 1.1 Test Windows : reprise d'un run mort dont un chemin est journalisé sans avoir été accordé (`aJournaledPathWhoseGrantWasNeverAppliedIsRecoveredAsANoOp`) ; la reprise d'un run tué après l'octroi était déjà couverte (`aSandboxWhoseOwnerWasKilledIsRecoveredByTheNextLauncher`).
- [x] 1.2 Vérification en fin de classe des tests de bac à sable : aucune entrée de leur SID ne reste. — `AppContainerGrantLeakCheck` (extension JUnit) sur `WindowsAppContainerRecoveryOwnershipTest` et `WindowsAppContainerWorkerSandboxBackendTest` : entrées explicites `S-1-15-2-…` du JDK relevées avant et après la classe ; vert (9 + 15 tests). Autotest `AppContainerGrantLeakCheckTest`.

## 2. Journal et reprise

- [x] 2.1 Journal écrit avant `icacls /grant` dans les trois boucles d'octroi du gabarit ; retrait à la reprise sous la preuve de propriété A01 (existant) ; rejouer `check-minos-01.py`, `check-post228-hardening.py`, `check-audit-remediation-v2.py` et les tests de `WindowsAppContainerRecoveryOwnershipTest`.
- [x] 2.2 Amender l'ADR 0038 si la conception le confirme. — ADR 0038 § 3 amendée.

## 3. Diagnostic

- [x] 3.1 Recensement des entrées orphelines (lecture seule, retrait sur confirmation) ; tests. — `scripts/windows/Find-MinosAppContainerGrants.ps1` : racines `MINOS_HOME	ools`, `JAVA_HOME` et `-Path` ; SID des runs vivants exclus (verrou tenu) ; journaux sans propriétaire prouvable signalés ; `-Remove` sous `ShouldProcess`. Test `OrphanAppContainerGrantDiagnosticTest` (rapport sans changement, `-WhatIf` sans changement, retrait).

## 4. Audit

- [x] 4.1 Rejouer le PIT de `minos-runtime-local` sur un runner éphémère (`docs/quality/code-audit-couverture.md` § 7.3) ; consigner les résultats. — Runner GitHub jetable, [run 37976513603](https://github.com/FTurleque/minos-code-intelligence/actions/runs/37976513603) : 2 013 mutants, 877 tués par une assertion (44 %, 60 % des couverts), 567 survivants, 547 sans couverture, 22 délais. Les tests Windows du bac à sable ne s'exécutent pas sur Linux : le PIT de leurs classes reste à faire sur un runner Windows jetable.

## Évidence d'implémentation (2026-10-08)

- Gabarit `windows-appcontainer-sandbox-v4.ps1.template` : `$granted.Add` et `Write-Recovery` avant `Grant-AppContainerDirectory` / `Grant-AppContainerFile`. Remédiation déclarée dans `WindowsContainmentScriptTest.removeApprovedJournalBeforeGrant` (référence qualifiée inchangée sinon).
- Tests : `WindowsContainmentScriptTest` (8), `WindowsLauncherScriptPlacementTest` (5), `ProviderSandboxSecurityRegressionTest` (2), `WindowsAppContainerRecoveryOwnershipTest` (9, vrai lanceur, 65 s, aucun sauté) verts ; aucune entrée `S-1-15-2-…` sur le JDK après la suite.
- Ouverts : 1.2 (vérification en fin de classe des tests de bac à sable), 3.1 (diagnostic des entrées orphelines), 2.2 (ADR 0038), 4.1 (PIT sur runner éphémère). Une fuite reste possible dans les tests : un `MINOS_HOME` temporaire abandonné par un processus tué n'est jamais repris.

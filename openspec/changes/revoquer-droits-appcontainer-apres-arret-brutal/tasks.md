# Tasks

## 0. Préalable (utilisateur)

- [x] 0.1 Rétablir le poste touché par H23 en administrateur (DACL du profil, entrées `S-1-15-2-…` du JDK) ; vérifier `java -XshowSettings:security -version` et `./mvnw -v`.

## 1. Test d'abord (machine jetable Windows)

- [x] 1.1 Test Windows : reprise d'un run mort dont un chemin est journalisé sans avoir été accordé (`aJournaledPathWhoseGrantWasNeverAppliedIsRecoveredAsANoOp`) ; la reprise d'un run tué après l'octroi était déjà couverte (`aSandboxWhoseOwnerWasKilledIsRecoveredByTheNextLauncher`).
- [ ] 1.2 Vérification en fin de classe des tests de bac à sable : aucune entrée de leur SID ne reste.

## 2. Journal et reprise

- [x] 2.1 Journal écrit avant `icacls /grant` dans les trois boucles d'octroi du gabarit ; retrait à la reprise sous la preuve de propriété A01 (existant) ; rejouer `check-minos-01.py`, `check-post228-hardening.py`, `check-audit-remediation-v2.py` et les tests de `WindowsAppContainerRecoveryOwnershipTest`.
- [ ] 2.2 Amender l'ADR 0038 si la conception le confirme.

## 3. Diagnostic

- [ ] 3.1 Recensement des entrées orphelines (lecture seule, retrait sur confirmation) ; tests.

## 4. Audit

- [ ] 4.1 Rejouer le PIT de `minos-runtime-local` sur un runner éphémère (`docs/quality/code-audit-couverture.md` § 7.3) ; consigner les résultats.

## Évidence d'implémentation (2026-10-08)

- Gabarit `windows-appcontainer-sandbox-v4.ps1.template` : `$granted.Add` et `Write-Recovery` avant `Grant-AppContainerDirectory` / `Grant-AppContainerFile`. Remédiation déclarée dans `WindowsContainmentScriptTest.removeApprovedJournalBeforeGrant` (référence qualifiée inchangée sinon).
- Tests : `WindowsContainmentScriptTest` (8), `WindowsLauncherScriptPlacementTest` (5), `ProviderSandboxSecurityRegressionTest` (2), `WindowsAppContainerRecoveryOwnershipTest` (9, vrai lanceur, 65 s, aucun sauté) verts ; aucune entrée `S-1-15-2-…` sur le JDK après la suite.
- Ouverts : 1.2 (vérification en fin de classe des tests de bac à sable), 3.1 (diagnostic des entrées orphelines), 2.2 (ADR 0038), 4.1 (PIT sur runner éphémère). Une fuite reste possible dans les tests : un `MINOS_HOME` temporaire abandonné par un processus tué n'est jamais repris.

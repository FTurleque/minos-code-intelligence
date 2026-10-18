# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Aucun fichier de `minos-runtime-local` n'est déplacé ni renommé.

## 1. Relevé et décision (AUD-ARC-09)

- [x] 1.1 [AUD-ARC-09] Relever, dans les 16 classes de test de `minos-runtime-local` qui construisent un `ProcessIndexerExecutor` et dans `minos-cli/src/test/java/com/minos/cli/operations/LocalRemoteIndexOperationsIntegrationTest.java` (`:164`, `:216`), l'entrée appelée (`execute` ou `executeSandboxed`) et ce que le test éprouve (processus réel ou transport). Relire les chaînes exigées par `scripts/remediation/check-post-mne.py:244` et `scripts/remediation/check-minos-01.py:159` dans `ProcessIndexerExecutor.java`. Consigner dans `design.md`.
- [x] 1.2 [AUD-ARC-09] **Décision à obtenir** : D2 (`execute` refuse, recommandé, ou reste exécutable sous seul test de structure). Consigner dans `design.md`.

## 2. Constructeur limité à la propriété (AUD-ARC-09)

- [x] 2.1 [AUD-ARC-09] Rouge : dans `minos-runtime-local`, test par réflexion « tout constructeur public de `StrongProcessOwnershipIndexerExecutor` prend un `WorkerNetworkPolicy` » (D4-a). **Preuve du rouge : le test échoue sur le constructeur `:40`.**
- [x] 2.2 [AUD-ARC-09] Retirer `public` du constructeur à deux arguments (D1) ; ajouter dans les sources de test du package racine la fabrique qu'utilise `sandbox/LinuxStrongProcessOwnershipContainmentTest` (`:78`). Preuve : `./mvnw -B -ntp -pl minos-runtime-local -am test` vert ; `./mvnw -B -ntp -pl minos-provider-scip -am test` vert (site de production à trois arguments inchangé) ; `python scripts/remediation/check-post228-hardening.py` et `python scripts/architecture/check-module-boundaries.py` verts ; scope JaCoCo `provider-execution-trust-boundary`. Scopes JaCoCo : étape « Targeted JaCoCo gate » verte sur `Verify (ubuntu-24.04)` (full) et `Verify (windows-2022)` (Windows) au run 39076454797 (PR #493).

## 3. Refus de l'exécution sans frontière (AUD-ARC-09) — selon D2

- [x] 3.1 [AUD-ARC-09] Rouge : test « `ProcessIndexerExecutor.execute` lève l'erreur de D2, ne démarre aucun processus et ne crée pas le répertoire des runs » (D4-b), et test « backend natif + exécuteur nu : refus ». **Preuve du rouge : au HEAD, `execute` lance le processus du plan.**
- [x] 3.2 [AUD-ARC-09] `ProcessIndexerExecutor.execute` refuse (D2-A) ; aide de test du module qui nomme le transformateur identité ; migration des classes relevées en 1.1 ; le test d'intégration de `minos-cli` change d'exécuteur sans utiliser le membre réservé `executeSandboxed` (A10). Javadoc de `nativeEphemeralWorkspace()` (D3). Preuve : `./mvnw -B -ntp -pl minos-runtime-local -am test` et `./mvnw -B -ntp -pl minos-cli -am test` verts ; `python scripts/remediation/check-p0-p2.py`, `python scripts/remediation/check-post-mne.py`, `python scripts/remediation/check-minos-01.py`, `python scripts/architecture/check-module-boundaries.py` verts ; scopes JaCoCo `provider-execution-trust-boundary` et `m25-remote-distributed-indexing` (`python scripts/quality/check-jacoco.py`). Scopes JaCoCo : étape « Targeted JaCoCo gate » verte sur `Verify (ubuntu-24.04)` (full) et `Verify (windows-2022)` (Windows) au run 39076454797 (PR #493).
- [x] 3.3 **(manuelle)** [AUD-ARC-09] À la PR : relever dans les rapports Surefire de `Verify (ubuntu-24.04)` et `Verify (windows-2022)` que `LinuxStrongProcessOwnershipContainmentTest` et `WindowsStrongProcessOwnershipContainmentTest` ne comptent aucun test sauté sur leur système ; noter le run id dans `SUIVI.md`. **Relevé (run 39076454797, tête a84934e6, PR #493) : sous Linux `LinuxStrongProcessOwnershipContainmentTest` 1/1 exécuté, 0 sauté ; sous Windows `WindowsStrongProcessOwnershipContainmentTest` 2/2 exécutés, 0 sauté ; `ProcessIndexerExecutorBoundaryRefusalTest` 3/3 sur les deux. Chaque classe saute sur l'autre système, comme prévu.**

## 4. Clôture

- [x] 4.1 Ajouter une note de mise en œuvre à l'ADR qui porte la frontière de confiance de l'exécution des providers (l'identifier dans `docs/architecture/adr/README.md`) ; `docs/developer/remote-worker-sandbox-disposition.md` si elle décrit le backend natif. Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 4.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-ARC-09 : statut, décision D2, écarts avec l'action de l'audit, run id de 3.3).
- [x] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : ADR concerné, `docs/developer/remote-worker-sandbox-disposition.md`, `docs/developer/testing.md` (aide de test).

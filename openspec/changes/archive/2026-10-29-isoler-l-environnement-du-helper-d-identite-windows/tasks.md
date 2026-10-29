# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Un seul `mvnw test` à la fois sur le poste.

## 1. Environnement du helper d'identité de chemin

- [x] 1.1 [revue PR #599] Rouge : `WindowsExecutionPathIdentityHelperEnvironmentTest` (secrets, `PSModulePath` et variables de toolchain dans l'environnement parent ; cible héritée en casse différente). **Preuve du rouge : 3 échecs sur 3 au HEAD `e365a9c1`, l'environnement du helper contenait `MINOS_TEAM_KEY_1`, `MINOS_TEAM_TOKEN`, `GITHUB_TOKEN`, `NPM_TOKEN`, `PGPASSWORD` et `PSModulePath`.**
- [x] 1.2 [revue PR #599] `WindowsExecutionPathIdentityProvider.applyHelperEnvironment` applique `ProviderProcessEnvironment.applyForTrustedLauncher(builder, Map.of())`, rendue publique, puis pose les deux cibles (D1, D2, D3). Mesure des variables nécessaires consignée dans `design.md`. Preuve : sous Windows, `WindowsExecutionPathIdentity*Test`, `ProviderProcessEnvironment*Test`, `ProviderWriteContainmentTest` et `LocalProviderWorkspaceTest` verts (35 tests, 1 sauté : autre OS) ; `python scripts/architecture/check-module-boundaries.py` et `python scripts/quality/check-private-io.py` verts.

## 2. Clôture

- [x] 2.1 [revue PR #599] Revue `minos-security-reviewer` : conforme, aucun constat bloquant ; remarque hors périmètre sur `queryFailureMessage` renvoyée à la PR #599.
- [x] 2.2 CI de la PR verte sur `Verify (windows-2022)` et `Verify (ubuntu-24.04)`. — PR #602 fusionnée (`7490b04a`) : `Verify (windows-2022)` et `Verify (ubuntu-24.04)` verts, run https://github.com/FTurleque/minos-code-intelligence/actions/runs/40784461918.
- [x] 2.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; archiver après fusion (aucune doc courante à mettre à jour : comportement interne, sans surface publique). — gates rapides et `openspec validate --all --strict` rejoués à l'archivage, le 2026-10-29.

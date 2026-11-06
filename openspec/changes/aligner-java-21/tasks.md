# Tasks

## 1. Rouge

- [x] 1.1 (AUD-DEP-01) `maven.compiler.release` à 21 seul : la compilation échoue sur `_` (JEP 456) et `check-toolchain-baseline.py` nomme chaque site resté en Java 25 (rouge mesuré le 2026-11-06). Rejouer : `check-toolchain-baseline.py`.

## 2. Code et build

- [x] 2.1 `pom.xml` : `maven.compiler.release` 21, Enforcer `[21,22)`. Remplacer les 62 variables sans nom `_` (32 fichiers) par `ignored`, sans changer le comportement des blocs. Tests existants inchangés. Rejouer : scope JaCoCo `m24-polyglot-provider-platform` (rouge connu sur poste Windows, comparer à `develop`).
- [x] 2.2 `ScipIndexerCatalog` : prérequis `scip-java` « Java 21 JDK » ; golden `cli-json.golden` ; commentaire de `NexusExportContract`. Rejouer : `CliJsonCharacterizationTest` (`minos-app`).

## 3. CI, Docker et scripts

- [x] 3.1 Workflows : 16 étapes `java-version` et leurs libellés à 21 ; qualifications de montée : Java 25 pour le candidat A puis Java 21. Rejouer : `check-workflow-pins.py`, `check-single-execution.py`, `check-ci-wiring.py`.
- [x] 3.2 Dockerfiles : `eclipse-temurin:21.0.12.1_1-{jdk,jre}-noble` avec les digests d'index relevés ; `verify-run-configurations.ps1` et `prod-mcp.ps1`. Rejouer : `check-image-pins.py`, `check-audit-remediation-v2.py`.
- [x] 3.3 Scripts Windows, de release et de jalons (`scripts/m24` à `m27`) qui exigent un JDK 25 : exiger un JDK 21. Rejouer : `check-twin-script-functions.py`, `check-job-object-launcher-parity.py`, `check-milestone-artifact-references.py`.
- [x] 3.4 Gates : `PREVIOUS_JAVA = "25"` et docstring de la règle 5 dans `check-toolchain-baseline.py` ; `check-audit-remediation-v2.py`, `check-current-docs.py`. Test : `test_check_toolchain_baseline.py` (fixtures sur un produit en Java 27, un test pour le produit en Java 21), `test_check_image_pins.py`. Rejouer : les deux auto-tests, `check-ci-wiring.py`, `check-single-execution.py`.

## 4. ADR, documentation et preuves

- [x] 4.1 ADR : `GEN-0077` (Accepted), `GEN-0067` archivé (Superseded), règle Archgate renommée ; index du registre, arc42 § 9, `docs/developer/archgate.md`, liens de `ARCH-0069`. Rejouer : `archgate check`.
- [x] 4.2 Documentation courante : `TOOLCHAIN_POLICY.md` (Java 21, support jusqu'en 2029-12), `AGENTS.md`, `openspec/config.yaml`, `STATUS.md`, `ROADMAP.md`, pages `docs/user/` et `docs/developer/`, arc42, skills `.claude/`. Rejouer : `check-current-docs.py`, `product-facts.py --check`.
- [x] 4.3 Preuve locale (2026-11-06) : `./mvnw -B -ntp verify` sous JDK 21.0.12.1 (Microsoft), 14 modules en `BUILD SUCCESS`, 4130 tests, 0 échec, 134 ignorés ; jar ombré (classes version 65) sous Java 21 réel : `initialize` (2025-06-18), `tools/list` (31 outils), `minos_index_status` (`NEVER_INDEXED`), sortie 0, `MINOS_HOME` vide.
- [x] 4.4 CI de la PR #665 (2026-11-06, tête `2b83136c`) : `Verify (ubuntu-24.04)` et `Verify (windows-2022)` verts sous Java 21 (run 42230669977), `IntelliJ plugin (gate)` et `Windows process ownership / Java 21` verts (run 42230669125, relancé une fois après un blocage du runner), SonarCloud réussi (82,2 % de couverture du nouveau code). Fusionnée dans `develop` (`0e464f24`).
- [ ] 4.5 **(manuelle)** Qualifications hors de la PR : `docker-release-validation`, `windows-installer`, `release-promotion-gate` ; indexation réelle d'un projet Java sous Java 21 (bac à sable Windows et Linux).

## 5. Clôture

- [ ] 5.1 Matrice de traçabilité du design à jour ; `openspec validate --all --strict` ; `python scripts/docs/check-capability-dossiers.py` ; `python .claude/scripts/run_gates.py` ; `archgate check`.
- [ ] 5.2 Avant archivage : `docs/project/governance/STATUS.md` (ligne Java), `docs/architecture/risks/register.md` (R-26), suivi d'audit si une case le cite.

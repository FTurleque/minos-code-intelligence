# Tasks

Ordre dans chaque section : rouge, correction, preuve. Chaque workflow modifié est relu par `yaml.safe_load` avant commit. Les noms de jobs exigés par `.github/required-checks.json` ne changent pas.

## 1. Relevé et auto-test du gate (AUD-DEP-12, AUD-DEP-05, AUD-DEP-13)

- [ ] 1.1 [AUD-DEP-12] Relever les durées des derniers runs réussis de chaque job sans délai (`gh run list` / `gh run view`) pour fixer les valeurs de `timeout-minutes` ; relever le système de chaque job qui construit le plugin (wrapper `gradlew` ou `gradlew.bat`). Consigner dans `design.md`.
- [ ] 1.2 [AUD-DEP-12] Créer `scripts/quality/test_check_workflow_pins.py` sur arbres temporaires, d'abord pour les règles **existantes** du gate (un cas refusé, un cas accepté par règle) ; le câbler dans `invariants` (`pr-ci.yml`). Preuve : `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts.

## 2. Jeton du checkout et délais (AUD-DEP-12)

- [ ] 2.1 [AUD-DEP-12] Rouge : cas d'auto-test « checkout sans `persist-credentials: false` », « job à `runs-on` sans `timeout-minutes` », « appel réutilisable accepté ». **Preuve du rouge : avec les règles ajoutées, le gate échoue sur le dépôt réel (25 checkouts, 7 jobs).**
- [ ] 2.2 [AUD-DEP-12] Ajouter `persist-credentials: false` aux 25 checkouts et `timeout-minutes` aux 7 jobs (D1, D3). Preuve : gate vert ; `python scripts/docs/check-current-docs.py` vert.

## 3. Runner et wrapper Gradle (AUD-DEP-05)

- [ ] 3.1 [AUD-DEP-05] Rouge : cas d'auto-test « `runs-on` en `-latest` » (gate des workflows) et « `gradle-version:` dans un workflow » (`test_check_toolchain_baseline.py`, cas `:36` inversé). **Preuve du rouge sur `intellij-plugin-release.yml:24,144` et sur les deux `gradle-version:`.**
- [ ] 3.2 [AUD-DEP-05] `intellij-plugin-release.yml` et `release-windows.yml` : `./gradlew` (ou `gradlew.bat`) à la place de `gradle-version:` + `gradle` ; `ubuntu-24.04` ; règle 3 de `check-toolchain-baseline.py` réécrite (D2) ; `docs/TOOLCHAIN_POLICY.md` et `AGENTS.md` alignés si le texte cite `gradle-version:`. Preuve : les deux gates et leurs auto-tests verts ; `check-current-docs.py` vert (chaînes exigées de `release-windows.yml` conservées).

## 4. Identifiants Docker Hub (AUD-DEP-13)

- [ ] 4.1 **(manuelle)** [AUD-DEP-13] Le propriétaire vérifie dans Docker Hub que le jeton est « Public Repo Read-only » (ou le recrée ainsi) et le confirme ; **décision à obtenir** : D4 (A recommandé, ou B). Consigner la date et la portée dans `docs/developer/supply-chain.md`.
- [ ] 4.2 [AUD-DEP-13] Rouge : cas d'auto-test « `run:` après `docker/login-action` sans `docker logout` » refusé, « job de la liste fermée » accepté, « entrée de liste périmée » refusée. **Preuve du rouge : 5 jobs signalés sur le dépôt réel.**
- [ ] 4.3 [AUD-DEP-13] Jobs de qualification Docker (`docker-release-validation.yml`, `docker-upgrade-qualification.yml`, `release-promotion-gate.yml`) : tirage des images épinglées puis `docker logout` avant les scripts ; `pr-ci.yml` `verify` et `code-audit.yml` `mutation` : liste fermée avec raison (option A) ou pré-tirage (option B). Preuve : gate vert ; `python scripts/quality/check-image-pins.py` vert.

## 5. Preuve en CI

- [ ] 5.1 **(manuelle)** [AUD-DEP-12, AUD-DEP-13] À la PR : tous les checks exigés verts ; relever que `Verify (ubuntu-24.04)` tire ses images sans erreur de quota ; lancer `docker-upgrade-qualification.yml` et `code-audit.yml` par `workflow_dispatch` ; noter les run ids dans `SUIVI.md`.
- [ ] 5.2 **(manuelle)** [AUD-DEP-05] Au prochain candidat de release : relever que `release-windows.yml` et `intellij-plugin-release.yml` construisent le plugin par le wrapper ; noter les run ids. Tant que ce n'est pas fait, AUD-DEP-05 reste « implémenté, preuve de release en attente ».

## 6. Clôture

- [ ] 6.1 `docs/developer/supply-chain.md` et `docs/developer/quality-gates.md` : règles nouvelles du gate, hypothèse « dépôt public » pour `persist-credentials`, liste fermée Docker Hub. Rejouer `check-current-docs.py`.
- [ ] 6.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-12, AUD-DEP-05, AUD-DEP-13 : statuts, décomptes corrigés, décision D4, run ids).
- [ ] 6.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/supply-chain.md`, `docs/TOOLCHAIN_POLICY.md`.

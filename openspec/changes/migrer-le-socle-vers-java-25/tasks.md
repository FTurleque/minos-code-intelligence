# Tasks

Ordre dans chaque section : d'abord le test ou le gate qui échoue (rouge attendu), puis la correction, puis la preuve. Chaque tâche porte l'identifiant du constat. Tout `-pl` s'accompagne de `-am` ; sous Windows, citer les arguments `-Dx.y=z`. Ne pas lancer `clean` à la racine sans le dire (il supprime le jar ombré utilisé ailleurs).

## 1. Décision et mesures préalables (AUD-DEP-01)

- [ ] 1.1 [AUD-DEP-01] Mesurer avant toute modification : dernier correctif et digests d'`eclipse-temurin:25` (JRE et JDK), date de fin de correctifs du JDK 24 et du JDK 25, lecture du bytecode 25 par les versions du POM de JaCoCo, SpotBugs, PIT, ArchUnit, maven-shade-plugin, et par scip-java du catalogue. Consigner les sources et les valeurs dans `design.md`.
- [ ] 1.2 [AUD-DEP-01] **Décision à obtenir** : D1 (migrer maintenant, recommandé, ou reporter avec risque daté). Consigner la réponse dans `design.md`. Si report : faire les sections 1 et 6 seulement, avec la version 24 comme source de vérité.
- [ ] 1.3 [AUD-DEP-01] Rédiger l'ADR qui remplace l'ADR 0005 (prochain numéro de `docs/adr/README.md`), passer 0005 en `Superseded`, ajouter une note de mise en œuvre aux ADR 0004, 0021 et 0027. Rejouer `python scripts/docs/check-current-docs.py`.

## 2. Gate de baseline Java (AUD-DEP-01)

- [ ] 2.1 [AUD-DEP-01] Rouge : cas ajoutés à l'auto-test de `scripts/quality/check-toolchain-baseline.py` sur arbres temporaires (workflow en retard, enforcer en retard, image en retard, politique sans date de fin de support, étape du plugin acceptée, `21` hors liste fermée refusé). **Preuve du rouge : les cas échouent avant l'extension du gate.**
- [ ] 2.2 [AUD-DEP-01] Étendre le gate (D2) ; liste fermée des workflows et jobs du plugin en Java 21. Preuve : auto-test vert, gate vert sur le dépôt réel, `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts.

## 3. Bascule des sites (AUD-DEP-01)

- [ ] 3.1 [AUD-DEP-01] Rouge : passer `maven.compiler.release` à 25 seul ; le gate de 2.2 doit nommer chaque site resté en 24. Corriger alors : enforcer `[25,26)`, les 15 `java-version: '24'` et les noms d'étapes des 10 workflows, les deux `FROM eclipse-temurin` (tag et digest mesurés en 1.1), le commentaire de `.github/dependabot.yml`. Rejouer `check-image-pins.py`, `check-workflow-pins.py`, `check-toolchain-baseline.py`.
- [ ] 3.2 [AUD-DEP-01] Gates et tests littéraux : `check-audit-remediation-v2.py` (regex de l'image), `test_check_image_pins.py` (le cas « major 25 » devient accepté, un cas « major 26 » refusé le remplace), `check-current-docs.py` (nom de l'étape de release), `docker/scripts/verify-run-configurations.ps1`. Prérequis public de `ScipIndexerCatalog` et golden `cli-json.golden` régénéré explicitement et relu. Rejouer `./mvnw -B -ntp -pl minos-app -am verify` et le scope JaCoCo du catalogue.
- [ ] 3.3 [AUD-DEP-01] Scripts à garde de version (liste du `design.md`, écart 2) : release, `MinosWindows.ps1`, `qualify-docker-upgrade.ps1`, scripts `m24`…`m27` rejoués par `historical-qualification.yml`. Établir d'abord ce que prouve `fixtures/java/java-24-smoke` avant d'y toucher (D4). Les `.ps1` restent en CRLF.
- [ ] 3.4 [AUD-DEP-01] Documentation courante (liste du `design.md`) : `TOOLCHAIN_POLICY.md` (D5), `AGENTS.md`, `openspec/config.yaml`, `README.md`, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/user/*`, `docs/developer/*`, `docs/architecture/*`, skill `minos-incident-triage`. Rejouer `check-current-docs.py` et `python scripts/docs/product-facts.py --check`. Ne pas modifier l'historique figé.

## 4. Qualification (AUD-DEP-01)

- [ ] 4.1 [AUD-DEP-01] Poste : `./mvnw -B -ntp verify` sous Windows avec `-Dminos.sandbox.tests.required=true` ; `python .claude/scripts/run_gates.py` ; `python scripts/quality/check-jacoco.py` (comparer `m24-polyglot-provider-platform` à `develop` avant d'investiguer). Consigner les compteurs.
- [ ] 4.2 [AUD-DEP-01] Indexation d'un projet Java par scip-java sous le JDK 25 hôte (fixture existante) : résultat identique à celui de `develop`, ou limitation déclarée.
- [ ] 4.3 [AUD-DEP-01] Runtime de l'installateur : comparer la liste de modules de `jdeps` avant et après, construire le candidat local (`build-local-windows-candidate.ps1`), exécuter `minos --version` et une indexation par le runtime embarqué.
- [ ] 4.4 **(manuelle)** [AUD-DEP-01] À la PR : `Verify (ubuntu-24.04)` et `Verify (windows-2022)` verts, classes de confinement 0 sauté ; lancer `code-audit.yml`, `docker-release-validation.yml`, `docker-upgrade-qualification.yml` (image 24 → image 25), `windows-installer.yml`, `windows-in-place-upgrade.yml` ; noter les run ids dans `SUIVI.md`.

## 5. Registre des risques (AUD-DEP-01)

- [ ] 5.1 [AUD-DEP-01] Ajouter au registre `docs/architecture/risks/register.md` la ligne « runtime Java hors support » : résolue par ce changement (avec la preuve de 4.4) ou ouverte avec échéance si D1 = report.

## 6. Clôture

- [ ] 6.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-01 : statut, décision D1, écarts, preuves, run ids).
- [ ] 6.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/TOOLCHAIN_POLICY.md`, index des ADR, notes de version.

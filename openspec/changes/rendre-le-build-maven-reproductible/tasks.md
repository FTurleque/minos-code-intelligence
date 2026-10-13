# Tasks

Ordre : mesure, puis rouge, correction, preuve. Toujours le wrapper ; tout `-pl` s'accompagne de `-am` ; sous Windows citer les arguments `-Dx.y=z`. Ne pas lancer `clean` à la racine sans le dire (il supprime le jar ombré utilisé ailleurs) : les doubles constructions se font dans des copies. Même bloc du POM racine que `migrer-le-socle-vers-java-25` : fusionner en série.

## 1. Mesure et décisions (AUD-DEP-11)

- [ ] 1.1 [AUD-DEP-11] Mesurer : versions effectivement résolues des plugins sans version (`./mvnw -B -ntp help:effective-pom`, par le wrapper) ; double construction du jar ombré au HEAD dans deux copies propres et liste des entrées qui diffèrent (dates, contenu) ; même comparaison entre un jar Linux et un jar Windows du même commit si les deux sont disponibles (artefacts de CI). Consigner dans `design.md` (lève les « non mesuré »).
- [ ] 1.2 [AUD-DEP-11] **Décisions à obtenir** : D1 (date du commit, recommandé, ou valeur fixe par release) et D4 (job sur les PR qui touchent un POM + release, recommandé, ou à chaque PR). Consigner dans `design.md`.

## 2. Plugins épinglés (AUD-DEP-11)

- [ ] 2.1 [AUD-DEP-11] Rouge : ajouter `requirePluginVersions` à l'enforcer **seul**. **Preuve du rouge : `./mvnw -B -ntp -N validate` échoue en nommant les plugins sans version.**
- [ ] 2.2 [AUD-DEP-11] `pluginManagement` de la racine : `maven-clean-plugin`, `maven-resources-plugin`, `maven-install-plugin`, `maven-deploy-plugin`, `maven-site-plugin` (et tout autre plugin nommé par 2.1) aux versions relevées en 1.1. Preuve : `./mvnw -B -ntp validate` vert sur le reactor ; `python scripts/architecture/check-module-boundaries.py` vert ; `./mvnw -B -ntp -pl minos-app -am verify` vert (aucun changement de comportement attendu : comparer le nombre de tests à `develop`).

## 3. Horodatage et preuve de reproductibilité (AUD-DEP-11)

- [ ] 3.1 [AUD-DEP-11] Rouge : `scripts/quality/check-reproducible-build.py` avec son `--self-test` (archives simulées : identiques → succès ; une entrée différente → échec nommant l'entrée ; dates d'entrées différentes → échec). Exécution réelle sur deux copies au HEAD. **Preuve du rouge : les SHA-256 du jar ombré diffèrent.**
- [ ] 3.2 [AUD-DEP-11] `project.build.outputTimestamp` selon D1 (valeur par défaut fixe dans le POM, date du commit transmise) ; traiter chaque entrée résiduelle listée en 1.1. Preuve : double construction identique sur le poste ; `ShadedJar*IT` verts ; `minos --version` inchangé.
- [ ] 3.3 [AUD-DEP-11] Transmettre la date du commit dans `scripts/release/build-windows-distribution.ps1` (`:196`, `:223`) et `scripts/ci/qualify-docker-release.sh` (`:28`). Preuve : dates des entrées du jar produit par chaque script égales à la date du commit ; `python scripts/release/check-supply-chain.py` vert.
- [ ] 3.4 [AUD-DEP-11] CI : auto-test câblé dans `invariants` ; job de double construction selon D4 (`package -DskipTests -pl minos-app -am`, actions épinglées, `timeout-minutes`, `persist-credentials: false`). Rejouer `python scripts/quality/check-ci-wiring.py`, `check-single-execution.py`, `check-workflow-pins.py` ; `yaml.safe_load`. Ne pas l'ajouter à `.github/required-checks.json`.
- [ ] 3.5 **(manuelle)** [AUD-DEP-11] À la PR : relever le résultat du job de double construction sous Linux et, si D5 le permet, comparer aux artefacts Windows ; noter les run ids et le verdict « identique entre plateformes ou non » dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 `docs/developer/supply-chain.md` : ce qui est reproductible (JAR, par plateforme), ce qui ne l'est pas (zip, installateur, images), commande de vérification indépendante. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-11 : statut, mesures 1.1, décisions D1 et D4, écarts).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/supply-chain.md`, `docs/developer/quality-gates.md`.

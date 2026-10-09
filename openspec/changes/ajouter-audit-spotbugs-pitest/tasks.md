# Tasks

Convention de traçabilité : chaque tâche cite `[« nom exact de l'exigence »]` (exigences de `specs/audit-qualite-code/spec.md`) puis sa validation observable. Tout `-pl` est accompagné de `-am`. Les journaux de build vont dans le répertoire temporaire de l'utilisateur, jamais dans le `target/` racine. Aucune tâche ne renomme ni ne déplace de code : aucun script `check-*.py` de chaînes littérales ni scope JaCoCo n'est concerné ; les gates de workflow, de documentation et de modules sont rejoués en 4.x.

## 1. Configuration Maven

- [x] 1.1 Centraliser les versions [« Le build habituel n'exécute ni SpotBugs ni PIT »] : propriétés `spotbugs.maven.plugin.version` (4.10.4.1), `spotbugs.version` (4.10.4), `pitest.maven.plugin.version` (1.30.0), `pitest.junit5.plugin.version` (1.2.3) et configuration commune dans le `pluginManagement` du `pom.xml` parent. Validation : le POM effectif de `minos-engine` sans profil ne contient aucun des deux plugins dans `build/plugins`.
- [x] 1.2 Profil `audit-spotbugs` [« SpotBugs produit des rapports exploitables et un contrôle bloquant explicite »] : plugin ajouté au build, but `spotbugs` lié à `verify`, `check` non lié, effort `Max`, seuil `Medium`, `failThreshold` `Medium`, rapports XML, SARIF et HTML, filtre `quality/spotbugs-exclude.xml`. Validation : le POM effectif avec le profil contient l'exécution `spotbugs-report` en phase `verify`.
- [x] 1.3 Profil `audit-mutation` [« PIT ne mute qu'un périmètre déclaré et ajustable sans modifier de POM »] : plugin ajouté sans liaison de phase, périmètre `targetClasses` / `targetTests`, JUnit 5 plugin, 2 fils, délais, tas, rapports HTML et XML, `-Duser.dir` à la racine. Validation : le POM effectif avec le profil contient `pitest-maven` sans exécution.

## 2. Vérification des outils

- [x] 2.1 SpotBugs sur tout le reactor [« SpotBugs produit des rapports exploitables… »] : `./mvnw -B -ntp -Paudit-spotbugs -DskipTests compile spotbugs:spotbugs` vert, rapports XML/SARIF/HTML présents pour chaque module (`minos-app` sous le `target/` racine). Résultat : 197 alertes `Medium`, aucune `High`.
- [x] 2.2 Contrôle bloquant [« …un contrôle bloquant explicite »] : `spotbugs:check` sur `minos-domain` échoue (1 alerte `Medium`) ; avec `-Dspotbugs.failThreshold=High` il réussit.
- [x] 2.3 PIT sur le périmètre initial [« PIT ne mute qu'un périmètre déclaré… », « Une analyse PIT sans mutation couverte n'est pas un succès »] : exécution verte, 73 mutants générés, 58 tués, 12 survivants, 3 sans couverture, 78 tests retenus, 889 exécutions de test ; `audit-report-summary.py pit --module minos-engine` réussit, et échoue pour `minos-domain` (rapport absent). Premier essai rouge diagnostiqué : le répertoire de travail (voir design D5).
- [x] 2.4 Surcharge du périmètre PIT sans modifier de POM [« …ajustable sans modifier de POM »] : relancé avec `-DtargetClasses=com.minos.hosted.HostedAuditChain` ; `mutations.xml` ne contient que cette classe (27 mutants). La forme PowerShell de la surcharge (deux classes) n'a pas été exécutée telle quelle.
- [x] 2.5 Liaison à `verify` du profil SpotBugs : `./mvnw -B -ntp -Paudit-spotbugs -pl minos-domain -am -DskipTests verify` vert, rapport rafraîchi. Un appel direct `spotbugs:check` sans profil résout aussi la configuration (propriétés par défaut du POM parent).

## 3. Documentation

- [x] 3.1 Rédiger `docs/quality/code-audit.md` [« Les commandes documentées correspondent à la configuration »] : rôles, versions, modules, profils, commandes PowerShell et bash, chemins des rapports, interprétation, faux positifs, coût, limites, préparation OpenSpec. Validation : chaque commande PowerShell a été exécutée ; les commandes bash, le workflow et la surcharge `-DtargetClasses` sont signalés comme non exécutés tant qu'ils le sont.
- [x] 3.2 Consigner séparément les constats de la première exécution dans `docs/quality/code-audit-constats.md` (alertes SpotBugs par module et par motif, mutants survivants de `HostedAuditChain` et `HostedAuthorizationService`, fragilité du répertoire de travail).
- [x] 3.3 Ajouter les liens depuis `docs/README.md`, `docs/developer/README.md` et `docs/developer/testing.md`.

## 4. Non-régression

- [x] 4.1 `./mvnw -B -ntp clean verify` sans profil : vert (2 271 tests, 0 échec, 0 erreur, 56 ignorés), aucune exécution de SpotBugs ni de PIT dans le journal. Lancé avant le déplacement des propriétés par défaut vers le bloc racine ; le POM effectif et les exécutions des profils ont été revérifiés après.
- [x] 4.2 `python scripts/quality/check-workflow-pins.py`, `check-single-execution.py` et son auto-test, `scripts/architecture/check-module-boundaries.py`, `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`, `scripts/quality/check-milestone-artifact-references.py` verts.
- [x] 4.3 `openspec validate ajouter-audit-spotbugs-pitest --strict` vert.

## 5. À décider ou à faire plus tard (hors de ce changement)

- [ ] 5.1 Exécuter les commandes bash sous Linux ou macOS et le workflow `code-audit.yml` (déclenchement manuel) pour les qualifier.
- [ ] 5.2 Décider du traitement des 197 alertes SpotBugs (corriger, fixer une base, ou exclure les faux positifs démontrés) avant tout contrôle bloquant en CI.
- [x] 5.3 Traiter les mutants survivants de `HostedAuditChain.verify` (changement séparé, tests d'abord). — Fait dans le changement `renforcer-tests-revelees-par-mutation` (H07, P1), commit `5b9ef15b`, archivé le 2026-10-09 ; les 4 gardes de `verify` restées vivantes sont des mutants équivalents (gardes du constructeur de `HostedTenantState`).
- [x] 5.4 Rendre `HostedProductionBoundaryTest` indépendant du répertoire de travail ; décider du sort de `-Duser.dir` dans le profil. — Décision du 2026-10-09 : `-Duser.dir` reste, c'est la convention de Surefire (`workingDirectory` à la racine) et de nombreux tests en dépendent (`fixtures/`, racine des références de caractérisation). `HostedProductionBoundaryTest` et `Golden` trouvent désormais seuls la racine (vérifié sous Surefire, et sous PIT sans `-Duser.dir` pour `HostedProductionBoundaryTest`).

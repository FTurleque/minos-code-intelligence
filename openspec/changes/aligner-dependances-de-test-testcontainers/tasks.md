# Tasks

## 1. Testcontainers

- [x] 1.1 Identifier la version 2.x de Testcontainers à retenir et la version de docker-java et de son transport qu'elle embarque (documentation et notes de version officielles) ; vérifier les versions de `httpclient5` et `httpcore5` embarquées. — 2026-10-08 : Testcontainers 2.0.5 est la dernière version (Maven Central) ; elle embarque docker-java 3.7.1, dernière version publiée, avec le transport `zerodep` qui ombre `httpclient5` 5.5.1 et `httpcore5` 5.3.6. Version attendue : un docker-java postérieur à 3.7.1 qui embarque `httpcore5` ≥ 5.4.3 et `httpclient5` ≥ 5.6.3.
- [x] 1.2 Remplacer `junit-jupiter` et `postgresql` 1.21.4 par `testcontainers-junit-jupiter` et `testcontainers-postgresql` 2.x (ou importer `testcontainers-bom`) dans `minos-bootstrap` et `minos-storage-postgresql` ; rejouer `check-module-boundaries.py` (dépendances de test autorisées) et les tests PostgreSQL avec `-Dminos.postgresql.tests.required=true`, sous Windows (Docker Desktop) et en CI Ubuntu ; rejouer `check-jacoco.py` sans `--skip-scope m30-postgresql-pgvector`. — Fait sous Windows le 2026-10-08 (BOM `testcontainers-bom` 2.0.5, `org.testcontainers.postgresql.PostgreSQLContainer` ; `verify` des deux modules vert : 80 et 117 tests). Restent : CI Ubuntu, `check-jacoco.py` ; `check-module-boundaries.py` n'existe pas dans `scripts/quality`. — CI Ubuntu : `clean verify -Dminos.postgresql.tests.required=true` et `check-jacoco.py` sans exclusion verts, run `pr-ci.yml` 37923977474 (PR #376, tête `038173aa`, contient toute la pile).
- [x] 1.3 `.github/dependabot.yml` : groupe Testcontainers (cœur et modules) ; rejouer `check-workflow-pins.py`. — groupe `testcontainers` (`org.testcontainers:*`) avant les groupes par type ; `check-workflow-pins.py` vert.

## 2. slf4j

- [x] 2.1 `slf4j-api` dans `dependencyManagement` (`slf4j.version`) ; `dependency:tree` sans version divergente ; vérifier que le jar ombré embarque toujours une seule version. — `slf4j-api` et `slf4j-simple` gérés par `slf4j.version` ; `dependency:tree` du réacteur : 2.0.20 partout.
- [x] 2.2 Décision sur `dependencyConvergence` (Enforcer) ; si oui, l'ajouter et corriger les divergences qu'elle révèle. — Non (décision de l'utilisateur, 2026-10-09) : `slf4j-api` converge déjà par `dependencyManagement` ; la règle s'appliquerait à tout le réacteur et ferait échouer le build sur le moindre écart transitif, pour un gain faible sur des dépendances de test.

## 3. Conteneurs orphelins sous Windows (H24)

- [x] 3.1 Établir pourquoi `TESTCONTAINERS_RYUK_DISABLED=true` est fixé par le profil `windows-docker-desktop-testcontainers` (`minos-storage-postgresql/pom.xml:132`, `minos-bootstrap/pom.xml:138`) et le retirer si Docker Desktop le permet ; sinon, étiqueter les conteneurs par exécution et supprimer ceux d'une exécution morte au démarrage de la suite. — Cause : le profil passait le tube Windows dans `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, chemin que Ryuk monte dans son conteneur Linux ; Ryuk ne démarrait pas (attente « Started » expirée). Avec `/var/run/docker.sock`, Ryuk démarre sous Docker Desktop ; `TESTCONTAINERS_RYUK_DISABLED` retiré des deux modules.
- [x] 3.2 Vérifier : un fork Surefire tué pendant `PostgresSchemaMigratorTest` ne laisse aucun conteneur. — 2026-10-08 : fork Surefire tué pendant `PostgresSchemaMigratorTest`, conteneur pgvector actif ; 30 s plus tard, aucun conteneur Testcontainers ne reste.

## 4. Validation

- [ ] 4.1 `-Paudit-dependency-check-tests aggregate` : plus aucune des quatre CVE de H03 (ou version attendue consignée) ; `audit-report-summary.py dependency-check` sans exception.

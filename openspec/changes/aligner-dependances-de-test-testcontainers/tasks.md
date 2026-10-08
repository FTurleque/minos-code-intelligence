# Tasks

## 1. Testcontainers

- [ ] 1.1 Identifier la version 2.x de Testcontainers à retenir et la version de docker-java et de son transport qu'elle embarque (documentation et notes de version officielles) ; vérifier les versions de `httpclient5` et `httpcore5` embarquées.
- [ ] 1.2 Remplacer `junit-jupiter` et `postgresql` 1.21.4 par `testcontainers-junit-jupiter` et `testcontainers-postgresql` 2.x (ou importer `testcontainers-bom`) dans `minos-bootstrap` et `minos-storage-postgresql` ; rejouer `check-module-boundaries.py` (dépendances de test autorisées) et les tests PostgreSQL avec `-Dminos.postgresql.tests.required=true`, sous Windows (Docker Desktop) et en CI Ubuntu ; rejouer `check-jacoco.py` sans `--skip-scope m30-postgresql-pgvector`.
- [ ] 1.3 `.github/dependabot.yml` : groupe Testcontainers (cœur et modules) ; rejouer `check-workflow-pins.py`.

## 2. slf4j

- [ ] 2.1 `slf4j-api` dans `dependencyManagement` (`slf4j.version`) ; `dependency:tree` sans version divergente ; vérifier que le jar ombré embarque toujours une seule version.
- [ ] 2.2 Décision sur `dependencyConvergence` (Enforcer) ; si oui, l'ajouter et corriger les divergences qu'elle révèle.

## 3. Conteneurs orphelins sous Windows (H24)

- [ ] 3.1 Établir pourquoi `TESTCONTAINERS_RYUK_DISABLED=true` est fixé par le profil `windows-docker-desktop-testcontainers` (`minos-storage-postgresql/pom.xml:132`, `minos-bootstrap/pom.xml:138`) et le retirer si Docker Desktop le permet ; sinon, étiqueter les conteneurs par exécution et supprimer ceux d'une exécution morte au démarrage de la suite.
- [ ] 3.2 Vérifier : un fork Surefire tué pendant `PostgresSchemaMigratorTest` ne laisse aucun conteneur.

## 4. Validation

- [ ] 4.1 `-Paudit-dependency-check-tests aggregate` : plus aucune des quatre CVE de H03 (ou version attendue consignée) ; `audit-report-summary.py dependency-check` sans exception.

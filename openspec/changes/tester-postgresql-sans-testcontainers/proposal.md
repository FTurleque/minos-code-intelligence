# Proposal

## Why

Le risque R-27 (`docs/architecture/risks/register.md:23`, constat AUD-DEP-14 / MINOS-AUD-H03) porte quatre CVE
(CVE-2026-71290, CVE-2026-64607, CVE-2026-54399, CVE-2026-54428) de `httpclient5` 5.5.1 et `httpcore5` 5.3.6,
recopiés dans `docker-java-transport-zerodep` 3.7.1 que Testcontainers 2.0.5 instancie en dur. Aucune montée ne
les corrige : docker-java 3.7.1 est la dernière version et une version imposée par `dependencyManagement` ne remplace
pas le client recopié. Le risque est accepté jusqu'au 2027-01-15 ; sa révision n'a pas d'issue tant que
Testcontainers reste dans l'arbre.

Testcontainers ne sert qu'à démarrer un PostgreSQL/pgvector pour les tests d'intégration de `minos-storage`, par une
seule classe, `PostgresTestSupport`. Le retirer et fournir le serveur de l'extérieur (conteneur démarré par le job CI
ou par un script local) supprime la source des quatre CVE au lieu de les accepter, et retire du même coup la
politique de tirage `PrePulledImagesOnlyPullPolicy` et l'option `--testcontainers-images`, qui n'existaient que pour
borner Testcontainers.

## What Changes

- Les tests PostgreSQL se connectent à un serveur **fourni de l'extérieur**, désigné par trois paramètres (URL JDBC,
  utilisateur, mot de passe) ; ils ne démarrent plus aucun conteneur eux-mêmes.
- Chaque classe de test PostgreSQL travaille dans **sa propre base**, créée avant la classe et supprimée après ; aucun
  résultat ne dépend de l'ordre d'exécution des classes.
- Le mode requis (`-Dminos.postgresql.tests.required=true`) reste la règle du job Linux : paramètres absents, serveur
  injoignable ou droit `CREATEDB` manquant **font échouer** la classe. Hors mode requis, les tests sont sautés et
  comptés comme tels par Surefire, avec la raison.
- La CI (`pr-ci.yml` `verify` sous Linux, `code-audit.yml` `mutation`) démarre le conteneur pgvector épinglé par une
  étape du job, avec un mot de passe **généré par le job** et masqué, jamais écrit dans le workflow ; le job Windows
  ne démarre aucun PostgreSQL et rien n'y change.
- Nouveau script `scripts/dev/start-test-postgres.sh` et `scripts/dev/start-test-postgres.ps1` : démarre le même
  conteneur, lié à `127.0.0.1`, attend son healthcheck et affiche les paramètres à passer à `mvnw`.
- Retirés : Testcontainers du `pom.xml` racine (propriété, BOM) et des POM de `minos-storage` et `minos-bootstrap`
  (dépendances, profil `windows-docker-desktop-testcontainers`), `PrePulledImagesOnlyPullPolicy` et son test, les
  variables `TESTCONTAINERS_*` des workflows, l'option `--testcontainers-images` de `check-image-pins.py`, la règle
  `TESTCONTAINERS_GUARD_ENV` de `check-workflow-pins.py`, le groupe Dependabot `testcontainers`.
- L'étape « Pull the Testcontainers images, then log out of Docker Hub » devient « Pull the PostgreSQL test image,
  then log out of Docker Hub » : l'image pgvector vient toujours de Docker Hub. `DOCKER_LOGIN_KEPT` ne change pas
  (aucun des deux jobs n'y figure).
- `mvnw dependency:tree` ne contient plus ni `org.testcontainers` ni `com.github.docker-java`.

Aucune surface publique ne change ; aucun code de production n'est modifié.

## Capabilities

### New Capabilities

Aucune : les tests d'intégration PostgreSQL et leur mode requis sont déjà décrits par `persistance-snapshots`
(scénario « Store PostgreSQL, sous Linux seulement ») ; créer une capacité « harnais de test » dupliquerait ce contrat.

### Modified Capabilities

- `persistance-snapshots` : exigences ajoutées sur le serveur PostgreSQL de test fourni de l'extérieur (mode requis
  fail-closed, saut compté, isolation par base, mot de passe généré, arbre de dépendances sans Testcontainers ni
  docker-java, script local) ; le scénario « Store PostgreSQL, sous Linux seulement » de « Les résultats des requêtes
  sont inchangés » est réécrit (il présume Docker démarré par les tests).
- `frontieres-de-modules` : le scénario « Profils existants » de « Les règles de mise en page de build valent aussi
  dans un profil » nomme le profil `windows-docker-desktop-testcontainers`, qui disparaît.

Choix de `persistance-snapshots` plutôt que `audit-qualite-code` ou `chaine-de-livraison` : le comportement observable
qui change est celui des tests d'intégration du backend PostgreSQL (où ils trouvent leur serveur, quand ils échouent
ou se sautent) ; `chaine-de-livraison` porte ce que les livrables redistribuent (Testcontainers n'est pas livré) et
`audit-qualite-code` les outils d'analyse, dont le rapport Dependency-Check n'est ici qu'une preuve.

## Hors périmètre

- R-28 et tout autre constat ; `ETAT-2026-10-13.md` et `findings.json`.
- Le code de production de `minos-storage` (connexion, migrations, pool) : inchangé.
- Le PostgreSQL géré par Docker pour les utilisateurs (`docker/compose-mcp.connected.yaml`) : seule sa valeur par
  défaut de `MINOS_POSTGRES_IMAGE` est relue, comme aujourd'hui.
- Exécuter les tests PostgreSQL sous Windows en CI : le scope JaCoCo `m30-postgresql-pgvector` reste qualifié sous
  Linux seulement (`--skip-scope` côté Windows).
- Les fichiers partagés `docs/architecture/risks/register.md`, `docs/project/audits/2026-10-10/SUIVI.md`,
  `docs/developer/supply-chain.md` : leur texte est proposé dans `design.md`, l'orchestrateur l'applique.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les quatre CVE viennent de `docker-java-transport-zerodep` 3.7.1 tiré par Testcontainers 2.0.5 | [Existant vérifié] | `docs/architecture/risks/register.md:23`, `pom.xml:62` |
| Seuls `PostgresTestSupport`, `PrePulledImagesOnlyPullPolicy` et son test importent `org.testcontainers` | [Existant vérifié] | `grep -rl org.testcontainers --include=*.java` (3 fichiers + une chaîne dans `ShadedJarCompositionRootIT.java:48`) |
| 33 classes, toutes dans `minos-storage`, étendent `PostgresTestSupport` | [Existant vérifié] | `grep -rl "extends PostgresTestSupport"` |
| `minos-bootstrap` déclare Testcontainers sans aucun test qui l'utilise | [Existant vérifié] | `minos-bootstrap/pom.xml:75-89` ; aucun `PostgresTestSupport` ni `org.testcontainers` sous `minos-bootstrap/src` |
| Un `services:` GitHub ne peut pas porter l'image (seconde source refusée) ni un mot de passe généré par le job | [Existant vérifié] / [Décision proposée] | `scripts/quality/check-image-pins.py:11-13,169` ; voir design D2 |
| Démarrage du conteneur CI par une étape `docker run` au lieu de `services:` | [Proposition] | design D2 ; écart à la décision du propriétaire |
| Mot de passe lu en propriété système, avec repli sur une variable d'environnement | [Proposition] | design D3 |
| Une base par classe de test | [Décision] (propriétaire), forme retenue en design D4 | demande du propriétaire |

## Impact

- **Modules du reactor** : `minos-storage` (tests seulement, POM), `minos-bootstrap` (POM seulement), racine (`pom.xml`).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun (outillage de test ; aucune décision d'architecture du produit).
- **Gates à rejouer** : `check-image-pins.py` et `test_check_image_pins.py`, `check-workflow-pins.py` et
  `test_check_workflow_pins.py`, `check-ci-wiring.py`, `check-single-execution.py`, `scripts/docs/check-current-docs.py`,
  `check-module-boundaries.py`, `check-jacoco.py` (scope `m30-postgresql-pgvector`), `python .claude/scripts/run_gates.py`.
- **Plateformes** : Linux exécute les tests PostgreSQL en mode requis ; Windows les saute (comptés) sans démarrer de
  conteneur. Le script local existe en `.sh` et `.ps1` (Windows PowerShell 5.1 compatible).
- **Documentation durable et diagrammes** : `docs/developer/testing.md`, `docs/developer/quality-gates.md`,
  `docs/developer/supply-chain.md` (partagé), dossiers `persistance-snapshots.md` et `recherche-semantique.md`,
  arc42 (02, 04, 07, 08, 12), `docs/developer/quality/code-audit-couverture.md`.

## Questions ouvertes

Bloquantes pour l'implémentation (pas pour la spécification) : D2 (`docker run` au lieu de `services:`) et D3 (repli
du mot de passe sur une variable d'environnement) s'écartent de la lettre des décisions du propriétaire ; voir
`design.md`, « Questions ouvertes ».

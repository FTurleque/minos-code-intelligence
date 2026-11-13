# Prompt : quatre changes OpenSpec en parallèle pour fermer l'audit et éliminer R-27, R-29 et R-30

> À coller dans une session Claude Code ouverte sur `N:\workspace-dev\minos-code-intelligence`, branche `develop` à jour (`git pull --ff-only`). La session joue le rôle d'**orchestrateur** : elle délègue à des sous-agents, intègre leur travail et me rend compte.

---

Tu orchestres quatre changes OpenSpec indépendants. Ils ferment le dernier constat ouvert de l'audit du 10/10/2026 (AUD-PERF-15), lèvent la dernière réserve de code d'AUD-SEC-04, et éliminent trois risques du registre (R-27, R-29, R-30). **R-28 (release non signée, AUD-DEP-06) est hors périmètre : on ne signe pas.**

Rédige tout en français. Le dépôt utilise le schéma OpenSpec `detailed-uml` (`openspec/config.yaml`), qui fait foi pour la forme des artefacts. Utilise les skills du dépôt : `openspec-propose` pour créer un change, `openspec-apply-change` pour l'implémenter, `minos-verify-local`, `minos-literal-gates`, `minos-security-checklist` et `minos-pr-flow`.

## 0. Lire avant tout (orchestrateur)

1. `docs/project/audits/2026-10-10/ETAT-2026-10-13.md`, sections « Suite au 9 novembre » et « Suite au 10 novembre ».
2. `docs/project/audits/2026-10-10/findings.json` : AUD-PERF-15, AUD-SEC-04 (`verification.reserve`), AUD-DEP-14 et AUD-DEP-03.
3. `docs/architecture/risks/register.md` : R-27, R-29 et R-30.
4. `docs/architecture/adr/` : DATA-0078 (gros snapshots), SEC-0074 et SEC-0079 (Java sous Windows, voie WSL2), et l'ADR d'origine du verrou de scip-java (cherche-le avec `grep -l "scip-java-classpath.lock" docs/architecture/adr`).
5. `openspec/specs/` : repère pour chaque change la capacité existante à **amender** (`persistance-snapshots`, `installation-des-providers`, `audit-qualite-code`, etc.). N'en crée une nouvelle que si aucune ne couvre le sujet, et justifie ce choix.

## 1. Les quatre changes

| # | Change (nom proposé) | Ferme | Fichiers dont il est propriétaire |
|---|---|---|---|
| A | `borner-les-gros-snapshots-au-tas` | AUD-PERF-15 | `minos-storage/**` (`SnapshotLimits`, caches de vues, stores fichier et PostgreSQL), `docker/Dockerfile.mcp*` (options de tas), `docker/compose-mcp*.yaml`, ADR DATA-0078 |
| B | `garder-les-chemins-de-jdk-hors-des-messages` | réserve d'AUD-SEC-04 | `minos-provider-scip/**/ScipJavaProjectJdk.java` et ses tests, `scripts/architecture/check-private-io.py` et son auto-test |
| C | `tester-postgresql-sans-testcontainers` | R-27, AUD-DEP-14 | `pom.xml` (Testcontainers), `minos-storage/src/test/**` (`PostgresTestSupport`, `PrePulledImagesOnlyPullPolicy`), `.github/workflows/pr-ci.yml` et `code-audit.yml` (étapes PostgreSQL et Docker Hub), `scripts/quality/check-image-pins.py` (`--testcontainers-images`), `scripts/quality/check-workflow-pins.py` (`DOCKER_LOGIN_KEPT`), un script de démarrage local |
| D | `forcer-guava-dans-le-classpath-de-scip-java` | R-29, et préparation de R-30 | `minos-provider-scip/src/main/resources/**/scip-java-classpath.lock.json`, `embedded-tools.json`, le script qui génère le verrou, `scripts/quality/embedded-tools-advisory-waivers.json`, l'ADR du verrou |

**Fichiers partagés.** Aucun sous-agent ne les modifie : l'orchestrateur s'en charge seul, à l'intégration (§ 4).
- `docs/architecture/risks/register.md`
- `docs/project/audits/2026-10-10/SUIVI.md`
- `docs/developer/supply-chain.md`

Chaque sous-agent écrit dans son `design.md` le texte exact qu'il propose pour ces fichiers. Un sous-agent qui a besoin de toucher un fichier hors de sa colonne s'arrête et le signale, sans le modifier.

### A. AUD-PERF-15 : borner les gros snapshots au tas

**État au 10/11.**
- `SnapshotLimits.java:32` fixe le cache de vues à 512 Mio, sans regarder `Runtime.maxMemory()`.
- Une vue maximale, 400 Mio × 125 %, le remplit seule.
- Les chargements de vue simultanés ne sont pas bornés. DATA-0078 (l. 102-106) reconnaît que le tas par défaut du conteneur MCP, environ 768 Mio, ne tient pas deux chargements.
- Sous PostgreSQL, le pilote a déjà chargé la ligne `bytea` en mémoire quand `copyBounded` s'applique.

**Attendu.**
1. **Admission au poids.** Un chargement de vue non mise en cache réserve son poids estimé avant décodage, sur un budget global. Si le budget est épuisé, il attend un temps borné puis échoue avec une erreur explicite et publique (« mémoire insuffisante pour charger ce snapshot »), jamais avec un `OutOfMemoryError`.
2. **Plafond du cache dérivé du tas** : `min(512 Mio, f × maxMemory)`. La fraction `f` est justifiée par une mesure et réglable. Une vue plus lourde que le budget est refusée à l'admission, avec un message qui donne le réglage à changer.
3. **Tas explicite dans les images MCP** : `-XX:MaxRAMPercentage` (ou `-Xmx`) dans `Dockerfile.mcp*` ou `JAVA_TOOL_OPTIONS` des compose. La valeur est cohérente avec `mem_limit`.
4. **PostgreSQL** : lire la taille de la ligne (`octet_length`) **avant** de la rapatrier, et refuser au-delà du plafond sans allouer.
5. **Tests.** Deux chargements simultanés d'un gros snapshot sur un petit tas : le second attend ou échoue proprement. Un refus d'admission. Un refus PostgreSQL sur ligne trop grosse, sans allocation. Utilise `LargeSnapshotMeasurement` et ses équivalents PostgreSQL.
6. **Amendement de DATA-0078** : les mesures, la fraction choisie, la suppression du risque reconnu.

**Critère de sortie.** Aucun chemin de chargement de vue n'alloue au-delà du budget. Le test de simultanéité passe, avec une JVM de test lancée en `-Xmx` réduit.

### B. Réserve d'AUD-SEC-04 : chemins de JDK dans les messages

**État au 10/11.** `ScipJavaProjectJdk.java:68` (`"JAVA_HOME does not contain javac: " + javaHome`) et `:90` (`IllegalStateException("JAVA_HOME does not point to a JDK containing javac: " + javaHome)`) exposent un chemin absolu. Le gate `message(Path)` ne les voit pas : ses suffixes (`check-private-io.py:142`) sont `Path|Root|Dir|Directory|File`, pas `Home`.

**Attendu, gate d'abord.**
1. Ajouter `Home` aux suffixes et prouver le gate rouge sur le dépôt réel. Donne le compte réel de tout ce qu'il trouve en plus.
2. Si c'est faisable sans faux positifs mesurés, couvrir aussi les expressions chaînées `+ x.getParent()`, `.normalize()`, `.resolve(…)` et `+ Path.of(x)`. Sinon, documente dans la docstring ce qui reste invisible.
3. Corriger chaque site avec `PublicErrorMessages.leafName(...)`, ou un message qui nomme le rôle (« JAVA_HOME ne désigne pas un JDK contenant javac »). La valeur reste dans le journal DEBUG si le diagnostic l'exige.
4. Un test par message corrigé, et des fixtures d'auto-test du gate : refusé, accepté par `leafName`, entrée de liste blanche périmée.

**Critère de sortie.** Le gate est vert. Une mutation qui réintroduit `+ javaHome` le rend rouge. `git grep -n "+ javaHome" -- "*.java"` ne renvoie aucun message.

### C. R-27 et AUD-DEP-14 : tester PostgreSQL sans Testcontainers

**Constat.** Les quatre CVE de R-27 viennent du client HTTP recopié dans `docker-java-transport-zerodep` 3.7.1, que Testcontainers 2.0.5 instancie en dur. Aucune montée ne les corrige. Or Testcontainers ne sert qu'à démarrer PostgreSQL et pgvector, par `minos-storage/.../PostgresTestSupport.java`. Les 38 fichiers de test qui l'utilisent passent tous par cette classe.

**Décisions déjà prises, à appliquer** (dis-moi seulement si l'une est impossible) :
- **CI** : PostgreSQL et pgvector démarrent comme conteneur de service GitHub Actions (`services:`), sur l'image déjà épinglée par digest, avec healthcheck. `PostgresTestSupport` lit l'URL JDBC, l'utilisateur et le mot de passe dans des propriétés système. Le mot de passe est généré par le job, jamais écrit en clair dans le workflow.
- **Mode requis** : `-Dminos.postgresql.tests.required=true` reste la règle sur le job Linux. Si l'URL est absente ou la base injoignable en mode requis, le job échoue. Hors mode requis, les tests sont sautés, et comptés comme tels.
- **Local** : un script `scripts/dev/start-test-postgres.(ps1|sh)` démarre le même conteneur, épinglé, sur un port local, et affiche les propriétés à passer à `mvnw`. Documente-le dans `docs/developer/testing.md`.
- **Isolation entre tests** : une base ou un schéma par classe de test, créé et supprimé par `PostgresTestSupport`. Aucune dépendance à l'ordre d'exécution.
- **Nettoyage** :
  - retirer Testcontainers du `pom.xml` (BOM et dépendances) ;
  - retirer `PrePulledImagesOnlyPullPolicy`, les variables `TESTCONTAINERS_*` des workflows et l'option `--testcontainers-images` de `check-image-pins.py` ;
  - retirer l'étape « Pull the Testcontainers images, then log out of Docker Hub » si plus rien ne tire d'image depuis le code du dépôt. Retire alors aussi le job de `DOCKER_LOGIN_KEPT` s'il y figure, et adapte `check-workflow-pins.py` et ses auto-tests ;
  - vérifier que `mvnw dependency:tree` ne contient plus ni `org.testcontainers` ni `com.github.docker-java`.
- **`code-audit.yml`** : le job de mutation reçoit le même conteneur de service si ses tests touchent PostgreSQL.

**Critère de sortie.**
- `mvnw -pl minos-storage -am verify` passe localement contre le conteneur du script.
- `dependency:tree` est sans Testcontainers ni docker-java.
- `check-image-pins.py`, `check-workflow-pins.py` et `check-ci-wiring.py` sont verts, avec leurs auto-tests.
- Le rapport `-Paudit-dependency-check-tests` ne contient plus les quatre CVE (à lancer seulement si l'outil tourne en local ; sinon, tâche CI à faire plus tard).

### D. R-29 (guava), et R-30 (minimatch) préparé

**R-29, état.** Le verrou `scip-java-classpath.lock.json` (70 jars, scip-java 0.13.2) contient guava 33.6.0-jre, touché par GHSA-xxph-c9ww-hj94 et corrigé en 33.7.2. Le registre dit « un jar du verrou ne se remplace pas seul ». C'est une règle du dépôt, pas une impossibilité : Coursier sait forcer une version (`--force-version`).

**Attendu.**
1. Écrire un ADR, ou amender celui du verrou : on autorise un **forçage nominatif et justifié** d'une dépendance transitive du verrou pour corriger un avis, à trois conditions. Le forçage est listé dans le catalogue avec sa raison. Il est vérifié par somme. Une indexation Java réelle le prouve.
2. Régénérer le verrou avec guava 33.7.2-jre forcé, par le script de génération du dépôt. Toutes les sommes SHA-256 sont recalculées, et `embedded-tools.json` est mis à jour.
3. Retirer la dérogation de `embedded-tools-advisory-waivers.json`.
4. **Preuve** :
   - les tests de `minos-provider-scip` qui chargent le classpath ;
   - une indexation Java réelle d'un petit projet Maven hors ligne sous bubblewrap, que la CI Linux sait faire. Localement, si WSL2 ou Linux est disponible ; sinon, tâche CI.
   - Si scip-java casse avec guava 33.7.2, arrête-toi et rapporte l'erreur : on garde alors R-29.

**R-30, préparation, sans code.** Rédige dans le `design.md` le texte d'une demande de correction de l'avis GHSA-g3hv-75wg-m249 pour le dépôt `github/advisory-database`. Elle demande que la plage affectée exclue minimatch 3.x. Les preuves sont celles de R-30 : le code vulnérable (`firstPhasePreProcess`, `secondPhasePreProcess` sous `optimizationLevel >= 2`) est absent de minimatch 3.1.5, vérifié sur l'archive du registre dont le sha512 est celui du verrou. Ajoute une tâche **manuelle** : « le propriétaire soumet la demande ». Ne soumets rien toi-même.

**Critère de sortie.** Le verrou contient guava 33.7.2-jre. La veille des outils embarqués ne déroge plus à GHSA-xxph-c9ww-hj94. L'indexation réelle est prouvée, ou laissée en tâche CI explicite.

## 2. Phase 1 : générer les quatre changes en parallèle

1. Lance **quatre sous-agents en parallèle**, un par change. Chacun reçoit :
   - sa section du § 1 ;
   - la liste de ses fichiers ;
   - la règle sur les fichiers partagés ;
   - le § 5.
2. Chacun crée son change avec le skill `openspec-propose`, selon le schéma `detailed-uml` :
   - **design** : une section « État vérifié au HEAD », qui revérifie chaque preuve `fichier:ligne` avant toute décision ;
   - **specs** : en `## MODIFIED` ou `## ADDED Requirements`, avec des scénarios GIVEN/WHEN/THEN vérifiables par une commande locale ou un test ;
   - **tasks** : chaque tâche porte l'ID qu'elle ferme (AUD-PERF-15, AUD-SEC-04, AUD-DEP-14/R-27, R-29, R-30). Ordre imposé : test ou gate rouge, correction, preuve. Les tâches qui exigent un runner ou le propriétaire sont marquées « manuelle ».
3. Quand les quatre ont fini, lance `openspec validate --all --strict`.
4. **Point d'arrêt unique.** Si un design soulève une décision qui n'est pas déjà tranchée ici, pose-moi toutes ces questions en une seule fois, puis attends ma réponse. Sinon, enchaîne sans attendre.

## 3. Phase 2 : implémenter les quatre en parallèle

1. Un **worktree git par change**, branché sur `develop` :
   - `perf/borner-les-gros-snapshots-au-tas` ;
   - `sec/garder-les-chemins-de-jdk-hors-des-messages` ;
   - `test/tester-postgresql-sans-testcontainers` ;
   - `deps/forcer-guava-dans-le-classpath-de-scip-java`.

   Utilise l'isolation par worktree des sous-agents si elle est disponible, sinon `git worktree add ../minos-wt-<x> -b <branche> develop`.
2. Lance **quatre sous-agents en parallèle**, un par worktree, avec le skill `openspec-apply-change`. Chacun :
   - suit ses tâches dans l'ordre, et prouve le rouge avant la correction ;
   - rejoue `minos-literal-gates` avant tout renommage ou déplacement ;
   - lance les tests Maven de ses modules (`mvnw -pl <module> -am test`, avec `JAVA_HOME` défini sur un JDK 21), puis ses gates et leurs auto-tests ;
   - commite sur sa branche avec des messages conventionnels en français, sans pousser ;
   - rend un compte-rendu : tâches faites, tâches manuelles restantes, commandes de preuve, fichiers partagés à modifier, avec le texte proposé.

   **Ressources.** Les quatre `mvnw` en parallèle se disputent le dépôt `~/.m2` et le CPU. Lance les builds Maven avec `-o` après un premier `dependency:go-offline` fait par l'orchestrateur. S'il le faut, sérialise les phases Maven les plus lourdes : un seul `verify` complet à la fois.

## 4. Phase 3 : intégrer (orchestrateur seul)

1. Ordre d'intégration, du moins au plus risqué : **B, D, A, puis C**. Après chaque branche :
   - `git rebase develop` (ou rebase sur la précédente) ;
   - les gates Python (`scripts/run_gates.py --fast` s'il existe) ;
   - les tests des modules touchés.

   Si un conflit porte sur un fichier qui n'est pas partagé, c'est un défaut du découpage : arrête-toi et rapporte-le.
2. Applique toi-même les modifications des fichiers partagés, à partir des textes proposés :
   - **`register.md`** :
     - R-27 passe dans les risques résolus (comme R-26), avec la preuve `dependency:tree` ;
     - R-29 passe en résolu si la phase D l'a prouvé ;
     - R-30 reste accepté, avec une ligne « demande de correction de l'avis préparée, en attente de soumission par le propriétaire » ;
   - **`supply-chain.md`** : la règle de forçage nominatif (D) et la fin de Testcontainers (C) ;
   - **`SUIVI.md`** : une ligne par constat ou risque, avec statut, branche, commit et commande de preuve.
3. Pour finir, sur une branche d'intégration locale qui empile les quatre : un `mvnw verify` complet sous JDK 21 (environ 20 minutes), avec PostgreSQL démarré par le script de C et `-Dminos.postgresql.tests.required=true`.

## 5. Contraintes communes

- **Aucune CI déclenchée et aucun push sans mon accord.** Montre-moi le diff de chaque branche avant d'ouvrir les quatre PR. Une PR par change, base `develop`, ouvertes dans l'ordre B, D, A, C.
- Ne modifie pas `ETAT-2026-10-13.md` ni `findings.json` : l'auditeur les mettra à jour après vérification.
- Ne touche à aucun autre constat ni à R-28.
- Aucun secret en clair : mot de passe PostgreSQL de test généré, jeton Docker Hub jamais affiché.
- Surfaces publiques additives seulement. Les goldens de caractérisation restent identiques à l'octet ; sinon, explique pourquoi.
- Windows **et** Linux : le job Windows ne lance pas PostgreSQL. Vérifie que le retrait de Testcontainers n'y casse rien : tests sautés et comptés, scope JaCoCo `m30-postgresql-pgvector` toujours exclu côté Windows.

## 6. Rendu final (court)

- Pour chaque change : son état (implémenté ou bloqué), la commande qui prouve la fermeture, et ses tâches manuelles.
- Le résultat du `verify` complet.
- Les quatre diffs prêts à pousser, et la liste des PR à ouvrir.
- Le texte de la demande pour l'avis GHSA-g3hv-75wg-m249, prêt à soumettre.

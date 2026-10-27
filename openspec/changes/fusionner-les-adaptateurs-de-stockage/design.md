# Design

## Context

L'ADR 0068 § 4 décide de réunir les adaptateurs de stockage dans `com.minos:minos-storage`. Cette décision est acceptée et reste à mettre en œuvre.

- Les implémentations gardent leurs packages : `com.minos.storage.local.*` et `com.minos.storage.postgresql.*`.
- Les codecs partagés rejoindront `com.minos.storage.codec` avec SH-03.
- Les ports et la sélection du backend restent dans le moteur et dans `minos-bootstrap`.
- `local` et `postgresql` ne dépendent pas l'un de l'autre.

SH-02 réalise la fusion. SH-01, qui devait fournir la baseline et la cartographie, n'a jamais été fait : la cartographie est reprise ci-dessous, dans « État vérifié au HEAD » (décision D1).

## État vérifié au HEAD `560a2fa9`

Les constats ont été relus fichier par fichier le 2026-10-26, depuis un worktree propre sur `origin/develop`.

**Les deux modules**

| Module | Fichiers `src/main` | Fichiers `src/test` | Dépendances (POM) | Particularités |
|---|---|---|---|---|
| `minos-storage-local` | 31 | 92 | `minos-domain`, `minos-engine`, test-jar du moteur (`test`) | Surefire `additionalClasspathElements` → `src/test/platform-access-control` (`pom.xml:48`) ; aucun service de production |
| `minos-storage-postgresql` | 20 | 45 | `minos-domain`, `minos-engine`, **`minos-storage-local`** (`pom.xml:31`), `org.postgresql:postgresql` 42.7.14, Jackson (databind, jdk8, jsr310), Testcontainers et `slf4j-simple` (`test`), test-jar du moteur | Produit un test-jar (`PostgresTestSupport`) ; même `additionalClasspathElements` (`pom.xml:109`) ; profil `windows-docker-desktop-testcontainers` ; service `META-INF/services/com.minos.storage.StorageBackendProvider` → `PostgresStorageBackendProvider` |

Les deux arbres ne partagent aucune classe ni aucun package, que ce soit en production ou en test. Un seul chemin de fichier existe dans les deux : `src/test/platform-access-control/META-INF/services/com.minos.io.PlatformAccessControl`, au contenu identique (`com.minos.testsupport.IcaclsTestAccessControl`).

**Consommateurs** (une dépendance sans portée explicite est en `compile`)

| Consommateur | `minos-storage-local` | `minos-storage-postgresql` | Action |
|---|---|---|---|
| `minos-bootstrap` | `compile` (`pom.xml:46-49`) | `test` (jar `:69-71`, test-jar `:75-78`) | `minos-storage` en `compile`, plus son test-jar en `test`. **Le cloisonnement Maven de PostgreSQL est perdu** : il est remplacé par la règle de packages R1 et le test de chargement T1 (décision D3). |
| `minos-provider-scip` | `compile` (`pom.xml:29-31`) ; seul `FileSymbolSnapshotStore` est utilisé (A13) | — | `minos-storage` en `compile` ; règle R2 ; couple A13 `(minos-provider-scip, minos-storage)` |
| `minos-app` | `compile` (`pom.xml:20`) | `compile` (`pom.xml:24`) | une seule dépendance `minos-storage` |
| reactor `pom.xml` | module `:27` | module `:31` | un seul module `minos-storage` |
| `minos-mcp/pom.xml:54` | commentaire | — | commentaire mis à jour |

**Couplage réel entre les deux backends (E04, point 1)**

- Dans le sens `local` → `postgresql` : aucune référence.
- Dans le sens `postgresql` → `local`, en production, 3 fichiers nomment 4 types, ce qui correspond exactement à A13 (`check-module-boundaries.py:571-576`) :

| Fichier | Type nommé |
|---|---|
| `PostgresProjectRegistry.java:7`, utilisé en `:47-49` | `registry.ProjectPathMappingStore` |
| `PostgresCodeKnowledgeSnapshotStore.java:12-13` | `store.KnowledgeSnapshotCodecs`, `store.SnapshotCodec` |
| `PostgresCodeKnowledgeSnapshotStore.java:282` (nom qualifié) | `store.SnapshotIntegrityService` |
| `PostgresSnapshotPayloadCodec.java:4-5` | `store.KnowledgeSnapshotCodecs`, `store.SnapshotCodec` |

- En test, 2 fichiers en nomment 3 : `PostgresProjectRegistryLifecycleTest.java:7` et `PostgresSnapshotFormatCompatibilityTest.java:4-5`.
- Le premier type n'est pas un codec. L'ADR 0068 (`:22`) et SH-03 (`TASKS.md:58-70`) ne mentionnent que les codecs : SH-03 devra placer `ProjectPathMappingStore` hors de `local`.

**Primitives du pointeur (E04, point 2)**

`ActiveSnapshotRepository` (`:77-106`) appelle des primitives de portée paquet de `SnapshotBinaryCodecSupport` (`:72`, `:850`, `:861`, `:1091`, `:1099`), et ses plafonds `MAX_*` (`:31-33`) dupliquent ceux du codec (`:80-82`). La fusion ne change rien à ce point, qui relève de SH-03.

**Garde-fou de la racine de composition (E04, point 3)**

Au HEAD, la règle `check-module-boundaries.py:254-258` tient `minos-bootstrap` à l'écart de PostgreSQL à la compilation, avec `BOOTSTRAP_TEST_ONLY_ADAPTERS` (`:57`) et son auto-test `test_check_module_boundaries.py:224-226`. La Javadoc de `StorageBackendSelection.java:16` s'en prévaut.

- Aucune règle de sources n'interdit à une classe de bootstrap de nommer `com.minos.storage.postgresql`.
- `git grep` sur `jdbc|org.postgresql|storage.postgresql` dans `minos-bootstrap/src/main` ne renvoie rien.
- Aucun test ne prouve que le mode local ne charge aucune classe JDBC.

**Chemin de SH-10 et préfixes JaCoCo (E04, point 4)**

- `TASKS.md:224` cite `minos-engine/.../com/minos/io/StableFileSystemIdentity.java`, alors que le fichier réel est `minos-engine/src/main/java/com/minos/orchestration/StableFileSystemIdentity.java`.
- Les portées de `check-jacoco.py` qui citent le stockage sont des préfixes de classes : `:27-34`, `:62-68`, `:95`, `:210`, `:215`, `:237-243` et `:245-259`. Aucune ne pointe vers le rapport d'un module de stockage (`report_of`). Comme SH-02 garde les packages, ces portées restent valides.

**E02, contournements du garde**

`test_check_module_boundaries.py` passe ses 210 cas.

- Le contournement 1, le `groupId` résolu, est fermé (`:304`, `:311`, `:372`).
- Le contournement 3, le `<build>` dans un profil, est fermé (`:399`, `:406`).
- Le contournement 2, `build-helper-maven-plugin:add-source`, reste ouvert. `check_pom_layout` (`:135-155`) ne contrôle que `maven-compiler-plugin`, et `test_plugins_in_a_profile_are_accepted` (`:414`) accepte tout plugin dans un profil. Aucun POM n'utilise ce plugin. Seul `ModuleArchitectureTest.importEveryModule` (« compiled by both ») le rattraperait, et seulement dans un `verify`.

**E01 et B07 à B13**

- **E01** : le schéma PostgreSQL est en version 4 (`PostgresSchemaMigrator.java:10`), et `resumableRunId` n'est ni lu (`PostgresIndexStateStore.java:34`) ni écrit (`:84-88`). Le constat est ouvert et aucun changement OpenSpec ne le traite.
- **B07, B08, B09 et B13** sont fusionnés (commit `14596db2`). Les tests à garder verts sont `PostgresJdbcUrlPolicyTest`, `PostgresJdbcUrlPolicyDriverParityTest`, `PostgresJdbcUrlPolicyShapeTest` et `PostgresConnectionFactoryTest`.

**Lieux vivants qui nomment les anciens modules**

Ceux-ci sont hors des modules eux-mêmes, de l'historique et des archives :

| Lieu | Nature | Action |
|---|---|---|
| `scripts/architecture/check-module-boundaries.py` (21), `test_check_module_boundaries.py` (31), `test_check_private_io.py` (9) | tables et auto-tests | réécriture (D2, D4) |
| `minos-app/.../ModuleArchitectureTest.java` (10) | table `ALLOWED`, `ADAPTERS` | réécriture (A17) |
| `scripts/remediation/check-post-mne.py`, `check-mne.py`, `check-mnd.py`, `check-p0-p2.py`, `check-audit-remediation-v2.py` | chemins de fichiers littéraux | préfixe `minos-storage/` |
| `scripts/quality/check-hosted-control-plane-consistency.py`, `check-runtime-dynamic-consistency.py`, `check-semantic-retrieval-consistency.py`, `check-workflow-pins.py`, `test_check_image_pins.py` | chemins | préfixe `minos-storage/` |
| `scripts/docs/check-current-docs.py:18-19` | `POSTGRES_POM`, `POSTGRES_SUPPORT` | `minos-storage/...` |
| `scripts/quality/comment-language.json` | langue `en` pour les deux modules ; maximum de jalons 5 (`local`) | une entrée `minos-storage` (langue `en`, maximum 5) |
| `quality/spotbugs-baseline/minos-storage-local.xml` (42), `minos-storage-postgresql.xml` (8) | bases par module | une base `minos-storage.xml` (50 entrées), comparée par `audit-report-summary.py` |
| `scripts/m15/run-final.ps1` | commandes `-pl` | `-pl minos-storage` |
| `.github/CODEOWNERS`, `.github/workflows/code-audit.yml:72`, `quality/dependency-check-suppressions.xml` | chemins et commentaire | mis à jour |
| `minos-app/.../DuplicationGuardTest.java`, `JsonEscapeGuardTest.java`, `minos-bootstrap/.../RuntimeIntelligenceFileAdaptersTest.java` | chemins de sources lus par les tests | mis à jour |
| `minos-engine/.../ProjectPathMappings.java`, `minos-engine/src/test/.../DerivedTenantKeys.java` | commentaires | mis à jour |
| `docs/architecture/` (28), `docs/developer/` (6), `docs/user/java-api.md:370-374`, `docs/project/governance` | documentation courante | mise à jour ; l'historique et les archives restent tels quels |
| `openspec/specs/` : `frontieres-de-modules` (`:16`, `:98`, `:178`, `:412`), `persistance-snapshots` (`:192`, `:298`, `:306`, `:332`), `stockage-prive-et-secrets` (`:178`, `:350`, `:356`, `:390-400`), `audit-qualite-code` (`:149`) | exigences et scénarios | deltas `MODIFIED` dans ce changement (tâche 5.4) |

**Changements actifs qui recouvrent la fusion**

- `lire-le-registre-sans-ecrire` (19/23) touche `minos-storage-local` (`registry`, `store`).
- `retirer-les-jalons-des-identifiants` (13/14) touche `FileProjectFingerprintSnapshotStore`, mais sa tâche 2.3 est déjà faite.

## Goals / Non-Goals

**Objectifs**

- Un seul module de stockage dans le reactor.
- Aucune classe dupliquée, aucun package éclaté, le service présent une seule fois dans le JAR ombré.
- Le backend local démarre et fonctionne sans PostgreSQL ni Docker, sans charger de classe JDBC.
- Le cloisonnement que Maven assurait est repris par des règles de packages, chacune avec un contre-exemple refusé.
- Les deux suites de stockage, les consommateurs, les goldens et les gates restent verts. Aucun seuil n'est abaissé.

**Hors objectifs**

- Le codec commun (SH-03) et la fin du couplage entre backends.
- La reprise PostgreSQL (E01).
- SH-06, SH-10 et SH-12.
- Tout renommage de package ou changement de format.

## Decisions

### D1 — La cartographie de SH-01 est intégrée à ce design

La table « Lieux vivants » et la table des consommateurs tiennent lieu de cartographie. Les goldens ne sont pas régénérés : leur identité à l'octet est vérifiée par `A2SurfaceCharacterizationTest`.

- **Option retenue** : intégrer la cartographie, sans livrable SH-01 séparé.
- **Option écartée** : un changement SH-01 qui ne produirait que de la documentation.

### D2 — Les deux nœuds du garde deviennent un seul nœud `minos-storage`

| Table | Nouvelle valeur |
|---|---|
| `ALLOWED_DEPENDENCIES` | `"minos-storage": {"minos-domain", "minos-engine"}`. L'arête vers `minos-storage-local` disparaît. `minos-provider-scip`, `minos-bootstrap` et `minos-app` pointent vers `minos-storage`. |
| `PACKAGE_ROOTS_BY_MODULE` (A14) | `minos-storage` garde la racine `storage`, que possédaient les deux anciens modules. |
| `ARTIFACT_TO_MODULE`, `MODULES`, `ADAPTER_MODULES` | un seul nœud. |
| A15 (`UNREFERENCED_PRODUCTION_TYPES`), A18 (`LEGACY_SNAPSHOT_VIEW_USERS`) | même renommage. |
| `ModuleArchitectureTest.ALLOWED` et `ADAPTERS` | identiques à `ALLOWED_DEPENDENCIES`, comme l'exige A17. |
| `BOOTSTRAP_TEST_ONLY_ADAPTERS` | supprimée, avec sa règle `:254-258` et son auto-test : le nœud qu'elle protégeait n'existe plus. R1 la remplace (D3). |

### D3 — Règles de packages et test de chargement à la place du cloisonnement Maven

- **R1** : aucune source de production de `minos-bootstrap` ne nomme un type de `com.minos.storage.postgresql`, ni de `org.postgresql`. Le backend PostgreSQL ne s'enregistre que par `ServiceLoader`.
- **R2** : même règle pour `minos-provider-scip`.
- **T1** : un test de `minos-bootstrap` compose une application avec le backend local, sur un `MINOS_HOME` temporaire. Il vérifie alors, sur le chargeur de classes du test, qu'aucune classe `org.postgresql.*` ni `com.minos.storage.postgresql.*` n'est chargée. Il doit être rouge si l'on force le chargement de `PostgresStorageBackend`.

Ces règles s'ajoutent à la règle de sources A2, qui interdit déjà toute classe d'adaptateur dans les surfaces et dans l'application.

- **Option retenue** : R1, R2 et T1.
- **Option écartée** : garder un module PostgreSQL séparé. L'ADR 0068 § 4 a rejeté ce choix.

### D4 — Le couple A13 entre backends devient un cliquet de packages dans le module

- La table `BACKEND_TYPES_NAMED_ACROSS_PACKAGES` tient les 4 types que `com.minos.storage.postgresql` nomme dans `com.minos.storage.local`, avec leur nombre de fichiers : `registry.ProjectPathMappingStore: 1`, `store.KnowledgeSnapshotCodecs: 2`, `store.SnapshotCodec: 2`, `store.SnapshotIntegrityService: 1`.
- Ses règles sont celles d'A13 : elle ne fait que rétrécir, une entrée périmée est refusée, et un type ou un fichier nouveau est refusé.
- Le sens `local` → `postgresql` est interdit sans exception.
- SH-03 videra la table.

Le couple `(minos-provider-scip, minos-storage)` reste dans A13, avec `storage.local.store.FileSymbolSnapshotStore: 1`.

### D5 — Le dernier contournement d'E02 est fermé

`check_pom_layout` refuse `org.codehaus.mojo:build-helper-maven-plugin` dans tout `<build>`, à la racine comme dans un profil. Le test du refus et celui d'un plugin autorisé dans un profil sont ajoutés.

- **Option retenue** : une liste de refus nommée.
- **Option écartée** : une liste d'autorisation de tous les plugins. Elle demanderait de lister chaque plugin du dépôt, pour un gain qui ne vise qu'un contournement prouvé.

### D6 — Un seul POM qui reprend les deux anciens

- Les dépendances sont l'union des deux POM, moins l'arête interne.
- Le test-jar est produit, puisque `minos-bootstrap` réutilise `PostgresTestSupport`.
- Surefire reprend `argLine` avec `-Djdk.net.URLClassPath.disableClassPathURLCheck=true`, `redirectTestOutputToFile=false` et `additionalClasspathElements`.
- Le profil Windows Docker Desktop est repris.
- Dépendances que le backend local reçoit désormais par transitivité : JDBC, Jackson et le pilote PostgreSQL entrent dans le graphe de tout consommateur de `minos-storage`. Le JAR ombré les contenait déjà, puisque `minos-app` déclarait les deux modules. L'API Java embarquée et le SBOM sont inventoriés dans la tâche 4.3. On n'écrit nulle part que PostgreSQL serait absent du classpath local.

## Risques et coût

| Coût | Ampleur | Traitement |
|---|---|---|
| Couverture SonarCloud du nouveau code, par module, seuil 80 % | Les 51 classes de production déplacées comptent comme du nouveau code de `minos-storage`. Les classes PostgreSQL ne sont couvertes que là où les tests PostgreSQL s'exécutent : sous Linux en CI, avec `required=true`. | Mesurer la couverture par classe sur le rapport JaCoCo Linux avant la PR (tâche 6.2). Toute classe sous 80 % reçoit des tests dans la PR. **Non mesuré à ce jour.** |
| Tests déplacés avec leur classe | 137 fichiers | `git mv`, sans modification de contenu, sauf le fichier de service de test fusionné |
| Portées `check-jacoco.py` | Préfixes de packages inchangés | Rejouer `check-jacoco.py` sur le rapport agrégé |
| Gates littéraux | 5 scripts de remédiation et 5 de qualité lisent des chemins | Préfixe `minos-storage/`, puis rejouer chaque script |
| Bases SpotBugs | 42 + 8 entrées | Une seule base `minos-storage.xml` ; résultat attendu : 0 nouvelle, 0 disparue |
| `comment-language.json` | 2 entrées | Une seule entrée |
| Recouvrement avec les changements actifs | `lire-le-registre-sans-ecrire` | Décision Q5 |
| Historique Git | Les fichiers déplacés | `git mv` dans un commit qui ne fait que déplacer, pour que `git log --follow` et l'attribution des lignes suivent |

## Plateformes

- Ce changement n'ajoute aucun comportement propre à un système.
- Les tests PostgreSQL sont exigés sous Linux (`pr-ci.yml:380`). Sous Windows, ils passent par le profil Docker Desktop quand Docker est présent.
- T1 s'exécute sur les deux systèmes.

## Mise en œuvre (2026-10-27, base `033a59fa`)

- **Base de départ (tâche 1.1)** : `develop` à `033a59fa` contient E01 (PR #580) et `lire-le-registre-sans-ecrire`. Il reste à ce dernier quatre tâches de qualification Docker, qui ne touchent pas le stockage. Le `verify` complet de la tête de la PR #580 est vert sous Windows et sert de base.
- **T1** : le test compose l'application dans un chargeur de classes isolé. Ce chargeur définit lui-même chaque classe `com.minos.*` et `org.postgresql.*` et l'enregistre. Les classes déjà chargées par d'autres tests du même JVM ne faussent donc pas la mesure. La variante à chargement forcé prouve que la vérification échoue et nomme la classe.
- **Clé du verrou consultatif PostgreSQL** : la chaîne `'minos-storage-postgresql'` de `PostgresSchemaMigrator` reste inchangée. C'est la clé partagée entre versions de MINOS, pas un nom de module.
- **Dependency-Check** : la suppression du faux CPE PostgreSQL vise désormais l'artefact `minos-storage`. Elle n'est exercée que par le workflow `code-audit`, et n'a pas été rejouée en local faute de clé NVD.
- **Dépendances transitives** : `minos-bootstrap` et `minos-provider-scip` reçoivent le pilote PostgreSQL et Jackson à la compilation. Le JAR ombré de `minos-app` les contenait déjà. Il compte 10 234 entrées, sans doublon, et le service `StorageBackendProvider` n'y est déclaré qu'une fois.

### D7 — Le service PostgreSQL est déclaré par l'assemblage

Avant la fusion, `minos-bootstrap` ne voyait `minos-storage-postgresql` qu'en portée `test`, qui n'est pas transitive. Le
fournisseur PostgreSQL n'arrivait donc qu'avec `minos-app`, et les tests des surfaces (CLI, API, MCP) enregistraient une
doublure `postgresql` (`CloseCountingStorageProvider`) sans rencontrer le vrai fournisseur. Dans un seul module, ce
fournisseur atteint par transitivité le classpath de test des surfaces, et la sélection refuse à bon droit deux
fournisseurs du même identifiant (AUD-ARC-04).

- **Option retenue (Q6, 2026-10-27)** : `META-INF/services/com.minos.storage.StorageBackendProvider` vit dans
  `minos-app/src/main/resources`. Le JAR distribué le contient, une seule fois. Les tests de `minos-bootstrap` qui
  sélectionnent PostgreSQL le reçoivent de `minos-bootstrap/src/test/postgresql-backend`, ajouté par
  `additionalClasspathElements`, comme `platform-access-control`. Ce répertoire n'entre pas dans le test-jar des
  surfaces. Un hôte qui dépend de `minos-api` n'a que le backend local, comme avant la fusion.
- **Options écartées** :
  - réécrire les tests des surfaces sans doublure : le backend local ne fait rien à la fermeture, il n'y a rien à
    observer ;
  - un accesseur additif de fermeture : il change l'API publique pour un besoin de test ;
  - un classpath de test filtré dans Surefire : configuration fragile.

## Décisions du propriétaire (2026-10-26)

- **Q1 = A** : E01 (reprise PostgreSQL) est traité par un changement séparé, `persister-la-reprise-dans-postgresql`, livré avant SH-02. La tâche 1.1 vérifie sa fusion.
- **Q2** : cliquet de packages `BACKEND_TYPES_NAMED_ACROSS_PACKAGES` (D4) jusqu'à SH-03.
- **Q3** : le contournement `build-helper-maven-plugin` est fermé dans SH-02 (D5).
- **Q4** : JDBC et Jackson transitives dans `minos-storage`, avec R1, R2 et T1 comme preuves (D3, D6).
- **Q5** : `lire-le-registre-sans-ecrire` est livré avant la fusion ; la tâche 1.1 vérifie sa fusion.
- **Q6 (2026-10-27)** : le service PostgreSQL est déclaré par l'assemblage `minos-app` (D7).

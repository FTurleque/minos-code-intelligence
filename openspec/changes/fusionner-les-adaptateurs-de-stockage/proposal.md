# Proposal

## Why

Le constat **AUD-ARC-08** (audit du 10 octobre 2026) relève que les deux adaptateurs de stockage sont deux modules Maven. Pourtant, le backend PostgreSQL dépend du backend local et leurs frontières ne correspondent pas à l'ADR 0068 § 4. Cet ADR décide de réunir les deux modules en un seul artefact, `com.minos:minos-storage`, dont les packages restent séparés (`local`, `postgresql`, puis `codec` avec SH-03). Cette décision est acceptée et reste à mettre en œuvre. La tâche **SH-02** du plan de stockage hexagonal la réalise. C'est le premier lot de la phase 4 du chantier de dette figée.

L'audit d'octobre 2026 demandait de vérifier quatre entrées au HEAD avant SH-02 (`README.md` du plan, lignes 35 à 38). Elles ont été relues au HEAD `560a2fa9` :

- **MINOS-AUD-E04 reste ouvert.**
  - `PostgresProjectRegistry` utilise `ProjectPathMappingStore` du backend local (`PostgresProjectRegistry.java:7`, `:47-49`). Le couplage ne se limite donc pas aux codecs.
  - Le pointeur de snapshot partage des primitives de portée paquet avec le codec (`ActiveSnapshotRepository.java:77-106`, `SnapshotBinaryCodecSupport.java:72`).
  - Fusionner les modules retire le garde-fou « `minos-bootstrap` ne compile pas contre PostgreSQL » (`minos-bootstrap/pom.xml:69-78`, `BOOTSTRAP_TEST_ONLY_ADAPTERS`).
  - SH-10 cite un chemin faux pour `StableFileSystemIdentity` (`TASKS.md:224`).
- **MINOS-AUD-E02 est corrigé, sauf un contournement.**
  - Les règles A2 ont leurs auto-tests : 210 cas verts.
  - Le contournement par `build-helper-maven-plugin` reste ouvert : `check_pom_layout` ne contrôle que `maven-compiler-plugin`. Seul `ModuleArchitectureTest` le rattrape, et seulement dans un `verify`.
- **MINOS-AUD-E01 reste ouvert.** Le backend PostgreSQL ne persiste pas `resumableRunId` : le schéma est en version 4 et `PostgresIndexStateStore.java:34` et `:84-88` ignorent ce champ. Aucun changement OpenSpec ne le traite.
- **MINOS-AUD-B07, B08, B09 et B13 sont corrigés et fusionnés** (commit `14596db2`, changement archivé `durcir-configuration-postgresql-et-secrets`). SH-02 doit garder leurs tests verts. Le gate `check-post-mne.py:99-100` lit `PostgresJdbcUrlPolicy` par un chemin qui contient `minos-storage-postgresql`.

## What Changes

- **Un seul module `minos-storage`** remplace `minos-storage-local` et `minos-storage-postgresql`.
  - Les sources, les tests et les ressources sont déplacés par `git mv`, sans renommer de package. Les deux arbres n'ont aucune classe ni aucun package en commun.
  - Le seul fichier commun, le service de test `META-INF/services/com.minos.io.PlatformAccessControl`, devient un fichier unique.
- **Le POM du nouveau module réunit ceux des deux anciens** :
  - les dépendances : JDBC, Jackson, Testcontainers, le test-jar du moteur ;
  - le test-jar ;
  - Surefire avec `additionalClasspathElements` ;
  - le profil `windows-docker-desktop-testcontainers` ;
  - la propriété `minos.postgresql.tests.required` et son mode strict en CI.
- **Le reactor et les POM consommateurs pointent vers `minos-storage`** : `minos-bootstrap`, `minos-provider-scip`, `minos-app`, avec la même portée qu'avant. Le seul service de production, `StorageBackendProvider` → `PostgresStorageBackendProvider`, est déclaré par l'assemblage `minos-app`, qui seul apportait le backend PostgreSQL avant la fusion (décision Q6). Les tests de `minos-bootstrap` le reçoivent par un répertoire de classpath de test placé hors de leur test-jar.
- **Le garde des frontières suit la fusion** :
  - les deux nœuds deviennent `minos-storage` dans `MODULES`, `ADAPTER_MODULES`, `ARTIFACT_TO_MODULE`, `ALLOWED_DEPENDENCIES` (A2), `PACKAGE_ROOTS_BY_MODULE` (A14), A15, A18 et `ModuleArchitectureTest.ALLOWED` (A17) ;
  - l'arête `minos-storage-postgresql` → `minos-storage-local` disparaît ;
  - aucune arête application → adaptateur ni surface → adaptateur n'est ajoutée.
- **Trois règles de packages remplacent le cloisonnement Maven perdu** (E04) :
  - `minos-bootstrap` ne nomme aucun type de `com.minos.storage.postgresql` en production. L'enregistrement du backend passe par `ServiceLoader`.
  - `minos-provider-scip` ne nomme aucun type de `com.minos.storage.postgresql`.
  - Le cliquet A13 du couple `(minos-storage-postgresql, minos-storage-local)` devient un cliquet de packages entre backends, dans le module : `com.minos.storage.postgresql` peut nommer quatre types de `com.minos.storage.local`, pas plus, et `local` ne nomme rien de `postgresql`. SH-03 le videra.
- **Le contournement `build-helper-maven-plugin` est fermé** (reste d'E02) : le garde refuse ce plugin dans un `<build>`, à la racine ou dans un profil.
- **Test « le mode local ne charge aucune classe JDBC »** : une application composée avec le backend local ne charge aucune classe `org.postgresql.*` ni `com.minos.storage.postgresql.*`. Ce test remplace la preuve qu'apportait le découpage Maven.
- **Scripts, documentation et qualité** : sont mis à jour les gates littéraux qui lisent un chemin des anciens modules, les bases SpotBugs (réunies), les maximums de `comment-language.json`, `CODEOWNERS`, `code-audit.yml`, `dependency-check-suppressions.xml`, l'arc42 § 5, le document des dépendances généré, `java-api.md` et les guides développeur.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` :
  - exigences ajoutées : un seul module de stockage ; les packages des backends cloisonnés ; la composition sans PostgreSQL en compilation ; le refus de `build-helper-maven-plugin` ;
  - exigences reformulées : celles qui nomment les anciens modules.
- `persistance-snapshots`, `stockage-prive-et-secrets` et `audit-qualite-code` : les scénarios qui lancent `-pl minos-storage-local` ou `-pl minos-storage-postgresql` sont reformulés avec `-pl minos-storage`, sans changer de comportement.

## Hors périmètre

- **SH-03**, le codec commun : `com.minos.storage.codec`, les primitives de `SnapshotBinaryCodecSupport`, l'emplacement de `ProjectPathMappingStore` et la mise à zéro du cliquet entre backends. Seul le point 1 d'E04 y est tranché.
- **E01**, `resumableRunId` dans PostgreSQL, avec la migration de schéma v5 et le test de contrat commun : changement séparé (décision Q1).
- SH-06, SH-10 et SH-12. La correction du chemin de `StableFileSystemIdentity` dans `TASKS.md:224` (point 4 d'E04) relève de la documentation : elle est faite ici, mais sans rien changer à SH-10.
- Tout changement de package, de format persisté, d'identifiant de configuration (`local`, `postgresql`), de service SPI ou de signature Java publique.
- Des POM de relocation pour les anciennes coordonnées : aucun artefact n'est publié dans un dépôt Maven, puisque `deploy` ne s'exécute jamais (`pom.xml:138`).
- Les changements actifs qui touchent `minos-storage-local`, `lire-le-registre-sans-ecrire` et `retirer-les-jalons-des-identifiants`, sont à livrer ou à rebaser avant l'implémentation (décision Q5).

## Impact

- **Modules** :
  - `minos-storage-local` et `minos-storage-postgresql` sont supprimés, `minos-storage` est créé : le reactor passe de 14 à 13 modules ;
  - les POM de `minos-bootstrap`, `minos-provider-scip`, `minos-app` et du reactor changent ;
  - aucune classe de `minos-engine`, `minos-application` ni des surfaces ne change, à part deux commentaires qui citent l'ancien module (`ProjectPathMappings.java`, `DerivedTenantKeys.java`).
- **Surfaces publiques** :
  - la CLI, l'API Java, le MCP, NEXUS et IntelliJ ne changent pas ;
  - les coordonnées Maven internes changent, et `docs/user/java-api.md` (lignes 370 à 374) renvoie au nouveau module ;
  - le JAR ombré reçoit les mêmes classes et le même service ;
  - les goldens de caractérisation restent identiques à l'octet.
- **ADR** :
  - aucun nouvel ADR : la décision est celle de l'ADR 0068 § 4, qui reçoit sa note de mise en œuvre ;
  - sa section « Écarts constatés » (lignes 203-205) est mise à jour : fusion réalisée, arête PostgreSQL → local retirée, reactor à 13 modules ;
  - l'arête d'exécution AUD-ARC-11 `minos-engine -> minos-storage-postgresql` du document généré devient `-> minos-storage`.
- **Gates** :
  - `check-module-boundaries.py` (A2, A13, A14, A15, A17, A18) et son auto-test ;
  - `ModuleArchitectureTest` ;
  - `check-post-mne.py`, `check-mne.py`, `check-mnd.py`, `check-p0-p2.py` et `check-audit-remediation-v2.py` ;
  - `check-hosted-control-plane-consistency.py`, `check-runtime-dynamic-consistency.py` et `check-semantic-retrieval-consistency.py` ;
  - `check-current-docs.py`, `check-comment-language.py`, `check-jacoco.py` et `test_check_private_io.py`.
- **Plateformes** : PostgreSQL est qualifié sous Linux, où `minos.postgresql.tests.required=true` (`pr-ci.yml:380`). Sous Windows, Testcontainers passe par le profil Docker Desktop. Aucun comportement nouveau n'est propre à un système.
- **Constats** : AUD-ARC-08 (SH-02), MINOS-AUD-E04 (points 3 et 4, cliquet du point 1) et MINOS-AUD-E02 (dernier contournement).

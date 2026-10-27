## 1. Préalables

- [x] 1.1 (SH-02, Q1, Q5) Vérifier les préalables. `develop` doit contenir le changement E01 si Q1 = A, ainsi que `lire-le-registre-sans-ecrire`. Relever le SHA de départ. Rejouer `python .claude/scripts/run_gates.py` et `./mvnw -B -ntp verify` sur ce SHA, et consigner la baseline : tests exécutés, tests indisponibles, tests déjà rouges.

## 2. Garde : nouvelles règles, rouges avant la fusion

- [x] 2.1 (SH-02, E02, D5) **Rouge.** Écrire les cas « `build-helper-maven-plugin` refusé à la racine et dans un profil » et « plugin ordinaire accepté dans un profil » dans `scripts/architecture/test_check_module_boundaries.py`. Les deux cas de refus doivent échouer. **Correction** dans `check_pom_layout`. **Preuve** : l'auto-test et le garde réel passent.
- [x] 2.2 (SH-02, E04, D3) **Rouge.** Écrire les cas R1 et R2 sur des arbres temporaires : `minos-bootstrap` et `minos-provider-scip` nomment `com.minos.storage.postgresql` ou `org.postgresql` par import, import statique, joker ou nom qualifié, et une simple mention en commentaire reste acceptée. **Correction** : une règle de sources dans le garde. **Preuve** : l'auto-test passe, et le garde réel passe au HEAD.
- [x] 2.3 (SH-02, E04, D3) **Rouge.** Écrire le test T1 dans `minos-bootstrap`, la composition locale qui ne charge aucune classe PostgreSQL, avec sa variante à chargement forcé. Le test doit échouer si la vérification est absente. **Correction** : la vérification. **Preuve** : `./mvnw -B -ntp -pl minos-bootstrap -am test "-Dtest=<T1>" "-Dsurefire.failIfNoSpecifiedTests=false"` passe sous Windows, et passera sous Linux en CI.

## 3. Fusion des modules

- [x] 3.1 (SH-02, D6) Faire un commit qui ne contient que des déplacements. Avec `git mv`, placer `minos-storage-local/src` et `minos-storage-postgresql/src` sous `minos-storage/src`, et fusionner les deux fichiers `src/test/platform-access-control/META-INF/services/com.minos.io.PlatformAccessControl`. Vérifier par `git diff -M --stat` qu'aucun contenu ne change.
- [x] 3.2 (SH-02, D6) Écrire `minos-storage/pom.xml` : union des dépendances sans l'arête interne, test-jar, Surefire (`argLine`, `redirectTestOutputToFile`, `additionalClasspathElements`) et profil `windows-docker-desktop-testcontainers`. Supprimer les deux anciens POM. Dans le reactor `pom.xml`, remplacer les deux modules par un seul. Faire pointer `minos-bootstrap` (`compile`, test-jar en `test`), `minos-provider-scip` (`compile`) et `minos-app` vers `minos-storage`. Mettre à jour le commentaire de `minos-mcp/pom.xml:54`.
- [x] 3.3 (SH-02, D2, D4) Mettre à jour `check-module-boundaries.py` :
  - remplacer les deux nœuds par `minos-storage` dans `MODULES`, `ADAPTER_MODULES`, `ARTIFACT_TO_MODULE`, `ALLOWED_DEPENDENCIES`, `PACKAGE_ROOTS_BY_MODULE`, A15 et A18 ;
  - retirer `BOOTSTRAP_TEST_ONLY_ADAPTERS`, sa règle et son auto-test ;
  - transformer le couple A13 `(minos-storage-postgresql, minos-storage-local)` en table `BACKEND_TYPES_NAMED_ACROSS_PACKAGES`, avec ses 4 types et leur nombre de fichiers ;
  - le couple `(minos-provider-scip, minos-storage)` garde `storage.local.store.FileSymbolSnapshotStore: 1`.

  **Rouge** : écrire les cas « nouveau type local nommé », « sens local → postgresql » et « entrée devenue inutile ». **Correction**, puis **preuve** : l'auto-test et le garde réel passent.
- [x] 3.4 (SH-02, D2) Dans `ModuleArchitectureTest`, mettre à jour `ALLOWED` et `ADAPTERS`, que A17 exige identiques au garde. Mettre à jour les chemins de sources lus par `DuplicationGuardTest`, `JsonEscapeGuardTest` et `RuntimeIntelligenceFileAdaptersTest`. **Preuve** : `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest,A2SurfaceCharacterizationTest" "-Dsurefire.failIfNoSpecifiedTests=false"` passe, avec 12/12 goldens identiques à l'octet.
- [x] 3.5 (SH-02) Lancer les suites de stockage : `./mvnw -B -ntp -pl minos-storage,minos-bootstrap,minos-provider-scip -am test`. Les tests des correctifs B07, B08, B09 et B13 doivent être verts, notamment `PostgresJdbcUrlPolicyTest`, `PostgresJdbcUrlPolicyDriverParityTest`, `PostgresJdbcUrlPolicyShapeTest` et `PostgresConnectionFactoryTest`.

## 4. Gates littéraux, qualité et outillage

- [x] 4.1 (SH-02) Mettre à jour les chemins littéraux et rejouer chaque script :
  - `check-post-mne.py`, `check-mne.py`, `check-mnd.py`, `check-p0-p2.py`, `check-audit-remediation-v2.py` ;
  - `check-hosted-control-plane-consistency.py`, `check-runtime-dynamic-consistency.py`, `check-semantic-retrieval-consistency.py`, `check-workflow-pins.py`, `test_check_image_pins.py` ;
  - `check-current-docs.py`, `test_check_private_io.py` ;
  - `scripts/m15/run-final.ps1`.

  Chercher aussi les anciens noms dans `scripts/` avec `git grep`.
- [x] 4.2 (SH-02) Réunir les deux bases SpotBugs dans `quality/spotbugs-baseline/minos-storage.xml`, puis comparer avec le profil `audit-spotbugs` et `audit-report-summary.py spotbugs --baseline-dir quality/spotbugs-baseline` : 0 alerte nouvelle et 0 disparue. Réunir aussi les entrées de `scripts/quality/comment-language.json`, puis rejouer `check-comment-language.py`.
- [x] 4.3 (SH-02, D6) Mettre à jour `.github/CODEOWNERS`, `.github/workflows/code-audit.yml` et `quality/dependency-check-suppressions.xml`. Inventorier ce que la fusion change pour l'API embarquée et le SBOM : dépendances JDBC et Jackson transitives, taille et avis tiers. Vérifier que le JAR ombré de `minos-app` contient le service `StorageBackendProvider` une seule fois et aucune classe en double.

## 5. Documentation et spécifications

- [x] 5.1 (SH-02) Mettre à jour l'arc42 § 5 et le document généré des dépendances, y compris l'arête d'exécution AUD-ARC-11 `minos-engine -> minos-storage`. Mettre à jour `docs/user/java-api.md` (lignes 370 à 374), les guides de `docs/developer/` et la documentation courante de `docs/project/governance`. L'historique et les archives restent tels quels.
- [x] 5.2 (SH-02) ADR 0068 :
  - ajouter une note de mise en œuvre au § 4 ;
  - mettre à jour la section « Écarts constatés » (lignes 203 à 205) : fusion réalisée, arête PostgreSQL → local retirée, reactor à 13 modules ;
  - noter le cloisonnement par les règles R1, R2 et D4.
- [x] 5.3 (E04, point 4) Corriger le chemin de `StableFileSystemIdentity` dans `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md:224`. Cocher les actions SH-02 qui ont une preuve.
- [x] 5.4 (SH-02) Écrire les deltas `MODIFIED` des exigences qui nomment les anciens modules :
  - `frontieres-de-modules` : lignes 16, 98, 178 et 412 ;
  - `persistance-snapshots` : lignes 192, 298, 306 et 332 ;
  - `stockage-prive-et-secrets` : lignes 178, 350, 356 et 390 à 400 ;
  - `audit-qualite-code` : ligne 149.

  Les comportements ne changent pas. Valider avec `openspec validate --all --strict`.

## 6. Clôture

- [x] 6.1 Lancer `./mvnw -B -ntp verify` complet sous Windows, puis le scénario local sans PostgreSQL ni Docker : `minos index` sur une fixture, avec le backend `local`. Lancer aussi `python .claude/scripts/run_gates.py`, `check-jacoco.py` sur le rapport agrégé et `archgate check`.
- [ ] 6.2 Mesurer la couverture JaCoCo du module `minos-storage`, classe par classe, sur le rapport Linux de la CI, avec les tests PostgreSQL exigés. Toute classe sous 80 % reçoit des tests dans la PR, avant la fusion.
- [ ] 6.3 Mettre à jour la ligne de phase 4 dans `docs/project/audits/2026-10-10/SUIVI.md` et la ligne SH-02 du plan de stockage hexagonal. Revue d'architecture (`minos-architecture-reviewer`) et revue de sécurité du stockage. `ETAT-2026-10-13.md` et `findings.json` ne sont pas modifiés.

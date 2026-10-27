## 1. Préalables

- [ ] 1.1 (AUD-ARC-08, SH-03) Relever le SHA de départ sur `develop`. Rejouer `python .claude/scripts/run_gates.py` et `./mvnw -B -ntp -pl minos-storage -am verify` sur ce SHA, et consigner la baseline : tests exécutés, tests sautés, tests déjà rouges. Relever la base SpotBugs de `minos-storage` (50 entrées).

## 2. Garde et règles : rouges avant les déplacements

- [ ] 2.1 (AUD-ARC-08, D5, Q4) **Rouge.** Dans `scripts/architecture/test_check_module_boundaries.py`, écrire sur des arbres temporaires :
  - un test PostgreSQL qui importe un type de `com.minos.storage.local` ;
  - une production de `com.minos.storage.local` qui nomme un type PostgreSQL par nom qualifié ;
  - un fichier de `com.minos.storage.codec` qui importe `java.sql.Connection`, et un de `com.minos.storage.home` qui nomme le registre local ;
  - une mention en Javadoc, acceptée.

  Les cas de refus doivent échouer contre le garde du HEAD. **Correction** : la règle `check_storage_package_isolation`, sur les sources de production et de test de `minos-storage`. Elle ne remplace pas encore D4. **Preuve** : l'auto-test passe. Le garde réel échoue sur les 3 fichiers de production PostgreSQL de D4 (`PostgresProjectRegistry`, `PostgresSnapshotPayloadCodec`, `PostgresCodeKnowledgeSnapshotStore`) et sur 2 tests (`PostgresSnapshotFormatCompatibilityTest`, `PostgresProjectRegistryLifecycleTest`) : c'est le rouge réel, corrigé par les tâches 3 et 4.
- [ ] 2.2 (AUD-ARC-08, D5) **Rouge.** Dans `ModuleArchitectureTest`, ajouter les règles `postgresql` ↛ `local` et `codec`/`home` ↛ `local`, `postgresql`, `org.postgresql`, `java.sql`. Les règles sur `codec` et `home` passent à vide tant que ces packages n'existent pas : prouver leur rouge par une classe de test synthétique compilée dans le package interdit, puis retirée. **Preuve** : `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ModuleArchitectureTest"` échoue sur `postgresql` ↛ `local` ; le message nomme les 4 types de D4 et les types imbriqués `KnowledgeSnapshotCodecs$Selection` et `SnapshotCodec$SnapshotEncoding` (`PostgresSnapshotPayloadCodec.java:16`, `:20`). Il passe après la tâche 4.
- [ ] 2.3 (AUD-ARC-08, D2, Q1) **Garde-fou d'octets.** Écrire `ActiveSnapshotPointerBytesTest` (`com.minos.storage.local.store`) : chaque `active.pointer` des fixtures historiques est relu puis réécrit à l'identique, et un octet en trop est refusé par `unexpected trailing data in active snapshot pointer`. Il passe au HEAD, et garde le format pendant l'extraction de la tâche 3.2. **Rouge** : une mutation temporaire de `writeString` (un octet de longueur en plus) le fait échouer ; elle est annulée.

## 3. Codec commun

- [ ] 3.1 (AUD-ARC-08, D1, D4, D6, Q3) Commit de déplacements purs. Avec `git mv`, placer dans `com.minos.storage.codec` :
  - les 8 classes du codec : `KnowledgeSnapshotCodecs`, `SnapshotCodec`, `SnapshotCodecV1`, `SnapshotCodecV2`, `SnapshotCodecV3`, `SnapshotBinaryCodecSupport`, `SnapshotIntegrityService`, `CodeKnowledgeSnapshotBinaryCodec` ;
  - leurs 10 classes de test, et les aides `CodecFixtures`, `PersistedSizeFixtures`, `LegacySnapshotContent` et `QueryViewWeightFixtures`.

  Ne changer que les lignes `package` et `import`, les visibilités nécessaires (`LegacySnapshotContent` et `QueryViewWeightFixtures` publiques) et les deux Javadoc `{@link ActiveSnapshotRepository}` (`SnapshotBinaryCodecSupport.java:68`, `LegacySnapshotContent.java:32`), réécrites en texte. Déplacer `FileSymbolSnapshotStoreTest.symbols` dans `CodecFixtures`, et faire déléguer l'original. Mettre à jour les imports des 9 tests du backend local qui utilisent le codec.

  Dans le même commit, écrire `KnowledgeSnapshotCodecsTest` : une charge utile d'en-tête version 9 est refusée par `unsupported knowledge snapshot format version: 9`. **Preuve** : `git diff -M --stat` ne montre que des déplacements, hors du nouveau test, et les tests du codec et du backend local passent.
- [ ] 3.2 (AUD-ARC-08, D2, Q1) Extraire `SnapshotBinaryPrimitives` (publique, finale) : `writeString`, `readRequiredString`, `readCount` et `readHeaderVersion`, publiques, avec ce qu'elles appellent (`readString`, `requireWritableString`, `MAX_STRING_CHARS`), sans changer le code. `SnapshotBinaryCodecSupport` délègue et reste non publique ; `ActiveSnapshotRepository` importe la nouvelle classe. Tests de la classe dans le même commit : refus d'une chaîne absente, d'une chaîne trop longue, d'un compte au-dessus du plafond, d'un mauvais nombre magique. **Preuve** : `ActiveSnapshotPointerBytesTest`, `SnapshotFormatCompatibilityTest`, `SnapshotBinaryCodecSupportLinkTest` et `SnapshotVerifiedReadTest` passent.
- [ ] 3.3 (AUD-ARC-08, D5) Faire importer le codec à `PostgresSnapshotPayloadCodec`, à `PostgresCodeKnowledgeSnapshotStore` (y compris le nom qualifié de `:282`) et à `PostgresSnapshotFormatCompatibilityTest`, ainsi qu'au backend local, à `RetentionFormatEquivalenceTest` (`minos-app`) et à `SnapshotScalabilityBenchmark` (`minos-bootstrap`). Dans le même commit, écrire dans `com.minos.storage.codec` le test de réencodage : `v2-well-formed.knowledge`, relu puis réécrit par le codec V2, garde son empreinte SHA-256. **Preuve** : `SnapshotFormatCompatibilityTest` (V1 et V2, magasin fichier) et `PostgresSnapshotFormatCompatibilityTest` (V2, PostgreSQL, avec Docker) comparent chacun le résultat à `LegacySnapshotContent` et passent.

## 4. Mapping de chemins

- [ ] 4.1 (AUD-ARC-08, D3, Q2) Avec `git mv`, placer `ProjectPathMappingStore` et `ProjectPathMappingStoreSymlinkTest` dans `com.minos.storage.home`. `ProjectPathMappingTest` reste dans `com.minos.storage.local.registry`. Mettre à jour les imports :
  - production : `DefaultMinosApplicationComposer`, `LocalProjectRegistry`, `PostgresProjectRegistry`, et la Javadoc du port `ProjectPathMappings` ;
  - tests : ceux de `minos-storage` (dont `ProjectPathMappingTest` et `PostgresProjectRegistryLifecycleTest`), `DefaultMinosApplicationComposerTest`, `DockerRuntimeBootstrapTest`, `PublicCliDiagnosticTest`.

  **Preuve** : `./mvnw -B -ntp -pl minos-storage,minos-bootstrap,minos-cli -am test` passe, PostgreSQL compris ; le garde et `ModuleArchitectureTest` passent.

## 5. Garde, gates littéraux et qualité

- [ ] 5.1 (AUD-ARC-08, D5) Retirer `BACKEND_TYPES_NAMED_ACROSS_PACKAGES`, `check_backend_types_named_across_packages` (`check-module-boundaries.py:873-882`), leurs appels et leur ligne de résumé (`:62`, `:1684`, `:1718`), et leurs auto-tests (`test_check_module_boundaries.py:1432-1476`, `:1861`). `check_storage_package_isolation` devient la seule règle entre backends. Changer la clé de `CodeKnowledgeSnapshotBinaryCodec` dans la liste des classes sans appelant (`:1064`). **Preuve** : l'auto-test et le garde réel passent ; `ModuleArchitectureTest` passe.
- [ ] 5.2 Mettre à jour les chemins et préfixes, puis rejouer chaque script :
  - `check-jacoco.py` : préfixes du codec dans les scopes `storage-codec-and-state` et `persistence-cache-indexes`, et ajout de `com/minos/storage/codec/SnapshotBinaryPrimitives` à `storage-codec-and-state` ;
  - `check-mne.py`, `check-post-mne.py`, `check-audit-remediation-v2.py`, `scripts/m15/run-final.ps1`.

  Chercher les anciens chemins dans `scripts/` avec `git grep` (`scripts/history/` reste tel quel). Rejouer `check-jacoco.py` sur le rapport agrégé de `verify`.
- [ ] 5.3 Régénérer `quality/spotbugs-baseline/minos-storage.xml` par le profil `audit-spotbugs`, puis comparer avec `audit-report-summary.py spotbugs --baseline-dir quality/spotbugs-baseline` : 0 alerte nouvelle et 0 disparue après correspondance des noms. Rejouer `check-comment-language.py` et `check-private-io.py`.

## 6. Documentation et spécifications

- [ ] 6.1 Mettre à jour les documents qui citent les noms qualifiés ou les chemins :
  - l'ADR 0068 : note de mise en œuvre du § 4, avec `codec` et `home`, les règles strictes et le retrait de D4 ;
  - l'ADR 0066 et l'ADR 0042 ;
  - l'arc42 § 5 (`05-vue-blocs.md:72`) et § 8 ;
  - `docs/user/java-api.md` : lignes 374-375, et une ligne pour les packages `com.minos.storage.codec` et `com.minos.storage.home`.

  L'historique et les archives restent tels quels.
- [ ] 6.2 Valider `openspec validate --all --strict`. Cocher les actions de SH-03 qui ont une preuve dans `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md`.

## 7. Clôture

- [ ] 7.1 Lancer `./mvnw -B -ntp verify` complet sous Windows, avec Docker Desktop pour les tests PostgreSQL, puis `python .claude/scripts/run_gates.py` et `archgate check`.
- [ ] 7.2 Mesurer la couverture JaCoCo de `minos-storage`, classe par classe, sur le rapport Linux de la CI (artefact `jacoco-modules-Linux`), où les tests PostgreSQL sont exigés. Toute classe sous 80 % reçoit des tests dans la PR, avant la fusion.
- [ ] 7.3 Mettre à jour la ligne de phase 4 dans `docs/project/audits/2026-10-10/SUIVI.md` (D4 vide, constat E04 points 1 et 2) et la ligne SH-03 du plan de stockage hexagonal. Revue d'architecture (`minos-architecture-reviewer`) et revue de sécurité du stockage. `ETAT-2026-10-13.md` et `findings.json` ne sont pas modifiés.

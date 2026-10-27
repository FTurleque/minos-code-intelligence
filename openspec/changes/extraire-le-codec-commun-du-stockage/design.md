# Design

## Context

SH-02 (changement archivé `2026-10-27-fusionner-les-adaptateurs-de-stockage`, PR #581) a réuni les backends local et PostgreSQL dans le module `minos-storage`. Leur séparation repose sur des règles de packages :

- R1 et R2 : `minos-bootstrap` et `minos-provider-scip` ne nomment pas PostgreSQL ;
- le test de chargement T1 : la composition locale ne charge aucune classe PostgreSQL ;
- la règle ArchUnit `onlyThePostgresqlBackendUsesPostgresql` (`ModuleArchitectureTest.java:201-206`) ;
- le cliquet D4, qui liste ce que PostgreSQL nomme encore du backend local.

L'ADR 0068 § 4 prévoit trois packages dans ce module : `local`, `postgresql` et `codec`. SH-03 crée `codec`, vide D4 et rend la séparation stricte.

## État vérifié au HEAD `cb9b1bb9`

### Le cliquet D4

`BACKEND_TYPES_NAMED_ACROSS_PACKAGES` (`check-module-boundaries.py:611-616`) compte 4 types et 6 fichiers :

| Type du backend local | Fichiers PostgreSQL qui le nomment |
|---|---|
| `storage.local.registry.ProjectPathMappingStore` | `PostgresProjectRegistry.java:7`, `:49` |
| `storage.local.store.KnowledgeSnapshotCodecs` | `PostgresSnapshotPayloadCodec.java:4`, `PostgresCodeKnowledgeSnapshotStore.java:12` |
| `storage.local.store.SnapshotCodec` | `PostgresSnapshotPayloadCodec.java:5`, `PostgresCodeKnowledgeSnapshotStore.java:13` |
| `storage.local.store.SnapshotIntegrityService` | `PostgresCodeKnowledgeSnapshotStore.java:282`, par nom qualifié |

Le sens inverse est déjà vide : aucune source de `com.minos.storage.local`, de production ou de test, ne nomme `com.minos.storage.postgresql` ni `org.postgresql`. Côté tests, deux classes de `com.minos.storage.postgresql` nomment le backend local, sans que D4 les compte (il ne lit que la production) :

- `PostgresSnapshotFormatCompatibilityTest.java:4-5` : `SnapshotCodecV2`, `SnapshotIntegrityService` ;
- `PostgresProjectRegistryLifecycleTest.java:7` : `ProjectPathMappingStore`.

### Le codec forme un bloc fermé

Le graphe des noms entre les classes de production de `com.minos.storage.local.store` part de `KnowledgeSnapshotCodecs`, `SnapshotCodec` et `SnapshotIntegrityService`. Il atteint 7 classes et n'en sort pas :

| Classe | Lignes | Visibilité | Nomme dans le bloc |
|---|---|---|---|
| `KnowledgeSnapshotCodecs` | 63 | publique | `SnapshotBinaryCodecSupport`, `SnapshotCodec`, V1, V2, V3 |
| `SnapshotCodec` | 65 | publique | `SnapshotBinaryCodecSupport`, `SnapshotIntegrityService` |
| `SnapshotCodecV1`, `V2`, `V3` | 51, 61, 63 | publiques | `SnapshotBinaryCodecSupport`, `SnapshotCodec` |
| `SnapshotBinaryCodecSupport` | 1 119 | **de paquet** (`:72`) | `SnapshotIntegrityService` |
| `SnapshotIntegrityService` | 86 | publique | — |

Hors du package, le bloc n'importe que `com.minos.store`, `com.minos.domain` et `com.minos.io` (moteur et domaine) : ni JDBC, ni classe du backend local.

`CodeKnowledgeSnapshotBinaryCodec` (publique, 8e classe) n'appelle que `SnapshotCodecV2`. Aucune classe de production ne l'appelle ; le garde la tient dans sa liste justifiée des classes sans appelant (`check-module-boundaries.py:1064`).

Quatre classes du backend local utilisent le bloc :

- `FileSymbolSnapshotStore` : `KnowledgeSnapshotCodecs`, `SnapshotCodec`, `SnapshotCodecV1`, `SnapshotIntegrityService`, tous publics ;
- `SnapshotCompactionService` : `SnapshotIntegrityService.logicalIdHash`, public ;
- `CodeKnowledgeSnapshotBinaryCodec` : `SnapshotCodecV2`, public ;
- `ActiveSnapshotRepository`, le pointeur actif : 4 méthodes **de paquet** de `SnapshotBinaryCodecSupport` (`writeString` `:861`, `readHeaderVersion` `:850`, `readRequiredString` `:1091`, `readCount` `:1099`), appelées en `ActiveSnapshotRepository.java:77-79`, `:92` et `:98-106`. C'est le point 2 d'E04.

### Les tests du codec

Dans `minos-storage/src/test/java/com/minos/storage/local/store` :

- **10 classes ne testent que le codec** : `CodeKnowledgeSnapshotBinaryCodecTest`, `SnapshotBinaryCodecSupportLinkTest` (membres de paquet), `SnapshotCodecAllocationGuardTest`, `SnapshotCodecTest`, `SnapshotCodecV3Test`, `SnapshotDecodeStringSharingTest`, `SnapshotEncodedSizeTest`, `SnapshotEncodingTest`, `SnapshotVerifiedReadTest` (membres de paquet), et la fixture `PersistedSizeFixtures`.
- **Leurs aides** :
  - `CodecFixtures` ;
  - `LegacySnapshotContent` (`final class` de paquet, `:35`), utilisé aussi par `SnapshotFormatCompatibilityTest` et `LegacySnapshotFixtureGenerator` ;
  - `QueryViewWeightFixtures` (`final class` de paquet, `:36`), utilisé par 7 classes de test ;
  - la méthode `FileSymbolSnapshotStoreTest.symbols`, qu'appellent `SnapshotCodecTest` (`:31`, `:53`, `:85`), `SnapshotEncodedSizeTest` (`:50`) et `CodecFixtures`, mais aussi une douzaine de tests du backend local (par exemple `FileSymbolSnapshotStoreCacheTest:25`, `PersistedSnapshotLimitTest:34`, `SnapshotPersistenceComponentsTest:124`, `InMemoryCodeKnowledgeStoreIndexTest:36`).
- **9 classes testent le backend local à travers le codec** et y restent : `FileSymbolSnapshotStoreCacheTest`, `PersistedSnapshotLimitTest`, `SnapshotFormatCompatibilityTest`, `SnapshotPersistenceComponentsTest`, `ActiveSnapshotIdTest`, `FileSymbolSnapshotStoreIntegrityTest`, `FileSymbolSnapshotStoreReadViewTest`, `QueryViewWeightTest`, et le générateur `LegacySnapshotFixtureGenerator`.
- **Les fixtures de format historiques** sont des ressources (`src/test/resources/snapshot-formats/base-d9ae1005/`), lues par chemin de ressource : elles ne bougent pas.
- **Ce que les tests affirment déjà** : le magasin fichier lit les trois fixtures (`SnapshotFormatCompatibilityTest`) ; PostgreSQL lit les deux fixtures V2 (`PostgresSnapshotFormatCompatibilityTest.java:41`, `:68`), jamais la V1, un format de fichier antérieur à PostgreSQL. Aucun test n'affirme le refus d'une version inconnue (`KnowledgeSnapshotCodecs.java:44`), ni le réencodage d'une fixture à l'identique.

Hors de `minos-storage`, `RetentionFormatEquivalenceTest` (`minos-app`) et `SnapshotScalabilityBenchmark` (`minos-bootstrap`, `:31-33`) importent des classes du codec.

### Le fichier de correspondance des chemins

`ProjectPathMappingStore` (117 lignes) implémente le port `com.minos.registry.ProjectPathMappings` du moteur. Il n'importe que `com.minos.io`, `com.minos.domain.registry`, `com.minos.registry` et `com.minos.diagnostics` (`:21`) : aucune classe du backend local. Il a trois utilisateurs de production : `DefaultMinosApplicationComposer.java:19`, `:148` (qui le câble comme port), `LocalProjectRegistry.java:544` et `PostgresProjectRegistry.java:49`. Ses tests :

- `ProjectPathMappingStoreSymlinkTest` ne teste que lui ; sous Windows, il passe à vide par un `return` (`:20`, `:35`) ;
- `ProjectPathMappingTest` l'exerce à travers `LocalProjectRegistry` (`:46`, `:55`, `:73`, `:80`, `:104`) ;
- d'autres tests du backend local le nomment, et dans `minos-cli`, `DockerRuntimeBootstrapTest` et `PublicCliDiagnosticTest` ; dans `minos-bootstrap`, `DefaultMinosApplicationComposerTest:103-104`.

### Ce qui cite les chemins et les noms

| Fichier | Citation |
|---|---|
| `scripts/quality/check-jacoco.py` | préfixes `com/minos/storage/local/store/SnapshotBinaryCodecSupport` (`:29`), `.../SnapshotCodec` et `.../SnapshotIntegrityService` (`:65`) |
| `scripts/remediation/check-mne.py` | chemin de `SnapshotBinaryCodecSupport.java` (`:37`, `:83`) |
| `scripts/remediation/check-post-mne.py` | chemin de `ProjectPathMappingStore.java` (`:95`, `:182`) |
| `scripts/remediation/check-audit-remediation-v2.py` | chemin de `SnapshotBinaryCodecSupport.java` (`:140`) |
| `scripts/m15/run-final.ps1` | noms de fichiers du codec (`:74-75`) |
| `scripts/architecture/check-module-boundaries.py` | D4 (`:609-616`, fonction `:873-882`, appels et résumé `:62`, `:1684`, `:1718`), classes sans appelant (`:1064`) |
| `scripts/architecture/test_check_module_boundaries.py` | auto-tests de D4 (`:1432-1476`, `:1861`) |
| `quality/spotbugs-baseline/minos-storage.xml` | entrées des classes du codec, dont 2 sur `KnowledgeSnapshotCodecs$Selection` |
| ADR 0068, 0066, 0042 ; arc42 § 5 (`05-vue-blocs.md:72`) et § 8 ; `docs/user/java-api.md:374-375` | noms qualifiés ou chemins |
| `docs/architecture/quality/scenarios.md:18`, `SYNTHESE.md:110` | un nom de test, sans chemin : inchangés |

`scripts/history/` cite d'anciens chemins et n'est pas exécuté : il reste tel quel.

## Goals / Non-Goals

**Goals**

- Vider D4 par une correction, sans entrée justifiée restante.
- Rendre la séparation stricte : entre les deux backends, et du codec et du mapping vers les backends, sur la production, les tests et le bytecode.
- Garder chaque format écrit identique à l'octet près, et les fixtures historiques lisibles par les deux backends.

**Non-Goals**

- Découper `SnapshotBinaryCodecSupport`, changer un format, un plafond ou un message d'erreur.
- Traiter A13 (SH-06), modifier un port du moteur, ajouter un module Maven.

## Decisions

### D1 — Le codec est un package du module, pas un module

`com.minos.storage.codec` vit dans `minos-storage`, sous la racine `storage` qu'il possède déjà (A14 : aucune racine nouvelle). Les classes gardent leur nom simple ; seul le package change. Le package `com.minos.storage` appartient à `minos-engine` (les ports) ; `com.minos.storage.codec` est un autre package, possédé par `minos-storage` seul (A3).

### D2 — Les primitives du pointeur deviennent une petite classe publique (Q1)

`SnapshotBinaryPrimitives` (finale, constructeur privé) porte, publiques, `writeString(DataOutputStream, String)`, `readRequiredString(DataInputStream, String)`, `readCount(DataInputStream, int, String)` et `readHeaderVersion(...)`, avec le même code. Ces primitives ne sont pas autonomes ; elles emmènent ce qu'elles appellent :

- `readString(DataInputStream)` (`SnapshotBinaryCodecSupport.java:1060`) et `requireWritableString` (`:877`), de portée paquet ;
- la constante `MAX_STRING_CHARS` (`:86`), que `SnapshotBinaryCodecSupport` lit aussi ailleurs (`:916`, `:940`, `:1033`, `:1039`) et qui reste lisible dans le package.

`SnapshotBinaryCodecSupport` délègue à ces membres et reste non publique. Le pointeur actif importe `SnapshotBinaryPrimitives`. L'extraction compte environ 90 lignes.

Le test `ActiveSnapshotPointerBytesTest` réécrit chaque `active.pointer` des fixtures historiques et compare les octets au fichier d'origine. Il est écrit avant l'extraction et doit rester vert après.

Écartées : rendre `SnapshotBinaryCodecSupport` publique, qui exposerait 1 119 lignes au backend local ; copier les primitives dans le pointeur, que rien ne garderait identiques au codec.

### D3 — Le mapping de chemins va dans un package neutre (Q2)

`ProjectPathMappingStore` va dans `com.minos.storage.home`, avec `ProjectPathMappingStoreSymlinkTest`. `ProjectPathMappingTest`, qui l'exerce à travers `LocalProjectRegistry`, reste dans `com.minos.storage.local.registry` et importe `home` : le déplacer violerait la règle `home` ↛ `local` (D5). Il garde ses constantes (`RUNTIME_DIRECTORY`, `FILE_NAME`), son fichier et ses refus. Les trois utilisateurs changent leur import ; le port `ProjectPathMappings` et son câblage par `minos-bootstrap` ne changent pas (R1 ne vise que PostgreSQL).

Écartées : injecter le port dans le backend PostgreSQL par un champ additif de `StorageBackendConfiguration`, qui modifie un port du moteur pour un besoin interne au module ; garder une entrée permanente dans D4.

### D4 — Le pont V2 suit le codec (Q3)

`CodeKnowledgeSnapshotBinaryCodec` va dans `com.minos.storage.codec`. Son entrée dans la liste des classes sans appelant change de clé, avec la même justification.

### D5 — Des règles strictes remplacent le cliquet, tests compris (Q4)

Le garde retire `BACKEND_TYPES_NAMED_ACROSS_PACKAGES` et sa fonction. Une règle `check_storage_package_isolation` lit les sources de **production et de test** de `minos-storage`, commentaires et littéraux masqués, et refuse :

| Package source | Ne nomme pas |
|---|---|
| `com.minos.storage.local` | `com.minos.storage.postgresql`, `org.postgresql` |
| `com.minos.storage.postgresql` | `com.minos.storage.local` |
| `com.minos.storage.codec`, `com.minos.storage.home` | `com.minos.storage.local`, `com.minos.storage.postgresql`, `org.postgresql`, `java.sql` |

Un nom compte par import simple, statique ou joker, et par nom qualifié. Le message nomme le fichier, le type et la règle.

`ModuleArchitectureTest` reçoit les mêmes interdictions sur le bytecode de production : `postgresql` ↛ `local`, et `codec`/`home` ↛ les deux backends, `org.postgresql` et `java.sql`. La règle `onlyThePostgresqlBackendUsesPostgresql` couvre déjà `local` ↛ `postgresql`.

Les tests PostgreSQL qui nomment le backend local passent par le codec ou par `home`, qui sont autorisés.

### D6 — Les tests suivent leur classe, leurs fixtures aussi

Les 10 classes de test du codec vont dans `com.minos.storage.codec` (même package que la classe testée : les membres de paquet restent accessibles). Leurs aides y vont aussi :

- `CodecFixtures` et `PersistedSizeFixtures` ;
- `LegacySnapshotContent` et `QueryViewWeightFixtures`, qui deviennent publiques, car des tests du backend local les utilisent encore ;
- la méthode `symbols`, qui passe dans `CodecFixtures` ; `FileSymbolSnapshotStoreTest.symbols` lui délègue, pour ne pas toucher la douzaine d'appelants du backend local.

Aucun test du codec ne nomme le backend local (D5). Les 9 tests du backend local qui utilisent le codec ne changent que leurs imports. Les Javadoc `{@link ActiveSnapshotRepository}` de `SnapshotBinaryCodecSupport.java:68` et de `LegacySnapshotContent.java:32` ne se résolvent plus depuis `codec` : elles nomment le pointeur en texte.

Deux tests manquent au HEAD pour les exigences de format :

- le refus d'une version inconnue par `KnowledgeSnapshotCodecs` ;
- le réencodage de `v2-well-formed.knowledge` par le codec V2, avec la même empreinte SHA-256.

Aucune classe ne peut nommer les deux backends (D5). La lecture des fixtures par les deux backends est donc prouvée par deux tests, un par backend, qui comparent chacun le résultat à `LegacySnapshotContent`. Le backend PostgreSQL ne lit que les fixtures V2 : la V1 est un format de fichier antérieur à PostgreSQL.

## Risques et coût

- **Octets** : un déplacement ne change aucun octet, mais l'extraction de D2 touche les primitives du pointeur et des snapshots. Les tests de format (`SnapshotFormatCompatibilityTest`, `PostgresSnapshotFormatCompatibilityTest`, `ActiveSnapshotPointerBytesTest`) le prouvent sur les fixtures publiées.
- **Gates littéraux** : 5 scripts citent des chemins ; chacun est rejoué (skill `minos-literal-gates`).
- **Couverture** : SonarCloud compte toute classe déplacée comme du nouveau code. Le rapport JaCoCo Linux de la CI doit montrer chaque classe de `minos-storage` au moins à 80 %, comme pour SH-02.
- **SpotBugs** : la base est régénérée avec les nouveaux noms ; 0 alerte nouvelle et 0 disparue après correspondance des noms.
- **JaCoCo** : `SnapshotBinaryPrimitives` entre dans le scope `storage-codec-and-state`, sinon l'extraction sort des lignes mesurées.
- **Coût** : environ 1 650 lignes déplacées sans changement, 90 lignes extraites, une règle de garde et ses auto-tests, une règle ArchUnit, un test d'octets du pointeur, une dizaine d'imports, 6 documents. Une PR, en commits séparés : règles rouges, déplacements purs, extraction, outillage, documentation.

## Plateformes

Rien de propre à une plateforme : déplacements de classes, garde Python et ArchUnit. `ProjectPathMappingStore` garde ses refus de lien symbolique ; son test `ProjectPathMappingStoreSymlinkTest` passe à vide sous Windows, comme au HEAD.

Seule la CI Linux exige les tests PostgreSQL (`-Dminos.postgresql.tests.required=true`, `.github/workflows/pr-ci.yml:380`). L'étape Windows ne passe pas ce drapeau (`:385`), et son gate JaCoCo saute le scope PostgreSQL (`:410`). Les tests PostgreSQL de ce changement sont donc prouvés sous Linux en CI, et sous Windows sur le poste avec Docker Desktop.

## Direction des dépendances

Aucune arête Maven n'est ajoutée ni retirée. À l'intérieur de `minos-storage`, les arêtes de packages deviennent `local → codec`, `local → home`, `postgresql → codec` et `postgresql → home`, sans cycle (A8). `minos-bootstrap → minos-storage` existe déjà ; son import passe de `local.registry` à `home`.

## Décisions du propriétaire (2026-10-27)

- **Q1** : une classe publique de primitives dans le codec (D2).
- **Q2** : `ProjectPathMappingStore` dans le package neutre `com.minos.storage.home` (D3).
- **Q3** : `CodeKnowledgeSnapshotBinaryCodec` déplacé dans le codec (D4).
- **Q4** : règles strictes sur la production et les tests, et sur le bytecode (D5).

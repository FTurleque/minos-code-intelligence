# Proposal

## Why

Le constat **AUD-ARC-08** (audit du 10 octobre 2026) relève que le backend PostgreSQL dépend du backend local. SH-02 a réuni les deux backends dans le module `minos-storage` et a figé ce couplage dans le cliquet de packages D4 (`BACKEND_TYPES_NAMED_ACROSS_PACKAGES`, `scripts/architecture/check-module-boundaries.py:611-616`) : 4 types du backend local, nommés par 6 fichiers PostgreSQL. La tâche **SH-03** du plan de stockage hexagonal vide ce cliquet. C'est le deuxième lot de la phase 4 du chantier de dette figée.

Au HEAD `cb9b1bb9`, les 4 entrées ont deux causes (constat **MINOS-AUD-E04**, points 1 et 2) :

- **Le codec de snapshot vit dans le backend local.** `KnowledgeSnapshotCodecs`, `SnapshotCodec` et `SnapshotIntegrityService` sont dans `com.minos.storage.local.store`. PostgreSQL les utilise pour encoder ses charges utiles (`PostgresSnapshotPayloadCodec.java:4-5`, `PostgresCodeKnowledgeSnapshotStore.java:12-13`, `:282`). Le pointeur actif du backend local emprunte 4 primitives de portée paquet au codec (`ActiveSnapshotRepository.java:77-79`, `:92`, `:98-106`).
- **Le fichier de correspondance des chemins vit dans le backend local.** `ProjectPathMappingStore` (`com.minos.storage.local.registry`) est le fichier de correspondance hôte et conteneur sous `MINOS_HOME`. La racine de composition (`DefaultMinosApplicationComposer.java:148`), le registre local (`LocalProjectRegistry.java:544`) et le registre PostgreSQL (`PostgresProjectRegistry.java:7`, `:49`) l'utilisent.

## What Changes

- **Un package `com.minos.storage.codec`** reçoit le codec, sans changer son comportement : `KnowledgeSnapshotCodecs`, `SnapshotCodec`, `SnapshotCodecV1`, `SnapshotCodecV2`, `SnapshotCodecV3`, `SnapshotBinaryCodecSupport`, `SnapshotIntegrityService` et `CodeKnowledgeSnapshotBinaryCodec` (décision Q3), avec leurs tests.
- **Une classe publique `SnapshotBinaryPrimitives`** du codec porte les 4 primitives qu'utilise le pointeur actif : écrire une chaîne, lire une chaîne exigée, lire un compte borné, lire un en-tête versionné (décision Q1). `SnapshotBinaryCodecSupport` les appelle et reste non publique. Aucun octet écrit ne change.
- **Un package `com.minos.storage.home`** reçoit `ProjectPathMappingStore`, sans changer son comportement ni le fichier qu'il écrit (décision Q2). `ProjectPathMappingTest`, qui l'exerce à travers le registre local, reste dans le backend local.
- **Le cliquet D4 est retiré.** Il est remplacé par des règles strictes, sur les sources de production **et de test** de `minos-storage` (décision Q4) :
  - `com.minos.storage.local` ne nomme pas `com.minos.storage.postgresql`, et réciproquement ;
  - `com.minos.storage.codec` et `com.minos.storage.home` ne nomment aucun des deux backends, ni `org.postgresql`, ni `java.sql`.
  Les mêmes interdictions existent sur le bytecode de production, dans `ModuleArchitectureTest`.
- **Les fixtures de format historiques** sont lues par chaque backend (V2 par les deux, V1 par le backend local), et le réencodage reste identique à l'octet près : c'est le critère d'acceptation de SH-03. Deux tests manquent au HEAD et sont ajoutés : le refus d'une version inconnue et le réencodage d'une fixture.
- **Gates littéraux, outillage et documentation** pointent vers les nouveaux chemins : `check-jacoco.py`, `check-mne.py`, `check-post-mne.py`, `check-audit-remediation-v2.py`, `run-final.ps1`, la table des classes sans appelant du garde, la base SpotBugs, l'ADR 0068, l'ADR 0066, l'ADR 0042, l'arc42 et `docs/user/java-api.md`.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : le cliquet entre backends est remplacé par des règles strictes, qui portent aussi sur le codec, le mapping de chemins et les sources de test.
- `persistance-snapshots` : les deux backends partagent un seul codec, et les formats publiés restent lisibles et identiques à l'octet près.

## Hors périmètre

- **SH-06** : `minos-provider-scip` nomme encore `FileSymbolSnapshotStore` du backend local (A13, 1 type, 1 fichier) et 9 types du runtime local. SH-06 les traite.
- **Découper `SnapshotBinaryCodecSupport`** (1 119 lignes). Le changement `decouper-les-classes-de-plus-de-800-lignes` l'a exclu (décision Q5) ; SH-03 ne fait que le déplacer et en extraire 4 primitives.
- **Un module Maven `minos-storage-codec`** : ADR 0068 § 4 et SH-03 l'excluent.
- **Une nouvelle version de format** (V4), un changement d'en-tête ou de plafond.
- **Le port `ProjectPathMappings`** du moteur ne change pas, ni sa signature, ni sa façon d'être câblé par `minos-bootstrap`.
- **Les écarts relevés à part** : `PostgresJdbcUrlPolicy` compare `verify-full` sans tenir compte de la casse ; `StorageBackendSelection` compare `"local"` en en tenant compte.

## Impact

- **Modules du reactor** : `minos-storage` (déplacements, imports), `minos-bootstrap` (un import de production, `DefaultMinosApplicationComposer`, et deux tests : `DefaultMinosApplicationComposerTest`, `SnapshotScalabilityBenchmark`), `minos-cli` (deux imports de test), `minos-app` (un import de test, `RetentionFormatEquivalenceTest`, et la règle ArchUnit). La direction des dépendances entre modules ne change pas : aucune arête Maven n'est ajoutée ni retirée.
- **Surfaces publiques** : aucune. Les contrats publics ne nomment que `com.minos.api.*` et `MinosApplication`, qui ne changent pas. CLI, MCP, IntelliJ et NEXUS ne sont pas touchés. Les classes déplacées sont internes : `docs/user/java-api.md` liste déjà leurs packages comme internes, et une nouvelle ligne y signale le changement de package, comme pour les renommages précédents.
- **ADR** : aucun nouvel ADR. L'ADR 0068 § 4 décide déjà le package `codec` ; sa note de mise en œuvre est complétée, avec le package `home`. Les ADR 0066 (persistance des snapshots) et 0042 (racine de composition) mettent à jour les noms qualifiés qu'ils citent.
- **Données** : aucun fichier ni aucune ligne de base ne change. Les formats V1, V2 et V3, le pointeur actif et le fichier de correspondance restent identiques à l'octet près.
- **Coût** : 9 classes de production déplacées (1 647 lignes), 1 classe extraite d'environ 90 lignes, une dizaine d'imports de production et une vingtaine de test, 11 tests et 4 fixtures déplacés, 3 tests ajoutés (octets du pointeur, version inconnue, réencodage), 5 gates littéraux, une règle de garde réécrite avec ses auto-tests, une règle ArchUnit, la base SpotBugs régénérée, et 6 documents.

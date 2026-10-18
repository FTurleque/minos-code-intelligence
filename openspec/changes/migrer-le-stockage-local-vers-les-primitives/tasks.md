# Tasks

Ordre dans chaque section : rouge, correction, preuve. Chaque tâche retire ses entrées de `scripts/architecture/private-io-allowlist.json` (le gate est un cliquet : un maximum supérieur au décompte réel échoue) et rejoue `python scripts/architecture/check-private-io.py`. Tests dans `minos-storage-local`, dans la même tâche que le code. Les sections 2 à 4 exigent `completer-les-primitives-d-e-s-privees` ; la section 1 non.

## 1. Fichiers Properties en mémoire (AUD-SEC-03)

- [ ] 1.1 [AUD-SEC-03] Rouge : pour `FileIndexStateStore.storeIo`, `LocalProjectRegistry.writePropertiesAtomically` et `ProjectPathMappingStore.save`, un test qui substitue un lien symbolique au fichier temporaire entre sa création et l'écriture (hook ou sous-classe de test) et exige l'échec sans écrire dans la cible du lien ; un test de contenu identique à l'octet près sur un jeu de propriétés avec caractères non ASCII (la ligne de commentaire datée de `Properties.store` est fixée par `java.properties.date` ou écartée). **Preuve du rouge : le test de lien échoue (l'écriture suit le lien).** Si le lien ne peut pas être substitué sans modifier le code (aucun point d'injection), le rouge est le test d'octets, et le cas de lien est couvert par `PrivateLocalStorageWritesTest` du socle.
- [ ] 1.2 [AUD-SEC-03] Écrire par `properties.store(ByteArrayOutputStream)` puis `PrivateLocalStorage.writePrivateFile` (D1) ; retirer les entrées `FileIndexStateStore` / `Files.newOutputStream`, `LocalProjectRegistry` / `Files.newBufferedWriter`, `ProjectPathMappingStore` / `Files.newBufferedWriter`. Preuve : `./mvnw -B -ntp -pl minos-storage-local -am test` ; `check-private-io.py` vert (`allowlisted-occurrences` baisse de 3).

## 2. Écritures en flux (AUD-SEC-03)

- [ ] 2.1 [AUD-SEC-03] Rouge : pour chaque écrivain (`writeSymbolSnapshotV1`, `writeKnowledgeSnapshotV2`, `writeKnowledgeSnapshotV3`, pointeur actif, empreintes et leur pointeur, session d'observation, index sémantique), un test où le fichier à écrire est un lien symbolique vers un fichier sentinelle : l'écriture échoue et la sentinelle est intacte ; les `FileProjectFingerprintSnapshotStoreSymlinkTest`, `FileRuntimeObservationStoreSymlinkTest`, `FileSemanticVectorStoreSymlinkTest` existants sont étendus, un test de codec est ajouté pour `SnapshotBinaryCodecSupport` (sans test dédié aujourd'hui). **Preuve du rouge : les écritures actuelles suivent le lien.**
- [ ] 2.2 [AUD-SEC-03] Remplacer les huit `Files.newOutputStream` par `PrivateLocalStorage.openPrivateOutputStream` (le reste du flux — `DigestOutputStream`, `BoundedOutputStream`, `DataOutputStream` — inchangé) ; retirer les entrées `FileProjectFingerprintSnapshotStore` (2), `ActiveSnapshotRepository`, `FileRuntimeObservationStore` / `Files.newOutputStream`, `FileSemanticVectorStore`, `SnapshotBinaryCodecSupport` (3). Preuve : tests du module verts, aller-retour du codec sur les trois versions de format inchangé, `check-private-io.py` vert.
- [ ] 2.3 [AUD-SEC-03] **(manuelle)** Écrire un snapshot réel (index de ce dépôt) avant et après, sous Windows et sous Linux ; comparer les empreintes SHA-256 des fichiers produits (identiques) et la durée d'écriture (seuil d'abandon : plus de 10 % de plus). Consigner dans `design.md`.

## 3. Sauvegarde du registre (AUD-SEC-03)

- [ ] 3.1 [AUD-SEC-03] Rouge : dans `ProjectPathMappingTest` et `InterProcessLocalProjectRegistryTest`, une sauvegarde `.m29-v1.bak` déjà présente avec un contenu sentinelle n'est ni modifiée ni remplacée par la migration ; deux migrations simultanées (deux threads, 50 répétitions) laissent une sauvegarde complète et valide ; la sauvegarde est privée (`0600` sous POSIX, ACL propriétaire seul sous Windows). **Preuve du rouge : le test de droits échoue sous Windows ou la course apparaît ; sinon, le test documente le comportement actuel et est conservé comme garde.**
- [ ] 3.2 [AUD-SEC-03] Copier par `openRegularFileNoFollow` + temporaire privé + `DurableAtomicFile.publish` (D2) ; retirer l'entrée `LocalProjectRegistry` / `Files.copy`. Preuve : `ProjectPathMappingTest` et `InterProcessLocalProjectRegistryWriteProtectedStorageTest` verts sans modification (une lecture ne crée toujours pas la sauvegarde), `check-private-io.py` vert.

## 4. Publication d'une session (AUD-SEC-03)

- [ ] 4.1 [AUD-SEC-03] Rouge : dans `FileRuntimeObservationStoreTest`, une session dont le nom cible existe déjà à l'insu du chemin « existante » (fichier posé à la main) : la publication ne remplace pas et rend `SaveResult(stored, true)` après relecture, ou échoue si le contenu diffère ; le test de capacité atteinte et de session immuable restent verts. **Preuve du rouge : `Files.move` remplace.**
- [ ] 4.2 [AUD-SEC-03] `moveAtomically` → `DurableAtomicFile.publish` (D3) ; retirer l'entrée `FileRuntimeObservationStore` / `Files.move`. Modifier `scripts/quality/check-runtime-dynamic-consistency.py:90` (`ATOMIC_MOVE` → `DurableAtomicFile.publish`) et rejouer son auto-test s'il en a un. Preuve : `python scripts/quality/check-runtime-dynamic-consistency.py`, tests du module, `check-private-io.py` vert.

## 5. Gates et couverture (AUD-SEC-03)

- [ ] 5.1 [AUD-SEC-03] Rejouer : `python scripts/architecture/check-private-io.py` (`allowlisted-occurrences` baisse de 13, dix entrées de moins), `python scripts/architecture/test_check_private_io.py -v`, `python scripts/quality/check-semantic-retrieval-consistency.py`, `python scripts/remediation/check-mne.py`, `check-post-mne.py`, `check-mnd.py`, `check-audit-remediation-v2.py`, `python .claude/scripts/run_gates.py --fast`.
- [ ] 5.2 [AUD-SEC-03] `./mvnw -B -ntp -pl minos-storage-local -am verify` puis `python scripts/quality/check-jacoco.py` sur `storage-codec-and-state`, `semantic-vector-store`, `persistence-cache-indexes`, `m26-runtime-dynamic-intelligence`, `global` ; aucun seuil abaissé. **(manuelle)** Mesurer le coût de la publication d'une session (D3) ; seuil d'abandon dans `design.md`.

## 6. Clôture

- [ ] 6.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (section « Migration des écritures brutes » : lot « stockage local » clos, entrées restantes).
- [ ] 6.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/developer/quality-gates.md` si le décompte du gate y figure.
- [ ] 6.3 Baseline SpotBugs : relire `quality/spotbugs-baseline/minos-storage-local.xml` pour les méthodes réécrites (comparaison par `instanceHash` dans `code-audit.yml`, qui n'est pas un check exigé) ; régénérer la baseline si une entrée disparaît ou change de place.

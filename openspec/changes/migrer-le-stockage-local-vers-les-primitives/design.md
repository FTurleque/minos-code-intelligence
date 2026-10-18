# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « stockage local » de `scripts/architecture/private-io-allowlist.json`. HEAD analysé : `4922f4ac` (`develop`). Dépend de `completer-les-primitives-d-e-s-privees` pour les écritures en flux. Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

Relevé exécuté : `check-private-io.py` → 10 entrées `minos-storage-local`, 13 occurrences (positions relues avec le gate lui-même).

| Site | Appel | Fichier écrit | Primitive retenue |
|---|---|---|---|
| `FileProjectFingerprintSnapshotStore.java:384` (`writeSnapshot`), `:524` (`writePointer`) | `Files.newOutputStream` | temporaire de `createPrivateTempFile` (`:108`, `:183`) | `openPrivateOutputStream` |
| `FileIndexStateStore.java:652` (`storeIo`) | `Files.newOutputStream` | temporaire (`:650`) | `writePrivateFile` (octets en mémoire) |
| `LocalProjectRegistry.java:453` | `Files.newBufferedWriter` UTF-8 | temporaire (`:451`) | `writePrivateFile` |
| `ProjectPathMappingStore.java:86` | `Files.newBufferedWriter` UTF-8 | temporaire (`:84`) | `writePrivateFile` |
| `LocalProjectRegistry.java:350` | `Files.copy(COPY_ATTRIBUTES)` | `<entrée>.m29-v1.bak` | lecture `openRegularFileNoFollow` + écriture privée + `DurableAtomicFile.publish` |
| `ActiveSnapshotRepository.java:72` | `Files.newOutputStream` | temporaire de `SnapshotRepository.createTemporaryPointer` (`:46`) | `openPrivateOutputStream` |
| `FileRuntimeObservationStore.java:243` | `Files.newOutputStream` | temporaire (`:113`) | `openPrivateOutputStream` |
| `FileRuntimeObservationStore.java:472` | `Files.move(ATOMIC_MOVE)` | `session-<hash>-<sha>.…` | `DurableAtomicFile.publish` |
| `FileSemanticVectorStore.java:192` | `Files.newOutputStream` | temporaire (`:187`) | `openPrivateOutputStream` |
| `SnapshotBinaryCodecSupport.java:103`, `:156`, `:304` | `Files.newOutputStream` | fichier passé par le codec (V1, V3, V2) | `openPrivateOutputStream` |

- **Les fichiers sont déjà privés.** Tous les appelants relevés créent le fichier par `createPrivateTempFile` ou `createPrivateFile` (`SnapshotRepository.createTemporarySnapshot` `:42`, `createTemporaryPointer` `:46`, `FileSymbolSnapshotStore.java:314`). La migration ne change donc pas les droits d'un fichier créé : elle ajoute le refus d'un lien substitué entre la création et l'ouverture, et revérifie le propriétaire à l'écriture. L'écriture reste suivie de `DurableAtomicFile.replace` / `publish`, déjà durable.
- **Sauvegarde du registre** (`LocalProjectRegistry.java:349-350`) : `if (!Files.exists(backup)) Files.copy(file, backup, COPY_ATTRIBUTES)`. Deux défauts : un contrôle puis une copie (deux processus peuvent se croiser ; `InterProcessLocalProjectRegistry` existe pour cela), et les droits de la copie dépendent de la plateforme et de `COPY_ATTRIBUTES` au lieu d'être imposés par une primitive privée (comportement par plateforme non mesuré ici). Le test `ProjectPathMappingTest.java:85-91` exige que la sauvegarde existe et contienne `rootPath=` ; `InterProcessLocalProjectRegistryWriteProtectedStorageTest.java:77` exige qu'une **lecture** ne la crée pas.
- **Publication d'une session** (`FileRuntimeObservationStore.java:468-476`) : `ATOMIC_MOVE` seul, sans synchronisation du fichier ni du dossier. L'unicité est déjà décidée avant l'écriture sous le verrou du projet (`:97-105`, session identique → `SaveResult(…, true)`), et le nom contient l'empreinte du contenu.
- **Gates littéraux** : `scripts/quality/check-runtime-dynamic-consistency.py:90` exige la chaîne `ATOMIC_MOVE` dans `FileRuntimeObservationStore.java` ; elle quitte le fichier. `scripts/remediation/check-audit-remediation-v2.py:144` exige `ConfinedFileOpener.openRegularFileNoFollow(file)` dans `SnapshotBinaryCodecSupport.java` (lecture, inchangée). `check-mne.py`, `check-post-mne.py`, `check-mnd.py` et `check-semantic-retrieval-consistency.py` lisent ces classes sans citer d'appel brut (recherche de `Files.` / `StandardOpenOption` / `OutputStream` dans `scripts/` : aucune occurrence hors le gate d'E/S).
- **Tests nommés par un gate** : `check-runtime-dynamic-consistency.py:132-137` exige des noms de tests de `FileRuntimeObservationStore` et de son test de lien : les étendre, ne pas les renommer.
- **Scopes JaCoCo** : `m26-runtime-dynamic-intelligence` (contient `FileRuntimeObservationStore`, plancher ligne 0,55 / branche 0,35), `storage-codec-and-state` (codec, état d'index, empreintes, registre, plancher ligne 0,89), `semantic-vector-store` (ligne 0,45), `persistence-cache-indexes`.
- **Recouvrements** (changements actifs non archivés ; leur code est dans `develop`, sauf mention) : `lire-le-registre-sans-ecrire` (touche `LocalProjectRegistry`, `FileIndexStateStore`, `FileProjectFingerprintSnapshotStore`, `FileRuntimeObservationStore`, `FileSemanticVectorStore`), `hacher-l-arbre-source-une-fois-par-indexation` et `donner-un-contrat-d-erreur-au-port-d-etat-d-index` (`FileIndexStateStore`, **non implémentés**), `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` (`FileSemanticVectorStore`, non implémenté), `retirer-les-jalons-des-identifiants` (`FileProjectFingerprintSnapshotStore`, non implémenté). Conflits de texte seulement, pas de contradiction de décision.

**Non vérifié** : aucun build ni test exécuté ; le coût de l'`fsync` ajouté à la publication d'une session n'est pas mesuré.

## Écarts avec la justification de la liste

1. La liste dit « vers `PrivateLocalStorage` / `DurableAtomicFile` » pour les écritures en flux. `PrivateLocalStorage` n'avait pas d'écriture en flux : elle vient avec le changement socle. Pour les trois fichiers `Properties`, l'écriture en mémoire suffit ; **ces trois sites peuvent être migrés avant le socle**.
2. La sauvegarde n'est pas une écriture mais une **copie** : aucune primitive de copie n'existe (hors périmètre du socle). La composition lecture confinée + écriture privée + publication exclusive est le remplacement.
3. `Files.move(ATOMIC_MOVE)` remplace silencieusement une cible existante (comportement non spécifié par le JDK, `DurableAtomicFile.java:154-160`). Ici la cible porte l'empreinte du contenu : la différence n'est observable que sous une course que le verrou du projet exclut. `publish` la refuse explicitement.

## Goals / Non-Goals

**Goals** : retirer les dix entrées ; aucun octet de format changé ; chaque site garde son test d'échec existant et gagne le cas « lien substitué ».

**Non-Goals** : changer un format ; ajouter une primitive ; réécrire la rétention ou les verrous.

## Decisions

### D1. Fichiers `Properties` : octets en mémoire — recommandation : `writePrivateFile`

`properties.store(ByteArrayOutputStream)` (ou `Writer` UTF-8 sur un `ByteArrayOutputStream` là où le code écrit aujourd'hui en UTF-8), puis `writePrivateFile(temporaire, octets)`. Contenu identique à l'octet près **hors la ligne de commentaire datée** que `Properties.store` écrit (`#<date>`) : le test fixe `java.properties.date` ou compare sans les lignes `#` (test de comparaison avant / après sur un jeu de propriétés avec caractères non ASCII). Option B : flux privé partout. Rejetée : un `Properties` de quelques lignes n'a pas besoin de flux, et cela libère trois sites de la dépendance au socle.

### D2. Sauvegarde du registre — recommandation : publication exclusive, attributs non copiés

Lecture bornée par `openRegularFileNoFollow`, écriture dans un temporaire privé du même dossier, `DurableAtomicFile.publish(temporaire, sauvegarde, …)`. Une `FileAlreadyExistsException` signifie « déjà sauvegardé » (même effet que le test `!Files.exists`, sans la course). `COPY_ATTRIBUTES` n'est pas reproduit : la date de modification de la sauvegarde devient la date de migration ; aucun code de production ne la lit (recherche de `m29-v1.bak` dans `src/main` : seul `LocalProjectRegistry.java:349-350`, la création ; les deux autres occurrences sont des tests qui lisent le contenu, pas la date). Option B : copier les dates par `Files.setLastModifiedTime` (un appel de plus, sans lecteur). Rejetée.

### D3. Publication d'une session — recommandation : `publish`, `FileAlreadyExistsException` traitée comme « déjà stockée »

Sous le verrou du projet, la session existante est détectée avant l'écriture ; `publish` n'ajoute que le refus d'écrasement et la synchronisation. Si la cible existe malgré tout (nom d'empreinte identique), le temporaire est supprimé et `SaveResult(stored, true)` rendu après relecture, comme la branche `:97-105`. La chaîne `ATOMIC_MOVE` quitte le fichier : `check-runtime-dynamic-consistency.py:90` exige désormais `DurableAtomicFile.publish` à sa place (modification du gate dans la même tâche, avec son auto-test si le script en a un). Le message « atomic runtime session publication is not supported » devient celui de `DurableAtomicFile` (« filesystem does not support required exclusive … ») ; `FileRuntimeObservationStoreTest` est relu pour le cas correspondant.

### D4. Ordre — recommandation : après `lire-le-registre-sans-ecrire`

Le changement touche sept fichiers que `lire-le-registre-sans-ecrire` a modifiés. Le faire après limite les conflits ; le cliquet de liste se recalcule à la fusion (le gate le signale dans les deux sens).

## Direction des dépendances

`storage → engine` (ADR 0022) : aucune dépendance nouvelle, aucun import hors `com.minos.io`. Aucun package nouveau.

## Qualification des capacités

- Écriture d'un fichier de stockage sans suivi de lien : **qualifiée** sous Windows et Linux (le suivi de lien est refusé par l'ouverture, testé par les `…SymlinkTest` existants étendus).
- Publication exclusive d'une session ou d'une sauvegarde : **qualifiée** sous NTFS, ext4, XFS ; **non supportée** (échec fermé) sur FAT et exFAT, où le lien dur n'existe pas.

## Windows et Linux

Les modes POSIX et les ACL ne changent pas pour un fichier déjà privé. Les tests de lien symbolique sont `@EnabledOnOs` ou `assumeTrue` sur la capacité de créer un lien (convention de `tests-java`) ; sous Windows sans privilège, ils s'appuient sur la jonction de répertoire déjà utilisée par `PrivateLocalStorageWindowsJunctionTest`.

## Risks / Trade-offs

- **`fsync` à chaque publication de session** : coût inconnu ; une session est publiée une fois par exécution runtime. À mesurer (tâche 5.2), seuil d'abandon : plus de 50 ms médians sur un SSD local.
- **Course sur les sept fichiers partagés avec des changements non implémentés** : voir D4 ; mitigation par ordre de fusion.
- **Régression de format** : exclue par la comparaison octet pour octet (D1) et par les tests d'aller-retour existants du codec.

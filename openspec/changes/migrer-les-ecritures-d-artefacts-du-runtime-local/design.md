# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « runtime local », partie artefacts et espaces de travail (la partie propre à Windows est dans `migrer-les-ecritures-windows-du-sandbox`). HEAD analysé : `4922f4ac` (`develop`). Dépend de `completer-les-primitives-d-e-s-privees`. Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

Relevé exécuté : `check-private-io.py` → 10 entrées, 12 occurrences pour ce changement (positions relues avec le gate).

| Site | Appel | Cible | Primitive retenue |
|---|---|---|---|
| `DistributedArtifactBundleStore.java:133`, `:136` (`createBundle`) | `Files.createTempFile(parent de la sortie)` puis `newOutputStream` dans un `ZipOutputStream` | paquet écrit dans le dossier **de l'appelant** (`:131-132`) | `openNewTreeFile` sur un nom `.bundle-<uuid>.tmp`, puis `replace` (D2) |
| `DistributedArtifactBundleStore.java:303` (`copyBounded`) | `newOutputStream(WRITE, TRUNCATE, NOFOLLOW)` après `createPrivateFile` (`:302`) | artefact extrait dans le cache | `createPrivateOutputStream` |
| `DistributedArtifactBundleStore.java:655`, `:657` (`move`, appelé `:150` et `:219`) | `Files.move` ATOMIC, repli non atomique | paquet publié ; artefact mis en cache | `DurableAtomicFile.replace` |
| `BoundedProcessOutput.java:91` (`openTarget`) | `Files.newByteChannel(WRITE, TRUNCATE, NOFOLLOW)` après `deleteIfExists` + `createPrivateFile` (`:89-90`) | stdout, stderr, métadonnées du provider (appelés avant le lancement, `ProcessIndexerExecutor.java:129-131`) | `createPrivateOutputStream` |
| `ProcessIndexerExecutor.java:276` (`quarantineHostileDirectory`) | `Files.move(ATOMIC_MOVE)` | **répertoire** peuplé par le provider, renommé à côté | `moveDirectory` |
| `ProcessIndexerExecutor.java:548` (`copyArtifactBounded`) | `newOutputStream(CREATE_NEW)` après `deleteIfExists` (`:532`) | `index.partial.scip`, `failed-index.scip` (`:358`, `:528`), copie inter-systèmes (`:630`) | `createPrivateOutputStream` |
| `ProcessIndexerExecutor.java:639` (`move`) | `Files.createTempFile` puis `deleteIfExists` (réservation de nom) | nom de copie dans le dossier cible (`:616` le rend privé) | nom `.artifact-transfer-<uuid>.tmp`, sans fichier temporaire |
| `ProviderWorkspaceFiles.java:151` (`copyBounded`) | `newOutputStream(CREATE_NEW)` | fichier du projet copié dans l'arbre du provider (`:81` crée le dossier, entrée durable) | `openNewTreeFile` |
| `RunDirectoryRetention.java:194` (`quarantine`) | `Files.move(ATOMIC_MOVE)` | **répertoire** de run mis en quarantaine (`:192` rend la quarantaine privée) | `moveDirectory` |
| `LocalIsolatedIndexWorker.java:188` | `Files.createDirectory(workspace)` | arbre de travail du provider, sous `providerRoot` rendu privé (`:184`) | reclassé (D1) |

- **Le conteneur Windows écrit par héritage.** `WindowsAppContainerWorkerSandboxBackend.java:599-640` (`requireInheritableOwnerAccess`) : le dossier de run reçoit une entrée **héritable** d'accès complet avant l'écriture du provider, « un fichier créé par l'identité AppContainer ne porte aucune entrée pour l'utilisateur courant ». `:546-550` : un répertoire rendu privé n'hérite pas de son parent. Conséquences : tout fichier que MINOS crée dans une zone inscriptible par le provider **et relit** doit être durci, jamais hérité ; tout fichier que le provider **lit** (copie du projet) doit hériter, jamais être durci.
- **Journaux et copies conservées** (`openTarget`, `copyArtifactBounded`) : aujourd'hui, `openTarget` crée durci (`createPrivateFile`) ; `copyArtifactBounded` crée par `CREATE_NEW` brut, donc **hérité** — dans le dossier de run, donc avec l'entrée héritable du conteneur sous Windows. Le durcissement vérifié de ces copies est un resserrement, pas un risque (à confirmer par la mesure de la tâche 5.1).
- **Paquet distribué** : `createBundle` reçoit un chemin de sortie explicite ; `LocalIsolatedIndexWorker.java:241-243` lui donne un temporaire de `createPrivateTempFile(workersRoot, …)`, tous les autres appelants (tests, CLI) un chemin libre. `createPrivateTempFile(parent)` durcirait le dossier de l'appelant, ce que l'entrée durable `Files.createDirectories` (`:132`, justification : « durcir son répertoire parent changerait les droits d'un répertoire que MINOS ne possède pas ») refuse.
- **Repli non atomique** : `DistributedArtifactBundleStore.java:657` replie sur `REPLACE_EXISTING` non atomique si `ATOMIC_MOVE` est refusé. `ProcessIndexerExecutor.move` (`:626-647`) refuse déjà ce repli et copie sous le budget SCIP.
- **Gates littéraux** : `check-remote-distributed-consistency.py:109-118` et `check-post-mne.py:205-246` exigent des chaînes (`manifest.properties`, `SharedCacheLeaseRegistry`, `FileTreeOperations.deleteRecursively`, `.bundle-` dans `LocalIsolatedIndexWorker`, `ProviderWorkspaceFiles.copyWorkspace`…) et interdisent `channel.lock()` ; aucune n'attend d'appel `Files.` sur les sites migrés. `check-minos-01.py:159-178` : `QUARANTINE_DIRECTORY`, `reclaimFirst`, `LinkOption.NOFOLLOW_LINKS` dans `RunDirectoryRetention.java` (conservés). `check-module-boundaries.py` (règle A10) cite `ProcessIndexerExecutor` et `BoundedProcessOutput` comme types exportés : aucune visibilité ne change.
- **Scopes JaCoCo** : `m25-remote-distributed-indexing` (`DistributedArtifactBundleStore`, `LocalIsolatedIndexWorker`), `provider-execution-trust-boundary` (`ProcessIndexerExecutor`, `ProviderWorkspaceFiles`), `run-directory-retention`.
- **Tests existants** : `DistributedArtifactBundleStoreTest`, `BundleCacheStoragePrivacyTest`, `BoundedProcessOutput{,Failure}Test`, `ProcessIndexerExecutor{,ArtifactDigest,ArtifactPreservation,BoundaryRefusal,PartialCleanup}Test`, `ProviderWorkspaceFiles{,Refusal,UnreadableDirectory}Test`, `RunDirectoryRetention{,Policy}Test`, `LocalIsolatedIndexWorkerTest`.
- **Recouvrements** (changements actifs, code dans `develop`, non archivés) : `refuser-l-execution-de-provider-sans-frontiere` et `espacer-le-sondage-de-propriete-des-processus` (`ProcessIndexerExecutor`, `LocalIsolatedIndexWorker`), `decouper-les-methodes-d-analyse-et-d-ingestion` (`LocalIsolatedIndexWorker`, spécifié seulement, non implémenté : six gates le citent).

**Non vérifié** : aucun build ni test exécuté ; aucun provider réel lancé sous Windows ni sous Linux (la CI n'en exécute aucun) ; les droits effectifs sous Windows de `index.partial.scip` et de `failed-index.scip` ne sont pas lus.

## Écarts avec la justification de la liste

1. **`BoundedProcessOutput` n'est pas une lecture** : la liste le range sous « lecture par canal … `ConfinedFileOpener` ». C'est l'ouverture en **écriture** des journaux du provider ; elle va à la création exclusive durcie (D3).
2. **`LocalIsolatedIndexWorker.java:188` n'est pas migrable comme la liste l'annonce** : le dossier est lu par le provider sandboxé (`ProviderWorkspaceFiles.copyWorkspace` y copie le projet) ; son voisin `ProviderWorkspaceFiles` est déjà une entrée durable pour la même raison (D1).
3. **`DistributedArtifactBundleStore.createBundle` ne peut pas durcir le dossier de l'appelant** (D2).
4. **Un `createTempFile` ne sert qu'à réserver un nom** (`ProcessIndexerExecutor.java:639-640`) : il est supprimé aussitôt. Il disparaît.
5. **`Files.move` de répertoire** (quarantaine, mise de côté) n'était couvert par aucune primitive : voir le socle.

## Goals / Non-Goals

**Goals** : neuf entrées retirées, une reclassée ; aucun fichier relu par MINOS dans une zone inscriptible par le provider n'est hérité ; aucun fichier lu par le provider n'est durci ; aucun repli non atomique.

**Non-Goals** : changer la politique de bac à sable, les quotas d'écriture, la rétention, le format du paquet ; migrer les `createDirectories` durables.

## Decisions

### D1. `LocalIsolatedIndexWorker.java:188` — recommandation : entrée durable, justification réécrite

Réécrire la justification de l'entrée (« arbre de travail du provider sandboxé, lu par le conteneur par héritage d'ACL ; un dossier privé de MINOS n'hérite pas de son parent »), sans toucher au code, comme `ProviderWorkspaceFiles`. Option B : `ensurePrivateDirectory(workspace)` si la mesure de la tâche 5.1 montre que l'accès du conteneur vient d'une entrée posée sur `workspace` lui-même. Rejetée par défaut : la mesure n'existe pas et la CI n'exécute aucun provider.

### D2. Paquet dans le dossier de l'appelant — recommandation : `openNewTreeFile` puis `replace`

Nom `.bundle-<uuid>.tmp` généré par le code, créé `CREATE_NEW` sans durcir le dossier ; publié par `DurableAtomicFile.replace` vers la cible. Droits : identiques à `Files.createTempFile` (POSIX `0600`, Windows hérité). Option B : exiger un dossier privé de l'appelant et durcir. Rejetée : contredit l'entrée durable `createDirectories` du même fichier.

### D3. Journaux et copies conservées — recommandation : `createPrivateOutputStream`

Suppression préalable conservée (`BoundedProcessOutput.java:89`, `ProcessIndexerExecutor.java:544`), création exclusive durcie ensuite : un nom réapparu fait échouer l'opération. Option B : `openPrivateOutputStream` (créer ou durcir). Rejetée : adopterait un fichier qui préexiste dans une zone inscriptible par le provider.

### D4. Réservation de nom — recommandation : nom UUID, plus de fichier temporaire

`ProcessIndexerExecutor.move` : `.artifact-transfer-<uuid>.tmp` ; la création exclusive de `copyArtifactBounded` garantit l'unicité. Option B : `createPrivateTempFile`. Rejetée : un fichier créé puis supprimé n'a pas de raison d'exister.

### D5. Repli non atomique de `DistributedArtifactBundleStore.move` — recommandation : le retirer

Les deux appels sont dans `MINOS_HOME` (cache) ou le dossier de sortie de l'appelant ; un déplacement non atomique d'un paquet que d'autres processus peuvent lire est un défaut. Conséquence nommée : un système de fichiers sans rename atomique fait échouer `createBundle` et `accept` (jamais observé pour un dossier sous `MINOS_HOME`).

## Direction des dépendances

`runtime → engine` (ADR 0022) ; `com.minos.io` est déjà importé par `DistributedArtifactBundleStore`, `ProcessIndexerExecutor` et `RunDirectoryRetention`, et le sera par `BoundedProcessOutput` (déjà : `PrivateLocalStorage`) et `ProviderWorkspaceFiles` (déjà : `ConfinedFileOpener`). Aucun package nouveau.

## Qualification des capacités

- Journaux et copies de MINOS non modifiables par le provider (Windows) : **qualifiée par la mesure 5.1** ; aujourd'hui **partielle** (`copyArtifactBounded` hérite).
- Copie du projet lisible par le provider : **qualifiée** (inchangée : héritage conservé).
- Mise de côté de répertoire atomique : **qualifiée** sous Linux ; **partielle** sous Windows (reprise bornée si le provider tient un handle).

## Windows et Linux

POSIX : `0600` / `0700`, aucun ACL ; l'isolation bubblewrap ne dépend pas des droits de fichier (`--unshare-all`, même uid). Windows : le cœur du changement (héritage contre durcissement, ci-dessus). Les tests d'ACL sont `@EnabledOnOs(WINDOWS)`.

## Risks / Trade-offs

- **Régression d'accès du conteneur** si un fichier destiné au provider est durci par erreur : couverte par un test `@EnabledOnOs(WINDOWS)` qui vérifie l'entrée héritée sur la copie du projet, et par la mesure 5.1 avec un vrai provider.
- **Quarantaine plus lente sous Windows** (reprise bornée d'`AccessDenied`) : bornée à environ une seconde (`DurableAtomicFile`).
- **Conflits de texte** avec les trois changements actifs ci-dessus : à rebaser, maxima recalculés.

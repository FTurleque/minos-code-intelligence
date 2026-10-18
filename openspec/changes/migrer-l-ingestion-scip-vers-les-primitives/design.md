# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « providers SCIP », partie ingestion. HEAD analysé : `4922f4ac` (`develop`). Dépend de `completer-les-primitives-d-e-s-privees` (lecture par canal, écriture en flux). Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

- `check-private-io.py` : `ScipIndexReader.java:53` (1), `ScipSymbolSnapshotImporter.java:135` et `:137` (2), tous `Files.newByteChannel`, justifiés « lecture par canal … vers `ConfinedFileOpener` ».
- `ScipIndexReader.java:40-67` : contrôle de chemin (`isSymbolicLink`, `isRegularFile NOFOLLOW`, `Files.size`, bornes), puis `Files.newByteChannel(READ, NOFOLLOW_LINKS)` ; préflight par `Channels.newInputStream(channel)`, `channel.position(0L)`, lecture finale. Le canal sert deux lectures du **même** descripteur : une ouverture `InputStream` ne suffit pas (D1).
- `ScipSymbolSnapshotImporter.java:125-165` (`freeze`) : contrôle de chemin, fichier de travail créé par `createScratchFile` (`:167-181`, `PrivateLocalStorage.createPrivateFile`), puis **deux** canaux : `sourceChannel` (`READ`, `NOFOLLOW`, `:135`) et `frozenChannel` (`WRITE`, `NOFOLLOW`, `:137`, enveloppé par `Channels.newOutputStream`) ; copie bornée par `BoundedInputStream`, empreinte SHA-256 pendant la copie, contrôle `sourceChannel.size()` avant / après, puis relecture de taille du fichier figé.
- `ScipSymbolSnapshotImporterBehaviorTest.java:206` exige que le message contienne « does not exist or is not a regular … » : le message de contrôle de chemin (`:128`) est conservé tel quel (D2).
- Gates littéraux : `check-remote-distributed-consistency.py:224` cite `ScipSymbolSnapshotImporter` (nom de classe, pas d'appel). `ScipIndexReader` n'est cité par aucun script. Aucun scope JaCoCo par classe : `global` seul.
- Recouvrement : `lire-l-identifiant-du-snapshot-a-la-reconciliation` (5/6) modifie l'importeur ; code dans `develop`, changement non archivé.

**Non vérifié** : aucun test exécuté. Le comportement du canal sous une jonction Windows suit celui de `openRegularFileNoFollow` (`ConfinedFileOpenerWindowsJunctionTest`).

## Écarts avec la justification de la liste

1. **`ScipSymbolSnapshotImporter.java:137` n'est pas une lecture** : c'est un canal `WRITE` sur le fichier figé. Le migrer vers `ConfinedFileOpener` serait un contresens ; il va à l'écriture privée.
2. Le canal de `ScipIndexReader` est relu après `position(0)` : l'ouverture confinée doit rendre un `SeekableByteChannel`, pas un `InputStream`.
3. Les deux classes refont à la main le contrôle de type que l'ouverture confinée fait déjà ; il est conservé comme première ligne (messages et tests existants) et l'ouverture le répète atomiquement avec l'ouverture elle-même.

## Goals / Non-Goals

**Goals** : retirer deux entrées (trois occurrences) ; l'artefact d'un provider n'est jamais lu en suivant un lien ; le fichier figé est écrit par une primitive privée.

**Non-Goals** : changer les limites, le préflight, la signature publique des deux classes ou les messages.

## Decisions

### D1. Lecture — recommandation : `openRegularFileNoFollowChannel`

Même descripteur pour `size()`, le préflight et l'analyse, comme aujourd'hui. Option B : deux ouvertures `openRegularFileNoFollow`. Rejetée : un fichier remplacé entre les deux ouvertures serait analysé après un préflight d'un autre contenu.

### D2. Contrôles de chemin existants — recommandation : conservés

Les `Files.isSymbolicLink` / `isRegularFile` qui précèdent l'ouverture restent (messages testés) ; la migration ne les supprime pas. Option B : les retirer au profit de la `ConfinementException` de l'ouverture. Rejetée : le test `…BehaviorTest.java:206` fixe le message, et le message actuel nomme le chemin, ce qui est un sujet séparé (hors périmètre).

### D3. Fichier figé — recommandation : `openPrivateOutputStream`

Le fichier est créé privé (`createPrivateFile`, `CREATE_NEW` fail-closed) avant d'être ouvert : l'ouverture rejoue le durcissement (sans effet) et refuse un lien. Option B : `openNewTreeFile` sur un nom jamais créé. Rejetée : elle déplacerait la création.

## Direction des dépendances

`adapters → engine` (ADR 0022) ; `com.minos.io` déjà importé par l'importeur (`:11`). Aucun package nouveau.

## Qualification des capacités

- Lecture d'un artefact provider sans suivi de lien : **qualifiée** sous Linux ; **partielle** sous Windows pour l'identité des ancêtres (déclarée pour `ConfinedFileOpener`).
- Index figé en écriture privée : **qualifiée**.

## Windows et Linux

Pas de branche de plateforme ajoutée. Tests : lien symbolique (`@EnabledOnOs` ou `assumeTrue` sur la capacité), jonction Windows pour le cas de répertoire, fichier spécial (FIFO) sous Linux seul.

## Risks / Trade-offs

- **Conflit avec `lire-l-identifiant-du-snapshot-a-la-reconciliation`** dans l'importeur : texte seulement.
- **Message de chemin absolu conservé** : volontaire, hors périmètre (voir proposition).

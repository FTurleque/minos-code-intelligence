# Design

## Context

Sprint 8 de l'audit du 10 octobre 2026, suite d'AUD-SEC-03 : la migration des 44 écritures brutes figées dans `scripts/architecture/private-io-allowlist.json`. HEAD analysé : `4922f4ac` (`develop`). Premier changement de la série : les neuf autres (`migrer-…`) consomment ce qu'il ajoute. Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

- `python scripts/architecture/check-private-io.py` (exécuté) : `SUCCESS (sources=532, allowlisted-occurrences=107, forbidden=20, primitives=4)`. Les 44 entrées nouvelles du garde pèsent 61 occurrences : 38 portent un lot de migration (50 occurrences), 5 sont `PrivateLocalStorage` (8) et 1 est `LinuxCgroupJob` (3), ces six étant définitives. Une septième, `LocalIsolatedIndexWorker`, est reclassée par `migrer-les-ecritures-d-artefacts-du-runtime-local`.
- **Écriture en flux** : `PrivateLocalStorage.writePrivateFile(Path, byte[])` (`PrivateLocalStorage.java:247-270`) crée le fichier privé ou le durcit, puis l'ouvre `WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS` (`:264`, seul `Files.newOutputStream` de la classe). Il n'existe pas de variante qui rende le flux. Les sites qui écrivent en flux (snapshots, vecteurs, archives, téléchargements) refont la même séquence à la main : `createPrivateTempFile` puis `Files.newOutputStream(temporaire)` (`FileIndexStateStore.java:650-652`, `FileSemanticVectorStore.java:187-192`, `PostgresCodeKnowledgeSnapshotStore.java:388`), ou `createPrivateFile` puis `newOutputStream` `NOFOLLOW` (`DistributedArtifactBundleStore.java:302-308`, `BoundedProcessOutput.java:90-93`).
- **Lecture par canal** : `ConfinedFileOpener.openRegularFileNoFollow` (`:131-150`) contrôle le type (`isSymbolicLink`, `isOther`, `isRegularFile`) puis ouvre `READ, NOFOLLOW_LINKS` et rend un `InputStream`. `BoundedFileDigest.sha256Exact` (`:21-45`) ouvre un canal `READ, NOFOLLOW_LINKS` (`:27`) pour lire `size()` avant et après le hachage ; `ScipIndexReader.java:53-66` relit le même descripteur (`position(0L)`) après un préflight ; `ScipSymbolSnapshotImporter.java:135-137` lit `size()` de la source.
- **Répertoires** : `DurableAtomicFile.publish` / `replace` (`:72-80`, `:101-136`) appellent `forceFile` (`FileChannel.open(file, WRITE)`, `:204`) puis une publication par lien dur (`publishExclusively`, `:161`) : inapplicables à un répertoire (le lien dur d'un répertoire n'existe pas, `FileChannel.open` d'un répertoire en écriture est refusé sous Windows). Six sites de `Files.move` (une dizaine d'appelants du helper de `ManagedScipProviderRuntimeManager` compris) déplacent un répertoire : `ProcessIndexerExecutor.java:276`, `RunDirectoryRetention.java:194`, `JGitRemoteRepositoryMaterializer.java:401`, `ManagedPolyglotScipRuntimeManager.java:500-502`, `ManagedScipPythonRuntimeManager.java:284`, `ManagedScipProviderRuntimeManager.java:929-930` (qui sert aussi à des fichiers).
- **Entrées de `PrivateLocalStorage`** (définitives) : `createDirectory` `:402`, `createFile` `:189`, `createTempDirectory` `:224`, `createTempFile` `:206`, `:352`, `:358`, `:814`, `newOutputStream` `:264`.
- Tests existants à étendre : `PrivateLocalStorageWritesTest`, `ConfinedFileOpenerTest`, `DurableAtomicFileTest`, `BoundedFileDigestTest`.
- Recouvrements : `verifier-le-proprietaire-des-dossiers-prives` (6/7) et `conserver-les-refus-conditionnels-windows` (10/11) modifient `PrivateLocalStorage` ; leur code est dans `develop` (PR 490 à 494), leurs changements ne sont pas archivés. Gates littéraux qui lisent les fichiers modifiés : `scripts/remediation/check-p0-p2.py:139-141` exige `attributes.isOther()` dans `PrivateLocalStorage.java` et `ConfinedFileOpener.java` et la phrase « does <em>not</em> claim the same ancestor-identity proof » dans `ConfinedFileOpener.java` (à conserver telles quelles) ; `check-runtime-dynamic-consistency.py:80` exige `ConfinedFileOpener.openRegularFileNoFollow` dans `RuntimeObservationEnvelopeCodec.java` (la méthode existante reste). Aucun gate ne cite les quatre méthodes nouvelles.

**Non vérifié** : aucun build ni test Java exécuté ; le coût d'un second durcissement sous Windows (écart 2) n'est pas mesuré.

## Écarts avec l'action attendue

Le sprint 8 renvoie la migration à « `PrivateLocalStorage` / `DurableAtomicFile` ». Relevé :

1. **Ces deux primitives ne suffisent pas.** Vingt-trois sites écrivent en flux, trois lisent par canal, six sites de `Files.move` déplacent un répertoire. Sans primitive nouvelle, la migration remplacerait une écriture brute par une copie en mémoire (inacceptable pour un snapshot de plusieurs centaines de Mo) ou par une entrée de liste de plus.
2. **Le durcissement par fichier est le mauvais niveau pour les extractions en masse.** `PrivateLocalStorage.harden` réécrit la DACL à la première rencontre d'un objet (`firstTouch`, `PrivateLocalStorage.java:468`) : milliers d'ACL pour une archive. Et sous Windows « un répertoire que MINOS a rendu privé n'hérite pas de son parent » (`WindowsAppContainerWorkerSandboxBackend.java:546-550`) : durcir les fichiers d'un arbre lu par le conteneur casserait l'accès accordé par héritage. D'où deux niveaux (D1).
3. **Une justification de la liste est inexacte.** `BoundedProcessOutput.java:91` et `ScipSymbolSnapshotImporter.java:137` sont des canaux **en écriture** ; la liste les range sous « lecture par canal … vers `ConfinedFileOpener` ». Ils migrent vers l'écriture privée, pas vers l'ouverture confinée.
4. **`Files.move` n'est pas une seule chose.** Fichier (remplacement atomique : `DurableAtomicFile.replace` suffit), répertoire (primitive absente), et trois sites qui retombent sur un déplacement non atomique quand `ATOMIC_MOVE` est refusé (`ManagedScipProviderRuntimeManager.java:930`, `ManagedPolyglotScipRuntimeManager.java:502`, `DistributedArtifactBundleStore.java:657`). Les primitives échouent fermées à cet endroit : changement voulu, à nommer dans chaque lot.

## Goals / Non-Goals

**Goals** : trois capacités manquantes dans le moteur, testées, sans nouvelle entrée de liste ; la dernière entrée du lot « moteur » retirée.

**Non-Goals** : migrer un site hors du moteur ; une primitive de copie ; toucher aux ACL ou à la vérification de propriétaire ; changer la signature d'une méthode publique existante.

## Decisions

### D1. Trois ouvertures en flux, deux niveaux de confidentialité — recommandation : trois ouvertures

- **Niveau durci, créer ou durcir** `PrivateLocalStorage.openPrivateOutputStream(Path file)` : même séquence que `writePrivateFile` (créer privé si absent, durcir sinon, refuser lien ou objet spécial), puis rendre le flux ouvert `WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS`. `writePrivateFile` est réécrit au-dessus : l'entrée `Files.newOutputStream` de la classe reste à 1. Sert un fichier qui préexiste déjà privé (temporaire de `createPrivateTempFile`, fichier de travail de `createPrivateFile`) ou que l'on remplace (verrou npm).
- **Niveau durci, création exclusive** `PrivateLocalStorage.createPrivateOutputStream(Path file)` : comme `createPrivateFile` (échec si le nom existe, droits privés à la création, durcissement vérifié) puis ouverture `NOFOLLOW_LINKS`. Sert les copies que MINOS relit dans un dossier **inscriptible par le provider** (journaux de processus, copie d'artefact conservée) : le fichier ne peut ni préexister ni hériter l'accès du conteneur, et un nom réapparu entre la suppression et la création fait échouer l'opération au lieu d'être adopté.
- **Niveau arbre privé** `PrivateLocalStorage.openNewTreeFile(Path file)` : `CREATE_NEW`, sans suivre de lien, droits POSIX `0600` demandés à la création, ACL Windows **héritée** du dossier, aucune réécriture de DACL. Refuse un parent immédiat qui n'est pas un répertoire réel (ni lien ni objet spécial) ; les ancêtres ne sont pas vérifiés. La confidentialité d'un tel fichier est celle de sa racine, déjà rendue privée par `ensurePrivateDirectory` ou, pour un arbre de provider, acquise par héritage : la primitive ne la **revérifie pas** par fichier (capacité « partielle », déclarée dans la Javadoc et `docs/developer/quality-gates.md`). Sert les extractions d'archive, les copies de JAR et d'artefacts, et la copie du projet dans l'arbre que le provider lit.
- Option B : un seul niveau durci partout. Rejetée : écart 2. Option C : retirer la création exclusive durcie (deux ouvertures). Rejetée : `BoundedProcessOutput.openTarget` (`:80-93`) et `DistributedArtifactBundleStore.copyBounded` (`:301-313`) refont exactement cette séquence à la main et la liste de tolérance les range sous deux rubriques différentes.

### D2. Noms et signatures — recommandation : ceux ci-dessus

`openPrivateOutputStream(Path)`, `createPrivateOutputStream(Path)` et `openNewTreeFile(Path)` rendent un `OutputStream` (l'appelant l'enveloppe dans un `BufferedOutputStream`). `ConfinedFileOpener.openRegularFileNoFollowChannel(Path)` rend un `SeekableByteChannel` en lecture seule, avec les mêmes contrôles que `openRegularFileNoFollow` et la même `ConfinementException` sans chemin. `DurableAtomicFile.moveDirectory(Path source, Path target, String label)`. Les noms se figent à l'implémentation ; aucun site existant n'est renommé.

### D3. Sémantique de `moveDirectory` — recommandation : exclusive, sans repli

Rename atomique de **l'entrée elle-même** (répertoire, mais aussi fichier ou lien qui l'a remplacée : `RunDirectoryRetention.quarantine` met de côté une entrée qu'un provider hostile a pu remplacer, et ne doit jamais suivre de lien) vers un nom **inexistant** ; une cible existante est refusée (`FileAlreadyExistsException`) ; un système de fichiers sans rename atomique échoue (`IOException` dont la cause est `AtomicMoveNotSupportedException`) ; jamais de copie puis suppression. Sous Windows, reprise bornée (la boucle de `atomicMove`, `:138-150`, **paramétrée** : elle passe aujourd'hui `REPLACE_EXISTING` en dur, `:141`, que `moveDirectory` ne doit pas demander) quand un lecteur tient le répertoire ouvert ; synchronisation du parent là où Java le permet (`forceDirectory`, inerte sous Windows, documenté). Les appelants qui remplacent un répertoire le suppriment avant (c'est déjà le cas de `replaceDirectory` et de `swapIn`). Limite à déclarer : l'exclusivité est un contrôle suivi d'un rename, pas une opération atomique (le lien dur n'existe pas pour un répertoire) ; sous POSIX, `rename(2)` remplace un répertoire **vide**. Option B : `REPLACE_EXISTING`. Rejetée : le JDK en laisse le comportement non spécifié (déjà noté `DurableAtomicFile.java:154-160`).

### D5. Un ADR pour les deux niveaux de confidentialité — recommandation : oui, court, en `Proposed`

Le niveau « arbre privé » est une décision de sécurité (confidentialité héritée de la racine, non revérifiée par fichier) qui contraint les neuf autres changements et la façon d'écrire dans une zone lue par un provider. Elle mérite un ADR (gabarit `docs/architecture/adr/template.md`, index et `arc42/09-decisions.md`), rédigé en tâche 5.1 et accepté par le propriétaire. Option B : `docs/developer/quality-gates.md` seul. Moins contraignant, mais la décision n'aurait pas d'identifiant à citer dans les revues.

### D4. Aucune entrée de liste nouvelle dans `PrivateLocalStorage` — recommandation : objectif dur

Les trois ouvertures factorisent les sites `Files.createFile` (`:189`) et `Files.newOutputStream` (`:264`) en méthodes privées partagées. La tâche 6.1 relit les maxima. Si cela s'avère impossible, une entrée « durable » de plus (même style que les cinq existantes) est une décision à reprendre ici, pas un correctif de dernière minute.

## Direction des dépendances

`minos-engine` n'importe rien de plus ; les neuf autres changements ajoutent chacun un appel vers `com.minos.io` (déjà autorisé : `application → engine`, `storage → engine`, `adapters → engine`, ADR 0022). Aucun package nouveau, aucun cycle (règle A8).

## Qualification des capacités

- Écriture en flux sans suivi de lien, niveau durci (créer ou durcir, création exclusive) : **qualifiée** (même garanties que `writePrivateFile`, déjà testé).
- Écriture en flux, niveau arbre privé : **partielle** — confidentialité héritée de la racine, non revérifiée par fichier.
- Lecture par canal sans suivi de lien : **qualifiée** sous POSIX et Windows (mêmes contrôles de type que l'existant) ; l'ancêtre n'est pas prouvé sous Windows, comme `openRegularFileNoFollow`.
- Publication de répertoire : **qualifiée** sous Linux ; **partielle** sous Windows (synchronisation du parent inerte, déjà déclarée pour les fichiers).

## Windows et Linux

POSIX : `0600` demandé à la création pour le niveau arbre ; sous Windows, héritage. Les tests d'ACL restent `@EnabledOnOs(WINDOWS)` ; ceux de mode POSIX `@EnabledOnOs({LINUX, MAC})` (convention de `tests-java`). Le rename de répertoire est testé sur les deux ; la reprise Windows par injection de la plateforme (`Platform`, `DurableAtomicFile.java:260`), comme `DurableAtomicFileTest`.

## Risks / Trade-offs

- **Second durcissement** : `openPrivateOutputStream` rejoue `hardenExistingFile` sur un fichier que `createPrivateTempFile` vient de durcir. Après la première touche, `harden` ne réécrit pas une DACL déjà conforme (`:470-478`) ; à mesurer sur un snapshot réel avant de généraliser (tâche 2.3).
- **Surface qui grossit** : cinq points d'entrée de plus dans `com.minos.io`. Compensé par leur usage immédiat dans neuf changements et par le retrait de 38 entrées de liste.
- **Faux sentiment de sécurité du niveau arbre** : d'où la qualification « partielle » et la règle de revue : tout appelant du niveau arbre nomme dans son test la racine privée dont il hérite.

# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « providers SCIP », partie installation. HEAD analysé : `4922f4ac` (`develop`). Dépend de `completer-les-primitives-d-e-s-privees` (écriture en flux à deux niveaux, publication de répertoire). Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

Relevé exécuté : `check-private-io.py` → 9 entrées, 14 occurrences (positions relues avec le gate).

| Site | Appel | Cible | Primitive retenue |
|---|---|---|---|
| `LockedNpmPackage.java:47` | `Files.copy(ressource, lock, REPLACE_EXISTING)` | `package-lock.json`, dossier rendu privé (`:39`), peut exister | `openPrivateOutputStream` |
| `ManagedPolyglotScipRuntimeManager.java:455` | `Files.createTempFile(workingDirectory, …)` | journal de commande (`run`, appelé avec `home` = `MINOS_HOME`, y compris par `probe` sur les chemins de lecture `inspect` / `list`) | `createPrivateTempFile` dans un sous-dossier dédié (D4) |
| `ManagedPolyglotScipRuntimeManager.java:500`, `:502` | `Files.move` (ATOMIC puis repli) | **répertoire** installé (`replaceDirectory`, après `deleteRecursively(destination)`) | `moveDirectory` |
| `ManagedScipProviderRuntimeManager.java:472` | `Files.newOutputStream(CREATE_NEW, NOFOLLOW)` (`writeNewFile`, `copyJar`) | JAR, lanceur et listing du classpath de `scip-java` | `openNewTreeFile` |
| `ManagedScipProviderRuntimeManager.java:504` | `Files.copy(ressource, partial)` | `*.partial` supprimé avant (`:503`) | `openNewTreeFile` puis `replace` |
| `ManagedScipProviderRuntimeManager.java:534` | `Files.copy(zip, executablePartial)` | `cs.partial.exe`, supprimé avant (`:522`) | `openNewTreeFile` puis `replace` |
| `ManagedScipProviderRuntimeManager.java:831` | `Files.newOutputStream(CREATE, TRUNCATE_EXISTING)` (`extractZipBounded`) | fichiers d'une archive Maven, Node ou assemblée | `openNewTreeFile` |
| `ManagedScipProviderRuntimeManager.java:929`, `:930` | `Files.move` (ATOMIC puis repli) | helper `move` : fichiers (`:505`, `:526`, `:542`, `:566`, `:603`) **et** répertoires (`:299`, `:445`, `:447`, `:457`, `:728`) | scindé : `replace` / `moveDirectory` |
| `ManagedScipPythonRuntimeManager.java:284` | `Files.move(REPLACE_EXISTING)` | **répertoire** (`:150`, après `deleteRecursively`), sans `ATOMIC_MOVE` | `moveDirectory` |
| `PinnedArtifactSource.java:165`, `:204` | `Files.newOutputStream(CREATE_NEW)` | archive `*.partial.zip` (copie embarquée, téléchargement) | `openNewTreeFile` |
| `ScipJavaProcessPlanFactory.java:314` | `Files.newOutputStream(CREATE_NEW)` | copie du projet dans `run/workspace`, arbre créé par `createDirectories` (entrée durable) | `openNewTreeFile` |

- **Pourquoi deux niveaux.** `ManagedScipProviderRuntimeManager.installClasspath` (Javadoc `:403-410`) : « Les fichiers héritent de l'accès propriétaire seul du dossier `partial` … au lieu de porter chacun une liste d'accès protégée … le bac à sable Windows accorde son accès en lecture sur le dossier, et un fichier protégé ne l'hériterait pas. » `WindowsAppContainerWorkerSandboxBackend.java:546-550` le confirme : un répertoire rendu privé n'hérite pas de son parent. Durcir ces fichiers (`openPrivateOutputStream`) casserait donc l'index `scip-java` sous Windows ; `openNewTreeFile` est le niveau fait pour eux.
- **Règle de choix** : `CREATE_NEW` possible (le fichier est supprimé avant, ou jamais créé) → `openNewTreeFile` ; le fichier peut préexister et doit être durci → `openPrivateOutputStream` (seul `LockedNpmPackage` : son voisin `package.json` est déjà écrit par `writePrivateFile`).
- **Repli non atomique** : `ManagedScipProviderRuntimeManager.java:930` (`catch AtomicMoveNotSupportedException → Files.move(REPLACE_EXISTING)`) et `ManagedPolyglotScipRuntimeManager.java:502` replient sur un déplacement non atomique ; `ManagedScipPythonRuntimeManager.java:284` n'a jamais demandé l'atomicité. Les primitives échouent fermées : un dossier d'outil à moitié déplacé n'est plus possible.
- **Extraction** : `extractZipBounded` ouvre `CREATE, TRUNCATE_EXISTING` (écrase). Avec `CREATE_NEW`, une archive qui contient deux fois le même chemin, ou deux chemins qui ne diffèrent que par la casse sous un système de fichiers insensible à la casse, échoue (D3). Les archives extraites sont celles que MINOS épingle ou assemble (empreinte SHA-256 vérifiée avant extraction, ADR 0040).
- **Appels de `run`** : `home` (`ManagedPolyglotScipRuntimeManager.java:64`, `MINOS_HOME` normalisé) pour les sondes et les installations (`:265`, `:300`, `:442`). `createPrivateTempFile(home, …)` appelle `ensurePrivateDirectory(home)` : refus d'un `MINOS_HOME` qui serait le profil ou un dossier d'un autre compte (`verifier-le-proprietaire-des-dossiers-prives`), resserrement des droits de `MINOS_HOME` si ils étaient plus larges.
- **Scopes JaCoCo** : `m24-polyglot-provider-platform` (planchers par classe : `ManagedScipProviderRuntimeManager` 0,61 / 0,49, `ManagedPolyglotScipRuntimeManager` 0,28 / 0,20, `ScipJavaProcessPlanFactory` 0,35 / 0,15) ; rouge sous Windows hors régression (comparer à `develop`).
- **Gates littéraux** : `check-tools-manifest.py`, `check-polyglot-provider-consistency.py`, `check-mnd.py`, `check-mne.py`, `check-post-mne.py`, `check-p0-p2.py` lisent ces classes ; la recherche de `Files.` / `ATOMIC_MOVE` / `StandardCopyOption` / `OutputStream` dans `scripts/` (hors gate d'E/S et historique) ne trouve aucune attente sur ces fichiers. Rejouer tout de même.
- **Tests existants** : `ManagedScipProviderRuntimeManager{,Runtime,Toolchain}Test`, `ManagedPolyglotScipRuntimeManager{,Toolchain}Test`, `ManagedScipPythonRuntimeManagerTest`, `PinnedArtifactSource{,Assembled}Test`, `LockedNpmPackage{,Refusal}Test`, `ScipJavaProcessPlanFactory{Staging,Refusal,…}Test`.
- **Recouvrements** (changements actifs, code dans `develop`, non archivés) : `surveiller-les-outils-embarques` (9/11 ; `LockedNpmPackage`, `ManagedScipProviderRuntimeManager`, `ManagedScipPythonRuntimeManager`) et `executer-scip-java-sur-classpath-verifie` (`ManagedScipProviderRuntimeManager`, `ScipJavaProcessPlanFactory`, backend bubblewrap). Texte seulement.

**Non vérifié** : aucun build ni test exécuté ; aucun provider réel lancé (la CI n'en exécute aucun) ; le coût de `forceFile` sur une archive de plusieurs centaines de Mo n'est pas mesuré.

## Écarts avec la justification de la liste

1. La liste renvoie à « `PrivateLocalStorage` / `DurableAtomicFile` » pour tout. Pour sept occurrences (`:472`, `:504`, `:534`, `:831`, `PinnedArtifactSource` ×2, `ScipJavaProcessPlanFactory:313`), le durcissement par fichier de `PrivateLocalStorage` aurait cassé l'héritage d'ACL (niveau arbre, D1) ; et les déplacements de **répertoire** (`replaceDirectory`, `swapIn`, l'installation npm et celle des arbres assemblés, Python) ne sont pas couverts par `DurableAtomicFile`.
2. Le helper `move` de `ManagedScipProviderRuntimeManager` sert à la fois des fichiers et des répertoires : il se scinde, ses appelants choisissent.
3. L'écrasement par l'extraction (`TRUNCATE_EXISTING`) est une propriété de l'existant que `CREATE_NEW` retire : changement voulu, nommé (D3).

## Goals / Non-Goals

**Goals** : neuf entrées retirées ; l'installation échoue fermée si le remplacement atomique manque ; aucun fichier d'arbre ne perd l'héritage d'ACL dont le bac à sable dépend.

**Non-Goals** : changer ce qui s'installe, les budgets, les empreintes ; toucher aux entrées `createDirectories` durables ; ajouter une primitive de copie.

## Decisions

### D1. Niveau par site — recommandation : la règle de choix ci-dessus

Tout fichier créé neuf dans un dossier privé : niveau arbre. Seul un fichier qui peut préexister et que MINOS doit durcir : niveau durci. Option B : niveau durci partout. Rejetée : casse l'accès du conteneur Windows (voir l'état vérifié).

### D2. Déplacements — recommandation : `replace` pour un fichier, `moveDirectory` pour un répertoire

Les appelants suppriment déjà la destination avant un déplacement de répertoire (`deleteRecursively(destination)` précède `:291`, `:720`, `:150`, `replaceDirectory`) ; `swapIn` déplace l'ancien arbre à côté puis le nouveau et restaure en cas d'échec. Aucun appelant n'a besoin du remplacement d'un répertoire existant.

### D3. Extraction — recommandation : `CREATE_NEW` après nettoyage du répertoire extrait, doublon = archive corrompue

`ensureMaven` et `ensureNode` extraient dans une racine qui n'est pas vidée et n'ont aucun nettoyage en cas d'échec : une extraction interrompue laisse des fichiers que `TRUNCATE_EXISTING` écrase aujourd'hui à la relance, mais que `CREATE_NEW` refuserait indéfiniment. L'extraction supprime donc d'abord le répertoire de premier niveau de l'archive (`apache-maven-<version>`, `nodeDistributionId()`) par `FileTreeOperations.deleteRecursively`, comme les installations d'arbre le font déjà pour `partial`, avec un test « relance après extraction partielle ». Une entrée dupliquée dans une archive épinglée par empreinte est un défaut de l'archive. Échec fermé avec le message d'archive existant (« archive entry escapes destination » est un autre cas ; un message neutre « archive has a duplicate entry » est ajouté, sans chemin absolu). Option B : `openPrivateOutputStream` (qui tronque). Rejetée : durcit chaque fichier, voir D1.

### D4. Journal de commande — recommandation : un sous-dossier privé dédié

`run` sert aussi `probe`, donc `inspect` et `list` (lecture : statut, `doctor`). `createPrivateTempFile(home, …)` rendrait `MINOS_HOME` lui-même privé (chmod ou réécriture de DACL) sur un chemin de lecture, et un `MINOS_HOME` partagé, en lecture seule ou refusé par la vérification de propriétaire ferait déclarer le provider indisponible à tort (faux négatif, contraire à « capability-honest »). Le journal est créé dans un sous-dossier dédié de `MINOS_HOME` (nom fixé à l'implémentation) : `ensurePrivateDirectory` ne durcit que cette feuille, pas ses parents existants. Aucune écriture nouvelle sur le chemin de lecture : le fichier temporaire existe déjà aujourd'hui. Option B : `createPrivateTempFile(home, …)`. Rejetée : écart ci-dessus.

### D6. Dossier parent des déplacements — recommandation : conserver `ensurePrivateDirectory(parent)`

Les helpers `move` de `ManagedScipProviderRuntimeManager` (`:927`) et de `ManagedScipPythonRuntimeManager` (`:282`) appellent d'abord `PrivateLocalStorage.ensurePrivateDirectory(parent)`. `replace` et `moveDirectory` ne le font pas : l'appel reste avant chaque publication, pour que le dossier parent (`scip-typescript/<version>` par exemple) existe et soit privé.

### D5. Mesure avant fusion — recommandation : deux mesures **(manuelles)**

(a) `forceFile` d'une archive Node de 30 à 40 Mo et d'un arbre `node_modules` déplacé (`moveDirectory`) sous Windows avec l'antivirus actif ; (b) l'installation puis l'indexation réelle de `scip-java` sous Windows (AppContainer) et sous Linux (bubblewrap) avec le résultat de la tâche 4.1. Seuil d'abandon : une installation qui dure plus de 25 % de plus, ou un échec d'accès du conteneur.

## Direction des dépendances

`adapters → engine` (ADR 0022) ; `com.minos.io` est déjà importé par les cinq fichiers. Aucun package nouveau, aucun cycle.

## Qualification des capacités

- Installation par déplacement atomique de fichier : **qualifiée** sous Linux et Windows (retry Windows borné de `DurableAtomicFile`).
- Publication atomique d'un répertoire installé : **qualifiée** sous Linux ; **partielle** sous Windows (antivirus, synchronisation du parent inerte).
- Héritage d'ACL des fichiers d'arbre pour le conteneur : **non revérifié par fichier** (niveau arbre, déclaré) ; qualifié par la mesure D5(b).

## Windows et Linux

POSIX : fichiers d'arbre en `0600` (au lieu de `0644 & ~umask`) dans un dossier `0700` ; les lanceurs exécutables sont recréés par le code (bit d'exécution inchangé : aucun appelant ne le positionnait sur ces fichiers). Windows : ACL héritée ; la jonction ou le lien dans l'arbre est refusé par `CREATE_NEW`. `LinuxBubblewrapWorkerSandboxBackend.java:360` isole les espaces de noms (`--unshare-all`) sans option `--uid` : le fichier `0600` du même utilisateur reste lisible ; à confirmer par D5(b).

## Risks / Trade-offs

- **Installation lente** (`fsync` d'archives, retry Windows) : mesurée par D5(a).
- **Perte du repli non atomique** : un volume sans rename atomique fait échouer l'installation au lieu de la dégrader ; cohérent avec « fail-closed » et jamais observé pour des dossiers sous `MINOS_HOME`.
- **Doublon de casse dans une archive Windows** : D3 ; non observé dans les archives épinglées, à vérifier à la mesure D5(b).
- **Justifications des entrées `createDirectories` restantes** : celles de `ManagedScipProviderRuntimeManager` (« à migrer si besoin »), `RustAnalyzerScipProcessPlanFactory` et `ScipJavaProcessPlanFactory` sont réécrites comme durables (tâche 4.4) : le changement ne les migre pas, la liste doit le dire.
- **Taille du changement** : neuf entrées, six fichiers. Découpage possible si la revue l'exige : binaires et archives (`PinnedArtifactSource`, `ManagedScipProviderRuntimeManager`) d'une part, arbres et journaux (`Polyglot`, `Python`, `LockedNpm`, `ScipJava`) d'autre part.

## Constat de mise en œuvre

- **Neuf entrées, quatorze occurrences retirées** de `private-io-allowlist.json` (`LockedNpmPackage`, `ManagedPolyglotScipRuntimeManager` ×2, `ManagedScipProviderRuntimeManager` ×3, `ManagedScipPythonRuntimeManager`, `PinnedArtifactSource`, `ScipJavaProcessPlanFactory`) ; les justifications des trois entrées `createDirectories` restantes sont réécrites comme durables (tâche 4.4), maxima inchangés.
- `LockedNpmPackage` : `openPrivateOutputStream` + `transferTo` (le lien est refusé, le fichier existant est remplacé et durci). `ManagedPolyglotScipRuntimeManager.run` : journal par `newCommandLog` → `createPrivateTempFile` dans le sous-dossier `.minos-command-logs` du dossier de travail (D4) ; un dossier de travail lien ou absent est refusé sans rien créer ni durcir ; les surcharges d'environnement sont validées avant tout fichier.
- Fichiers d'arbre : `ManagedScipProviderRuntimeManager.writeNewFile`, `copyPackagedResource`, copie de `cs.exe`, `extractZipBounded` (`openExtractedFile` : un nom déjà pris donne « archive has a duplicate entry », sans chemin), `PinnedArtifactSource.copyVerified` / `download`, `ScipJavaProcessPlanFactory.copyRegularFileBounded` écrivent par `openNewTreeFile`. `extractDistribution` supprime d'abord l'arbre de premier niveau de la distribution (D3) : relance après extraction interrompue testée.
- Déplacements : le helper `move` est scindé en `moveFile` (`DurableAtomicFile.replace`) et `moveDirectory` (`DurableAtomicFile.moveDirectory`), `ensurePrivateDirectory(parent)` conservé avant chaque publication (D6) ; `swapIn` reçoit un `DirectoryMover` package-private (défaut `moveDirectory`) ; `ManagedPolyglotScipRuntimeManager.replaceDirectory` et `ManagedScipPythonRuntimeManager.moveDirectory` publient par `DurableAtomicFile.moveDirectory`. Plus aucun repli non atomique.
- Tests : `ManagedToolInstallationFilesTest` (10 : extraction, doublon, lien, relance, ressource, swap réel / restauration / refus non atomique), `LockedNpmPackageLockfileWriteTest` (4), `ManagedRuntimeDirectoryPublicationTest` (7 : journal, répertoires polyglotte et Python), `ScipJavaStagedFileCopyTest` (2), un test ajouté à `PinnedArtifactSourceTest` ; `ManagedRuntimeCommandRunnerTest` lit désormais le sous-dossier du journal. Module `minos-provider-scip` : tests verts. Gates : `check-tools-manifest`, `check-polyglot-provider-consistency`, `check-mnd`, `check-mne`, `check-post-mne`, `check-p0-p2`, `test_check_private_io`, `run_gates.py --fast` 21/21, `check-current-docs` ; `docs/developer/polyglot-providers.md` précise le renommage atomique.
- Écarts : 1.1 — le journal est testé par `newCommandLog` (privé, dossier dédié, lien refusé) et non en cours de commande ; 2.1 (c) — l'héritage d'ACL Windows du fichier d'arbre est qualifié au niveau du moteur (`PrivateLocalStorageStreamsTest`), ici seule la permission POSIX `0600` est assertée ; le repli non atomique est supprimé par construction (aucune API du module ne le propose plus), l'échec fermé est testé par injection sur `swapIn` ; `Coursier` (`cs.exe`) et `download` (HTTP) ne sont pas exécutés par un test.
- **Tâche 4.1 (D5) : (a) mesurée, (b) non faite.** (a) Remplacement d'une archive de 36 Mio, médiane de 7 passes : `DurableAtomicFile.replace` 28,0 ms contre `Files.move` 4,3 ms sous Windows (poste, antivirus actif), 78,8 ms contre 9,4 ms sous Linux en conteneur ; déplacement d'un arbre de 4 000 fichiers : `moveDirectory` 6,5 ms contre 6,5 ms sous Windows, 21,8 ms contre 0,05 ms sous Linux. Surcoût absolu de 24 à 70 ms par installation, très en deçà de 25 % d'une installation qui télécharge, extrait ou lance `npm ci`. (b) L'installation puis l'indexation réelles de `scip-java` (AppContainer, bubblewrap) ne sont pas réalisables sur ce poste (aucun provider réel lancé, la CI n'en exécute aucun) : à faire sur un hôte avec réseau avant de considérer l'héritage d'ACL des fichiers d'arbre comme qualifié de bout en bout.
- Reste pour la CI : JaCoCo (`m24-polyglot-provider-platform`, `global`), SpotBugs (baseline `minos-provider-scip.xml`).

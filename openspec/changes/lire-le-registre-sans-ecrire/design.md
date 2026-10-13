# Design

## Context

Défaut constaté le 13 octobre 2026 pendant `separer-l-image-de-requete` (PR #441). Il n'a pas d'identifiant d'audit ; les tâches portent les identifiants locaux **LS-01** (registre), **LS-02** (observations runtime), **LS-03** (migration héritée), **LS-04** (qualification d'image). HEAD analysé : `d0aa51a8` (`develop`) pour le code Java, `4e5a18f5` (branche de la PR #441) pour le script de qualification et les fichiers compose.

## État vérifié au HEAD

### Le défaut — reproduit

**Avec Docker** (moteur 29.8.2, linux/amd64). Jar du HEAD `d0aa51a8` construit par `./mvnw -B -ntp -DskipTests -DskipITs package` (JDK 25.0.4.1), image construite par la cible `query` du `Dockerfile.mcp.release` de la PR #441. Un volume de données est préparé par un conteneur en écriture (`project add`, `import-scip` de `fixtures/typescript/typescript-modules`), puis le serveur MCP est lancé dans un conteneur durci comme `minos-mcp` (`--network none --read-only --cap-drop ALL`, `no-new-privileges`, uid 10001, `/tmp` en tmpfs) et les 31 outils de `tools/list` sont appelés un par un :

| Données | Réponses | Refus fonctionnels | Échecs internes |
|---|---|---|---|
| en écriture | 23 | 8 (5 « team mode is disabled », 3 « unknown symbol » dus aux arguments génériques) | **0** |
| en lecture seule (`:ro`) | 0 | 5 (« team mode is disabled », rendus avant toute lecture) | **26** |

Journal du serveur en lecture seule : `MCP tool execution failed (tool=minos_find_symbols, type=java.nio.file.FileSystemException, rootCause=java.nio.file.FileSystemException)` pour chacun des 26. Le serveur **démarre** : `initialize` et `tools/list` répondent, ce qui explique que les qualifications n'aient rien vu.

**Sans Docker**, par deux tests écrits avant tout correctif (commit `test(mcp): rouge — …`) :

- `InterProcessLocalProjectRegistryWriteProtectedStorageTest` (`minos-storage-local`) et `MinosMcpWriteProtectedHomeTest` (`minos-mcp`), avec le support de test `WriteProtectedTree` (`minos-engine`, `com.minos.testsupport`).
- **Windows** (entrée ACL de refus d'écriture sur chaque objet) : `AccessDeniedException` sur `.registry.lock` dès `findProject` ; `minos_find_symbols` rend « MINOS tool execution failed » ; `Tests run: 2, Errors: 2` et `Tests run: 2, Failures: 2`.
- **Linux** (conteneur `eclipse-temurin:25…-jdk-noble`, utilisateur non root, fichiers en `0400`) : la lecture réussit mais **rouvre le verrou aux écritures** — `.registry.lock` passe de `r--------` à `rw-------` ; `Tests run: 2, Failures: 2` deux fois.

Sous POSIX, aucune permission ne résiste à son propriétaire (le durcissement remet `0600`, root ignore les permissions) : un vrai refus `EROFS` ne s'obtient pas depuis un test Java. Le test POSIX prouve donc l'**absence d'écriture** (permissions, tailles, dates, entrées identiques avant et après), et la preuve du refus réel est faite par la qualification Docker (LS-04).

### La cause

- `InterProcessLocalProjectRegistry.java:93-120` : `findProject`, `findWorkspace`, `listProjects`, `inventory`, `listWorkspaces`, `workspaceInventory` passent tous par `withLock` (`:137-144`), le même bail que les écritures.
- `BoundedFileLease.java:102-105` : `openPrivateLockChannel` prépare le fichier de verrou (`:107-125` : `hardenExistingFile` s'il existe, `createPrivateFile` sinon) puis l'ouvre par `FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)`. Sur un montage en lecture seule, l'ouverture échoue (`EROFS`).
- Tous les outils MCP résolvent d'abord le projet par le registre : un seul verrou les fait tous échouer.

### Depuis quand, et pourquoi

| Date | Commit | Fait |
|---|---|---|
| 2026-08-04 | `2de847bd` (v1.0.1) | `docker/compose.mcp.prod.yaml` monte `/var/lib/minos` avec `read_only: true` pour `minos-mcp`. Le registre est alors lu sans verrou. |
| 2026-08-10 | `845136ed` « Serialize local project registry across processes » | Création de `InterProcessLocalProjectRegistry` : lectures **et** écritures sous le même verrou exclusif. `a8204b5b` le branche le même jour. |

Le verrou répondait à un besoin réel (deux processus enregistrant le même projet). Il a été posé sur les lectures par symétrie, sans que rien ne relie ce choix au montage en lecture seule : le test de contrat `M29DockerAdministrationContractTest:29` vérifie le **texte** du compose, pas qu'une requête y répond. Le défaut est donc présent dans toute image construite depuis le 10 août 2026.

### Pourquoi rien ne l'a détecté

- `MinosMcpReadOnlyHomeTest` (AUD-C02) compare chemins, tailles et dates de modification de `MINOS_HOME` avant et après deux outils. Un fichier de verrou **déjà créé** par l'enregistrement du projet, rouvert en écriture, ne change ni de taille ni de date : le test reste vert.
- `scripts/ci/qualify-docker-release.sh` (avant la PR #441) s'arrêtait à `tools/list`. Depuis la PR #441 il appelle `minos_find_symbols`, mais sur un `--tmpfs /var/lib/minos:rw` (commentaire du script, `:296-299` à `4e5a18f5`).
- `scripts/ci/qualify-docker-upgrade.ps1` et le lanceur ne font pas de requête sur le service `minos-mcp`.

### Autres écritures du plan de requête

Recherche par lecture du code des primitives d'écriture atteignables depuis le démarrage du serveur et depuis les outils, puis contre-épreuve par exécution (prototype ci-dessous).

| # | Site | Déclencheur | Condition | Effet sur un montage en lecture seule |
|---|---|---|---|---|
| LS-01 | `InterProcessLocalProjectRegistry.java:137-144` | toute lecture du registre | toujours | **échec de tous les outils** (le défaut) |
| LS-02 | `FileRuntimeObservationStore.java:140,158` → `lock` (`:454-458`) → `BoundedFileLease.acquire` | `minos_runtime_sessions`, `minos_runtime_report`, `minos_runtime_symbol` | seulement si `runtime-observations/<projet>/` existe, donc après un import de session runtime | échec de ces trois outils. **Non exercé par la reproduction** (le jeu d'essai n'a pas de session) |
| LS-03 | `LocalProjectRegistry.java:290-306,324,328` (`migrateLegacyProject` : copie `.m29-v1.bak`, fichier temporaire, remplacement) | toute lecture d'une entrée de projet | entrée encore au format hérité (`rootPath`) **et** correspondance de chemins configurée (cas Docker) | échec de la lecture ; et, sous un bail partagé, deux lecteurs migreraient en même temps |
| — | `FileIndexStateStore.java:216-220` (`migrateLegacyRun`) | `minos_index_status` | état référençant un run encore au format hérité non partitionné | échec de cet outil ; même nature que LS-03, **non reproduit**, à couvrir par un test avant de décider (tâche 3.3) |
| — | Ouverture : `LocalStorageBackend.java:36-43,65`, constructeurs des stores, `LocalStorageRetentionService.java:65-67`, `ScipProjectSnapshotLifecycle.java:51-64` | démarrage | répertoire absent, ou permissions POSIX différentes de `0700` | aucun si le répertoire de données a été initialisé par l'administration (constaté : le serveur démarre) ; échec sinon. Hors périmètre |
| — | `CompositeProviderRuntimeManager.java:25` → `list()` des gestionnaires de runtimes | démarrage | toolchains présentes sur le `PATH`, ou Windows | échecs avalés, non bloquants ; aucune toolchain dans l'image de requête. Hors périmètre |

**Lectures vérifiées sans écriture** : snapshots de symboles (`FileSymbolSnapshotStore`, ouverture par `ConfinedFileOpener`, verrou en mémoire), index sémantique en lecture (`FileSemanticVectorStore.load`, bail réservé au remplacement), état d'index en lecture (`FileIndexStateStore.findProjectState`, `listRuns`), empreintes (`FileProjectFingerprintSnapshotStore`, « Reads take no lease »). Aucun cache disque, journal d'audit ni compteur écrit par `minos-mcp`.

### Prototype de faisabilité de l'option A

Branche locale `proto/lecture-du-registre-sous-verrou-partage` (67 lignes ajoutées, non destinée à être fusionnée telle quelle) : `BoundedFileLease.acquireShared` et six lectures du registre sous ce bail.

| Preuve | Résultat |
|---|---|
| Docker, 31 outils, données en lecture seule | 23 réponses, 8 refus fonctionnels, **0 échec interne** — identique aux données en écriture |
| Tests rouges, Windows | `InterProcessLocalProjectRegistryWriteProtectedStorageTest` 2/2, `MinosMcpWriteProtectedHomeTest` 2/2 (les 31 outils, arborescence intacte) |
| Tests rouges, Linux (non root) | mêmes classes, 2/2 et 2/2 |
| Tests existants du registre et du bail, Windows et Linux | `InterProcessLocalProjectRegistryTest`, `…SymlinkTest`, `BoundedFileLeaseTest`, `BoundedFileLeaseSymlinkTest` verts |
| Partie MCP du script de qualification modifié (LS-04) | rouge sur l'image du HEAD (« minos_find_symbols is an error on the query image »), verte sur l'image du prototype (« read-only data root: minos_find_symbols answered 7 symbol(s) ») |

**Non mesuré** : l'exclusion lecteur/écrivain entre deux processus (elle découle de la sémantique des verrous partagés du système, à prouver par test, tâche 1.2) ; LS-02 et LS-03 ; le script de qualification en entier sur le runner Linux.

## Goals / Non-Goals

**Goals** : une requête MCP répond sur un répertoire de données monté en lecture seule ; aucune lecture du plan de requête ne crée, ne durcit ni n'ouvre un fichier en écriture ; la cohérence face à un écrivain concurrent est conservée ; la qualification d'image échoue si le défaut revient.

**Non-Goals** : rendre le démarrage possible sur un répertoire de données vide en lecture seule ; changer les verrous d'écriture ; supporter un volume en lecture seule sous Windows natif.

## Decisions

### D1. Tenir la lecture seule, ou cesser de l'annoncer — **décision du propriétaire, en attente**

**Option A (recommandée) — lecture sous bail partagé.**

- Pour : c'est ce que disent déjà l'ADR 0017, l'ADR 0021, `docs/user/docker-runtime.md:11` et le test de contrat ; le conteneur persistant et exposé aux clients IA garde un répertoire de données qu'il ne peut pas altérer (défense en profondeur réelle : une faille du serveur ne peut ni corrompre l'index ni planter un fichier pour `minos-admin`) ; le prototype montre que le registre est le seul obstacle sur le chemin courant ; le correctif est court et reste dans les primitives d'E/S.
- Contre : un mode d'acquisition de plus dans `BoundedFileLease` ; deux écritures conditionnelles à traiter (LS-02, LS-03) ; une sémantique à tenir sur deux plateformes.

**Option B — monter les données en écriture pour `minos-mcp`.**

- Pour : aucun code Java ; correctif immédiat (deux lignes de compose, un test de contrat, la documentation, « Mise en œuvre » de l'ADR 0021 amendée).
- Contre : la propriété « le plan de requête ne peut pas écrire les données » est perdue et doit être retirée de la documentation ; l'ADR 0017 reste vrai pour les outils mais plus pour le processus ; les lectures continuent d'écrire (durcissement et ouverture du verrou), ce que `MinosMcpReadOnlyHomeTest` prétend exclure ; un utilisateur qui monte lui-même ses données en lecture seule garde le défaut.

**Option C, écartée** — lire sans aucun verrou quand le support est en lecture seule. Chaque mutation du registre publie un seul fichier de façon atomique, donc une lecture isolée resterait cohérente ; mais une lecture d'inventaire qui croise projets et espaces verrait un état intermédiaire, et « détecter que le support est en lecture seule » revient à tenter une écriture et à interpréter son échec — un mode dégradé déclenché par une erreur, contraire au fail-closed.

**Recommandation : A.** Si le propriétaire veut rétablir le service avant le correctif, B peut être livrée d'abord comme mesure provisoire, à condition de corriger la documentation dans la même PR, puis retirée par A.

### D2. Un bail partagé pour les lecteurs, dans la primitive existante (option A)

`BoundedFileLease.acquireShared(lockFile, jvmLock, timeout, description)` :

- prend le verrou JVM comme `acquire` (même échéance bornée, même traitement de l'interruption) ;
- **n'écrit rien** : le fichier de verrou existant est vérifié comme le vérifie un écrivain (fichier régulier, jamais un lien, privé — `PrivateLocalStorage.verifyPrivateFile`), sans être durci ; un fichier non conforme est **refusé**, pas réparé ;
- l'ouvre par `StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS` et prend un verrou **partagé** sur toute son étendue (`tryLock(0L, Long.MAX_VALUE, true)`), celle que couvre déjà le `tryLock()` exclusif de `acquire`, qui reste écrit tel quel (littéral exigé par `check-mnd.py`).

Un écrivain (`acquire`, exclusif) attend la fin des lecteurs et réciproquement ; plusieurs processus lecteurs ne s'excluent pas. Dans une même JVM, les lecteurs restent sérialisés par le verrou JVM (Java refuse deux verrous qui se recouvrent dans un même processus) : aucune régression par rapport à aujourd'hui.

L'ouverture en lecture reste dans `BoundedFileLease`, seule primitive autorisée à ouvrir un canal de verrou (`check-private-io.py`). Aucun type nouveau n'est exporté : `InterProcessLocalProjectRegistry` choisit `acquireShared` pour ses six lectures et garde `acquire` pour ses cinq mutations.

### D3. Fichier de verrou absent : le lecteur ne le crée pas (option A) — **à confirmer**

Un lecteur ne crée jamais le fichier de verrou. S'il est absent, aucun écrivain n'a jamais tenu le bail sur ce registre (toute mutation le crée d'abord) : le lecteur ne prend que le verrou JVM. La seule course possible est avec la **première** mutation du registre, qui publie un fichier unique de façon atomique : le lecteur voit le registre vide ou le projet complet.

Alternative : refuser la lecture tant que le verrou n'existe pas (« registre non initialisé »). Plus strict, mais `minos project list` sur une installation neuve échouerait au lieu de répondre « aucun projet ». **Recommandation : ne pas créer, ne pas refuser.**

### D4. Une lecture ne migre pas (LS-03, option A)

Sous un bail partagé, une lecture qui rencontre une entrée héritée la **résout en mémoire** et la rend ; elle ne copie ni ne remplace rien. La migration sur disque est faite par la prochaine opération qui tient le bail exclusif (toute mutation du registre). La sortie des lectures est inchangée. Conséquence acceptée : sur une installation jamais réécrite depuis la migration, l'entrée reste au format hérité jusqu'à la prochaine commande d'administration.

### D5. Observations runtime : même bail partagé (LS-02, option A)

`FileRuntimeObservationStore.find` et `list` prennent `acquireShared` ; l'import et la suppression gardent `acquire`. Le verrou de projet absent suit D3.

### D6. La qualification prouve le montage, pas seulement l'image (LS-04)

`scripts/ci/qualify-docker-release.sh` : l'index est préparé dans un volume nommé par des conteneurs en écriture (rôle de `minos-admin`), le conteneur MCP monte ce volume `readonly`, une **sonde d'écriture doit échouer** (sinon la requête ne prouverait rien), puis la poignée de main et `minos_find_symbols`. Deux tests de contrat de `minos-app` fixent ces littéraux. Tant que le correctif n'est pas livré, cette qualification est **rouge** : sa modification et le correctif vont dans la même PR. En option B, D6 est remplacée par une qualification qui monte les données comme le compose corrigé.

## Direction des dépendances

`minos-engine` (`com.minos.io`) ← `minos-storage-local` : inchangée (ADR 0022). Aucun module ni package nouveau, aucun cycle. `minos-mcp` reçoit `minos-engine:test-jar` en portée `test` : l'arête de compilation vers `minos-engine` existe déjà (même justification que dans `minos-storage-local/pom.xml`).

## Qualification des capacités

| Capacité | Aujourd'hui | Après l'option A |
|---|---|---|
| Requête MCP, données en lecture seule, conteneur Linux | **non supportée** (annoncée à tort) | **qualifiée** par la qualification d'image et les tests |
| Requête MCP, données en lecture seule, après import de sessions runtime | non supportée | qualifiée (tâche 2) |
| Requête MCP sur un registre hérité non migré, en lecture seule | non supportée | qualifiée (tâche 3) |
| Démarrage sur un répertoire de données vide en lecture seule | refus | refus, inchangé (hors périmètre) |
| Volume en lecture seule sous Windows natif | non supportée | **non supportée**, à dire dans `docs/user/docker-runtime.md` |

## Windows et Linux

- **Linux** : un verrou partagé (`fcntl`) se prend sur un descripteur ouvert en lecture, y compris sur un montage en lecture seule ; il est vu par un écrivain qui passe par un autre montage du même répertoire (les deux conteneurs partagent le noyau). Prouvé pour la lecture par le prototype ; l'exclusion entre processus reste à prouver par test (tâche 1.2).
- **Windows** : `LockFileEx` accepte un handle ouvert en lecture. La lecture ne demande plus `FILE_WRITE_DATA` : prouvé par le test avec entrée ACL de refus. Le durcissement de l'ouverture (réécriture de DACL) reste une écriture de métadonnées : un volume réellement en lecture seule n'est pas supporté.
- Les tests propres à une plateforme ne sont pas nécessaires : `WriteProtectedTree` choisit le mécanisme selon le système de fichiers et le même test s'exécute sur les deux runners.

## Sécurité

- **Fail-closed** : un fichier de verrou qui est un lien, un objet spécial ou qui accorde un accès à un autre principal est refusé en lecture comme en écriture ; un lecteur n'a plus le pouvoir de le « réparer ». Une échéance dépassée échoue comme aujourd'hui.
- **E/S privées** : aucune ouverture hors de `BoundedFileLease` ; `check-private-io.py` rejoué.
- **Sorties** : aucun message nouveau ne porte de chemin ; l'échec interne d'un outil reste opaque pour le client et le journal WARNING garde sa forme actuelle (outil, type, cause racine, sans chemin).
- Le changement **réduit** ce que le plan de requête peut écrire.

## Risks / Trade-offs

- **Systèmes de fichiers réseau** (NFS, SMB) : la sémantique des verrous partagés y dépend du serveur. Inchangé par rapport au verrou exclusif actuel, qui en dépend déjà.
- **Montages liés depuis un hôte Windows ou macOS** (Docker Desktop) : les deux conteneurs sont dans la même machine virtuelle ; non mesuré sur un montage lié d'un chemin Windows (la reproduction utilise un volume nommé). À mesurer en tâche 4.3.
- **Famine d'un écrivain** par un flux continu de lecteurs : chaque lecture tient le bail quelques millisecondes et l'écrivain a une échéance de 10 s ; risque jugé faible, test d'attente en tâche 1.2.
- **Gates littéraux** : `scripts/remediation/check-mnd.py` exige le littéral `tryLock()` dans `BoundedFileLease.java` — **le prototype le casse** (« missing MND invariant: tryLock() ») en réécrivant l'appel exclusif ; le correctif garde `channel.tryLock()` pour l'écrivain. `check-private-io.py` passe sur le prototype (« PRIVATE I/O PRIMITIVES GATE SUCCESS », aucune tolérance ajoutée). `check-mnd.py:113-114` exige aussi `ReentrantLock[] JVM_LOCKS` et `JVM_LOCK_STRIPES` dans le registre (conservés) ; `check-mne.py:108` et `check-runtime-dynamic-consistency.py:88` citent des faits de `FileRuntimeObservationStore` à relire avant la tâche 2.

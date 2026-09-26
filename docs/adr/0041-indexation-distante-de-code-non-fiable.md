# 0041 — Indexation distante de code non fiable : quota d'écriture OS ou fermeture assumée

Status: Accepted (2026-09-26) — option (b) retenue : l'indexation distante de code non fiable reste fermée par décision, le refus est journalisé et diagnosticable, les revendications sont alignées sur ce que l'OS applique. L'option (a) reste chiffrée ci-dessous pour un ADR ultérieur si un besoin `remote index` est confirmé.

Complète et amende la §4 de [0038](0038-aggregate-worker-resource-containment.md) (quota d'écriture assumé comme supervision) ; s'appuie sur [0036](0036-fail-closed-production-boundaries-and-measured-program-graph.md) (claims sandbox qualifiés par plateforme) et sur la correction documentaire G2 du sprint 1 ([`../audit/SPRINT-1-SUIVI.md`](../audit/SPRINT-1-SUIVI.md), V3).

Toutes les références `fichier:ligne` ci-dessous ont été relues sur la base `38756b28`.

## 1. Contexte

L'audit ([`../audit/AUDIT-2026-09.md`](../audit/AUDIT-2026-09.md), § 3 A1) constate que `remote index` est refusé sur tous les OS parce que le quota d'écriture disque des deux backends sandbox est `SUPERVISED_HARD_KILL`, que la qualification exige `OS_ENFORCED` sur cette dimension, et que le repli vers le backend natif se fait sans journal. Le sprint 1 (G2) a aligné le README, `docs/STATUS.md`, `docs/ROADMAP.md` et `docs/user/{cli,remote-indexing,installation,production-installation}.md` sur ce fait, et le gate `scripts/docs/product-facts.py:126-132` verrouille désormais la phrase exacte de `docs/STATUS.md:13`. **Le fond n'est pas tranché** : faut-il rendre ce quota réellement appliqué par l'OS (et rouvrir `remote index`), ou assumer la fermeture comme une décision d'architecture ?

Le principe directeur reste celui de l'ADR 0038 : une revendication d'isolation qui n'est pas exactement vraie est pire qu'une absence de revendication.

## 2. État exact du code

### 2.1 Dispositions déclarées, par backend

| Dimension (`WorkerResourceContainment`) | Linux `linux-bubblewrap-cgroup2-v5` (`LinuxBubblewrapWorkerSandboxBackend.java:154-173`) | Windows `windows-appcontainer-job-v3` (`WindowsAppContainerWorkerSandboxBackend.java:181-201`) | Exigé pour `UNTRUSTED_CODE_SUPPORTED` (`WorkerResourceContainment.java:95-106`) | Exigé pour le contrat local géré (`:109-120`) |
|---|---|---|---|---|
| Processus agrégés | `OS_ENFORCED` — `pids.max` (l. 157) | `OS_ENFORCED` — `JOB_OBJECT_LIMIT_ACTIVE_PROCESS` (l. 184) | `OS_ENFORCED` | `OS_ENFORCED` |
| Mémoire agrégée | `OS_ENFORCED` — `memory.max`/`memory.swap.max` (l. 158) | `OS_ENFORCED` — `JOB_OBJECT_LIMIT_JOB_MEMORY` (l. 185) | `OS_ENFORCED` | `OS_ENFORCED` |
| CPU agrégée | `OS_ENFORCED` — `cpu.max` (l. 159) | `OS_ENFORCED` — hard cap + `JOB_OBJECT_LIMIT_JOB_TIME` (l. 186) | `OS_ENFORCED` | `OS_ENFORCED` |
| Wall-clock | `SUPERVISED_HARD_KILL` (l. 160) | `SUPERVISED_HARD_KILL` (l. 187) | appliqué pendant l'exécution | idem |
| **Octets écrits** | **`SUPERVISED_HARD_KILL`** (l. 161) | **`SUPERVISED_HARD_KILL`** (l. 188) | **`OS_ENFORCED`** (l. 102) | appliqué pendant l'exécution (l. 116) |
| **Entrées écrites** | **`SUPERVISED_HARD_KILL`** (l. 162) | **`SUPERVISED_HARD_KILL`** (l. 189) | **`OS_ENFORCED`** (l. 103) | appliqué pendant l'exécution (l. 117) |
| Terminaison des descendants | `OS_ENFORCED` — `cgroup.kill` (l. 163) | `OS_ENFORCED` — `KILL_ON_JOB_CLOSE` + `TerminateJobObject` (l. 190) | `OS_ENFORCED` | `OS_ENFORCED` |
| Récupération du scratch | `SUPERVISED_HARD_KILL` (l. 164) | `SUPERVISED_HARD_KILL` (l. 191) | appliqué pendant l'exécution | idem |

Les deux seules dimensions qui séparent le contrat local géré du contrat « code non fiable » sont donc les **octets** et les **entrées** écrits. Tout le reste est déjà au niveau exigé.

Le quota supervisé est `ProviderWriteQuota.DEFAULT` = 8 GiB / 400 000 entrées / période d'échantillonnage 250 ms (`ProviderWriteQuota.java:23-28`), appliqué par `ProviderWriteQuotaSupervisor` (`:22-36` : parcours borné, liens symboliques jamais suivis, perte de visibilité = violation) que `ProcessIndexerExecutor.startWriteQuotaSupervisor` démarre (`ProcessIndexerExecutor.java:304-319`) sur les trois racines inscriptibles du provider : répertoire de travail, parent de l'artefact généré, répertoire de run (`:368-374`). Sous Windows, le stockage privé implicite de l'AppContainer (`%LOCALAPPDATA%\Packages\<sid>\AC`, résolu par `GetAppContainerFolderPath`, `windows-fragments/appcontainer-private-storage.ps1frag:2-78`) est supervisé séparément avec 1 GiB / 50 000 entrées réservés sur le budget global (`WindowsAppContainerWorkerSandboxBackend.java:66-73`).

### 2.2 Où la rétrogradation a lieu

Les deux backends **déclarent** `UNTRUSTED_CODE_SUPPORTED` (`LinuxBubblewrapWorkerSandboxBackend.java:123-125`, `WindowsAppContainerWorkerSandboxBackend.java:147`). C'est le constructeur du record `WorkerSandboxQualification` qui rétrograde en `UNTRUSTED_CODE_UNSUPPORTED` dès que `containment.qualifiedForUntrustedCode()` est faux (`WorkerSandboxQualification.java:41-51`), en ajoutant la limitation `WORKER_UNTRUSTED_CODE_FAIL_CLOSED_INCOMPLETE_HARD_CONTAINMENT` puis les codes `FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL` et `FILESYSTEM_WRITE_ENTRIES_…` (`WorkerResourceContainment.java:122-126`). `supportsUntrustedCode()` (`WorkerSandboxBackend.java:43-49`) renvoie donc `false` pour les deux backends sur leur propre OS.

### 2.3 Où et comment le repli se fait, et pourquoi il est muet

`WorkerSandboxBackends.strongestAvailable` (`WorkerSandboxBackends.java:15-28`) découvre le backend de l'OS, le **filtre** sur `supportsUntrustedCode` puis tombe sur `nativeEphemeralWorkspace()` via `orElseGet`. Le `filter` ne journalise rien. Les seuls WARNING existants concernent l'*absence* de prérequis (`LinuxBubblewrapWorkerSandboxBackend.java:88-94` racine cgroup, `:302-335` sonde ; `WindowsAppContainerWorkerSandboxBackend.java:106-121` sonde/launcher) — le commentaire de `:113-117` reconnaît d'ailleurs que « `strongestAvailable()` silently falls back ». Autrement dit : quand *tout* est installé et sondé avec succès, le backend est écarté **sans aucune trace**, précisément parce que son quota est supervisé.

`LocalIsolatedIndexWorker` choisit ce sélecteur strict (`LocalIsolatedIndexWorker.java:215-221`) et lève avant tout provider (`:117-121`) : `sandbox backend native-process-ephemeral-workspace-v1 is not qualified for untrusted remote code on the current platform; execution is fail-closed`. Le message nomme le backend **natif** de repli, pas la dimension manquante du backend OS écarté : l'opérateur qui a provisionné bwrap, AppArmor et la délégation cgroup ne peut pas comprendre pourquoi il est refusé. La CLI le rend après `error: ` (`CliCommandSupport.java:70-71`), une fois passé par `PublicErrorMessages.sanitize` (`:114`) — sanitisé, donc, mais toujours sans la raison réelle.

Pire, le refus survient **après** des effets de bord : `LocalRemoteIndexOperations.indexUnderSourceLease` a déjà matérialisé la révision, pris le bail, **enregistré le projet** et épinglé la source (`LocalRemoteIndexOperations.java:117-124`) avant que le décorateur crée le worker (`:126-136`) dont `execute` refuse.

Le chemin local géré (`StrongProcessOwnershipIndexerExecutor.java:89-93`, `StrongOwnedProcessExecutors.java:52-56`) utilise le sélecteur *étroit* `strongestAvailableForManagedLocalProvider` (`WorkerSandboxBackends.java:38-51`) et **fonctionne** : les sandbox sont bien exercées en production locale.

### 2.4 Ce que `minos doctor` dit aujourd'hui

Rien. `DoctorCommand` (`minos-cli/src/main/java/com/minos/cli/DoctorCommand.java:25-48`) rapporte les commandes `java/javac/mvn/node/npm/python/docker`, l'état des providers, les permissions de stockage privé (`:104-121`) et un verdict `READY`/`ACTION_REQUIRED` calculé sur les seuls providers (`:40-42`). Aucune ligne sur la sandbox, la qualification code non fiable ni `remote index`. Aucun test de `minos-cli/src/test` ne référence `DoctorCommand`. Note : la sortie affiche déjà `MINOS_HOME` en chemin absolu (`:60`, `:88`) ; toute ligne ajoutée devra s'en abstenir.

### 2.5 « ≈ 2 000 lignes inatteignables » : vérification

L'affirmation de l'audit est inexacte dans sa nature, pas dans son ordre de grandeur :

- **La sandbox elle-même est atteignable.** `LinuxBubblewrapWorkerSandboxBackend` (474 l.), `LinuxCgroupJob` (502), `WindowsAppContainerWorkerSandboxBackend` (789), `WindowsContainmentScript` (83), `ProviderWriteQuotaSupervisor` (322), `ProviderWriteQuota` (42), `WorkerResourceContainment` (166), `WorkerSandboxQualification` (136), `WorkerSandboxBackend(s)` (182) — ≈ 2 700 lignes Java plus ≈ 1 100 lignes PowerShell (`windows-appcontainer-sandbox-v4.ps1.template` 584, `windows-job-object-owner-v1.ps1.template` 137, fragments) — sont exécutées par l'indexation **locale** des providers gérés (§ 2.3).
- **Ce qui est réellement bloqué en production est le transport distant.** Fichiers dédiés : `LocalIsolatedIndexWorker` (227 l.), `DistributedIndexerExecutor` (192), `DistributedArtifactBundleStore` (706, construit par `LocalRemoteIndexOperations.java:40` mais dont `createBundle`/vérification ne sont atteints que via le worker), `DistributedArtifactCachePolicy` (29) ; dans `minos-engine`, `DistributedArtifactManifest` (129) et `DistributedIndexing` (71) ; dans `minos-cli`, `RemoteIndexCommand` (260) et `LocalRemoteIndexOperations` (231), soit 491 l. dont `remote materialize` partage une partie. Total des fichiers : ≈ 1 850 lignes. En ne retenant que ce qui est **effectivement inexécutable** — `LocalIsolatedIndexWorker` après `:128` (≈ 100 l.), les trois autres classes de transport (927), les deux types `remote` (200) et la branche `index` de la CLI (≈ 300 l. sur 491) — on obtient **≈ 1 530 lignes** de chemin `remote index` dont l'exécution est impossible aujourd'hui, verrouillées par `WorkerResourceContainmentTest.currentOsBackendsFailClosedUntilStorageIsOsEnforced` (`:74-86`), `LinuxCgroupJobContainmentTest:158-178`, `WindowsJobObjectContainmentTest:163-181` et `WorkerSandboxQualificationTest:80-107`.
- La part *spécifique* au distant dans la sandbox est minuscule : le sélecteur strict (`WorkerSandboxBackends.java:15-28`) et les deux contrôles de `LocalIsolatedIndexWorker.execute:117-127`.

### 2.6 Revendications documentaires encore désalignées

- `docs/developer/remote-worker-sandbox-disposition.md:54-57` affirme que `UNTRUSTED_CODE_SUPPORTED` exige « au minimum `SUPERVISED_HARD_KILL` » sur le quota d'écriture — **c'est faux** : le code exige `OS_ENFORCED` (`WorkerResourceContainment.java:102-103`). Le tableau `:71-75` titre « Backends qualifiés » et présente les deux backends sans dire qu'ils sont rétrogradés. G2 n'a pas relu ce fichier (hors de sa liste, `SPRINT-1-SUIVI.md:93`).
- `README.md:18` et `product-facts.py:175-176` exigent que la ligne #98 contienne `CLOSED|fermée|completed|qualifiée` : l'issue est fermée sur les primitives, mais le mot « qualifiée » reste admis par le gate.
- Tout le reste (`README.md:190-192`, `docs/STATUS.md:13`, `docs/ROADMAP.md:35`, `docs/user/cli.md:338`, `docs/user/remote-indexing.md:35-39,110`) est exact.

## 3. Ce qu'exigerait un quota d'écriture `OS_ENFORCED`, par variante

Le quota doit borner **les octets et les entrées** (les deux dimensions sont exigées) sur **toutes** les racines inscriptibles du provider (§ 2.1), sans privilège au moment de l'exécution (MINOS tourne non élevé : `WindowsNonElevatedIndexingTest.java:40`, délégation cgroup sans droit hors sous-arbre : `scripts/deploy/provision-linux-sandbox-cgroup.sh:11-24`).

### 3.1 Linux

| Variante | Octets | Entrées | Prérequis | Verdict |
|---|---|---|---|---|
| **L1 — `tmpfs` de taille fixe dans le profil bwrap** (`--size N --tmpfs DEST`, bwrap ≥ 0.5.0, présent dans Debian 12/bubblewrap 0.8.0 [S1]) | oui (`size=`) | **non** : bwrap n'expose pas `nr_inodes` ; la valeur par défaut est la moitié des pages RAM [S2], pas une borne MINOS | aucun nouveau paquet | Insuffisant seul. De plus un tmpfs monté dans le namespace de bwrap est **invisible de l'hôte** : l'artefact devrait être recopié depuis l'intérieur de la sandbox vers un bind hôte inscriptible, lui-même non borné — le problème se déplace. Enfin les pages tmpfs sont imputées à `memory.max` du job (8 GiB) : le budget disque et le budget mémoire se cannibalisent. Reste utile comme durcissement du `/tmp` déjà monté sans taille (`LinuxBubblewrapWorkerSandboxBackend.java:273-274`). |
| **L2 — quota de projet XFS** sur `MINOS_HOME/{runs,distributed-workers,local-provider-workspaces}` (`mount -o prjquota`, `xfs_quota -x -c 'project -s'`, `limit -p bhard= ihard=` [S3]) | oui | oui (`ihard`) | volume XFS, option de montage `prjquota` (donc `/etc/fstab` ou `rootflags=`), **root une fois** pour créer les projets et poser les limites | Viable. Le quota est **par arbre**, pas par job : deux providers concurrents partagent 8 GiB. L'hôte est protégé (objectif de l'ADR 0038 §4) ; un provider hostile peut affamer un provider voisin (déni de service interne, à documenter). |
| **L3 — quota de projet ext4** (`tune2fs -O project,quota`, montage `prjquota`, `setquota -P`) | oui | oui | fonctionnalité `project` activée sur le volume (non vérifié en ligne dans cette note ; à confirmer sur les distributions cibles), root une fois | Mêmes propriétés que L2 ; couvre les installations Ubuntu par défaut (ext4) à condition de réactiver la fonctionnalité sur un volume démonté. |
| **L4 — qgroups btrfs** (`btrfs quota enable`, `btrfs qgroup limit` [S4]) | oui | **non** (les qgroups bornent des octets) | btrfs, root, « performance hit » sur tout traitement d'extents [S4] | Insuffisant seul (entrées non bornées) et coûteux. |
| **L5 — tmpfs hôte dimensionné par systemd** (`TemporaryFileSystem=…:size=,nr_inodes=` dans l'unité) | oui | oui | unité systemd système avec sandboxing, root à l'installation | Viable mais impose le mode « unité systemd » que la doc ne rend qu'*recommandé* (`docs/user/remote-indexing.md:84-86`), place tout le scratch en RAM/swap, et reste par service, pas par job. |

Pour L2/L3, deux points non négociables. D'abord, le quota de projet ne couvre que les racines **hôte** : les entrées créées dans le `tmpfs /tmp` de bwrap (`LinuxBubblewrapWorkerSandboxBackend.java:273-274`) ne sont bornées que par `memory.max` du job — `FILESYSTEM_WRITE_ENTRIES = OS_ENFORCED` ne vaudrait donc que pour les racines hôte sous quota, et la revendication devra le dire ainsi (`--size` sur `/tmp` borne les octets, pas le nombre d'inodes, L1). Ensuite, les trois racines inscriptibles doivent être **dans** l'arbre quota. Aujourd'hui `runs/` (`ProcessIndexerExecutor.java:49-50`), `distributed-workers/` (`LocalIsolatedIndexWorker.java:77-78`) et `local-provider-workspaces/` (`LocalProviderWorkspace.java:54`) sont sous `MINOS_HOME` ; l'artefact généré dépend du provider (`ProcessIndexerExecutor.java:91-92`). Le backend devrait **refuser** toute racine hors quota (fail-closed), et la preuve d'application devrait être sondée pour de vrai (comme `LinuxCgroupJob.qualifyRoot`) : un « slot » de sonde avec limite minuscule provisionné par le script, sur lequel MINOS écrit jusqu'à `EDQUOT` avant de revendiquer quoi que ce soit.

### 3.2 Windows

| Variante | Verdict |
|---|---|
| **W1 — quotas NTFS** | Ils sont **par utilisateur et par volume**, configurés par un administrateur [S5]. Le processus AppContainer s'exécute sous le SID de l'utilisateur MINOS (le SID AppContainer est une capacité du jeton, pas le propriétaire des fichiers) : le quota compterait les écritures de MINOS et de l'utilisateur lui-même. Isoler exigerait un compte local dédié (création admin + gestion d'identifiants) — exclu. |
| **W2 — VHD/VHDX à taille fixe** | `AttachVirtualDisk` échoue sans `SE_MANAGE_VOLUME_PRIVILEGE` [S6], accordé par défaut aux seuls administrateurs ; MINOS non élevé ne peut ni créer ni attacher le disque. Il faudrait un attachement persistant fait par un administrateur à chaque démarrage. Et même alors, le **stockage privé AppContainer** (`…\Packages\<sid>\AC`) reste sur le volume système, inscriptible par le SID AppContainer quel que soit `TEMP`, donc **toujours supervisé** (`appcontainer-private-storage.ps1frag:63-78`). |
| **W3 — Job Object** | Aucune limite d'E/S disque ou de stockage n'existe sur un Job Object (seulement des contrôles de débit E/S). |

**Conclusion Windows** : aucune variante ne rend les deux dimensions `OS_ENFORCED` sans privilège administrateur *et* sans laisser le stockage privé AppContainer supervisé. La revendication « sandbox qualifiée Linux + Windows » pour du code non fiable **n'est pas restaurable** sur Windows avec l'architecture AppContainer actuelle ; seule une frontière d'un autre ordre (machine virtuelle / Windows Sandbox / conteneur Hyper-V) le permettrait, ce qui est un autre backend, pas une correction.

## 4. Option (a) — rendre le quota d'écriture appliqué par l'OS (Linux seulement)

Périmètre réaliste : L2/L3 (quota de projet sur les arbres de `MINOS_HOME`) + L1 en durcissement du `/tmp`, Windows restant `UNTRUSTED_CODE_UNSUPPORTED` avec une disposition explicite.

**Travail (≈ 9 à 11 jours-personne)** :

| Lot | Contenu | Fichiers | Estimation |
|---|---|---|---|
| a1 | Script de provisionnement root, idempotent : vérifie le type de FS et l'option `prjquota`, crée les identifiants de projet pour `runs`, `distributed-workers`, `local-provider-workspaces` et un slot de sonde, pose `bhard`/`ihard` | `scripts/deploy/provision-linux-sandbox-quota.sh` (nouveau), `docs/user/remote-indexing.md` (§ prérequis) | 1 j |
| a2 | Sonde `LinuxProjectQuota` mémoïsée (lecture de `/proc/self/mountinfo`, vérification du projid par `FS_IOC_FSGETXATTR` via `java.lang.foreign` ou `lsattr -p`, écriture jusqu'à `EDQUOT` sur le slot de sonde) | `minos-runtime-local/…/LinuxProjectQuota.java` (nouveau) + test | 1,5 j |
| a3 | `containment()` Linux devient dépendant de la sonde (`OS_ENFORCED` sur octets/entrées si et seulement si la sonde passe) ; `--size` sur `--tmpfs /tmp` ; refus fail-closed de toute racine inscriptible hors arbre quota dans `sandboxPlan` ; nouvelle `PlatformDisposition.BLOCKED_NO_OS_ENFORCED_WRITE_QUOTA` déclarée par Linux sans quota et par Windows | `LinuxBubblewrapWorkerSandboxBackend.java`, `WindowsAppContainerWorkerSandboxBackend.java` (`qualification()`), `WorkerSandboxQualification.java` | 1,5 j |
| a4 | Tests : `currentOsBackendsFailClosedUntilStorageIsOsEnforced` **scindé** en « Linux sans quota reste fermé » / « Linux avec quota sondé revendique » / « Windows reste fermé » ; `LinuxCgroupJobContainmentTest:158-178` conditionné à la sonde ; test d'intégration réel « un provider qui dépasse le quota reçoit `EDQUOT` et le run est récupéré » ; `WorkerSandboxBackendsTest:15-40` déjà tolérant (commentaire `:37-39`) | tests `minos-runtime-local` | 2 j |
| a5 | CI : volume XFS sur périphérique boucle monté `prjquota` sur `ubuntu-24.04` (le FS racine du runner est ext4 sans `project`), provisionnement, et pare-feu de versions comme `install-linux-sandbox-toolchain.sh:27` | `scripts/ci/…`, `.github/workflows/pr-ci.yml:111-132` | 1 j (+ risque de flakiness) |
| a6 | Critères communs (§ 6) : journal du repli, `doctor`, documents, gate `product-facts.py:126-132` (la phrase « refusée » de `STATUS` devient conditionnelle) | voir § 6 | 2 j |

**Prérequis opérateur** : Linux uniquement ; `MINOS_HOME` sur XFS monté `prjquota` (ou ext4 avec fonctionnalité `project`) ; exécution root d'un script à l'installation, en plus de bwrap/prlimit, du profil AppArmor et de la délégation cgroup déjà exigés (`docs/user/remote-indexing.md:59-106`). Aucun privilège à l'exécution.

**Ce qui devient qualifiable** : `linux-bubblewrap-cgroup2-v5` passe `UNTRUSTED_CODE_SUPPORTED` **sur les hôtes provisionnés**, la dimension « entrées » n'étant `OS_ENFORCED` que pour les racines hôte sous quota (le `/tmp` tmpfs reste borné par `memory.max`, § 3.1) ; `remote index` fonctionne sur Linux en `ALLOW` et `DENY`. **Ce qui ne l'est pas** : Windows (§ 3.2), Linux non provisionné, tout autre OS.

**Risques résiduels** : quota par arbre et non par job (déni de service entre providers concurrents, l'hôte restant protégé) ; le superviseur reste nécessaire pour l'attribution par job et la destruction rapide ; couplage au type de système de fichiers de l'opérateur ; la sonde `EDQUOT` doit rester bornée et mémoïsée ; dérive possible si un futur chemin inscriptible est ajouté hors arbre (d'où le refus fail-closed en a3).

**Coût de test** : tests unitaires portables ; tests d'intégration Linux avec **root pour le provisionnement** (périphérique boucle + `mkfs.xfs` + montage) en CI et sur tout poste développeur Linux ; rien de nouveau sous Windows.

**Impact sur `currentOsBackendsFailClosedUntilStorageIsOsEnforced`** : renommé et scindé (a4) ; jamais supprimé ; l'assertion Windows est conservée à l'identique.

**Intersections de zone** : a3 exige que les racines inscriptibles restent sous les arbres quota, ce qui touche la structure de `runs/` que R1 (`ProcessIndexerExecutor`, `RunDirectoryRetention`, points de contrôle de l'ADR 0039) modifie en parallèle ; la copie de workspace de `LocalIsolatedIndexWorker.java:137-148` ; `LocalProviderWorkspace` ; `DoctorCommand` et `LocalRemoteIndexOperations` (`minos-cli`).

## 5. Option (b) — assumer la fermeture, la rendre explicite et diagnosticable

**Travail (≈ 4 à 5 jours-personne)** :

| Lot | Contenu | Fichiers | Estimation |
|---|---|---|---|
| b1 | Sélection **rapportée** : `WorkerSandboxBackends` expose un `WorkerSandboxSelection` (backend retenu, backend écarté, `unmetRequirements()`) ; `strongestAvailable` journalise en WARNING « backend `<id>` écarté pour l'exécution de code non fiable : `FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL`, … » — identifiants et codes seulement, jamais de chemin | `WorkerSandboxBackends.java`, `WorkerSandboxQualification.java` (accès aux codes), tests `WorkerSandboxBackendsTest` | 1 j |
| b2 | Marquage du code : Javadoc et limitation lisible par machine `WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041` ajoutée par les deux backends ; aucune suppression — le transport distant (§ 2.5) n'est pas mort, il est **fermé par décision** et ses tests (`LocalIsolatedIndexWorkerTest` 478 l., `DistributedArtifactBundleStoreTest` 354, `DistributedIndexerExecutorScopeTest` 137, `StrongOwnershipRemoteSandboxCompositionTest` 94) prouvent le contrat qu'un futur backend devra honorer | `LinuxBubblewrapWorkerSandboxBackend.java`, `WindowsAppContainerWorkerSandboxBackend.java`, `WorkerSandboxBackend.java` | 0,5 j |
| b3 | `minos doctor` : section `workerSandbox` (texte et JSON) — backend local géré retenu, backend « code non fiable » retenu, `remoteIndexing: UNAVAILABLE`, liste des codes manquants, référence à cet ADR ; sans chemin ; `ready` inchangé (la fermeture est une décision, pas une panne) ; test dédié | `minos-cli/…/DoctorCommand.java` + test (**hors zone**, voir intersections) | 1,5 j |
| b4 | Refus **précoce** de `remote index` : vérifier `supportsUntrustedCode()` avant matérialisation/enregistrement/épinglage (`LocalRemoteIndexOperations.java:117-124`) avec le même diagnostic ; message de `LocalIsolatedIndexWorker.execute:117-121` enrichi des codes manquants | `LocalRemoteIndexOperations.java` (**hors zone**), `LocalIsolatedIndexWorker.java` (**zone impl-resume**) | 0,5 j |
| b5 | Documents : ADR 0038 §4 marqué « amendé par 0041 » ; `remote-worker-sandbox-disposition.md:54-75` corrigé (exigence `OS_ENFORCED`, tableau « Backends intégrés — rétrogradés ») ; `README.md:190-192`, `docs/user/remote-indexing.md:35-39`, `docs/user/cli.md:77-89,338` : « fermé par décision (ADR 0041) » au lieu de « pas encore » ; gate `product-facts.py:126-132,175` aligné | `docs/**`, `README.md`, `scripts/docs/product-facts.py` | 1 j |
| b6 | Tests : `currentOsBackendsFailClosedUntilStorageIsOsEnforced` renommé `currentOsBackendsStayFailClosedByDecisionAdr0041`, étendu aux codes exacts et à la nouvelle limitation ; `LinuxCgroupJobContainmentTest:158-178` et `WindowsJobObjectContainmentTest:163-181` idem | tests `minos-runtime-local` | 0,5 j |

**Prérequis opérateur** : aucun nouveau. **Ce qui devient qualifiable** : rien ; le chemin local géré reste exactement ce qu'il est sur les deux OS. **Risques résiduels** : la fonctionnalité `remote index` reste indisponible ; si un besoin apparaît, l'option (a) Linux reste ouverte et documentée ici. **Coût de test** : unitaire et portable, aucun privilège, aucune modification de CI. **Impact sur le test verrou** : renommé et renforcé, jamais supprimé.

**Intersections de zone** : `DoctorCommand` (`minos-cli`, b3), `LocalRemoteIndexOperations` (`minos-cli`, b4), `LocalIsolatedIndexWorker` (zone `impl-resume`, b4). La dépendance `minos-cli → minos-runtime-local` existe déjà (`LocalRemoteIndexOperations.java:17`) mais A2 (phase 2) rediscutera les frontières ; b3 devra passer par le port que A2 retiendra ou être fait après A2.

## 6. Critères d'acceptation communs et leur satisfaction

| Critère | Option (a) | Option (b) |
|---|---|---|
| Repli journalisé en WARNING avec la dimension non `OS_ENFORCED`, sans chemin absolu | b1 (identique), plus un WARNING quand la sonde quota échoue | b1 |
| `minos doctor` dit si l'indexation distante est disponible et sinon pourquoi | b3, avec en plus l'état de la sonde quota (`AVAILABLE` sur Linux provisionné) | b3 (`UNAVAILABLE`, codes, ADR) |
| Revendication README/docs/`WorkerSandboxQualification` exactement alignée sur ce que l'OS applique | « Linux avec quota de projet provisionné : supporté ; Windows : non supporté (stockage privé AppContainer supervisé) » — la phrase de `docs/STATUS.md:13` et le gate deviennent conditionnels | « fermé par décision, sur tous les OS » — `docs/STATUS.md:13` et le gate restent vrais ; `remote-worker-sandbox-disposition.md` corrigé |
| Test verrou mis à jour en cohérence, jamais supprimé | scindé (a4) | renommé et renforcé (b6) |

## 7. Recommandation

**Option (b), maintenant.** Motifs, par ordre de poids :

1. **Windows est hors d'atteinte** (§ 3.2) : même en payant l'option (a), la revendication que le README affichait avant G2 — « qualifiée Linux + Windows » — ne redeviendra jamais vraie. L'option (a) est une option « Linux serveur provisionné », pas une restauration de la promesse produit.
2. **Le défaut réel de l'audit est l'opacité, pas la fermeture.** Le repli muet (§ 2.3), un `doctor` aveugle (§ 2.4), un refus tardif après enregistrement du projet, et une page développeur qui contredit le code (§ 2.6) se corrigent en 4 à 5 jours sans prérequis opérateur, et le principe « aucune revendication fausse » de l'ADR 0038 est alors intégralement tenu.
3. **L'option (a) empile un troisième prérequis root** (quota de projet) sur la délégation cgroup et le profil AppArmor, lie MINOS au système de fichiers de l'opérateur, ajoute un montage XFS en boucle à la CI, et touche la structure de `runs/` **pendant** que R1 la remanie (ADR 0039). Ce n'est pas le bon sprint.
4. **Hypothèse, invérifiable depuis le dépôt** : ni l'audit ni les issues qu'il cite ne documentent une demande utilisateur de `remote index`. Si cette hypothèse est fausse, ce motif tombe et seuls les motifs 1 à 3 restent ; si elle est vraie, ouvrir ce chemin sur Linux seul coûterait plus qu'il ne rapporte. Si une demande apparaît, la variante L2/L3 est chiffrée ici et pourra faire l'objet d'un ADR dédié, sans rien défaire de (b).

Durcissement gratuit à garder en réserve quelle que soit l'option (« à traiter plus tard ») : `--size` sur le `--tmpfs /tmp` de bwrap (§ 3.1, L1).

## 8. Question de décision

**Réponse (2026-09-26) : option (b) validée par l'utilisateur**, avec réattribution à `impl-sandbox` des trois fichiers d'intersection (`LocalIsolatedIndexWorker`, `DoctorCommand`, `LocalRemoteIndexOperations`). Question posée :

**Acceptez-vous que l'indexation distante de code non fiable reste fermée par décision sur tous les OS (option b, ≈ 4–5 j-p, refus journalisé et diagnosticable, documents alignés), l'ouverture Linux par quota de projet (option a, ≈ 9–11 j-p, Linux seulement, prérequis root à l'installation, Windows restant fermé) étant reportée à un ADR ultérieur si un besoin `remote index` est confirmé ?**

## Sources externes consultées

- [S1] Page de manuel `bwrap(1)`, Debian 12 (bubblewrap 0.8.0) : « `--size` modifies the size of the created mount when preceding a `--tmpfs` action ; `--perms` and `--size` can be combined » — https://manpages.debian.org/bookworm/bubblewrap/bwrap.1.en.html
- [S2] Documentation noyau, tmpfs : options `size`, `nr_blocks`, `nr_inodes` (défaut : moitié des pages RAM) — https://docs.kernel.org/filesystems/tmpfs.html
- [S3] `xfs_quota(8)` : quota de projet (`mount -o prjquota`, `project -s`, `limit -p bhard= ihard=`), commandes « administrator » sous `-x` — https://man7.org/linux/man-pages/man8/xfs_quota.8.html
- [S4] `btrfs-quota(8)` : « When quotas are activated, they affect all extent processing, which takes a performance hit » ; les qgroups bornent des octets — https://btrfs.readthedocs.io/en/latest/btrfs-quota.html
- [S5] Microsoft Learn, *Managing Disk Quotas* : quotas NTFS par utilisateur et par volume, administrés par un administrateur — https://learn.microsoft.com/en-us/windows/win32/fileio/managing-disk-quotas
- [S6] Microsoft Learn, `AttachVirtualDisk` : échec « if the caller does not have `SE_MANAGE_VOLUME_PRIVILEGE` access rights » — https://learn.microsoft.com/en-us/windows/win32/api/virtdisk/nf-virtdisk-attachvirtualdisk
- Documentation noyau, cgroup v2 (`memory.max`, imputation des pages tmpfs/shmem au cgroup) — https://docs.kernel.org/admin-guide/cgroup-v2.html

## Liens

- audit : [`../audit/AUDIT-2026-09.md`](../audit/AUDIT-2026-09.md) (A1, G2) ; suivi sprint 1 : [`../audit/SPRINT-1-SUIVI.md`](../audit/SPRINT-1-SUIVI.md)
- ADR amendé : [0038](0038-aggregate-worker-resource-containment.md) §4 ; ADR liés : [0036](0036-fail-closed-production-boundaries-and-measured-program-graph.md), [0039](0039-reprise-indexation-apres-interruption.md) (intersection `runs/`)
- utilisateur : [`../user/remote-indexing.md`](../user/remote-indexing.md), [`../user/cli.md`](../user/cli.md) ; développeur : [`../developer/remote-worker-sandbox-disposition.md`](../developer/remote-worker-sandbox-disposition.md)

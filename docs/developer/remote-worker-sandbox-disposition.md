# Remote worker — disposition d’isolation et de confinement

L’indexation distante traite le provider et le dépôt matérialisé comme du code non fiable. Une copie dans une workspace éphémère et un processus distinct ne suffisent donc jamais : le provider doit être lancé par un backend OS dont la qualification annonce `UNTRUSTED_CODE_SUPPORTED` sur la plateforme courante.

## Contrat fail-closed

`LocalIsolatedIndexWorker` vérifie la qualification avant toute copie ou exécution, indépendamment de la politique réseau :

- `ALLOW` autorise le réseau **dans** une sandbox OS qualifiée ;
- `DENY` exige en plus une preuve de blocage réseau `OS_ENFORCED` ;
- `native-process-ephemeral-workspace-v1` reste `UNTRUSTED_CODE_UNSUPPORTED` et est refusé avec `ALLOW` comme avec `DENY` ;
- aucun opt-in unsafe permettant d’exécuter du code distant avec les droits de l’hôte n’existe.

Cette disposition conserve l'invariant diagnostique `WORKER_SANDBOX_CLAIM_PROHIBITED` : un backend process-only ne peut jamais revendiquer le statut d'une sandbox OS qualifiée.

La provenance, les checksums, le bundle distribué, le rejet des symlinks, les budgets de source et les règles `.gitignore`/`.minosignore` restent appliqués en complément de la sandbox.

## Confinement agrégé des ressources

`WorkerResourceContainment` décrit, dimension par dimension, ce que la plateforme garantit réellement. Trois dispositions distinctes ne sont jamais confondues :

| Disposition | Signification |
|---|---|
| `OS_ENFORCED` | le noyau refuse lui-même le dépassement ; ni un `fork`, ni la mort de MINOS ne contournent la limite |
| `SUPERVISED_HARD_KILL` | MINOS observe la dimension **pendant** l’exécution, à une période bornée, et détruit toute la frontière de job au premier dépassement |
| `MEASURED_ONLY` | valeur connue seulement après coup : diagnostic, jamais une garantie |
| `UNAVAILABLE` | la primitive n’existe pas sur cet hôte ou cette configuration |

Une limite **par processus** (`RLIMIT_AS`, `RLIMIT_NPROC`, `RLIMIT_CPU`) est multipliée par chaque `fork` : elle reste une défense en profondeur et n’est jamais déclarée `OS_ENFORCED` sur une dimension agrégée.

`UNTRUSTED_CODE_SUPPORTED` exige :

- `OS_ENFORCED` sur le nombre de processus agrégé, la mémoire agrégée, la CPU agrégée et la terminaison des descendants ;
- au minimum `SUPERVISED_HARD_KILL` sur le wall-clock, le quota d’écriture (octets **et** nombre d’entrées) et la récupération du scratch.

Le constructeur de `WorkerSandboxQualification` refuse toute autre combinaison : la revendication ne peut pas diverger du confinement réel.

| Dimension | Linux | Windows |
|---|---|---|
| Processus agrégés | `OS_ENFORCED` — `pids.max` | `OS_ENFORCED` — `JOB_OBJECT_LIMIT_ACTIVE_PROCESS` |
| Mémoire agrégée | `OS_ENFORCED` — `memory.max` + `memory.swap.max` | `OS_ENFORCED` — `JOB_OBJECT_LIMIT_JOB_MEMORY` |
| CPU agrégée | `OS_ENFORCED` — `cpu.max` | `OS_ENFORCED` — hard cap CPU + `JOB_OBJECT_LIMIT_JOB_TIME` |
| Descendants terminés | `OS_ENFORCED` — `cgroup.kill` | `OS_ENFORCED` — `KILL_ON_JOB_CLOSE` + `TerminateJobObject` |
| Wall-clock | `SUPERVISED_HARD_KILL` — timeout MINOS | `SUPERVISED_HARD_KILL` — timeout MINOS |
| Quota d’écriture (octets/entrées) | `SUPERVISED_HARD_KILL` | `SUPERVISED_HARD_KILL` |
| Récupération du scratch | `SUPERVISED_HARD_KILL` | `SUPERVISED_HARD_KILL` |

## Backends qualifiés

| Plateforme | Backend | Frontière de job | `ALLOW` | `DENY` |
|---|---|---|---|---|
| Linux | `linux-bubblewrap-cgroup2-v5` | bubblewrap (namespaces, racine hôte read-only, capacités supprimées) + cgroup v2 dédié au run | namespace réseau hôte explicitement partagé | namespace réseau isolé |
| Windows | `windows-appcontainer-job-v3` | AppContainer, ACL éphémères ciblées, vérification du token, Job Object configuré avant la création du processus | seule capacité `internetClient` (`S-1-15-3-1`) | ensemble de capacités vide |
| Autre | aucun | — | refus | refus |

### Linux

La découverte sonde réellement `bubblewrap`, `prlimit`, les user namespaces, la politique LSM **et** la délégation cgroup v2. Le cgroup est :

- résolu depuis `MINOS_SANDBOX_CGROUP_ROOT` ou depuis le cgroup du processus MINOS lui-même ;
- vérifié par une sonde qui crée, configure et supprime réellement un cgroup avant toute revendication ;
- créé par run/provider/scope, avant l’exécution, avec `memory.max`, `memory.swap.max`, `pids.max` et `cpu.max` relus depuis le noyau ;
- rejoint par le processus sandbox **avant** l’exec du code non fiable, donc hérité par tous ses descendants ;
- invisible et non modifiable depuis la sandbox : `--unshare-all` isole le cgroup namespace et `/sys/fs/cgroup` n’est jamais monté ;
- détruit par `cgroup.kill` puis supprimé, quel que soit le résultat.

Sans frontière cgroup délégée, le backend Linux n’existe pas : `PlatformDisposition.BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY` est déclaré, le sélecteur retombe sur le backend process-only et l’exécution distante échoue avant le provider. L'absence des primitives namespace/seccomp reste signalée par `BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND`; aucune de ces situations ne déclenche un fallback d'exécution native.

Le scratch `/tmp` de la sandbox est un `tmpfs` privé dont les pages sont comptabilisées dans `memory.max` : son volume et son nombre d’inodes sont donc bornés par la frontière de job elle-même.

### Windows

La découverte exige Windows PowerShell 5.1 et le lanceur AppContainer. Le lanceur :

- crée et configure le Job Object **avant** de créer le processus contenu ;
- crée le processus `CREATE_SUSPENDED`, vérifie `TokenIsAppContainer`, l’assigne au job puis vérifie `IsProcessInJob` avant `ResumeThread` — aucun enfant ne peut donc exister hors du job ;
- relit les limites appliquées avec `QueryInformationJobObject` et refuse explicitement `JOB_OBJECT_LIMIT_BREAKAWAY_OK` / `SILENT_BREAKAWAY_OK` ;
- appelle `TerminateJobObject` sur tous les chemins de sortie, en plus de `KILL_ON_JOB_CLOSE`.

## Quota d’écriture filesystem

Ni Linux ni Windows n’offrent de quota disque par job à un utilisateur non privilégié. MINOS ne prétend donc pas à une garantie OS sur cette dimension : `ProviderWriteQuotaSupervisor` échantillonne, à période bornée (250 ms par défaut), **toutes** les racines rendues accessibles en écriture au provider (workspace, artifact, run) et détruit la frontière de job dès que le budget d’octets **ou** d’entrées est dépassé. Chaque échantillon est lui-même borné — le parcours s’arrête au dépassement — et ne suit jamais les symlinks.

La limite honnête de ce mécanisme est le dépassement possible pendant une période d’échantillonnage : le provider peut écrire au débit du disque pendant au plus une période avant d’être détruit. Le budget effectif est donc « budget + une période d’écriture », borné et documenté, et non « budget exact ». C’est précisément la raison pour laquelle cette dimension est déclarée `SUPERVISED_HARD_KILL` et non `OS_ENFORCED`.

Après succès, erreur, timeout ou dépassement, `ProviderResidueReclamation` supprime tout ce que le provider a écrit dans le run, ne conservant que les diagnostics écrits par MINOS. La workspace éphémère du worker est supprimée dans tous les cas par `LocalIsolatedIndexWorker`.

## Rétention des runs

`RunDirectoryRetention` traite un run précédent comme du résidu non fiable. Un run illisible ou dépassant le budget de mesure n’interrompt plus la rétention : il est classé récupérable en priorité et supprimé en premier. La suppression est bornée par invocation ; ce qui ne tient pas dans le budget est déplacé par renommage dans `runs/.quarantine` et terminé par les invocations suivantes. Rien n’est jamais supprimé hors de `runs` et aucun symlink n’est suivi.

## Limites et qualification

La qualification est propre à la plateforme et protégée par des tests négatifs réseau/filesystem, des tests adversariaux de confinement (mémoire agrégée, nombre de processus, CPU, descendants survivants, provider hostile en écriture) ainsi que par un test du chemin réel `ProcessIndexerExecutor → sandbox OS → provider → artefact`. `ALLOW` ne relâche ni les ACL/mounts, ni les limites mémoire/processus/CPU, ni les contrôles de provenance. `DENY` ne doit être annoncé que lorsque la primitive réseau OS est effectivement exercée.

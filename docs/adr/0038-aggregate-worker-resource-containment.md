# 0038 — Confiner les ressources des workers non fiables de manière agrégée et fail-closed

Status: Accepted — MINOS-01 (P1).

Complète [0033](0033-immutable-remote-revisions-and-verified-worker-artifacts.md) et précise la section « Real OS worker sandbox » de [0036](0036-fail-closed-production-boundaries-and-measured-program-graph.md).

## Contexte

MINOS annonçait `UNTRUSTED_CODE_SUPPORTED` pour ses backends worker Linux et Windows alors que le confinement réel n'était pas agrégé :

- sous Linux, les seules bornes de ressources étaient `RLIMIT_AS`, `RLIMIT_NPROC` et `RLIMIT_CPU` appliquées par `prlimit`. Ce sont des limites **par processus** : un provider les multiplie simplement en forkant ;
- aucune plateforme ne bornait le volume ni le nombre d'entrées écrites par le provider. Un provider hostile pouvait remplir le disque ou créer une quantité pathologique de fichiers ;
- un ancien run pathologique pouvait faire dépasser son budget de parcours à `RunDirectoryRetention`, dont l'échec se propageait et bloquait toute indexation ultérieure ;
- la qualification n'exprimait pas la différence entre une garantie réellement appliquée par l'OS, une supervision MINOS et une simple mesure a posteriori.

Une revendication d'isolation qui n'est pas exactement vraie est pire qu'une absence de revendication.

## Décision

### 1. Rendre le confinement explicite et par dimension

`WorkerResourceContainment` décrit chaque dimension (processus agrégés, mémoire agrégée, CPU agrégée, wall-clock, octets écrits, entrées écrites, terminaison des descendants, récupération du scratch) avec une disposition parmi `OS_ENFORCED`, `SUPERVISED_HARD_KILL`, `MEASURED_ONLY` et `UNAVAILABLE`.

`WorkerSandboxQualification` refuse structurellement `UNTRUSTED_CODE_SUPPORTED` sans une frontière de job `OS_ENFORCED` sur les quatre dimensions agrégées et au moins une application pendant l'exécution sur les autres. Une limite par processus ne peut plus être présentée comme une garantie agrégée.

### 2. Donner à Linux une vraie frontière de job : cgroup v2

Un cgroup v2 dédié au run/provider/scope est créé avant l'exécution, configuré avec `memory.max`, `memory.swap.max`, `pids.max` et `cpu.max` relus depuis le noyau, rejoint par le processus sandbox avant l'exec du code non fiable, invisible depuis la sandbox, puis détruit par `cgroup.kill` et supprimé. `prlimit` est conservé comme défense en profondeur par processus.

Sans racine cgroup déléguée réellement sondée, le backend Linux n'existe pas et l'exécution non fiable échoue avant le lancement du provider.

### 3. Durcir la frontière Windows existante

Le Job Object est créé et configuré **avant** le processus contenu, qui est créé suspendu, vérifié `TokenIsAppContainer`, assigné puis vérifié par `IsProcessInJob` avant `ResumeThread`. Les limites appliquées sont relues via `QueryInformationJobObject`, tout breakaway est explicitement refusé, une limite de temps CPU de job est ajoutée et `TerminateJobObject` est appelé sur tous les chemins de sortie.

### 4. Assumer le quota d'écriture comme supervision, pas comme garantie OS

Aucune des deux plateformes n'offre de quota disque par job à un utilisateur non privilégié. MINOS applique donc un budget d'octets **et** d'entrées pendant l'exécution, sur toutes les racines accessibles en écriture, avec destruction de la frontière de job au dépassement, et le déclare `SUPERVISED_HARD_KILL` — jamais `OS_ENFORCED`.

### 5. Ne jamais laisser un résidu bloquer le run suivant

Le résidu du provider dans le run est récupéré après succès, erreur, timeout ou dépassement. La rétention traite un ancien run illisible ou hors budget comme récupérable en priorité, borne la suppression par invocation et met en quarantaine le reliquat au lieu de propager un échec.

## Conséquences

### Positives

- une revendication `UNTRUSTED_CODE_SUPPORTED` correspond exactement au confinement enforceable sur l'hôte ;
- un `fork` ne multiplie plus aucune limite agrégée et aucun descendant ne survit au run ;
- un provider hostile ne peut ni remplir le disque, ni épuiser les entrées, ni empoisonner `runsRoot` ;
- l'absence d'une primitive se traduit par un refus avant exécution, pas par une garantie affaiblie et silencieuse.

### Coûts

- Linux exige une délégation cgroup v2 (unité systemd `Delegate=yes` ou `MINOS_SANDBOX_CGROUP_ROOT`) ; sans elle, l'indexation distante est refusée ;
- la qualification du processus MINOS déplace celui-ci dans un cgroup enfant dédié afin que la racine déléguée puisse porter `cgroup.subtree_control` ;
- la supervision d'écriture ajoute un parcours périodique borné des racines accessibles au provider ;
- le quota filesystem reste une supervision MINOS et doit être documenté comme telle tant qu'aucune primitive de quota par job non privilégiée n'existe.

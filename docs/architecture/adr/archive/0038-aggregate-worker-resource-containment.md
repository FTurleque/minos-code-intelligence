---
id: ADR-0038
title: "Confiner les ressources des workers non fiables de manière agrégée et fail-closed"
domain: security
rules: false
---

# 0038 — Confiner les ressources des workers non fiables de manière agrégée et fail-closed

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](../0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Superseded by [ADR-0070](../0070-code-non-fiable-confinement-et-indexation-distante.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Status: Accepted — MINOS-01 (P1). Amendé par [0041](0041-indexation-distante-de-code-non-fiable.md) (2026-09-26) sur la §4 : le quota d'écriture reste une supervision et l'indexation distante de code non fiable est fermée par décision.

Complète [0033](0033-immutable-remote-revisions-and-verified-worker-artifacts.md) et précise la §3 « Treat sandbox claims as platform-qualified facts » de [0036](0036-fail-closed-production-boundaries-and-measured-program-graph.md).

## Context

MINOS annonçait `UNTRUSTED_CODE_SUPPORTED` pour ses backends worker Linux et Windows alors que le confinement réel n'était pas agrégé :

- sous Linux, les seules bornes de ressources étaient `RLIMIT_AS`, `RLIMIT_NPROC` et `RLIMIT_CPU` appliquées par `prlimit`. Ce sont des limites **par processus** : un provider les multiplie simplement en forkant ;
- aucune plateforme ne bornait le volume ni le nombre d'entrées écrites par le provider. Un provider hostile pouvait remplir le disque ou créer une quantité pathologique de fichiers ;
- un ancien run pathologique pouvait faire dépasser son budget de parcours à `RunDirectoryRetention`, dont l'échec se propageait et bloquait toute indexation ultérieure ;
- la qualification n'exprimait pas la différence entre une garantie réellement appliquée par l'OS, une supervision MINOS et une simple mesure a posteriori.

Une revendication d'isolation qui n'est pas exactement vraie est pire qu'une absence de revendication.

## Decision

### 1. Rendre le confinement explicite et par dimension

`WorkerResourceContainment` décrit chaque dimension (processus agrégés, mémoire agrégée, CPU agrégée, wall-clock, octets écrits, entrées écrites, terminaison des descendants, récupération du scratch) avec une disposition parmi `OS_ENFORCED`, `SUPERVISED_HARD_KILL`, `MEASURED_ONLY` et `UNAVAILABLE`.

`WorkerSandboxQualification` refuse structurellement `UNTRUSTED_CODE_SUPPORTED` sans une frontière de job `OS_ENFORCED` sur les quatre dimensions agrégées et au moins une application pendant l'exécution sur les autres. Une limite par processus ne peut plus être présentée comme une garantie agrégée.

### 2. Donner à Linux une vraie frontière de job : cgroup v2

Un cgroup v2 dédié au run/provider/scope est créé avant l'exécution, configuré avec `memory.max`, `memory.swap.max`, `pids.max` et `cpu.max` relus depuis le noyau, rejoint par le processus sandbox avant l'exec du code non fiable, invisible depuis la sandbox, puis détruit par `cgroup.kill` et supprimé. `prlimit` est conservé comme défense en profondeur par processus.

*Amendé le 2026-10-16 (MINOS-AUD-H17) : `RLIMIT_NPROC` n'est ni une limite par processus ni une limite de l'arbre du provider, mais un plafond des tâches de l'utilisateur réel, threads de l'hôte compris.* Posé à 128 par `prlimit --nproc`, il faisait échouer le lancement (bubblewrap en `EAGAIN` au `fork`) dès que le compte exécutait déjà 128 threads : un IDE ouvert, un runner CI chargé, le compte d'un conteneur. Il ne défendait rien que `pids.max` ne borne déjà. Il est retiré : le nombre de processus de l'arbre du provider est borné par `pids.max` seul, obligatoire (sans cgroup délégué, pas de backend). `prlimit` garde `RLIMIT_AS`, `RLIMIT_NOFILE` et `RLIMIT_CPU`.

Sans racine cgroup déléguée réellement sondée, le backend Linux n'existe pas et l'exécution non fiable échoue avant le lancement du provider.

### 3. Durcir la frontière Windows existante

Le Job Object est créé et configuré **avant** le processus contenu, qui est créé suspendu, vérifié `TokenIsAppContainer`, assigné puis vérifié par `IsProcessInJob` avant `ResumeThread`. Les limites appliquées sont relues via `QueryInformationJobObject`, tout breakaway est explicitement refusé, une limite de temps CPU de job est ajoutée et `TerminateJobObject` est appelé sur tous les chemins de sortie.

*Amendé le 2026-10-08 (MINOS-AUD-H22, H23) : les droits accordés au SID du conteneur sur l'hôte sont eux aussi un résidu.* Chaque chemin est inscrit au journal de récupération du run **avant** `icacls /grant` ; un lanceur tué entre les deux laisse une ligne sans entrée, dont le retrait est sans effet. La reprise qui prouve la mort du propriétaire (verrou exclusif, A01) retire chaque droit journalisé ; une propriété non prouvable n'est jamais récupérée. Les entrées que rien ne journalise (builds antérieurs, `MINOS_HOME` de test abandonné) sont recensées par `scripts/windows/Find-MinosAppContainerGrants.ps1`, en lecture seule, et retirées seulement avec `-Remove` et confirmation. Les classes de tests qui démarrent le vrai bac à sable vérifient en fin de classe qu'aucune entrée nouvelle ne reste sur le JDK.

### 4. Assumer le quota d'écriture comme supervision, pas comme garantie OS

*Amendé par [0041](0041-indexation-distante-de-code-non-fiable.md) : cette supervision n'est pas un état transitoire « en attente d'une primitive » mais une décision. `UNTRUSTED_CODE_SUPPORTED` exige un quota `OS_ENFORCED` sur les octets **et** les entrées ; aucun backend intégré ne l'atteint, et l'indexation distante reste fermée sur tous les OS, avec un refus journalisé et diagnosticable.*

Aucune des deux plateformes n'offre de quota disque par job à un utilisateur non privilégié. MINOS applique donc un budget d'octets **et** d'entrées pendant l'exécution, sur toutes les racines accessibles en écriture, avec destruction de la frontière de job au dépassement, et le déclare `SUPERVISED_HARD_KILL` — jamais `OS_ENFORCED`.

### 5. Ne jamais laisser un résidu bloquer le run suivant

Le résidu du provider dans le run est récupéré après succès, erreur, timeout ou dépassement. La rétention traite un ancien run illisible ou hors budget comme récupérable en priorité, borne la suppression par invocation et met en quarantaine le reliquat au lieu de propager un échec.

## Do's and Don'ts

### Do

- Décrire chaque dimension de confinement par `WorkerResourceContainment`, avec une disposition parmi `OS_ENFORCED`, `SUPERVISED_HARD_KILL`, `MEASURED_ONLY` et `UNAVAILABLE`.
- Sous Linux, créer avant l'exécution un cgroup v2 dédié au run, configuré (`memory.max`, `memory.swap.max`, `pids.max`, `cpu.max`) et relu depuis le noyau, rejoint avant l'exec du code non fiable, puis détruit par `cgroup.kill`.
- Sous Windows, configurer le Job Object avant le processus, vérifier l'appartenance par `IsProcessInJob` avant `ResumeThread`, refuser tout breakaway et appeler `TerminateJobObject` sur tous les chemins de sortie.
- Inscrire chaque droit accordé au SID du conteneur au journal de récupération du run avant `icacls /grant`.
- Récupérer le résidu du provider après succès, erreur, timeout ou dépassement.

### Don't

- Ne pas déclarer `UNTRUSTED_CODE_SUPPORTED` sans frontière de job `OS_ENFORCED` sur les quatre dimensions agrégées.
- Ne pas présenter une limite par processus comme une garantie agrégée.
- Ne pas exécuter de code non fiable sous Linux sans racine cgroup déléguée réellement sondée.
- Ne pas déclarer le quota d'écriture `OS_ENFORCED` : il est `SUPERVISED_HARD_KILL`.
- Ne pas laisser un résidu ni un ancien run hors budget bloquer le run suivant.

## Consequences

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

## Compliance and Enforcement

- Tests `WorkerResourceContainmentTest` et `WorkerSandboxQualificationTest` (`minos-runtime-local`) — aucune qualification pour du code non fiable sans frontière de job agrégée appliquée par l'OS ; la supervision et la mesure a posteriori ne la remplacent pas.
- Test `LinuxBubblewrapWorkerSandboxBackendTest` — la commande bubblewrap ne passe pas `--nproc` (amendement du 2026-10-16).
- Test `ProcessIndexerExecutorBoundaryRefusalTest` — aucun exécuteur de `minos-runtime-local` ne lance un provider sans frontière.
- Revue : une nouvelle dimension ou un nouveau backend déclare sa disposition réelle, sans promouvoir une supervision en garantie OS.

## Mise en œuvre (AUD-ARC-09, octobre 2026)

Ajout, sans rien retirer à la décision ci-dessus : aucun exécuteur du module `minos-runtime-local` ne lance plus un provider sans frontière.

- **`ProcessIndexerExecutor.execute` refuse** (`IllegalStateException` au message fixe « provider process requires an ownership or sandbox boundary », avant toute création de répertoire de run) : toute exécution passe par `executeSandboxed` avec le transformateur qu'une frontière fournit. Le backend natif (`nativeEphemeralWorkspace()`) ne peut donc plus exécuter un exécuteur de processus nu ; il reste public parce que les fixtures de refus d'autres modules le construisent, et sa Javadoc dit qu'il ne confine rien.
- **Le constructeur à deux arguments de `StrongProcessOwnershipIndexerExecutor` est package-private** : la frontière de propriété seule (sans bac à sable ni politique réseau) n'est plus constructible hors du package ; les deux tests de confinement de la propriété l'atteignent par une fabrique de test. Le site de production reste le constructeur à trois arguments (politique réseau).
- **Garde** : `ProcessIndexerExecutorBoundaryRefusalTest` (tout constructeur public prend une politique réseau ; `execute` refuse sans créer de run ; le backend natif refuse un exécuteur nu). La règle A10 ne voit pas le package racine, d'où un test de structure plutôt qu'une règle de gate.
- **Limite** : un test qui exerce le processus lui-même passe par `UnboundedProcessForTests`, seul contournement nommé ; il ne prouve aucune frontière.

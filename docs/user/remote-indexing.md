# Indexer une révision distante

MINOS peut matérialiser une révision Git distante immuable. Cette surface est opt-in et limitée aux endpoints HTTPS officiels GitHub.com et GitLab.com.

## Préparer la révision exacte

Récupérez le SHA complet de 40 caractères de la branche ou du tag. Une branche seule n’est jamais suffisante.

```powershell
minos.cmd remote materialize https://github.com/acme/project `
  --ref refs/heads/main `
  --commit 0123456789abcdef0123456789abcdef01234567 `
  --format json
```

Pour un monorepo, ajoutez par exemple `--subdir services/catalog`. Le résultat confirme l’URI canonique, la révision, les racines confinées et l’état du cache.

## Dépôt privé

Ne placez jamais le token dans l’URL ou la commande. Passez seulement le nom d’une variable d’environnement :

```powershell
$env:MINOS_REMOTE_TOKEN='<token>'
minos.cmd remote materialize https://github.com/acme/private-project `
  --ref main `
  --commit 0123456789abcdef0123456789abcdef01234567 `
  --credential-env MINOS_REMOTE_TOKEN `
  --format json
```

MINOS ne persiste ni le token ni le nom de sa variable. Utilisez un token read-only.

Le nom de la variable n'est pas libre : seules `MINOS_REMOTE_TOKEN` (ou `MINOS_REMOTE_TOKEN_<SUFFIXE>`) et la variable usuelle de l'hôte visé (`MINOS_GITHUB_TOKEN`, `GITHUB_TOKEN`, `GH_TOKEN` pour github.com ; `MINOS_GITLAB_TOKEN`, `GITLAB_TOKEN` pour gitlab.com) sont acceptées. Toute autre variable est **refusée avant tout accès réseau**, avec un message qui liste les noms admis : une configuration ne peut pas faire partir une clé cloud ou le jeton d'un autre hôte. Si vous utilisiez un autre nom, renommez simplement la variable.

## État de `remote index`

`remote materialize` est utilisable indépendamment de la sandbox provider. En revanche, `remote index` n’exécute du code distant que si **toutes** les dimensions de confinement exigées sont qualifiées au niveau OS. Une **sandbox OS qualifiée** désigne ici une frontière qui satisfait réellement toutes ces exigences ; **aucun backend intégré ne l’est, par décision** ([ADR 0041](../adr/0041-indexation-distante-de-code-non-fiable.md), 2026-09-26).

Les backends locaux intégrés bornent la mémoire, les processus, la CPU et la durée via les primitives OS prévues (cgroup v2/bubblewrap sous Linux, AppContainer/Job Object sous Windows). Le quota d’écriture bytes/entrées reste **supervisé par MINOS** (`SUPERVISED_HARD_KILL`), pas un quota stockage `OS_ENFORCED` : l’ADR 0041 explique pourquoi cette limite est assumée plutôt que comblée (aucune primitive non privilégiée sous Windows ; quota de projet XFS/ext4 sous Linux seulement, chiffré et reporté). La qualification `UNTRUSTED_CODE_SUPPORTED` exigeant un quota stockage OS-enforced, les backends intégrés restent **fail-closed pour `remote index`**, sur tous les OS. Il n’existe pas d’option unsafe permettant de contourner cette exigence.

Le refus est explicite et diagnosticable :

- `remote index` refuse **avant** toute matérialisation, prise de bail, enregistrement de projet ou épinglage, avec un message sans chemin dont le contenu dépend de la cause (`WorkerSandboxSelection`) :
  - `REJECTED_BY_DECISION` — un backend OS a été découvert mais est écarté par décision (ADR 0041) : le message cite ce backend et les codes exacts des dimensions non OS-enforced (`FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL`, `FILESYSTEM_WRITE_ENTRIES_…`) ;
  - `NO_OS_BACKEND_AVAILABLE` — aucun backend OS n'a été découvert : le message cite le prérequis manquant (`LINUX_BUBBLEWRAP_NOT_FOUND`, `WINDOWS_POWERSHELL_NOT_FOUND`, …) ou la plateforme sans backend (`PLATFORM_OTHER_HAS_NO_OS_SANDBOX_BACKEND`) ;
  - `EXECUTOR_NOT_SANDBOX_CAPABLE` — l'exécuteur du provider n'expose aucune capacité sandbox (`EXECUTOR_NOT_PROCESS_SANDBOX_CAPABLE`) : ce refus est opposé en profondeur par le worker (`LocalIsolatedIndexWorker`), pas par le contrôle précoce ;
- le sélecteur journalise en WARNING, sans chemin, chaque backend OS écarté avec ses codes de dimension, et l'absence de tout backend OS avec les codes du prérequis manquant ou de la plateforme sans backend ;
- `minos doctor` (section `workerSandbox`) dit si l’indexation distante est disponible et, sinon, distingue un **prérequis manquant** (aucun backend OS découvert : `LINUX_BUBBLEWRAP_NOT_FOUND`, `LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING`, `WINDOWS_POWERSHELL_NOT_FOUND`, …) de la **décision** (`REJECTED_BY_DECISION`, marqueur `ADR 0041`).

La commande suivante décrit donc le contrat cible et ne réussira que sur un backend futur réellement qualifié pour toutes les dimensions (nouvel ADR requis) :

```powershell
minos.cmd remote index https://github.com/acme/project `
  --ref main `
  --commit 0123456789abcdef0123456789abcdef01234567 `
  --name acme-project-at-commit `
  --provider scip-java `
  --worker local-qualified `
  --worker-network allow `
  --format json
```

`--worker-network` est obligatoire :

- `ALLOW` (`allow` en CLI) autorise le réseau uniquement dans une sandbox par ailleurs qualifiée ;
- `DENY` (`deny` en CLI) exige en plus un blocage réseau au niveau OS.

Sous Linux, la qualification CPU/mémoire/processus exige notamment une racine cgroup v2 déléguée : soit le cgroup du processus MINOS lui-même (unité systemd avec `Delegate=yes`), soit un sous-arbre explicitement désigné par `MINOS_SANDBOX_CGROUP_ROOT`. Cette condition ne remplace pas l’exigence distincte de quota stockage OS-enforced.

### Prérequis opérateur — sandbox Linux (indexation locale gérée)

Ces prérequis servent l'**indexation locale gérée** (providers gérés lancés sous sandbox OS). Ils **ne rouvrent pas `remote index`**, fermé par décision ([ADR 0041](../adr/0041-indexation-distante-de-code-non-fiable.md)) : une fois réunis, `minos doctor` passe seulement de la cause `NO_OS_BACKEND_AVAILABLE` (prérequis manquant) à `REJECTED_BY_DECISION`, et `remote index` refuse toujours.

La qualification `linux-bubblewrap-cgroup2-v5` (voir [`remote-worker-sandbox-disposition.md`](../developer/remote-worker-sandbox-disposition.md)) sonde réellement les primitives disponibles sur l'hôte avant toute revendication. Sans elles, aucun backend OS Linux n'est découvert : les providers locaux gérés n'ont pas de sandbox OS et `remote index` refuse avec la cause `NO_OS_BACKEND_AVAILABLE` — il n'existe aucun contournement. Sur un hôte opérateur (hors CI, où `pr-ci.yml`/`scripts/ci/delegate-linux-cgroup.sh` provisionnent déjà tout ceci), il faut réunir explicitement :

1. **`bwrap` et `prlimit`** — installez `bubblewrap` et `util-linux` avec le gestionnaire de paquets de la distribution, par exemple :

   ```bash
   # Debian / Ubuntu
   sudo apt-get install --yes bubblewrap util-linux
   # Fedora / RHEL
   sudo dnf install -y bubblewrap util-linux
   ```

2. **User namespaces non privilégiés autorisés par le noyau/LSM** — bubblewrap a besoin de créer un user namespace sans privilège root. Sur certaines distributions (Ubuntu récent notamment), le LSM AppArmor restreint cette création par défaut pour les binaires non confinés ; il faut alors charger un profil qui l'autorise explicitement, par exemple le profil `bwrap-userns-restrict` fourni par le paquet `apparmor-profiles` :

   ```bash
   sudo apt-get install --yes apparmor apparmor-profiles
   profile=/usr/share/apparmor/extra-profiles/bwrap-userns-restrict
   sudo cp "$profile" /etc/apparmor.d/minos-bwrap-userns-restrict
   sudo apparmor_parser -r /etc/apparmor.d/minos-bwrap-userns-restrict
   ```

   Sur une distribution sans AppArmor (ou où `kernel.unprivileged_userns_clone` est déjà activé sans restriction LSM additionnelle), cette étape peut ne pas être nécessaire ; la découverte MINOS sonde la capacité réelle et n'exige pas de mécanisme LSM spécifique — seulement que l'opération réussisse. Si elle échoue, MINOS refuse le backend Linux plutôt que de deviner une politique de contournement.

3. **Une racine cgroup v2 déléguée** — deux options, décrites dans [`quality-gates.md`](../developer/quality-gates.md) :

   - **Recommandé en production : une unité systemd avec `Delegate=yes`.** systemd place lui-même le processus MINOS dans le cgroup délégué ; aucune étape supplémentaire n'est requise et aucune permission n'est accordée hors de ce sous-arbre.

   - **Sinon : provisionner explicitement un sous-arbre.** Le script [`scripts/deploy/provision-linux-sandbox-cgroup.sh`](../../scripts/deploy/provision-linux-sandbox-cgroup.sh) crée le sous-arbre, active les contrôleurs `memory`/`pids`/`cpu`, délègue le sous-arbre au compte MINOS, et — avec `--attach-pid` — place le shell qui lancera MINOS **à l'intérieur** de ce sous-arbre :

     ```bash
     # une seule fois : provisionner le sous-arbre délégué
     scripts/deploy/provision-linux-sandbox-cgroup.sh

     # par shell/session : provisionner (idempotent) et y placer CE shell
     scripts/deploy/provision-linux-sandbox-cgroup.sh --attach-pid $$
     export MINOS_SANDBOX_CGROUP_ROOT=/sys/fs/cgroup/minos.slice
     ```

     MINOS lancé depuis ce shell hérite du cgroup et n'a donc jamais besoin de migrer un processus au-delà de sa propre frontière.

#### Pourquoi `--attach-pid` plutôt qu'une permission plus large

cgroup v2 n'autorise un délégataire non privilégié à migrer un processus que s'il peut écrire **à la fois** le `cgroup.procs` de destination **et** celui de l'ancêtre commun des cgroups source et destination. Un MINOS démarré *hors* du sous-arbre délégué a donc le cgroup racine comme ancêtre commun — et accorder au compte MINOS un droit d'écriture durable sur `/sys/fs/cgroup/cgroup.procs` lui permettrait de déplacer des processus n'importe où dans la hiérarchie, y compris **hors** de sa propre frontière de délégation. C'est une évasion de délégation ; MINOS ne demande donc jamais ce droit.

L'unique migration nécessaire est effectuée par le script pendant sa phase privilégiée (`--attach-pid`). MINOS se retrouve déjà dans le cgroup contrôleur, n'a aucune migration à faire, et n'écrit que dans le sous-arbre qu'il possède réellement. C'est exactement la forme que produit nativement `Delegate=yes`.

Sans l'une de ces deux options, le backend Linux se déclare `BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY` et les providers locaux gérés n'ont pas de sandbox OS ; `remote index` échoue (ici avec la cause `NO_OS_BACKEND_AVAILABLE`, et par décision une fois la racine déléguée) avant tout lancement de provider — jamais par un repli silencieux vers une exécution non confinée. En particulier, si le shell n'a pas été attaché, la qualification de la racine déléguée échoue et MINOS reste fail-closed au lieu de tenter une migration privilégiée.

#### Mise à jour : arrêter les instances plus anciennes qui partagent la racine déléguée

À partir de la version qui suit 1.2.0 (`1.3.0-SNAPSHOT` sur `develop` au moment du changement), chaque cgroup créé par MINOS porte dans son nom une marque d'appartenance `<job>.own-<pid>-t<ticks>-n<espace PID>_<espace temps>-<jeton>` : le PID du MINOS propriétaire, son instant de démarrage en ticks noyau depuis le boot (`/proc/<pid>/stat`, champ 22, insensible aux sauts d'horloge murale), les identifiants de ses espaces de noms PID et temps (`/proc/<pid>/ns/pid` et `ns/time`) et un jeton propre à l'instance.

**En cas de doute, MINOS ne récupère pas.** Un résidu laissé derrière coûte de la mémoire et des `pids` de la racine déléguée, et il est signalé ; un processus vivant tué serait une régression grave. Le balayage exécuté à la qualification de la racine ne tue donc un cgroup que sur une preuve positive que son propriétaire est mort : son PID est absent d'une table des processus qui a montré qu'elle fonctionne (`/proc` lisible, celle de cet espace de PID, sans masquage `hidepid` des processus d'un autre compte), ou son PID existe avec des ticks de démarrage complets et différents (réutilisation du PID), dans les mêmes espaces de noms PID et temps que le balayeur. Un cgroup qui ne contient aucun processus (ni en dessous) est supprimé, jamais tué. Tout le reste est laissé intact : propriétaire vivant, `/proc` illisible ou partiel, lecture de `stat` tronquée ou mal formée, propriétaire d'un autre espace de noms, marque sans espaces de noms, cgroup non marqué encore peuplé.

Ce que voit l'opérateur : chaque qualification écrit au plus deux lignes, sans aucun chemin absolu. Un INFO « MINOS reclaimed N stale cgroup(s) … » nomme les cgroups récupérés. Un WARNING « MINOS left N cgroup(s) intact … » nomme chaque cgroup laissé en place, avec sa raison, y compris un cgroup récupéré qu'il a été impossible de supprimer ; il ajoute « N entries were not examined » quand la racine contient plus de 4 096 entrées.

Avant de lancer cette version sur une racine déléguée partagée (le même `MINOS_SANDBOX_CGROUP_ROOT`, ou la même unité `Delegate=yes`), **arrêtez** :

- toute instance **MINOS ≤ 1.2.0** (CLI, serveur MCP, plugin IntelliJ) : à la qualification de la racine, elle tue **tout** cgroup `minos-*` peuplé qu'elle trouve, y compris les jobs en cours d'une instance plus récente ;
- tout **build `develop` antérieur** à ce changement de format : ces builds écrivent une marque `.own-<pid>-<epochMillis>-<jeton>` (instant mural, qui peut faire passer une instance vivante pour morte après un saut d'horloge NTP) ou `.own-<pid>-t<ticks>-<jeton>` (sans espaces de noms). Ils lisent la marque du nouveau format comme « non marqué » : ils ne tuent jamais un cgroup peuplé, mais prennent un cgroup encore vide pour un cgroup « non marqué et vide » et le suppriment.

Compatibilité dans l'autre sens : un cgroup marqué par ces builds `develop` antérieurs n'est **jamais** récupéré automatiquement, même si son PID propriétaire semble mort : rien dans sa marque ne prouve que ce PID se lit dans le même espace de noms que celui du balayeur. Il est signalé dans le WARNING, avec son nom et la raison, et c'est à l'opérateur de le supprimer (`cgroup.kill`, puis `rmdir`) après avoir arrêté l'instance qui l'a créé. Il en va de même d'un cgroup `minos-*` sans marque qui contient encore des processus.

Le transport vérifié utilise `minos-distributed-artifact-v2` et lie chaque artefact à son `projectRelativeRoot`. Le format historique `minos-distributed-artifact-v1` reste reconnu comme fait de compatibilité/documentation, mais il ne transporte pas le scope et n’est donc pas accepté comme provenance vérifiée pour une nouvelle exécution. Le résultat expose le snapshot actif et, pour chaque provider, sa version, le worker, l’isolation, la politique réseau, les SHA-256 vérifiés et le scope du module indexé.

Le backend natif ne fournit qu'une isolation de processus et de workspace. Il refuse `deny` : ces primitives ne prouvent pas un blocage réseau au niveau OS. Il reste également interdit comme repli pour `remote index` avec `allow`, car elles ne prouvent pas le confinement complet de code non fiable.

## Sécurité et limites

- HTTPS `github.com` / `gitlab.com` uniquement ;
- pas de GitHub/GitLab Enterprise, SSH, submodules ou ref non épinglée ;
- checkout exact et propre obligatoire ;
- cache source et cache artefact locaux, reconstructibles et bornés ;
- `.gitignore`, `.minosignore`, hard ignores et budgets appliqués à la workspace du provider ;
- symlinks rejetés ;
- manifest V2, checksum, provenance et scope validés avant import ;
- promotion atomique locale inchangée ;
- aucune capability CFG/def-use/data-flow/security déduite du seul transport SCIP ;
- aucun scheduler, worker partagé ou hosted mode fourni par cette commande.

Toute erreur de commit, qualification sandbox, politique réseau, budget, checksum, provenance ou confinement est fail-closed et aucun snapshot partiel n’est promu.

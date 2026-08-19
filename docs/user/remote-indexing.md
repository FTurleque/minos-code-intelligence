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

## État de `remote index`

`remote materialize` est utilisable indépendamment de la sandbox provider. En revanche, `remote index` n’exécute du code distant que si **toutes** les dimensions de confinement exigées sont qualifiées au niveau OS. Une **sandbox OS qualifiée** désigne ici une frontière qui satisfait réellement toutes ces exigences ; les backends intégrés actuels n’atteignent pas encore cette qualification complète.

Les backends locaux intégrés bornent aujourd’hui la mémoire, les processus, la CPU et la durée via les primitives OS prévues (cgroup v2/bubblewrap sous Linux, AppContainer/Job Object sous Windows). Le quota d’écriture bytes/entrées reste supervisé par MINOS et n’est pas encore un quota stockage `OS_ENFORCED`. La qualification `UNTRUSTED_CODE_SUPPORTED` exigeant un quota stockage OS-enforced, les backends intégrés actuels restent **fail-closed pour `remote index`**. Il n’existe pas d’option unsafe permettant de contourner cette exigence.

La commande suivante décrit donc le contrat cible et ne réussira que sur un backend futur réellement qualifié pour toutes les dimensions :

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

### Prérequis opérateur — sandbox Linux

La qualification `linux-bubblewrap-cgroup2-v5` (voir [`remote-worker-sandbox-disposition.md`](../developer/remote-worker-sandbox-disposition.md)) sonde réellement les primitives disponibles sur l'hôte avant toute revendication. Sans elles, MINOS reste fail-closed sur `remote index` — il n'existe aucun contournement. Sur un hôte opérateur (hors CI, où `pr-ci.yml`/`scripts/ci/delegate-linux-cgroup.sh` provisionnent déjà tout ceci), il faut réunir explicitement :

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

Sans l'une de ces deux options, le backend Linux se déclare `BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY` et `remote index` échoue avant tout lancement de provider — jamais par un repli silencieux vers une exécution non confinée. En particulier, si le shell n'a pas été attaché, la qualification de la racine déléguée échoue et MINOS reste fail-closed au lieu de tenter une migration privilégiée.

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

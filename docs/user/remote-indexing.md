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

`remote materialize` est utilisable indépendamment de la sandbox provider. En revanche, `remote index` n’exécute du code distant que si **toutes** les dimensions de confinement exigées sont qualifiées au niveau OS.

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

Le transport vérifié utilise `minos-distributed-artifact-v2` et lie chaque artefact à son `projectRelativeRoot`. Les manifests V1 ne sont pas acceptés comme provenance vérifiée pour une nouvelle exécution. Le résultat expose le snapshot actif et, pour chaque provider, sa version, le worker, l’isolation, la politique réseau, les SHA-256 vérifiés et le scope du module indexé.

Le backend natif ne fournit qu'une isolation de processus et de workspace. Il ne prouve ni le confinement complet de code non fiable ni le blocage réseau au niveau OS et n’est donc jamais une solution de repli pour `remote index`.

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

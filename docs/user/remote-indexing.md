# Indexer une révision distante

MINOS peut matérialiser puis indexer une révision Git distante immuable. Cette surface est opt-in et limitée aux endpoints HTTPS officiels GitHub.com et GitLab.com.

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

## Indexer dans la sandbox locale qualifiée

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

- `ALLOW` (`allow` en CLI) laisse le provider accéder au réseau à l’intérieur de la sandbox qualifiée ;
- `DENY` (`deny` en CLI) bloque le réseau au niveau OS à l’intérieur de cette même sandbox.

La sandbox OS qualifiée utilise bubblewrap/namespaces/`prlimit` sous Linux et AppContainer + Job Object sous Windows. Si ce mécanisme n’est pas disponible, l’indexation échoue avant l’exécution. Le backend process-only natif n’est accepté ni avec `allow`, ni avec `deny`; il n’existe pas d’option unsafe de contournement.

Le transport utilise `minos-distributed-artifact-v1`. Le résultat expose le snapshot actif et, pour chaque provider, sa version, le worker, l’isolation, la politique réseau et les SHA-256 vérifiés.

Le backend natif ne fournit qu'une isolation de processus et de workspace. Il refuse `deny`, car ces primitives seules ne prouvent pas un blocage réseau au niveau OS, et le worker distant le refuse aussi avec `allow`, car elles ne prouvent pas le confinement de code non fiable.

## Sécurité et limites

- HTTPS `github.com` / `gitlab.com` uniquement ;
- pas de GitHub/GitLab Enterprise, SSH, submodules ou ref non épinglée ;
- checkout exact et propre obligatoire ;
- cache source et cache artefact locaux, reconstructibles et bornés ;
- `.gitignore`, `.minosignore`, hard ignores et budgets appliqués à la workspace du provider ;
- symlinks rejetés ;
- manifest, checksum et provenance validés avant import ;
- promotion atomique locale inchangée ;
- aucune capability CFG/def-use/data-flow/security déduite du seul transport SCIP ;
- aucun scheduler, worker partagé ou hosted mode fourni par cette commande.

Toute erreur de commit, qualification sandbox, politique réseau, budget, checksum, provenance ou confinement est fail-closed et aucun snapshot partiel n’est promu.

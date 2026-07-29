# Indexer une révision distante — M25

M25 permet de matérialiser puis d’indexer une révision Git distante immuable. Cette surface est opt-in et limitée aux endpoints HTTPS officiels GitHub.com et GitLab.com.

## Préparer la révision exacte

Récupérer le SHA complet de 40 caractères de la branche ou du tag à indexer. Une branche seule n’est jamais suffisante.

```powershell
minos.cmd remote materialize https://github.com/acme/project `
  --ref refs/heads/main `
  --commit 0123456789abcdef0123456789abcdef01234567 `
  --format json
```

Pour un monorepo :

```powershell
minos.cmd remote materialize https://gitlab.com/acme/platform `
  --ref release/2026.07 `
  --commit 0123456789abcdef0123456789abcdef01234567 `
  --subdir services/catalog `
  --format json
```

Le JSON confirme host, URI canonique, ref, commit, racines confinées, clé/cache-hit et date de matérialisation.

## Dépôt privé

Ne jamais placer le token dans l’URL ou dans une commande. Définir une variable d’environnement puis passer seulement son nom :

```powershell
$env:MINOS_REMOTE_TOKEN='<token>'
minos.cmd remote materialize https://github.com/acme/private-project `
  --ref main `
  --commit 0123456789abcdef0123456789abcdef01234567 `
  --credential-env MINOS_REMOTE_TOKEN `
  --format json
```

MINOS ne persiste ni le token ni le nom de sa variable dans le cache ou l’évidence. Les permissions du token restent sous responsabilité opérateur et doivent être read-only.

## Indexer via le worker natif

```powershell
minos.cmd remote index https://github.com/acme/project `
  --ref main `
  --commit 0123456789abcdef0123456789abcdef01234567 `
  --name acme-project-at-commit `
  --provider scip-java `
  --worker local-native `
  --worker-network allow `
  --format json
```

`--worker-network` est obligatoire. Le worker natif accepte `allow` explicitement. Il refuse `deny`, car une copie de workspace et un process séparé ne prouvent pas un blocage réseau au niveau OS. Il faut un backend durci futur pour une politique `deny` réellement enforced.

Le transport utilise le format strict `minos-distributed-artifact-v1`. Le résultat JSON expose le snapshot actif et une évidence par provider : version, langage, worker, isolation, politique réseau, SHA-256 de `index.scip`, SHA-256 du bundle et état du cache vérifié.

## Sécurité et limites

- HTTPS `github.com` / `gitlab.com` uniquement ;
- pas de GitHub/GitLab Enterprise, SSH, submodules ou ref non épinglée dans M25 ;
- checkout exact et propre obligatoire ;
- cache source et cache artefact locaux, reconstructibles et bornés ;
- symlinks rejetés dans la workspace worker ;
- manifest et checksum validés avant import ;
- promotion atomique locale inchangée ;
- aucune capability CFG/def-use/data-flow/security déduite du seul transport SCIP ;
- aucun scheduler, worker partagé ou hosted mode n’est fourni par M25.

La validation est fail-closed : une erreur de commit, checksum, provenance, politique réseau ou confinement provoque un échec ; MINOS ne promeut pas de snapshot partiel.

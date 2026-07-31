# Remote worker — disposition d’isolation M28

M28 distingue explicitement trois notions qui ne doivent jamais être confondues :

1. la copie dans une workspace éphémère ;
2. la séparation dans un processus distinct ;
3. une sandbox OS qualifiée pour exécuter du code non fiable.

Le backend courant `native-process-ephemeral-workspace-v1` fournit uniquement les deux premières garanties. Il conserve les invariants M25 de provenance, de confinement applicatif, de suppression de `.git`, de rejet des symlinks et d’artefact signé par checksums, mais **ne constitue pas une sandbox pour code non fiable**.

## Disposition courante

| Propriété | Disposition M28 |
|---|---|
| Isolation déclarée | `PROCESS_EPHEMERAL_WORKSPACE` |
| Garantie réseau | `NONE` |
| Politique `ALLOW` | supportée explicitement |
| Politique `DENY` | `FAIL_CLOSED_NOT_ENFORCED` |
| Code non fiable | `UNTRUSTED_CODE_UNSUPPORTED` |
| Claim « sandbox » | interdit |

La politique `DENY` est rejetée avant l’exécution du provider. MINOS ne transforme pas l’absence de mécanisme OS en succès logique.

## Matrice plateforme

| Plateforme | Disposition | Mécanisme manquant avant qualification |
|---|---|---|
| Windows | `BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND` | restricted token/AppContainer, Job Object borné, règles réseau OS et tests d’évasion |
| Linux | `BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND` | namespaces adaptés, seccomp/capabilities, règles réseau OS et tests d’évasion |
| Autre | `NOT_APPLICABLE` | backend et protocole de qualification dédiés |

Cette matrice est exposée par `WorkerSandboxQualification` et protégée par `WorkerSandboxQualificationTest` ainsi que `scripts/m28/check-m28.py`.

## Conditions d’un futur PASS

Un backend ne peut annoncer `OS_ENFORCED` et `UNTRUSTED_CODE_SUPPORTED` que si :

- son mécanisme d’isolation est propre à la plateforme et réellement utilisé pour lancer le provider ;
- la politique réseau `DENY` est observée par un test négatif externe au processus indexeur ;
- les accès filesystem, processus, IPC et réseau sont testés contre des tentatives d’évasion ;
- la disposition qualifiée est associée à une plateforme, une version et une preuve exact-head ;
- les bundles, checksums et contrôles de provenance M25 restent inchangés.

En l’absence de ces preuves, le comportement autorisé reste `ALLOW` explicite ou échec fail-closed.

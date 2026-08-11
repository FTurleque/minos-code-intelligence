# Remote worker — disposition d’isolation

L’indexation distante traite le provider et le dépôt matérialisé comme du code non fiable. Une copie dans une workspace éphémère et un processus distinct ne suffisent donc jamais : le provider doit être lancé par un backend OS dont la qualification annonce `UNTRUSTED_CODE_SUPPORTED` sur la plateforme courante.

## Contrat fail-closed

`LocalIsolatedIndexWorker` vérifie la qualification avant toute copie ou exécution, indépendamment de la politique réseau :

- `ALLOW` autorise le réseau **dans** une sandbox OS qualifiée ;
- `DENY` exige en plus une preuve de blocage réseau `OS_ENFORCED` ;
- `native-process-ephemeral-workspace-v1` reste `UNTRUSTED_CODE_UNSUPPORTED` et est refusé avec `ALLOW` comme avec `DENY` ;
- aucun opt-in unsafe permettant d’exécuter du code distant avec les droits de l’hôte n’existe.

Cette disposition conserve l'invariant diagnostique `WORKER_SANDBOX_CLAIM_PROHIBITED` : un backend process-only ne peut jamais revendiquer le statut d'une sandbox OS qualifiée.

La provenance, les checksums, le bundle distribué, le rejet des symlinks, les budgets de source et les règles `.gitignore`/`.minosignore` restent appliqués en complément de la sandbox.

## Backends qualifiés

| Plateforme | Backend | Isolation hôte | `ALLOW` | `DENY` |
|---|---|---|---|---|
| Linux | `linux-bubblewrap-prlimit-v4` | bubblewrap, namespaces, racine hôte read-only, capacités supprimées, limites `prlimit` | namespace réseau hôte explicitement partagé | namespace réseau isolé |
| Windows | `windows-appcontainer-job-v2` | AppContainer, ACL éphémères ciblées, vérification du token, Job Object borné | seule capacité `internetClient` (`S-1-15-3-1`) | ensemble de capacités vide |
| Autre | aucun | — | refus | refus |

La découverte Linux sonde réellement `bubblewrap`, `prlimit`, les user namespaces et la politique LSM. La découverte Windows exige Windows PowerShell 5.1 et le lanceur AppContainer. Si la primitive qualifiée n’est pas disponible, le sélecteur peut décrire le backend natif pour le diagnostic, mais le worker distant échoue avant le provider.

Sur Linux, l'absence des primitives namespace/seccomp requises reste signalée par `BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND`; elle ne déclenche jamais un fallback d'exécution native.

## Limites et qualification

La qualification est propre à la plateforme et protégée par des tests négatifs réseau/filesystem ainsi que par un test du chemin réel `ProcessIndexerExecutor → sandbox OS → provider → artefact`. `ALLOW` ne relâche ni les ACL/mounts, ni les limites mémoire/processus/CPU, ni les contrôles de provenance. `DENY` ne doit être annoncé que lorsque la primitive réseau OS est effectivement exercée.

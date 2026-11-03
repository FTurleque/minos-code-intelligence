# Proposal

## Why

**AUD-SEC-03, lot « runtime local », partie artefacts et espaces de travail** (sprint 8 de l'audit du 10 octobre 2026). Dix entrées de `scripts/architecture/private-io-allowlist.json` (12 occurrences) nomment ce lot dans six fichiers de `minos-runtime-local` : `DistributedArtifactBundleStore`, `BoundedProcessOutput`, `ProcessIndexerExecutor`, `ProviderWorkspaceFiles`, `RunDirectoryRetention` et `LocalIsolatedIndexWorker`. Ils écrivent les paquets d'artefacts, les sorties des processus provider, les copies d'artefacts conservées et la copie du projet dans l'arbre du provider, et ils mettent de côté des répertoires hostiles ou périmés.

Ces fichiers touchent la frontière la plus sensible du produit : un répertoire que le provider non fiable (conteneur AppContainer ou bubblewrap) lit et écrit. Un durcissement mal placé casse l'accès du conteneur ; un durcissement absent laisse le conteneur modifier ce que MINOS relit (journaux, copie d'artefact). La migration doit donc choisir le niveau d'écriture site par site.

## What Changes

- Sorties des processus (`BoundedProcessOutput.openTarget`) et copies d'artefacts conservées (`ProcessIndexerExecutor.copyArtifactBounded`, `DistributedArtifactBundleStore.copyBounded`) : création exclusive **durcie** (`createPrivateOutputStream`), propriétaire seul, non héritée par le conteneur.
- Copie du projet dans l'arbre du provider (`ProviderWorkspaceFiles.copyBounded`) et paquet écrit dans le dossier de l'appelant (`DistributedArtifactBundleStore.createBundle`) : écriture d'**arbre** (`openNewTreeFile`), accès hérité.
- Déplacements de fichiers (`DistributedArtifactBundleStore.move`) : `DurableAtomicFile.replace`, sans repli non atomique. Mises de côté de répertoires (`ProcessIndexerExecutor.quarantineHostileDirectory`, `RunDirectoryRetention.quarantine`) : `DurableAtomicFile.moveDirectory`.
- Réservation de nom de `ProcessIndexerExecutor.move` (`createTempFile` suivi d'un `deleteIfExists`) : un temporaire privé, sans suppression.
- `LocalIsolatedIndexWorker` : l'entrée `createDirectory` est reclassée (décision D1), pas migrée.
- Neuf entrées retirées et une reclassée ; le décompte du gate baisse de 11 occurrences.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : une exigence ajoutée (les sorties et les copies d'artefacts que MINOS relit ne sont jamais modifiables par le provider ; l'arbre de travail du provider garde l'accès hérité).
- `stockage-prive-et-secrets` : une exigence ajoutée (mise de côté de répertoire fail-closed ; paquet d'artefact publié atomiquement sans repli).

## Hors périmètre

- Les `Files.createDirectories` de `ProviderWorkspaceFiles`, `DistributedArtifactBundleStore:132`, `LinuxBubblewrapWorkerSandboxBackend`, `WindowsAppContainerWorkerSandboxBackend` : entrées durables.
- Les entrées `LOGGER.log(cause)` et `FileChannel.lock` du même module.
- Le sandbox Windows (`SandboxLauncherScript`, redirection de la sonde) : `migrer-les-ecritures-windows-du-sandbox`.
- Le format du paquet (manifeste, ordre des entrées ZIP), les budgets et les limites.
- La rétention elle-même (`RunDirectoryRetention` : stratégie, seuils).

## Impact

- **Modules du reactor** : `minos-runtime-local` seul. Dépend du socle.
- **Surfaces publiques** : aucune ; format du paquet distribué inchangé.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/architecture/check-module-boundaries.py` (règle A10 : aucune classe exportée ne change de visibilité), `python scripts/quality/check-remote-distributed-consistency.py`, `python scripts/remediation/check-minos-01.py`, `check-post-mne.py`, `check-mnd.py`, `python scripts/quality/check-jacoco.py` (scopes `m25-remote-distributed-indexing`, `provider-execution-trust-boundary`, `run-directory-retention`, `global`), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows (AppContainer, héritage d'ACL) et Linux (bubblewrap) ; la CI n'exécute aucun provider, la preuve réelle est manuelle (tâche 5.1).

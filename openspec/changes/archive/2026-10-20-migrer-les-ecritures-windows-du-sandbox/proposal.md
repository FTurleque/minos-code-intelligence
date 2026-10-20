# Proposal

## Why

**AUD-SEC-03, lot « runtime local », partie Windows** (sprint 8 de l'audit du 10 octobre 2026). Deux entrées de `scripts/architecture/private-io-allowlist.json` concernent le bac à sable Windows :

- `SandboxLauncherScript.java:289` (`Files.move`, `ATOMIC_MOVE`) : publication du lanceur PowerShell de l'AppContainer par un déplacement qui, selon le JDK, remplace une cible existante. Le lanceur est ce que MINOS fait exécuter à l'utilisateur ; sa publication doit être exclusive et durable.
- `WindowsAppContainerWorkerSandboxBackend.java:424` (`redirectOutput(output.toFile())`) : la sonde de capacité de l'AppContainer écrit sa sortie dans un fichier non borné, que le lanceur remplit au-delà du tampon d'un tube (correctif H18). Le fichier tire ses droits de son dossier.

Les deux sites n'existent que sous Windows, et la CI Windows (`Verify (windows-2022)`) est la seule preuve automatique : ce changement est séparé de `migrer-les-ecritures-d-artefacts-du-runtime-local` pour qu'un échec propre à Windows ne bloque pas le reste du module.

## What Changes

- Le lanceur est publié par `DurableAtomicFile.publish` (lien dur exclusif, fichier synchronisé), la boucle de reprise bornée de `moveWithRetry` autour d'elle.
- La sonde lit la sortie du lanceur par un tube vidé en continu (`BoundedProcessOutput.capture`) vers un fichier privé que `BoundedProcessOutput.openTarget` crée ; la sortie devient bornée. Le test H18 (`AppContainerProbeOutputTest`) reste le garde.
- Deux entrées retirées.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : une exigence ajoutée (le lanceur de l'AppContainer est publié sans écraser une cible existante ; la sortie de la sonde est bornée et privée).

## Hors périmètre

- Les ACL du lanceur et sa vérification (`verify`, `requireParentNotReplaceableByOthers`) ; `verifier-le-proprietaire-des-dossiers-prives`.
- Le contenu du lanceur (`WindowsContainmentScript`) et du gabarit PowerShell.
- `Files.createDirectories(artifactParent)` du backend (`:265`) : entrée durable (héritage d'ACL du conteneur).
- Les entrées `LOGGER.log(cause)` du backend et de `WindowsJobObjectProcessOwnership`.
- La séquence de lancement de l'AppContainer.

## Impact

- **Modules du reactor** : `minos-runtime-local` seul. **Indépendant du socle** : `DurableAtomicFile.publish` et `BoundedProcessOutput.capture(Process, Path, Path)` existent déjà.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/remediation/check-post228-hardening.py` (exige `probeOsIsolation()` et la chaîne `CAPABILITY_PROBE_CACHE` dans le backend), `python scripts/remediation/check-minos-01.py`, `check-mnd.py`, `check-mne.py`, `check-post-mne.py`, `python scripts/quality/check-jacoco.py` (scope `provider-sandbox-windows`, Windows seulement), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows (code et tests propres à Windows) ; sous Linux le gate Python s'exécute, les tests sont ignorés.

# Proposal

## Why

**AUD-SEC-03, lot « stockage local »** (sprint 8 de l'audit du 10 octobre 2026). Dix entrées de `scripts/architecture/private-io-allowlist.json`, soit 13 occurrences, nomment ce lot : huit fichiers de `minos-storage-local` écrivent encore avec `Files.newOutputStream`, `Files.newBufferedWriter`, `Files.copy` ou `Files.move`.

Aucune de ces écritures ne crée un fichier lisible par tous : chacune ouvre un fichier temporaire que `PrivateLocalStorage.createPrivateTempFile` vient de créer privé. Ce qui manque, c'est le suivi de lien (`Files.newOutputStream(temporaire)` suit un lien substitué après la création), la vérification de type de la cible et, pour deux sites, l'atomicité (`Files.copy` de sauvegarde, `Files.move` d'une session). Le moteur ne l'offre pas encore en flux ; `completer-les-primitives-d-e-s-privees` l'ajoute.

## What Changes

- Les écritures en flux (snapshots V1, V2, V3, pointeur actif, empreintes, vecteurs sémantiques, session d'observation) passent par `PrivateLocalStorage.openPrivateOutputStream`.
- Les écritures de fichiers `Properties` (état d'index, registre, correspondance de chemins) passent par `PrivateLocalStorage.writePrivateFile` : un `Properties` est petit et borné, le contenu s'encode en mémoire avec le même jeu de caractères qu'aujourd'hui.
- La sauvegarde `.m29-v1.bak` du registre est lue par `ConfinedFileOpener.openRegularFileNoFollow`, écrite dans un temporaire privé, puis publiée par `DurableAtomicFile.publish` (jamais d'écrasement).
- La publication d'une session d'observation passe de `Files.move(ATOMIC_MOVE)` à `DurableAtomicFile.publish`.
- Dix entrées de liste retirées ; le gate littéral `check-runtime-dynamic-consistency.py` est mis à jour pour la chaîne `ATOMIC_MOVE` qui quitte `FileRuntimeObservationStore.java`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : deux exigences ajoutées (les écritures du stockage local passent par les primitives ; une sauvegarde de migration et une session d'observation déjà présentes ne sont jamais écrasées).

## Hors périmètre

- Le format des fichiers, les noms, l'ordre des clés et le contenu des snapshots : octet pour octet identiques.
- `FileHostedControlPlaneStore` et les stockages qui passent déjà par les primitives.
- Le lot PostgreSQL (`migrer-le-stockage-postgresql-vers-les-primitives`), qui consomme le même codec.
- Les verrous (`BoundedFileLease`) et la rétention : inchangés.
- Une lecture « sans écriture » du registre : `lire-le-registre-sans-ecrire`.

## Impact

- **Modules du reactor** : `minos-storage-local` seul. Dépend de `completer-les-primitives-d-e-s-privees` (flux) ; les trois sites `Properties` n'en dépendent pas.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune ; formats de fichier inchangés.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/quality/check-runtime-dynamic-consistency.py`, `python scripts/quality/check-semantic-retrieval-consistency.py`, `python scripts/remediation/check-mne.py`, `python scripts/remediation/check-post-mne.py`, `python scripts/remediation/check-mnd.py`, `python scripts/remediation/check-audit-remediation-v2.py`, `python scripts/quality/check-jacoco.py` (scopes `storage-codec-and-state`, `semantic-vector-store`, `persistence-cache-indexes`, `m26-runtime-dynamic-intelligence`, `global`), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows et Linux ; la publication exclusive par lien dur exige un système de fichiers qui les gère (NTFS, ext4, XFS).

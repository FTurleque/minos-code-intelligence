# Proposal

## Why

**AUD-SEC-03, lot « application et registre », partie `minos-integration-git`** (sprint 8 de l'audit du 10 octobre 2026). Une entrée de `scripts/architecture/private-io-allowlist.json` (`JGitRemoteRepositoryMaterializer.java`, `Files.move`, maximum 1, `:401`) nomme ce lot. Le matérialiseur de dépôts distants clone dans un répertoire temporaire privé du cache (`:177-178`) puis publie ce répertoire comme entrée de cache par `Files.move(ATOMIC_MOVE)` (`moveDirectory`, `:399-405`). C'est un **répertoire**, pas un fichier : `DurableAtomicFile.replace` ne s'y applique pas, la primitive de publication de répertoire vient du socle `completer-les-primitives-d-e-s-privees`.

## What Changes

- `moveDirectory` du matérialiseur appelle `DurableAtomicFile.moveDirectory` (rename atomique, reprise bornée sous Windows, échec fermé sans repli).
- Le message d'erreur actuel (« remote repository cache requires atomic directory publication ») est conservé comme message public de l'échec.
- Le gate littéral `check-remote-distributed-consistency.py:81` attend la chaîne `ATOMIC_MOVE` dans le fichier : elle le quitte, l'attente passe à `moveDirectory`.
- L'entrée est retirée.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : une exigence ajoutée (une entrée de cache de dépôt distant est publiée par un déplacement atomique de répertoire, sans repli, et ne remplace jamais une entrée existante).

## Hors périmètre

- Le clonage (`JGitRemoteGitClient`), les budgets, l'éviction, le verrou du cache (`BoundedFileLease`, `SharedCacheLeaseRegistry`) et le contrôle de propriétaire des dépôts analysés.
- `moveFile` du matérialiseur (`DurableAtomicFile.replace`, déjà une primitive).
- Les écritures de métadonnées (`writePrivateFile`, déjà une primitive).

## Impact

- **Modules du reactor** : `minos-integration-git` seul. Dépend du socle (`DurableAtomicFile.moveDirectory`).
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/quality/check-remote-distributed-consistency.py` (attente `ATOMIC_MOVE` modifiée dans la même tâche), `python scripts/remediation/check-post-mne.py`, `python scripts/quality/check-jacoco.py` (scope `m25-remote-distributed-indexing`), `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows (antivirus qui tient un handle sur un clone neuf) et Linux.

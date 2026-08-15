# Local persistence policy

MINOS classifies local writes by authority before choosing a publication primitive.

- **Authoritative/control-plane state** (active snapshots, project/index state, registry metadata, hosted tenant state, MCP backend configuration) must use `DurableAtomicFile`: force the temporary file, require an atomic same-filesystem rename, then sync the parent directory where the platform supports it. A lost post-commit acknowledgement is represented by `CommitUncertainException` and must be reconciled against the authoritative state before a caller reports failure or retries.
- **Rebuildable derived state** (semantic indexes and validated remote caches) must never silently replace an existing file through a non-atomic fallback. They may be discarded and rebuilt when publication cannot meet the atomicity requirement.
- **Ephemeral provider artifacts** may cross filesystems. MINOS copies them under the existing artifact byte budget into the target filesystem and only then performs durable atomic publication; source cleanup is not the commit point.
- **Packaged IntelliJ launcher copies** are not persistent authority. The plugin resource is authoritative and the installed launcher is reconstructed before each Windows launch. A stale or incomplete installed copy is therefore replaceable rather than recoverable state; it must never be treated as project/control-plane data.

Milestone structural gates must assert the selected persistence primitive and its recovery contract (for example `DurableAtomicFile.publish/replace`), rather than depending on a lower-level implementation token such as a direct `ATOMIC_MOVE` call in each store.

This policy deliberately separates crash-consistency guarantees from cache availability and prevents new file-backed stores from inventing weaker fallback semantics.
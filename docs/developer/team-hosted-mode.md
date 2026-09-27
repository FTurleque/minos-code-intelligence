# Team / Hosted Mode — architecture M27

M27 adds an opt-in tenant control plane without changing the local-first code-intelligence path.

## Boundaries

- `com.minos.hosted` in domain owns tenant, principal, role, workspace, exact-snapshot binding, audit and retention invariants.
- engine ports isolate identity verification, external key resolution, binding verification and control-plane persistence.
- `FileHostedControlPlaneStore` owns bounded binary encoding, AES-256-GCM authenticated encryption, atomic publication, locking, symlink rejection and optimistic version checks.
- `HmacHostedIdentityProvider` is a bounded reference adapter for `mht1` tokens, not a requirement that operators use HMAC as their external IdP.
- `HostedControlPlaneService` derives tenant/principal only from authenticated claims, enforces RBAC and records HMAC-chained audit decisions.
- CLI/API are administration surfaces; MCP is read-only and has no token arguments.

`ProjectDiscoveryService` and provider negotiation remain language/provider agnostic. Hosted metadata does not modify snapshots, capabilities, semantic scores or runtime observations.

## Persistence and cryptography

Each `<tenant-uuid>.mht` file contains a versioned header, tenant UUID, key id, random 96-bit nonce and AES-GCM ciphertext. The authenticated header is AAD. The decoder applies byte/count/string bounds before constructing a validated `HostedTenantState`.

`HostedTenantKeyProvider` resolves three 256-bit purpose keys. The environment reference adapter decodes `MINOS_TEAM_KEY_<KEY_ID>` and derives keys with HMAC-SHA-256 over tenant, key id and purpose. State and logs never contain the master key or bearer tokens.

Old key ids remain necessary to verify retained pre-rotation audit events. Key destruction is an operator action after an explicit retention plan/apply has removed those events.

## Concurrency, audit and retention

The store holds an inter-process file lock and requires the expected tenant version. A conflicting writer fails; M27 does not silently retry non-idempotent mutations.

Audit links are verified on every authenticated load. RBAC denials after successful token authentication are appended as `DENIED`; successful mutations append `ALLOWED`. Malformed/unauthenticated requests cannot safely name a tenant and are not persisted as tenant audit events.

Refusals are bounded so that they can never starve authorized mutations. A refusal is chained only while the refusals already held by the retained chain (`DENIED` events) stay below `HostedRetentionPolicy.deniedAuditCapacity()` (nine tenths of `maxAuditEvents`), the chain stays below `MAX_AUDIT_EVENTS - authorizedAuditHeadroom()` (one tenth of `maxAuditEvents` is left to authorized mutations below the hard capacity) and the per-principal refusal budget of the process is not exhausted. Authorized events never consume the refusal reserve: a tenant holding many legitimate events awaiting an explicit retention still chains the first refusal of an attack. The count is derived from the retained chain on each refusal, so an explicit retention releases the reserve without any persisted counter. A refusal that is not chained is still enforced, journaled as a WARNING and delivered to `HostedAuditSink.publishUnchained`.

An unchained refusal can never be mistaken for a chain link. `HostedAuditEvent.chaining()` is `UNCHAINED` (every event of the chain, persisted or exported, is `CHAINED`), its `sequence` is `0` (it has no chain position, so it never collides with the `(tenant, sequence)` of a chained event; it is identified by its `hash`), its `previousHash` only records the chain head observed at refusal time, and its HMAC is computed over the canonical input prefixed with the `minos-hosted-audit-unchained-refusal-v1` domain tag. The tenant state rejects any unchained event and the chain verification fails on an unchained refusal re-tagged as the next chain link. Chained events keep the canonical HMAC input of the persisted history: existing tenant files and exports still verify, the encrypted store format is unchanged, and the `team audit` / `minos_team_audit` JSON (chained events only) is unchanged.

Retention first returns a deterministic plan. Only `retention-apply` removes eligible archived workspaces or old audit events, updates the audit anchor and appends its own event.

## Qualification

`scripts/m27/check-hosted.py` validates the architecture and non-regression contracts. `run-hosted-e2e.py` exercises the shaded JAR across process restarts, two tenants, RBAC denial/audit, exact snapshot binding, encryption tamper rejection, rotation and explicit retention. `run-final.ps1` and `run-final.sh` qualify one clean exact HEAD on Windows and Linux without GitHub Actions.

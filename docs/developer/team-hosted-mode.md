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

Retention first returns a deterministic plan. Only `retention-apply` removes eligible archived workspaces or old audit events, updates the audit anchor and appends its own event.

## Qualification

`scripts/m27/check-hosted.py` validates the architecture and non-regression contracts. `run-hosted-e2e.py` exercises the shaded JAR across process restarts, two tenants, RBAC denial/audit, exact snapshot binding, encryption tamper rejection, rotation and explicit retention. `run-final.ps1` and `run-final.sh` qualify one clean exact HEAD on Windows and Linux without GitHub Actions.

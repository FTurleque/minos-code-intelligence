# ADR-0035 — Opt-in tenant control plane with external keys

- Status: **Accepted**
- Date: 2026-07-29
- Milestone: M27

## Context

MINOS is local-first, while a team needs shared workspace metadata, membership, authorization, retention and audit. Turning the existing process into an implicit network service would weaken local mode, blur tenant boundaries and force MINOS to own credentials or key custody.

## Decision

M27 adds an **opt-in embeddable control plane**, not a hosted SaaS deployment:

- `MINOS_HOSTED_MODE=enabled` composes the team service; absence keeps local mode unchanged;
- tenant identity is present in every state aggregate and is derived from the authenticated bearer token, never accepted as an operation parameter;
- the reference identity provider signs bounded `mht1` bearer tokens with HMAC-SHA-256; production integrators may replace the identity port;
- OWNER, ADMIN, CONTRIBUTOR, VIEWER and AUDITOR roles map to explicit permissions;
- one encrypted file per tenant is authenticated with AES-256-GCM and optimistic versioning;
- 256-bit master material is supplied externally (`MINOS_TEAM_KEY_<KEY_ID>`); purpose- and tenant-specific encryption, token and audit keys are derived in memory;
- audit events form an HMAC-SHA-256 chain, including allowed and RBAC-denied authenticated mutations;
- key rotation is explicit and old key material must remain available while retained historical audit events reference it;
- retention is plan/apply: no implicit deletion or eviction;
- shared project bindings preserve the exact active structured snapshot identity;
- CLI and Java API expose administration; MCP exposes five strictly read-only views and obtains its token from `MINOS_TEAM_TOKEN`, never tool arguments.

## Consequences

The reference implementation is portable on Windows and Linux and can be embedded behind another transport. It is not a claim that MINOS operates a public multi-tenant service, provisions an IdP/KMS, provides TLS, or supplies network isolation. Operators own process isolation, secret injection, backup and availability.

The static snapshot remains authoritative. Membership and workspace metadata neither promote provider capabilities nor change code-intelligence facts.

## Rejected alternatives

- trusting a caller-supplied tenant or principal id;
- storing bearer tokens or master keys in tenant state;
- accepting secrets in CLI/MCP arguments;
- unencrypted JSON control-plane state;
- automatic retention deletion;
- an M27-specific HTTP server or mandatory cloud dependency.

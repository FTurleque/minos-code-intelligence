---
id: ADR-0035
title: "Opt-in tenant control plane with external keys"
domain: security
rules: false
---

# ADR-0035 — Opt-in tenant control plane with external keys

- Status: **Accepted — final M27 dispositions recorded from exact-head Windows + Linux evidence.**
- Date: 2026-07-29
- Milestone: M27

Qualified HEAD: `d4bd51ef52cb329ab75b70b32bc22e2b236bd65d`
Merge develop: `ee22c3b39b9cd891c18cb61188eb8e973fc7e822`

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

## Do's and Don'ts

### Do

- N'activer le plan de contrôle d'équipe que par `MINOS_HOSTED_MODE=enabled` ; sans lui, garder le mode local inchangé.
- Dériver l'identité du tenant du jeton porteur authentifié.
- Fournir de l'extérieur la matière maîtresse de 256 bits (`MINOS_TEAM_KEY_<KEY_ID>`) et dériver en mémoire les clés par usage et par tenant.
- Chaîner les événements d'audit par HMAC-SHA-256, mutations refusées par le RBAC comprises.
- Appliquer la rétention en plan/apply, sans suppression ni éviction implicite.
- Faire lire au MCP son jeton dans `MINOS_TEAM_TOKEN`, jamais dans un argument d'outil.

### Don't

- Ne pas accepter un identifiant de tenant ou de principal fourni par l'appelant.
- Ne pas stocker de jeton porteur ni de clé maîtresse dans l'état du tenant.
- Ne pas accepter de secret en argument de CLI ou de MCP.
- Ne pas stocker l'état du plan de contrôle en JSON non chiffré.
- Ne pas présenter ce plan de contrôle comme un service multi-tenant opéré par MINOS.

## Consequences

The reference implementation is portable on Windows and Linux and can be embedded behind another transport. It is not a claim that MINOS operates a public multi-tenant service, provisions an IdP/KMS, provides TLS, or supplies network isolation. Operators own process isolation, secret injection, backup and availability.

The static snapshot remains authoritative. Membership and workspace metadata neither promote provider capabilities nor change code-intelligence facts.

## Compliance and Enforcement

- Gate `scripts/quality/check-hosted-control-plane-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — vérifie notamment le préfixe `MINOS_TEAM_KEY_`, le chiffrement AES/GCM sans sérialisation Java du magasin, la chaîne d'audit `HmacSHA256`, la rétention plan/apply, le jeton MCP lu dans `MINOS_TEAM_TOKEN` et l'absence de `bearerToken` et d'outils de mutation dans `MinosMcpTools`, ainsi que la ligne de statut de cet ADR.
- Revue : aucune nouvelle opération ne reçoit d'identifiant de tenant ni de secret en paramètre.

## Rejected alternatives

- trusting a caller-supplied tenant or principal id;
- storing bearer tokens or master keys in tenant state;
- accepting secrets in CLI/MCP arguments;
- unencrypted JSON control-plane state;
- automatic retention deletion;
- an M27-specific HTTP server or mandatory cloud dependency.

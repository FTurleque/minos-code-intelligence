# Team / Hosted Mode — M27

M27 provides a local, **opt-in** team control plane. It is suitable for a controlled process or for embedding behind an operator-owned transport. It is not an automatically operated cloud service.

## Enablement and keys

Local mode remains the default. To enable team mode, inject a distinct base64-encoded 32-byte master key for each key id:

```powershell
$env:MINOS_HOSTED_MODE='enabled'
$env:MINOS_TEAM_KEY_KEY_A='<base64-32-bytes>'
```

The suffix is the uppercase key id with non-alphanumeric characters replaced by `_`. MINOS derives separate tenant-scoped keys for AES-256-GCM encryption, HMAC token signing and the HMAC audit chain. Master keys and bearer tokens are never persisted.

Bootstrap returns a bearer token once:

```powershell
minos.cmd team bootstrap --tenant <uuid> --name 'My team' --key-id key-a `
  --owner alice --owner-name 'Alice' --request-id bootstrap-1
$env:MINOS_TEAM_TOKEN='<returned bearerToken>'
```

Do not put the token on the command line. `--token` and `--bearer-token` are rejected.

## Workspaces, members and exact snapshots

```powershell
minos.cmd team workspace-create --name Platform --request-id workspace-1
minos.cmd team members
minos.cmd team member-grant --principal bob --display-name Bob --role CONTRIBUTOR --request-id member-1
minos.cmd team project-bind --workspace <uuid> --project <uuid> `
  --snapshot <exact-active-snapshot-id> --request-id binding-1
```

The tenant comes only from the authenticated token. A binding is accepted only for the project’s exact active snapshot. M27 never changes the snapshot or its provider capabilities.

Roles are `OWNER`, `ADMIN`, `CONTRIBUTOR`, `VIEWER` and `AUDITOR`. The last owner cannot be removed or demoted.

## Audit, retention and rotation

```powershell
minos.cmd team audit --limit 200
minos.cmd team retention-plan
minos.cmd team retention-set --max-audit-events 10000 --audit-days 365 `
  --archived-workspace-days 90 --request-id retention-1
minos.cmd team retention-apply --request-id retention-apply-1
minos.cmd team key-rotate --key-id key-b --token-hours 1 --request-id rotate-1
```

Retention never deletes implicitly. Review the plan, then apply it explicitly. Key rotation returns a replacement token; old tokens fail closed. Retain old key material until every retained audit event signed with it has expired through an explicit retention apply.

## MCP

Five M27 tools are read-only: `minos_team_tenant`, `minos_team_workspaces`, `minos_team_workspace`, `minos_team_members` and `minos_team_audit`. They read `MINOS_TEAM_TOKEN` from the MCP process environment. No tool accepts credentials or mutations.

## Operator responsibilities

The operator supplies secret management, OS/process isolation, backups, transport authentication/TLS if a network transport is added, monitoring and availability. M27 qualifies the embedded control plane, not an operated SaaS.

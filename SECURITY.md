# Security Policy

## Scope

MINOS Code Intelligence is proprietary source-available software. Security reports are welcome for vulnerabilities affecting the repository, release artifacts, installers, MCP surface, remote-repository handling, provider execution/sandboxing, persistence backends, secret handling, or hosted/team control-plane features.

## Supported versions

Security fixes are developed against the current `develop` branch and released through the normal `develop` -> `main` promotion flow. Older snapshots are not guaranteed to receive backports unless explicitly stated in release notes.

## Reporting a vulnerability

Do not publish exploit details, credentials, private source code, or reproduction data in a public issue or discussion.

Prefer GitHub's private vulnerability reporting / Security Advisory flow for this repository when it is available. If that private channel is not available, open a public issue containing only a request for private security contact and no vulnerability details; the maintainer will establish a private channel before technical information is exchanged.

A useful report should include:

- affected commit or release;
- impacted component and platform;
- prerequisites and trust boundary involved;
- minimal reproduction steps;
- expected versus observed behavior;
- impact assessment;
- suggested remediation, when known.

## Handling expectations

Reports are triaged for reproducibility and severity before remediation. Confirmed vulnerabilities should receive regression coverage whenever practical, and fixes must pass the repository's required Linux, Windows, dependency-vulnerability, and static-analysis gates before promotion.

## Secrets and sensitive data

Never include real API keys, database passwords, access tokens, tenant keys, proprietary third-party source code, personal data, or production dumps in reports. Use synthetic values and the smallest possible reproduction fixture.

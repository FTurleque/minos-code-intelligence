#!/usr/bin/env python3
"""Verify a real Docker A -> B upgrade qualification exists for the exact candidate SHA.

The `Docker A-to-B Upgrade Qualification` workflow (.github/workflows/docker-upgrade-qualification.yml)
proves a real in-place Docker MCP upgrade on a self-hosted Windows/Docker Desktop runner, and uploads its
evidence as an artifact named `docker-upgrade-<candidate-sha>-<run-attempt>` regardless of whether the
qualification passed or failed (the upload step runs with `if: always()`). That evidence was never
consumed by anything: a develop -> main promotion could previously be declared qualified without any real
upgrade proof for the SHA actually being promoted, with proof left over from a different/older SHA, or
even with proof of a *failed* upgrade attempt for the right SHA.

This script requires the GitHub CLI (`gh`, authenticated) and confirms:
  1. a non-expired artifact named `docker-upgrade-<candidate-sha>-*` exists;
  2. the workflow run that produced it has that same candidate SHA as its head commit;
  3. that run's conclusion is "success" (not merely "completed" - a failed run also uploads evidence).
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-sha", required=True, help="Full candidate commit SHA being promoted")
    parser.add_argument("--repo", required=True, help="owner/name of the GitHub repository")
    return parser.parse_args()


def gh_jq_lines(path: str, jq_filter: str) -> list[dict]:
    result = subprocess.run(
        ["gh", "api", path, "--paginate", "--jq", jq_filter],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"gh api {path} failed: {result.stderr.strip()}")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def find_evidence(repo: str, candidate_sha: str) -> str | None:
    prefix = f"docker-upgrade-{candidate_sha}-"
    artifacts = gh_jq_lines(
        f"repos/{repo}/actions/artifacts?per_page=100",
        ".artifacts[] | {name, expired, workflow_run}",
    )
    matches = [
        a for a in artifacts
        if a.get("name", "").startswith(prefix)
        and not a.get("expired", True)
        and (a.get("workflow_run") or {}).get("head_sha") == candidate_sha
    ]
    if not matches:
        return None

    for artifact in matches:
        run_id = artifact["workflow_run"]["id"]
        runs = gh_jq_lines(f"repos/{repo}/actions/runs/{run_id}", ".conclusion")
        conclusion = runs[0] if runs else None
        if conclusion == "success":
            return artifact["name"]
    return None


def main() -> int:
    args = parse_args()
    if not SHA_RE.match(args.candidate_sha):
        print(f"ERROR: --candidate-sha must be a full 40-character hex SHA, got: {args.candidate_sha!r}", file=sys.stderr)
        return 1

    try:
        evidence_name = find_evidence(args.repo, args.candidate_sha)
    except RuntimeError as failure:
        print(f"ERROR: could not query Docker upgrade evidence: {failure}", file=sys.stderr)
        return 1

    if evidence_name is None:
        print(
            "ERROR: no successful Docker A -> B upgrade qualification evidence found for candidate "
            f"{args.candidate_sha}.\n"
            "Dispatch `.github/workflows/docker-upgrade-qualification.yml` for this exact SHA on the "
            "self-hosted `minos-docker` runner and let it complete successfully before promoting this "
            "candidate. A promotion must never be declared qualified using upgrade evidence from a "
            "different SHA, or evidence from a failed run.",
            file=sys.stderr,
        )
        return 1

    print(f"OK: Docker A -> B upgrade evidence found for {args.candidate_sha}: {evidence_name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

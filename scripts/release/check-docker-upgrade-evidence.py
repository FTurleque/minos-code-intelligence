#!/usr/bin/env python3
"""Verify a real Docker A -> B upgrade qualification exists for the exact candidate SHA.

The `Docker A-to-B Upgrade Qualification` job proves a real in-place Docker MCP upgrade on a
GitHub-hosted Linux runner, and uploads its evidence as an artifact named
`docker-upgrade-<candidate-sha>-<run-attempt>` regardless of whether the qualification passed or
failed (the upload step runs with `if: always()`). Artifact presence alone is not proof: this
script also downloads the artifact and validates its `qualification.json` manifest, so a promotion
can never be declared qualified using evidence for a different SHA, from a failed run, or from a
tampered/incomplete artifact whose manifest doesn't actually say PASS.

Confirms:
  1. a non-expired artifact named `docker-upgrade-<candidate-sha>-*` exists;
  2. the workflow run that produced it has that same candidate SHA as its head commit;
  3. the qualification job in that run concluded "success" (not merely "completed" - a failed job
     also uploads evidence, and the containing run may still be in progress - see
     qualification_job_conclusion());
  4. the artifact's qualification.json manifest itself says candidate == the expected SHA and
     result == "PASS" (defense in depth: don't trust the artifact name/run metadata alone);
  5. optionally, that the manifest's `previous` field matches an expected previous SHA (e.g. the
     promotion's base branch), so a promotion can't be satisfied by evidence proving an upgrade
     from the wrong baseline.

Requires the GitHub CLI (`gh`, authenticated).
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-sha", required=True, help="Full candidate commit SHA being promoted")
    parser.add_argument("--repo", required=True, help="owner/name of the GitHub repository")
    parser.add_argument(
        "--expected-previous-sha",
        default=None,
        help="If set, also require the manifest's 'previous' field to equal this SHA "
        "(e.g. the promotion's base branch, so evidence proving an upgrade from the wrong baseline is rejected)",
    )
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


def list_artifacts(repo: str) -> list[dict]:
    return gh_jq_lines(
        f"repos/{repo}/actions/artifacts?per_page=100",
        ".artifacts[] | {id, name, expired, workflow_run}",
    )


# release-promotion-gate.yml runs the qualification and the evidence check as two jobs of the SAME
# workflow run (by design - see that file's header comment). That means the artifact's
# workflow_run.id is this check's OWN run, whose overall conclusion cannot be "success" yet: this
# very job is still in progress and is part of that run. Checking the run's conclusion here is
# self-referential and always sees None. What actually matters - and is checked instead - is
# whether the specific job that performed the qualification succeeded, independent of whether
# anything else in the same run (like this check) has finished yet.
QUALIFICATION_JOB_NAMES = {
    "Docker A -> B upgrade qualification (GitHub-hosted)",  # release-promotion-gate.yml
    "Real Docker A -> B upgrade (GitHub-hosted)",  # docker-upgrade-qualification.yml (manual dispatch)
}


def qualification_job_conclusion(repo: str, run_id: int) -> str | None:
    jobs = gh_jq_lines(f"repos/{repo}/actions/runs/{run_id}/jobs", ".jobs[] | {name, conclusion}")
    for job in jobs:
        if job.get("name") in QUALIFICATION_JOB_NAMES:
            return job.get("conclusion")
    return None


def download_manifest(repo: str, artifact_id: int) -> dict | None:
    """Download the artifact zip and return its qualification.json, or None if absent/unreadable."""
    with tempfile.TemporaryDirectory() as tmp:
        zip_path = Path(tmp) / "evidence.zip"
        with zip_path.open("wb") as handle:
            result = subprocess.run(
                ["gh", "api", f"repos/{repo}/actions/artifacts/{artifact_id}/zip"],
                stdout=handle,
                stderr=subprocess.PIPE,
                check=False,
            )
        if result.returncode != 0:
            raise RuntimeError(f"failed to download artifact {artifact_id}: {result.stderr.decode().strip()}")
        try:
            with zipfile.ZipFile(zip_path) as archive:
                if "qualification.json" not in archive.namelist():
                    return None
                return json.loads(archive.read("qualification.json"))
        except zipfile.BadZipFile:
            return None


def select_evidence(artifacts: list[dict], candidate_sha: str) -> list[dict]:
    """Pure: artifacts whose name matches the candidate prefix, non-expired, run head_sha matches."""
    prefix = f"docker-upgrade-{candidate_sha}-"
    return [
        a
        for a in artifacts
        if a.get("name", "").startswith(prefix)
        and not a.get("expired", True)
        and (a.get("workflow_run") or {}).get("head_sha") == candidate_sha
    ]


def validate_manifest(manifest: dict | None, candidate_sha: str, expected_previous_sha: str | None) -> str | None:
    """Pure: returns an error message if the manifest is invalid, else None."""
    if manifest is None:
        return "artifact has no qualification.json manifest"
    if manifest.get("candidate") != candidate_sha:
        return f"manifest candidate {manifest.get('candidate')!r} does not match expected {candidate_sha!r}"
    if manifest.get("result") != "PASS":
        return f"manifest result is {manifest.get('result')!r}, not 'PASS'"
    if expected_previous_sha is not None and manifest.get("previous") != expected_previous_sha:
        return (
            f"manifest previous {manifest.get('previous')!r} does not match expected "
            f"baseline {expected_previous_sha!r}"
        )
    return None


def find_evidence(repo: str, candidate_sha: str, expected_previous_sha: str | None) -> tuple[str | None, str | None]:
    """Returns (evidence_artifact_name, error_message). Exactly one is non-None."""
    matches = select_evidence(list_artifacts(repo), candidate_sha)
    if not matches:
        return None, "no non-expired artifact found matching this candidate SHA"

    last_error = "no matching artifact came from a successful qualification job"
    for artifact in matches:
        run_id = artifact["workflow_run"]["id"]
        conclusion = qualification_job_conclusion(repo, run_id)
        if conclusion != "success":
            last_error = f"artifact {artifact['name']!r} qualification job concluded {conclusion!r}, not 'success'"
            continue
        manifest = download_manifest(repo, artifact["id"])
        manifest_error = validate_manifest(manifest, candidate_sha, expected_previous_sha)
        if manifest_error is not None:
            last_error = f"artifact {artifact['name']!r}: {manifest_error}"
            continue
        return artifact["name"], None
    return None, last_error


def main() -> int:
    args = parse_args()
    if not SHA_RE.match(args.candidate_sha):
        print(f"ERROR: --candidate-sha must be a full 40-character hex SHA, got: {args.candidate_sha!r}", file=sys.stderr)
        return 1
    if args.expected_previous_sha is not None and not SHA_RE.match(args.expected_previous_sha):
        print(
            f"ERROR: --expected-previous-sha must be a full 40-character hex SHA, got: {args.expected_previous_sha!r}",
            file=sys.stderr,
        )
        return 1

    try:
        evidence_name, error = find_evidence(args.repo, args.candidate_sha, args.expected_previous_sha)
    except RuntimeError as failure:
        print(f"ERROR: could not query Docker upgrade evidence: {failure}", file=sys.stderr)
        return 1

    if evidence_name is None:
        print(
            "ERROR: no successful, valid Docker A -> B upgrade qualification evidence found for candidate "
            f"{args.candidate_sha}: {error}\n"
            "Dispatch the Docker A -> B upgrade qualification for this exact SHA and let it complete "
            "successfully before promoting this candidate. A promotion must never be declared qualified "
            "using upgrade evidence from a different SHA, from a failed run, or from a manifest that "
            "doesn't itself confirm PASS for this exact candidate.",
            file=sys.stderr,
        )
        return 1

    print(f"OK: Docker A -> B upgrade evidence found for {args.candidate_sha}: {evidence_name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Real GitHub revision -> worker -> verified SCIP -> active snapshot proof for M25."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "target" / "minos-code-intelligence-0.2.0-SNAPSHOT-all.jar"
DEFAULT_REPOSITORY = "https://github.com/FTurleque/minos-code-intelligence"
DEFAULT_REFERENCE = "refs/heads/m25-remote-distributed-indexing"
SHA1 = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")


def run(command: list[str], env: dict[str, str], json_output: bool = False) -> object | str:
    printable = list(command)
    if "--credential-env" in printable:
        credential_index = printable.index("--credential-env") + 1
        if credential_index < len(printable):
            printable[credential_index] = "<redacted-env-reference>"
    print("+ " + " ".join(printable), flush=True)
    completed = subprocess.run(
        command,
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=1800,
        check=False,
    )
    stdout = completed.stdout or ""
    stderr = completed.stderr or ""
    if stdout:
        print(stdout, end="" if stdout.endswith("\n") else "\n", flush=True)
    if stderr:
        print(stderr, end="" if stderr.endswith("\n") else "\n", file=sys.stderr, flush=True)
    if completed.returncode != 0:
        detail = stderr.strip() or stdout.strip() or "<no process output>"
        raise RuntimeError(f"command failed with exit {completed.returncode}: {' '.join(command)}\n{detail}")
    if json_output:
        try:
            return json.loads(stdout)
        except json.JSONDecodeError as exception:
            raise RuntimeError(f"invalid JSON from {' '.join(command)}: {exception}") from exception
    return stdout


def cli(env: dict[str, str], *arguments: str) -> dict[str, object]:
    value = run(["java", "-jar", str(JAR), *arguments], env, json_output=True)
    if not isinstance(value, dict):
        raise RuntimeError(f"CLI returned a non-object for: {' '.join(arguments)}")
    return value


def require(value: object, expected: object, label: str) -> None:
    if value != expected:
        raise RuntimeError(f"{label}: expected={expected!r} actual={value!r}")


def github_credential_environment(env: dict[str, str], repository: str) -> str | None:
    if "github.com" not in repository.lower():
        return None
    name = "MINOS_M25_E2E_GITHUB_TOKEN"
    if env.get(name):
        return name
    completed = subprocess.run(
        ["gh", "auth", "token"],
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
        check=False,
    )
    token = (completed.stdout or "").strip()
    if completed.returncode != 0 or not token:
        raise RuntimeError("the private GitHub e2e requires an authenticated gh session or injected credential")
    env[name] = token
    return name


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--repository", default=DEFAULT_REPOSITORY)
    parser.add_argument("--reference", default=DEFAULT_REFERENCE)
    parser.add_argument("--output", required=True)
    arguments = parser.parse_args()

    try:
        expected_head = arguments.expected_head.lower()
        if not SHA1.fullmatch(expected_head):
            raise RuntimeError("--expected-head must be a lowercase complete Git SHA-1")
        local_head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        require(local_head, expected_head, "local exact HEAD")
        if not JAR.is_file():
            raise RuntimeError(f"shaded CLI jar is missing: {JAR}")

        with tempfile.TemporaryDirectory(prefix="minos-m25-remote-e2e-") as raw_home:
            home = Path(raw_home)
            env = os.environ.copy()
            env["MINOS_HOME"] = str(home)
            env["MINOS_SEMANTIC_PROVIDER"] = "disabled"
            for name in (
                "MINOS_SEMANTIC_MODEL", "MINOS_SEMANTIC_DIMENSIONS",
                "MINOS_SEMANTIC_ENDPOINT", "MINOS_SEMANTIC_TIMEOUT_SECONDS",
            ):
                env.pop(name, None)
            credential_environment = github_credential_environment(env, arguments.repository)

            provider = cli(env, "providers", "scip-go", "--format", "json")
            if provider.get("runtimeState") != "READY":
                cli(env, "tools", "install", "scip-go", "--format", "json")
                provider = cli(env, "providers", "scip-go", "--format", "json")
            require(provider.get("version"), "0.2.7", "scip-go version")
            require(provider.get("runtimeState"), "READY", "scip-go runtime readiness")

            common_values = [
                arguments.repository,
                "--ref", arguments.reference,
                "--commit", expected_head,
                "--subdir", "fixtures/m24/go",
            ]
            if credential_environment is not None:
                common_values.extend(("--credential-env", credential_environment))
            common_values.extend(("--format", "json"))
            common = tuple(common_values)
            first = cli(env, "remote", "materialize", *common)
            second = cli(env, "remote", "materialize", *common)
            require(first.get("cacheHit"), False, "first remote materialization cache state")
            require(second.get("cacheHit"), True, "second remote materialization cache state")
            require(first.get("commit"), expected_head, "first materialized commit")
            require(second.get("commit"), expected_head, "cached materialized commit")
            require(first.get("cacheKey"), second.get("cacheKey"), "remote cache key stability")

            indexed = cli(
                env,
                "remote", "index", *common[:-2],
                "--name", "m25-remote-go",
                "--provider", "scip-go",
                "--worker", "local-native",
                "--worker-network", "allow",
                "--format", "json",
            )
            materialization = indexed.get("materialization")
            execution = indexed.get("execution")
            artifacts = indexed.get("artifacts")
            if not isinstance(materialization, dict) or not isinstance(execution, dict):
                raise RuntimeError("remote index result is missing materialization/execution evidence")
            if not isinstance(artifacts, list) or len(artifacts) != 1 or not isinstance(artifacts[0], dict):
                raise RuntimeError("remote index result must contain one scip-go artifact evidence")
            artifact = artifacts[0]
            require(materialization.get("commit"), expected_head, "indexed source commit")
            require(materialization.get("cacheHit"), True, "remote index source cache state")
            require(execution.get("status"), "SUCCEEDED", "remote index status")
            active_snapshot = execution.get("activeSnapshotId")
            if not isinstance(active_snapshot, str) or not active_snapshot:
                raise RuntimeError("remote index did not promote an active snapshot")
            require(artifact.get("providerId"), "scip-go", "artifact provider")
            require(artifact.get("providerVersion"), "0.2.7", "artifact provider version")
            require(artifact.get("language"), "GO", "artifact language")
            require(artifact.get("isolation"), "PROCESS_EPHEMERAL_WORKSPACE", "worker isolation")
            require(artifact.get("networkPolicy"), "ALLOW", "worker network policy")
            require(artifact.get("networkDenyEnforced"), False, "worker network deny claim")
            for key in ("artifactSha256", "bundleSha256", "cacheKey"):
                if not SHA256.fullmatch(str(artifact.get(key, ""))):
                    raise RuntimeError(f"artifact evidence {key} is not a lowercase SHA-256")

            symbols = cli(
                env, "find-symbol", "m25-remote-go", "Greeter", "--limit", "20", "--format", "json")
            values = symbols.get("symbols")
            if not isinstance(values, list):
                raise RuntimeError("find-symbol did not return a symbol list")
            matching = [value for value in values if isinstance(value, dict)
                        and isinstance(value.get("origin"), dict)
                        and value["origin"].get("providerId") == "scip-go"]
            if not matching:
                raise RuntimeError("remote snapshot contains no Greeter symbol with scip-go provenance")
            require(matching[0]["origin"].get("providerVersion"), "0.2.7", "snapshot provider version")

            evidence = {
                "status": "PASS",
                "platform": f"{platform.system()}-{platform.machine()}",
                "repository": materialization.get("repository"),
                "reference": materialization.get("reference"),
                "commit": expected_head,
                "sourceCache": {"first": "MISS", "second": "HIT", "index": "HIT"},
                "provider": {"id": "scip-go", "version": "0.2.7", "runtime": "READY"},
                "worker": {
                    "id": artifact.get("workerId"),
                    "isolation": artifact.get("isolation"),
                    "networkPolicy": artifact.get("networkPolicy"),
                    "networkDenyEnforced": artifact.get("networkDenyEnforced"),
                },
                "artifact": {
                    "artifactSha256": artifact.get("artifactSha256"),
                    "bundleSha256": artifact.get("bundleSha256"),
                    "verifiedCacheKey": artifact.get("cacheKey"),
                },
                "activeSnapshotId": active_snapshot,
                "symbol": {"name": matching[0].get("name"), "id": matching[0].get("id")},
            }
            output = ROOT / arguments.output
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            print("M25 REMOTE E2E EVIDENCE: " + json.dumps(evidence, sort_keys=True))
            print("M25 REMOTE INDEXING END-TO-END SUCCESS")
            return 0
    except Exception as exception:
        print(f"M25 REMOTE INDEXING END-TO-END FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

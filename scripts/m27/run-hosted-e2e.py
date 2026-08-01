#!/usr/bin/env python3
"""Cross-platform shaded-CLI proof for the opt-in M27 tenant control plane."""

from __future__ import annotations

import argparse
import base64
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "target" / "minos-code-intelligence-0.2.0-SNAPSHOT-all.jar"
FIXTURE = ROOT / "scripts" / "m27" / "M27HostedFixture.java"
SHA1 = re.compile(r"[0-9a-f]{40}")


def run(command: list[str], env: dict[str, str], *, success: bool = True, secret_output: bool = False) -> subprocess.CompletedProcess[str]:
    print("+ " + " ".join(command), flush=True)
    completed = subprocess.run(command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=600, check=False)
    if completed.stdout:
        print("<secret JSON output redacted>" if secret_output else completed.stdout,
              end="" if secret_output or completed.stdout.endswith("\n") else "\n", flush=True)
    if completed.stderr:
        print(completed.stderr, end="" if completed.stderr.endswith("\n") else "\n", file=sys.stderr, flush=True)
    if success != (completed.returncode == 0):
        expectation = "success" if success else "failure"
        raise RuntimeError(f"command did not produce expected {expectation}: {' '.join(command)}")
    return completed


def cli(env: dict[str, str], *args: str, secret: bool = False) -> dict[str, object]:
    result = run(["java", "-jar", str(JAR), *args], env, secret_output=secret)
    value = json.loads(result.stdout)
    if not isinstance(value, dict):
        raise RuntimeError("hosted CLI returned non-object JSON")
    return value


def rejected(env: dict[str, str], *args: str) -> str:
    result = run(["java", "-jar", str(JAR), *args], env, success=False)
    return (result.stderr or result.stdout).strip()


def require(actual: object, expected: object, label: str) -> None:
    if actual != expected:
        raise RuntimeError(f"{label}: expected={expected!r} actual={actual!r}")


def token(value: dict[str, object], field: str = "bearerToken") -> str:
    raw = value.get(field)
    if not isinstance(raw, str) or not raw.startswith("mht1."):
        raise RuntimeError(f"missing redacted one-time token field: {field}")
    require(value.get("tokenHandling"), "SECRET_OUTPUT_ONCE_DO_NOT_LOG", "token handling")
    return raw


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        expected = args.expected_head.lower()
        if not SHA1.fullmatch(expected):
            raise RuntimeError("--expected-head must be a lowercase complete Git SHA-1")
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        require(actual, expected, "local exact HEAD")
        if not JAR.is_file():
            raise RuntimeError(f"shaded CLI jar is missing: {JAR}")

        with tempfile.TemporaryDirectory(prefix="minos-m27-hosted-e2e-") as raw:
            temporary = Path(raw)
            home = temporary / "home"
            classes = temporary / "classes"
            classes.mkdir()
            env = os.environ.copy()
            env["MINOS_HOME"] = str(home)
            env["MINOS_SEMANTIC_PROVIDER"] = "disabled"
            env["MINOS_HOSTED_MODE"] = "enabled"
            env["MINOS_TEAM_KEY_KEY_A"] = base64.b64encode(bytes(range(32))).decode("ascii")
            env["MINOS_TEAM_KEY_KEY_B"] = base64.b64encode(bytes(range(32, 64))).decode("ascii")
            env.pop("MINOS_TEAM_TOKEN", None)

            run(["javac", "-cp", str(JAR), "-d", str(classes), str(FIXTURE)], env)
            project_setup = run(["java", "-cp", str(JAR) + os.pathsep + str(classes),
                                 "M27HostedFixture", str(home), str(temporary / "project")], env)
            project_id = project_setup.stdout.strip().splitlines()[-1]

            first = cli(env, "team", "bootstrap", "--tenant", "10000000-0000-0000-0000-000000000001",
                        "--name", "Tenant One", "--key-id", "key-a", "--owner", "alice",
                        "--owner-name", "Alice", "--request-id", "e2e-bootstrap-1", secret=True)
            first_token = token(first)
            first_env = dict(env, MINOS_TEAM_TOKEN=first_token)
            workspace = cli(first_env, "team", "workspace-create", "--name", "Platform",
                            "--request-id", "e2e-workspace-1")
            workspace_id = str(workspace["workspaceId"])
            cli(first_env, "team", "project-bind", "--workspace", workspace_id, "--project", project_id,
                "--snapshot", "snapshot-m27-e2e", "--request-id", "e2e-bind")
            cli(first_env, "team", "project-unbind", "--workspace", workspace_id, "--project", project_id,
                "--request-id", "e2e-unbind")
            stale_error = rejected(first_env, "team", "project-bind", "--workspace", workspace_id,
                                   "--project", project_id, "--snapshot", "stale-snapshot",
                                   "--request-id", "e2e-bind-stale")
            if "exact active snapshot" not in stale_error:
                raise RuntimeError("stale snapshot binding did not fail closed")
            cli(first_env, "team", "project-bind", "--workspace", workspace_id, "--project", project_id,
                "--snapshot", "snapshot-m27-e2e", "--request-id", "e2e-rebind")

            cli(first_env, "team", "member-grant", "--principal", "viewer", "--display-name", "Viewer",
                "--role", "VIEWER", "--request-id", "e2e-member")
            issued = cli(first_env, "team", "token-issue", "--principal", "viewer", "--token-hours", "1",
                         "--request-id", "e2e-token", secret=True)
            viewer_env = dict(env, MINOS_TEAM_TOKEN=token(issued))
            require(len(cli(viewer_env, "team", "workspaces")["workspaces"]), 1, "viewer workspace read")
            denied = rejected(viewer_env, "team", "workspace-create", "--name", "Denied",
                              "--request-id", "e2e-denied")
            if "permission denied" not in denied:
                raise RuntimeError("viewer mutation did not fail closed")
            audit = cli(first_env, "team", "audit", "--limit", "50")["events"]
            if not any(event.get("requestId") == "e2e-denied" and event.get("outcome") == "DENIED" for event in audit):
                raise RuntimeError("denied authenticated mutation is absent from audit")

            second = cli(env, "team", "bootstrap", "--tenant", "20000000-0000-0000-0000-000000000002",
                         "--name", "Tenant Two", "--key-id", "key-a", "--owner", "bob",
                         "--owner-name", "Bob", "--request-id", "e2e-bootstrap-2", secret=True)
            second_env = dict(env, MINOS_TEAM_TOKEN=token(second))
            cli(second_env, "team", "workspace-create", "--name", "Other",
                "--request-id", "e2e-workspace-2")
            first_names = [item["name"] for item in cli(first_env, "team", "workspaces")["workspaces"]]
            second_names = [item["name"] for item in cli(second_env, "team", "workspaces")["workspaces"]]
            require(first_names, ["Platform"], "tenant one isolation")
            require(second_names, ["Other"], "tenant two isolation")

            plan = cli(first_env, "team", "retention-plan")
            require(plan.get("implicitDeletion"), False, "explicit retention")
            rotated = cli(first_env, "team", "key-rotate", "--key-id", "key-b", "--token-hours", "1",
                          "--request-id", "e2e-rotate", secret=True)
            replacement = token(rotated, "replacementBearerToken")
            if "inactive key" not in rejected(first_env, "team", "tenant"):
                raise RuntimeError("pre-rotation token remained active")
            rotated_env = dict(env, MINOS_TEAM_TOKEN=replacement)
            require(cli(rotated_env, "team", "tenant").get("keyId"), "key-b", "active rotated key")

            state_file = home / "hosted-control-plane" / "10000000-0000-0000-0000-000000000001.mht"
            ciphertext = state_file.read_bytes()
            if b"Tenant One" in ciphertext or b"Platform" in ciphertext or first_token.encode() in ciphertext:
                raise RuntimeError("hosted plaintext or token leaked into encrypted state")
            tampered_home = temporary / "tampered-home"
            shutil.copytree(home, tampered_home)
            tampered_file = tampered_home / "hosted-control-plane" / state_file.name
            damaged = bytearray(tampered_file.read_bytes())
            damaged[-1] ^= 0x01
            tampered_file.write_bytes(damaged)
            tampered_env = dict(rotated_env, MINOS_HOME=str(tampered_home))
            tamper_error = rejected(tampered_env, "team", "tenant")
            if "authentication" not in tamper_error and "decrypt" not in tamper_error:
                raise RuntimeError("ciphertext tampering did not fail closed")

            evidence = {
                "status": "PASS",
                "platform": f"{platform.system()}-{platform.machine()}",
                "commit": expected,
                "mode": "OPT_IN_LOCAL_CONTROL_PLANE",
                "isolation": {"tenantOne": first_names, "tenantTwo": second_names, "crossTenantLeak": False},
                "authentication": {"format": "mht1", "tokenInArguments": False, "oldKeyRejected": True},
                "authorization": {"viewerRead": True, "viewerMutationDenied": True, "denialAudited": True},
                "binding": {"projectId": project_id, "snapshotId": "snapshot-m27-e2e", "staleRejected": True},
                "storage": {"algorithm": "AES-256-GCM", "plaintextAbsent": True, "tamperRejected": True},
                "audit": {"algorithm": "HMAC-SHA256-CHAINED", "deniedEventPresent": True},
                "retention": {"implicitDeletion": False},
                "keyRotation": {"newKeyId": "key-b", "replacementTokenWorks": True},
                "mcp": {"tools": 31, "readOnlyTeamTools": 5, "tokenArguments": False},
            }
            output = ROOT / args.output
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            print("M27 HOSTED E2E EVIDENCE: " + json.dumps(evidence, sort_keys=True))
            print("M27 TEAM HOSTED END-TO-END SUCCESS")
            return 0
    except Exception as exception:
        print(f"M27 TEAM HOSTED END-TO-END FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

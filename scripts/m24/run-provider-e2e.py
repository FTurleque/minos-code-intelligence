#!/usr/bin/env python3
"""Cross-platform real-provider evaluator for M24.

The evaluator never promotes a provider. It exercises every provider that can be
made READY on the current host, and requires e2e success when the provider's
operational profile already claims the current qualification platform.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "target" / "minos-code-intelligence-0.2.0-SNAPSHOT-all.jar"


@dataclass(frozen=True)
class ProviderCase:
    key: str
    provider_id: str
    version: str
    fixture: str
    symbol: str
    managed_install: bool
    prepare: str | None = None


CASES = (
    ProviderCase("clang", "scip-clang", "0.4.0", "fixtures/m24/clang", "m24_greeting", False, "cmake"),
    ProviderCase("csharp", "scip-dotnet", "0.2.14", "fixtures/m24/csharp", "IGreeter", True),
    ProviderCase("go", "scip-go", "0.2.7", "fixtures/m24/go", "Greeter", True),
    ProviderCase("rust", "rust-analyzer-scip", "0.3.2989", "fixtures/m24/rust", "Greeter", False),
)


def qualification_platform() -> str:
    system = platform.system().lower()
    machine = platform.machine().lower()
    if system == "windows" and machine in {"amd64", "x86_64"}:
        return "WINDOWS_X64"
    if system == "linux" and machine in {"amd64", "x86_64"}:
        return "LINUX_X64"
    if system == "darwin" and machine in {"arm64", "aarch64"}:
        return "MACOS_ARM64"
    return f"UNQUALIFIED_{system.upper()}_{machine.upper()}"


def env_for(home: Path) -> dict[str, str]:
    env = os.environ.copy()
    env["MINOS_HOME"] = str(home)
    env["MINOS_SEMANTIC_PROVIDER"] = "disabled"
    for name in (
        "MINOS_SEMANTIC_MODEL",
        "MINOS_SEMANTIC_DIMENSIONS",
        "MINOS_SEMANTIC_ENDPOINT",
        "MINOS_SEMANTIC_TIMEOUT_SECONDS",
    ):
        env.pop(name, None)
    return env


def run(command: list[str], env: dict[str, str], cwd: Path = ROOT, json_output: bool = False) -> object | str:
    print("+ " + " ".join(command), flush=True)
    completed = subprocess.run(
        command,
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=1800,
        check=False,
    )
    output = completed.stdout or ""
    print(output, end="" if output.endswith("\n") else "\n", flush=True)
    if completed.returncode != 0:
        raise RuntimeError(f"command failed with exit {completed.returncode}: {' '.join(command)}")
    if json_output:
        try:
            return json.loads(output)
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"invalid JSON from {' '.join(command)}: {exc}") from exc
    return output


def cli(env: dict[str, str], *args: str, json_output: bool = False) -> object | str:
    return run(["java", "-jar", str(JAR), *args], env, json_output=json_output)


def provider_view(env: dict[str, str], provider_id: str) -> dict[str, object]:
    value = cli(env, "providers", provider_id, "--format", "json", json_output=True)
    if not isinstance(value, dict):
        raise RuntimeError(f"provider view is not an object: {provider_id}")
    return value


def prepare_fixture(case: ProviderCase, project: Path, env: dict[str, str]) -> None:
    if case.prepare != "cmake":
        return
    cmake = shutil.which("cmake")
    if cmake is None:
        raise RuntimeError("cmake is required to prepare the scip-clang compilation database")
    run([cmake, "-S", str(project), "-B", str(project / "build"), "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"], env, cwd=project)
    compdb = project / "build" / "compile_commands.json"
    if not compdb.is_file():
        raise RuntimeError(f"CMake did not produce {compdb}")


def symbol_snapshot(env: dict[str, str], project_name: str, symbol_name: str, provider_id: str, provider_version: str) -> dict[str, object]:
    result = cli(env, "find-symbol", project_name, symbol_name, "--limit", "20", "--format", "json", json_output=True)
    if not isinstance(result, dict) or not isinstance(result.get("symbols"), list) or not result["symbols"]:
        raise RuntimeError(f"find-symbol returned no symbols for {provider_id}:{symbol_name}")
    symbols = [value for value in result["symbols"] if isinstance(value, dict)]
    matching = [value for value in symbols if isinstance(value.get("origin"), dict)
                and value["origin"].get("providerId") == provider_id]
    if not matching:
        raise RuntimeError(f"find-symbol returned no symbol with provenance providerId={provider_id}")
    symbol = matching[0]
    origin = symbol["origin"]
    if origin.get("providerVersion") != provider_version:
        raise RuntimeError(
            f"provider provenance version mismatch for {provider_id}: expected={provider_version} actual={origin.get('providerVersion')}")
    if symbol.get("identityQuality") not in {"STRUCTURAL_FALLBACK", "PROVIDER_SCOPED_FALLBACK", "CANONICAL"}:
        raise RuntimeError(f"unexpected identity quality for {provider_id}: {symbol.get('identityQuality')}")
    return symbol


def evaluate_case(case: ProviderCase, platform_id: str, base_home: Path) -> dict[str, object]:
    home = base_home / case.key
    home.mkdir(parents=True, exist_ok=True)
    env = env_for(home)
    view = provider_view(env, case.provider_id)
    claimed = platform_id in list(view.get("qualificationPlatforms") or [])
    state = str(view.get("runtimeState"))

    if state != "READY" and case.managed_install:
        try:
            cli(env, "tools", "install", case.provider_id, "--format", "json")
        except Exception as exc:
            if claimed:
                raise
            print(f"M24 EVALUATION NOTICE: {case.provider_id} managed install unavailable: {exc}", flush=True)
        view = provider_view(env, case.provider_id)
        state = str(view.get("runtimeState"))

    if state != "READY":
        message = {
            "provider": case.provider_id,
            "version": case.version,
            "platform": platform_id,
            "state": state,
            "claimedPlatform": claimed,
            "e2e": "NOT_RUN",
            "diagnostics": view.get("runtimeDiagnostics", []),
        }
        print("M24 PROVIDER EVALUATION: " + json.dumps(message, sort_keys=True), flush=True)
        if claimed:
            raise RuntimeError(f"{case.provider_id} claims {platform_id} but runtime is {state}")
        return message

    with tempfile.TemporaryDirectory(prefix=f"minos-m24-{case.key}-") as raw_temp:
        temp = Path(raw_temp)
        source = ROOT / case.fixture
        project = temp / "project"
        shutil.copytree(source, project)
        try:
            prepare_fixture(case, project, env)
        except Exception as exc:
            if claimed:
                raise
            message = {
                "provider": case.provider_id,
                "version": case.version,
                "platform": platform_id,
                "state": state,
                "claimedPlatform": claimed,
                "e2e": "NOT_RUN",
                "diagnostics": [str(exc)],
            }
            print("M24 PROVIDER EVALUATION: " + json.dumps(message, sort_keys=True), flush=True)
            return message

        project_name = f"m24-{case.key}"
        cli(env, "project", "add", str(project), "--name", project_name, "--format", "json")
        inspected = cli(env, "project", "inspect", project_name, "--format", "json", json_output=True)
        if not isinstance(inspected, dict):
            raise RuntimeError(f"project inspect returned invalid JSON for {case.provider_id}")

        first_index = cli(env, "index", project_name, "--provider", case.provider_id, "--force-full", "--format", "json", json_output=True)
        if not isinstance(first_index, dict):
            raise RuntimeError(f"first index returned invalid JSON for {case.provider_id}")
        first = symbol_snapshot(env, project_name, case.symbol, case.provider_id, case.version)
        symbol_id = str(first.get("id"))
        symbol_key = str(first.get("symbolKey"))
        run_one = str(first["origin"].get("indexRunId"))
        if not symbol_id or symbol_id == "None" or not symbol_key or symbol_key == "None":
            raise RuntimeError(f"missing stable identity fields for {case.provider_id}")

        usages = cli(env, "find-usages", project_name, symbol_id, "--limit", "100", "--format", "json", json_output=True)
        if not isinstance(usages, dict):
            raise RuntimeError(f"find-usages returned invalid JSON for {case.provider_id}")

        second_index = cli(env, "index", project_name, "--provider", case.provider_id, "--force-full", "--format", "json", json_output=True)
        if not isinstance(second_index, dict):
            raise RuntimeError(f"second index returned invalid JSON for {case.provider_id}")
        second = symbol_snapshot(env, project_name, case.symbol, case.provider_id, case.version)
        run_two = str(second["origin"].get("indexRunId"))
        if second.get("id") != symbol_id or second.get("symbolKey") != symbol_key:
            raise RuntimeError(f"stable identity changed across repeated indexation for {case.provider_id}")
        if run_one == run_two:
            raise RuntimeError(f"provenance indexRunId did not change across repeated indexation for {case.provider_id}")

        relation_counts: dict[str, object] = {}
        for command in ("find-implementations", "dependencies", "dependents"):
            try:
                value = cli(env, command, project_name, symbol_id, "--format", "json", json_output=True)
                relation_counts[command] = value.get("count") if isinstance(value, dict) else None
            except Exception as exc:
                relation_counts[command] = f"UNAVAILABLE: {exc}"

        message = {
            "provider": case.provider_id,
            "version": case.version,
            "platform": platform_id,
            "state": state,
            "claimedPlatform": claimed,
            "e2e": "PASS",
            "symbolId": symbol_id,
            "symbolKey": symbol_key,
            "firstIndexRun": run_one,
            "secondIndexRun": run_two,
            "usageCount": usages.get("count"),
            "relations": relation_counts,
        }
        print("M24 PROVIDER EVALUATION: " + json.dumps(message, sort_keys=True), flush=True)
        return message


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="target/m24/provider-evaluation.json")
    args = parser.parse_args()
    try:
        if not JAR.is_file():
            raise RuntimeError(f"fat JAR not found: {JAR}; run Maven verify first")
        platform_id = qualification_platform()
        with tempfile.TemporaryDirectory(prefix="minos-m24-home-") as raw_home:
            base_home = Path(raw_home)
            results = [evaluate_case(case, platform_id, base_home) for case in CASES]
        output = ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        payload = {"platform": platform_id, "providers": results}
        output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print("M24 PROVIDER END-TO-END EVALUATION SUCCESS")
        print(f"Platform: {platform_id}")
        print(f"Evidence: {output}")
        return 0
    except Exception as exc:
        print(f"M24 PROVIDER END-TO-END EVALUATION FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

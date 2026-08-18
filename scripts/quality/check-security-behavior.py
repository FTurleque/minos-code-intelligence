#!/usr/bin/env python3
"""Require executed behavioral evidence for MINOS security-critical runtime invariants."""

from __future__ import annotations

import argparse
import platform
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


REQUIRED = {
    "linux": {
        "com.minos.runtime.LinuxBubblewrapWorkerSandboxBackendTest": {
            "realLinuxSandboxBlocksHostWriteAndNetworkAndAppliesRlimits",
            "qualifiedBackendLaunchesRealProcessIndexerExecutor",
        },
        "com.minos.storage.postgresql.PostgresLoopbackHostPolicyTest": {
            "dnsNamesStartingWith127RemainExternal",
            "onlyCanonicalIpv4LoopbackLiteralsBypassExternalTlsRequirement",
        },
        "com.minos.storage.postgresql.PostgresProjectRegistryMutationSerializationTest": {
            "workspaceMutationAndDeleteWaitForTheSameProjectAdvisoryLock",
        },
    },
    "windows": {
        "com.minos.runtime.WindowsAppContainerWorkerSandboxBackendTest": {
            "realWindowsSandboxUsesAppContainerJobLimitsAndBlocksNetworkAndHostWrite",
            "qualifiedBackendLaunchesRealProcessIndexerExecutor",
        },
        "com.minos.runtime.WindowsSandboxSensitivePlanTest": {
            "sensitiveTransportPlanIsErasedWhileProviderStillRuns",
        },
    },
}


def current_platform() -> str:
    system = platform.system().lower()
    if system.startswith("win"):
        return "windows"
    if system == "linux":
        return "linux"
    return "other"


def executed_testcases(root: Path) -> dict[tuple[str, str], str]:
    results: dict[tuple[str, str], str] = {}
    reports = sorted(root.glob("**/target/surefire-reports/TEST-*.xml"))
    if not reports:
        raise RuntimeError("no Surefire XML reports found; run Maven tests before the security behavior gate")
    for report in reports:
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError as exception:
            raise RuntimeError(f"invalid Surefire XML report {report}: {exception}") from exception
        for testcase in suite.findall("testcase"):
            class_name = testcase.attrib.get("classname", "")
            test_name = testcase.attrib.get("name", "")
            if not class_name or not test_name:
                continue
            status = "PASS"
            if testcase.find("skipped") is not None:
                status = "SKIPPED"
            elif testcase.find("failure") is not None:
                status = "FAIL"
            elif testcase.find("error") is not None:
                status = "ERROR"
            results[(class_name, test_name)] = status
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("auto", "linux", "windows"), default="auto")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()

    selected = current_platform() if args.platform == "auto" else args.platform
    if selected not in REQUIRED:
        print(f"SECURITY BEHAVIOR EVIDENCE SKIPPED: unsupported platform {selected}")
        return 0

    try:
        observed = executed_testcases(Path(args.root))
    except RuntimeError as exception:
        print(f"SECURITY BEHAVIOR EVIDENCE FAILED: {exception}", file=sys.stderr)
        return 1

    failures: list[str] = []
    for class_name, methods in REQUIRED[selected].items():
        for method in sorted(methods):
            status = observed.get((class_name, method))
            if status != "PASS":
                failures.append(f"{class_name}#{method}: {status or 'MISSING'}")

    if failures:
        print(f"SECURITY BEHAVIOR EVIDENCE FAILED ({selected})", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print(f"SECURITY BEHAVIOR EVIDENCE SUCCESS ({selected})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Fail-closed static invariants for the post-#228 quota/readiness remediation."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing hardening evidence: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, token: str) -> None:
    if token not in text:
        raise RuntimeError(f"{relative}: missing post-228 invariant: {token}")


def forbid(relative: str, text: str, token: str) -> None:
    if token in text:
        raise RuntimeError(f"{relative}: forbidden post-228 regression marker: {token}")


def validate_runtime_contracts() -> None:
    supervisor_path = "minos-runtime-local/src/main/java/com/minos/runtime/ProviderWriteQuotaSupervisor.java"
    supervisor = read(supervisor_path)
    for token in (
        "provider write quota visibility lost",
        "failure instanceof NoSuchFileException",
        "breachAndKill",
        "jobKill.run()",
        "cannot inspect an entry below a supervised writable root",
    ):
        require(supervisor_path, supervisor, token)
    forbid(supervisor_path, supervisor, "catch (IOException | RuntimeException ignored)")

    windows_path = "minos-runtime-local/src/main/java/com/minos/runtime/WindowsAppContainerWorkerSandboxBackend.java"
    windows = read(windows_path)
    for token in (
        "PRIVATE_STORAGE_MAX_BYTES",
        "PRIVATE_STORAGE_MAX_ENTRIES",
        "EXPLICIT_ROOT_WRITE_QUOTA",
        "probeOsIsolation()",
        "CAPABILITY_PROBE_CACHE",
        "privateStorageMaxBytes=",
        "WINDOWS_RUNTIME_CAPABILITY_PROBE_REQUIRED",
    ):
        require(windows_path, windows, token)

    template_path = (
        "minos-runtime-local/src/main/resources/com/minos/runtime/"
        "windows-appcontainer-sandbox-v4.ps1.template"
    )
    template = read(template_path)
    for token in (
        "#minos-include:appcontainer-private-storage",
        "DenyPrivateRegistryWrites(profileName, profileSid)",
        "StartPrivateFileStorageSupervisor(",
        "privateStorageStop.Set()",
    ):
        require(template_path, template, token)

    storage_path = (
        "minos-runtime-local/src/main/resources/com/minos/runtime/windows-fragments/"
        "appcontainer-private-storage.ps1frag"
    )
    storage = read(storage_path)
    for token in (
        "GetAppContainerFolderPath",
        "AccessControlType.Deny",
        "RegistryRights.SetValue",
        "MINOS_APPCONTAINER_PRIVATE_STORAGE_QUOTA_BREACH",
        "MINOS_APPCONTAINER_PRIVATE_STORAGE_VISIBILITY_LOST",
        "TerminateJobObject(job, 1)",
    ):
        require(storage_path, storage, token)

    composition_path = (
        "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/StrongOwnedProcessExecutors.java"
    )
    composition = read(composition_path)
    require(composition_path, composition, "return qualifySandbox(status, sandbox, isDockerRuntimeLocation());")
    require(composition_path, composition, "strongestAvailableForManagedLocalProvider(minosHome)")
    forbid(composition_path, composition, "StrongProcessOwnershipIndexerExecutor.detectCapability(minosHome)")


def validate_documentation() -> None:
    status_path = "docs/STATUS.md"
    roadmap_path = "docs/ROADMAP.md"
    risks_path = "docs/architecture/risks/register.md"
    status = read(status_path)
    roadmap = read(roadmap_path)
    risks = read(risks_path)
    merge_sha = "a042e97ac5e3e2ab7207fa603d85563ea1f71712"
    qualified_sha = "1a551ff72f95db4e14e8a9597d897491b9c1589a"
    for relative, text in ((status_path, status), (roadmap_path, roadmap), (risks_path, risks)):
        require(relative, text, "#228")
        require(relative, text, merge_sha)
        require(relative, text, qualified_sha)
    for relative, text in ((status_path, status), (roadmap_path, roadmap), (risks_path, risks)):
        forbid(relative, text, "#228 en qualification")
        forbid(relative, text, "#228 non intégrée")


def main() -> int:
    try:
        validate_runtime_contracts()
        validate_documentation()
        print("POST-228 HARDENING INVARIANTS SUCCESS")
        return 0
    except Exception as exc:
        print(f"POST-228 HARDENING INVARIANTS FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Fail-closed invariants for MINOS-01: aggregate worker resource containment.

This gate exists so that the P1 finding cannot silently come back. It forbids returning to
per-process limits presented as aggregate guarantees, removing the runtime capability probe,
dropping the supervised write quota or deleting the adversarial containment tests.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

RUNTIME = "minos-runtime-local/src/main/java/com/minos/runtime"
RUNTIME_TESTS = "minos-runtime-local/src/test/java/com/minos/runtime"
WINDOWS_LAUNCHER = "minos-runtime-local/src/main/resources/com/minos/runtime/windows-appcontainer-sandbox-v4.ps1"

ADVERSARIAL_TESTS = {
    f"{RUNTIME_TESTS}/LinuxCgroupJobContainmentTest.java": (
        "@EnabledOnOs(OS.LINUX)",
        "theDelegatedCgroupAppliesAggregateMemoryProcessAndCpuLimits",
        "severalDescendantsCannotExceedTheAggregateMemoryLimitTogether",
        "aForkingProviderCannotExceedTheAggregateProcessLimit",
        "anAbusiveProviderIsThrottledByTheAggregateCpuLimit",
        "noDescendantSurvivesTheJobBoundary",
        "aJobDirectoryCanNeverEscapeTheDelegatedRoot",
        "theSandboxJoinsTheJobBeforeItExecutesAnyProviderCode",
    ),
    f"{RUNTIME_TESTS}/WindowsJobObjectContainmentTest.java": (
        "@EnabledOnOs(OS.WINDOWS)",
        "neitherChildNorGrandchildSurvivesTheJobObject",
        "theSandboxPlanCarriesEveryAggregateJobLimit",
        "theQualifiedBackendDeclaresTheAggregateContainmentItReallyEnforces",
    ),
    f"{RUNTIME_TESTS}/ProviderWriteContainmentTest.java": (
        "aHostileProviderIsKilledDuringExecutionAndItsResidueIsReclaimed",
        "theRunThatFollowsAHostileRunStartsNormally",
    ),
    f"{RUNTIME_TESTS}/ProviderWriteQuotaSupervisorTest.java": (
        "aProviderThatFillsTheDiskIsDetectedAndTheJobIsDestroyed",
        "aProviderThatExhaustsInodesIsDetectedAndTheJobIsDestroyed",
        "aProviderStayingInsideItsBudgetIsNeverKilled",
    ),
    f"{RUNTIME_TESTS}/RunDirectoryRetentionTest.java": (
        "anOversizedHostileRunIsReclaimedInsteadOfBlockingEveryFutureIndexation",
        "aRunTooLargeToDeleteInOnePassIsQuarantinedAndDrainedLater",
        "pruneNeverDeletesOutsideTheRunsRoot",
    ),
    f"{RUNTIME_TESTS}/WorkerResourceContainmentTest.java": (
        "aBackendWithoutAnAggregateJobBoundaryIsNeverQualifiedForUntrustedCode",
        "supervisionIsNeverAcceptedAsASubstituteForAnAggregateOsJobBoundary",
        "aMeasurementAfterExecutionIsNeverContainment",
    ),
}


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing MINOS-01 evidence file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle not in text:
            raise RuntimeError(f"{relative}: missing MINOS-01 invariant: {needle}")


def forbid(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle in text:
            raise RuntimeError(f"{relative}: forbidden MINOS-01 regression: {needle}")


def main() -> int:
    try:
        containment = read(f"{RUNTIME}/WorkerResourceContainment.java")
        qualification = read(f"{RUNTIME}/WorkerSandboxQualification.java")
        backend = read(f"{RUNTIME}/WorkerSandboxBackend.java")
        cgroup = read(f"{RUNTIME}/LinuxCgroupJob.java")
        linux = read(f"{RUNTIME}/LinuxBubblewrapWorkerSandboxBackend.java")
        windows = read(f"{RUNTIME}/WindowsAppContainerWorkerSandboxBackend.java")
        launcher = read(WINDOWS_LAUNCHER)
        quota = read(f"{RUNTIME}/ProviderWriteQuota.java")
        supervisor = read(f"{RUNTIME}/ProviderWriteQuotaSupervisor.java")
        reclamation = read(f"{RUNTIME}/ProviderResidueReclamation.java")
        executor = read(f"{RUNTIME}/ProcessIndexerExecutor.java")
        retention = read(f"{RUNTIME}/RunDirectoryRetention.java")
        worker = read(f"{RUNTIME}/LocalIsolatedIndexWorker.java")

        # 1. Containment is per dimension, and supervision never substitutes an aggregate OS boundary.
        require("WorkerResourceContainment.java", containment,
                "OS_ENFORCED", "SUPERVISED_HARD_KILL", "MEASURED_ONLY", "UNAVAILABLE",
                "aggregateProcessCount", "aggregateMemory", "aggregateCpu", "descendantTermination",
                "filesystemWriteBytes", "filesystemWriteEntries", "scratchReclamation",
                "aggregateJobBoundaryEnforced", "qualifiedForUntrustedCode", "unmetRequirements")
        for dimension in (
            "AGGREGATE_PROCESS_COUNT",
            "AGGREGATE_MEMORY",
            "AGGREGATE_CPU",
            "DESCENDANT_TERMINATION",
        ):
            require("WorkerResourceContainment.java", containment, f'requireOsEnforced(unmet, "{dimension}"')

        # 2. The qualification itself is fail-closed: no untrusted-code claim without containment.
        require("WorkerSandboxQualification.java", qualification,
                "WorkerResourceContainment containment",
                "!containment.qualifiedForUntrustedCode()",
                "BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY",
                "containment.qualifiedForUntrustedCode()")
        require("WorkerSandboxBackend.java", backend,
                "evidence.containment().qualifiedForUntrustedCode()", "resourceContainment")

        # 3. Linux owns a real cgroup v2 job boundary, probed for real before it is claimed.
        require("LinuxCgroupJob.java", cgroup,
                "memory.max", "memory.swap.max", "pids.max", "cpu.max", "cgroup.kill",
                "cgroup.procs", "cgroup.subtree_control", "delegatedRoot", "requireApplied",
                "MINOS_SANDBOX_CGROUP_ROOT", "enterThenExec")
        require("LinuxBubblewrapWorkerSandboxBackend.java", linux,
                "LinuxCgroupJob.delegatedRoot().isEmpty()",
                "LINUX_CGROUP_V2_AGGREGATE_MEMORY_PIDS_CPU_JOB_BOUNDARY",
                "LINUX_PRLIMIT_PER_PROCESS_DEFENCE_IN_DEPTH_ONLY",
                "job.enterThenExec(shell, sandbox)",
                "ProviderWriteQuota.DEFAULT",
                "probeOsIsolation()")
        forbid("LinuxBubblewrapWorkerSandboxBackend.java", linux,
               "LINUX_PRLIMIT_ADDRESS_SPACE_PROCESS_COUNT_OPEN_FILES_CPU")
        if not re.search(r"job\s*\?\s*containment\(\)\s*:\s*WorkerResourceContainment\.none", linux):
            raise RuntimeError(
                "LinuxBubblewrapWorkerSandboxBackend.java: containment must degrade when no cgroup job exists")

        # 4. Windows proves job membership, refuses breakaway and never leaves a survivor.
        require("windows-appcontainer-sandbox-v4.ps1", launcher,
                "CREATE_SUSPENDED", "IsProcessInJob", "TerminateJobObject", "QueryInformationJobObject",
                "JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE", "JOB_OBJECT_LIMIT_ACTIVE_PROCESS",
                "JOB_OBJECT_LIMIT_JOB_MEMORY", "JOB_OBJECT_LIMIT_JOB_TIME",
                "JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP",
                "MINOS job object must never allow breakaway",
                "sandbox process is not a member of the MINOS job object")
        require("WindowsAppContainerWorkerSandboxBackend.java", windows,
                "WINDOWS_JOB_BREAKAWAY_PROHIBITED", "WINDOWS_JOB_TERMINATED_ON_EVERY_EXIT_PATH",
                "jobCpuSeconds", "ProviderWriteQuota.DEFAULT", "containment()")

        # 5. The filesystem budget is enforced during execution, not measured afterwards.
        require("ProviderWriteQuota.java", quota, "maxBytes", "maxEntries", "samplePeriod")
        require("ProviderWriteQuotaSupervisor.java", supervisor,
                "jobKill.run()", "FileVisitResult.TERMINATE", "breach", "samplePeriod")
        require("ProcessIndexerExecutor.java", executor,
                "ProviderWriteQuotaSupervisor.start", "providerWriteQuota()",
                "transformer.killContainedJob()", "transformer.releaseContainment()",
                "write containment breached", "ProviderResidueReclamation.reclaim",
                "providerWritableRoots")
        require("ProviderResidueReclamation.java", reclamation,
                "RETAINED_ENTRIES", "LinkOption.NOFOLLOW_LINKS", "startsWith(root)")
        if executor.index("ProviderWriteQuotaSupervisor.start") > executor.index("process.waitFor"):
            raise RuntimeError("ProcessIndexerExecutor.java: the write quota starts after the provider ran")

        # 6. A hostile previous run can never block the runs that follow it.
        require("RunDirectoryRetention.java", retention,
                "QUARANTINE_DIRECTORY", "reclaimFirst", "DeletionBudget", "Budgets",
                "maxDeleteEntriesPerPrune", "LinkOption.NOFOLLOW_LINKS")
        forbid("RunDirectoryRetention.java", retention,
               'throw new IOException("retained run directory exceeds traversal scan limit',
               'throw new IOException("MINOS runs root exceeds entry scan limit")')

        # 7. The remote worker still refuses everything before copying or executing anything.
        require("LocalIsolatedIndexWorker.java", worker, "!sandboxBackend.supportsUntrustedCode()")

        # 8. The adversarial proofs stay permanent and enabled.
        for relative, needles in ADVERSARIAL_TESTS.items():
            text = read(relative)
            require(relative, text, *needles)
            forbid(relative, text, "@Disabled")

        # 9. The documented claims describe the containment the code really enforces.
        disposition = read("docs/developer/remote-worker-sandbox-disposition.md")
        quality = read("docs/developer/quality-gates.md")
        require("docs/developer/remote-worker-sandbox-disposition.md", disposition,
                "cgroup v2", "memory.max", "pids.max", "cpu.max", "cgroup.kill",
                "Job Object", "fail-closed", "quota d’écriture")
        forbid("docs/developer/remote-worker-sandbox-disposition.md", disposition,
               "linux-bubblewrap-prlimit-v4", "windows-appcontainer-job-v2")
        require("docs/developer/quality-gates.md", quality, "check-minos-01.py")

        # 10. The CI gate itself is wired and provisions a real delegated cgroup on Linux.
        delegation = read("scripts/ci/delegate-linux-cgroup.sh")
        require("scripts/ci/delegate-linux-cgroup.sh", delegation,
                "MINOS_SANDBOX_CGROUP_ROOT", "cgroup.subtree_control", "cgroup.procs")
        require(".github/workflows/pr-ci.yml", read(".github/workflows/pr-ci.yml"),
                "scripts/remediation/check-minos-01.py", "scripts/ci/delegate-linux-cgroup.sh")
        for relative in (
            ".github/workflows/m19-advanced-code-intelligence.yml",
            ".github/workflows/m20-semantic-hybrid-intelligence.yml",
        ):
            require(relative, read(relative), "scripts/ci/delegate-linux-cgroup.sh")

        print("MINOS-01 WORKER RESOURCE CONTAINMENT INVARIANTS SUCCESS")
        return 0
    except Exception as exception:
        print(f"MINOS-01 WORKER RESOURCE CONTAINMENT INVARIANTS FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

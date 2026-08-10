from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="\n")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one test anchor, found {count}")
    write(path, text.replace(old, new, 1))


# MNC-01: exactly one concurrent registration may own rollback rights.
registry_test = "minos-application/src/test/java/com/minos/registry/InterProcessLocalProjectRegistryTest.java"
text = read(registry_test)
text = text.replace("import static org.junit.jupiter.api.Assertions.assertTrue;\n",
                    "import static org.junit.jupiter.api.Assertions.assertFalse;\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n", 1)
insert = r'''
    @Test
    void concurrentRegistrationResultGrantsRollbackOwnershipToExactlyOneCaller() throws Exception {
        Path storage = temporary.resolve("registration-result");
        Path project = Files.createDirectory(temporary.resolve("registration-result-project"));
        InterProcessLocalProjectRegistry first = new InterProcessLocalProjectRegistry(storage);
        InterProcessLocalProjectRegistry second = new InterProcessLocalProjectRegistry(storage);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                start.await();
                return first.registerProjectWithResult(project, "first");
            });
            var two = executor.submit(() -> {
                start.await();
                return second.registerProjectWithResult(project, "second");
            });
            start.countDown();
            ProjectRegistry.RegistrationResult left = one.get();
            ProjectRegistry.RegistrationResult right = two.get();
            assertEquals(left.project().id(), right.project().id());
            assertTrue(left.createdByThisCall() ^ right.createdByThisCall());
            assertFalse(left.createdByThisCall() && right.createdByThisCall());
        }
    }
'''
if not text.endswith("}\n"):
    raise SystemExit("registry test closing brace missing")
text = text[:-2] + insert + "}\n"
write(registry_test, text)

# MNC-18: closed project leases do not leave unbounded JVM lock entries.
lease_test = "minos-application/src/test/java/com/minos/orchestration/ProjectIndexLeaseTest.java"
text = read(lease_test)
insert = r'''
    @Test
    void releasesJvmLockStateAfterLastLeaseCloses() throws Exception {
        int baseline = ProjectIndexLease.retainedJvmLockCount();
        for (int index = 0; index < 128; index++) {
            try (ProjectIndexLease ignored = ProjectIndexLease.acquire(temporary, UUID.randomUUID())) {
                assertTrue(ProjectIndexLease.retainedJvmLockCount() >= baseline + 1);
            }
        }
        assertTrue(ProjectIndexLease.retainedJvmLockCount() <= baseline);
    }
'''
if not text.endswith("}\n"):
    raise SystemExit("lease test closing brace missing")
text = text[:-2] + insert + "}\n"
write(lease_test, text)

# MNC-02: constructing a Linux sandbox plan must never expose / as a read-only bind.
write(
    "minos-runtime-local/src/test/java/com/minos/runtime/LinuxBubblewrapWorkerSandboxIsolationTest.java",
    r'''package com.minos.runtime;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class LinuxBubblewrapWorkerSandboxIsolationTest {

    @TempDir
    Path temporary;

    @Test
    void sandboxPlanNeverBindsWholeHostRoot() throws Exception {
        Path executable = Path.of("/bin/true");
        LinuxBubblewrapWorkerSandboxBackend backend =
                new LinuxBubblewrapWorkerSandboxBackend(executable, executable);
        Path working = Files.createDirectory(temporary.resolve("workspace"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        Path artifact = working.resolve("index.scip");
        IndexerProcessPlan plan = new IndexerProcessPlan(
                List.of(executable.toString()), working, Map.of(), artifact, Duration.ofSeconds(10));

        List<String> command = backend.sandboxPlan(plan, run, WorkerNetworkPolicy.DENY).command();
        for (int index = 0; index + 2 < command.size(); index++) {
            assertFalse("--ro-bind".equals(command.get(index))
                    && "/".equals(command.get(index + 1))
                    && "/".equals(command.get(index + 2)));
        }
        assertTrue(command.contains("--unshare-all"));
        assertTrue(backend.qualification().evidence().contains("LINUX_MINIMAL_RUNTIME_READ_ONLY_ALLOWLIST"));
    }
}
'''
)

# MNC-03: packaged lockfiles are bound to the exact root package integrity.
write(
    "minos-provider-scip/src/test/java/com/minos/adapter/scip/runtime/LockedNpmPackageTest.java",
    r'''package com.minos.adapter.scip.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

class LockedNpmPackageTest {

    @TempDir
    Path temporary;

    @Test
    void verifiesPackagedPythonLockIntegrity() throws Exception {
        verifyResource(
                "scip-python-package-lock.json",
                "@sourcegraph/scip-python",
                "0.6.6",
                "sha512-qoKL1Rggg0o5newAFbCFAKlS0AjWxG5MA+mC28BtgxOv0DhO4zdL8u7151FxEppDpXMVvm7+yXSjXotoVH9cMQ==");
    }

    @Test
    void verifiesPackagedTypeScriptLockIntegrity() throws Exception {
        verifyResource(
                "scip-typescript-package-lock.json",
                "@sourcegraph/scip-typescript",
                "0.4.0",
                "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==");
    }

    private void verifyResource(String resource, String packageName, String version, String integrity) throws Exception {
        Path lock = temporary.resolve(resource);
        try (InputStream input = LockedNpmPackageTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("missing test resource " + resource);
            Files.copy(input, lock);
        }
        LockedNpmPackage.verify(lock, packageName, version, integrity);
    }
}
'''
)

# MNC-10: structural SCIP limit is enforced by the preflight reader before returning an Index.
write(
    "minos-provider-scip/src/test/java/com/minos/adapter/scip/ScipIndexReaderPreflightTest.java",
    r'''package com.minos.adapter.scip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipIndexReaderPreflightTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsDocumentOverflowDuringWirePreflight() throws Exception {
        Path artifact = temporary.resolve("overflow.scip");
        Index index = Index.newBuilder()
                .addDocuments(Document.newBuilder().setRelativePath("a.java"))
                .addDocuments(Document.newBuilder().setRelativePath("b.java"))
                .build();
        try (OutputStream output = Files.newOutputStream(artifact)) {
            index.writeTo(output);
        }
        ScipIngestionLimits limits = new ScipIngestionLimits(
                1024 * 1024L, 1L, 100L, 100L, 100L);
        IOException failure = assertThrows(IOException.class, () -> new ScipIndexReader(limits).read(artifact));
        assertTrue(failure.getMessage().contains("documents limit"));
    }
}
'''
)

# MNC-12: ignore files are bounded before regex compilation.
write(
    "minos-application/src/test/java/com/minos/discovery/ProjectIgnorePolicyBoundsTest.java",
    r'''package com.minos.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectIgnorePolicyBoundsTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsOversizedRootIgnoreFileWhileReading() throws Exception {
        Files.writeString(temporary.resolve(".gitignore"), "x".repeat(1024 * 1024 + 1));
        assertThrows(IOException.class, () -> ProjectIgnorePolicy.load(temporary));
    }
}
'''
)

# MNC-20: corrupt protocol counts do not request protocol-scale ArrayList capacities.
write(
    "minos-storage-local/src/test/java/com/minos/store/SnapshotCodecAllocationGuardTest.java",
    r'''package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotCodecAllocationGuardTest {

    @TempDir
    Path temporary;

    @Test
    void truncatedSnapshotWithMaximumSymbolCountFailsWithoutProtocolScalePreallocation() throws Exception {
        Path file = temporary.resolve("corrupt.knowledge");
        UUID project = UUID.randomUUID();
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(0x4D4E5359);
            output.writeInt(2);
            output.writeLong(project.getMostSignificantBits());
            output.writeLong(project.getLeastSignificantBits());
            output.writeInt(1);
            output.writeChar('s');
            output.writeInt(10_000_000);
        }
        assertThrows(IOException.class, () -> new SnapshotCodecV2().read(file));
    }
}
'''
)

print("MNC regression tests staged")

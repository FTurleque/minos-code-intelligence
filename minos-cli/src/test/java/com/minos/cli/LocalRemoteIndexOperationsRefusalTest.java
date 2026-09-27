package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.bootstrap.LocalRemoteIndexingRuntimeFixtures;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.runtime.DistributedArtifactBundleStore;
import com.minos.runtime.WorkerSandboxBackend;
import com.minos.runtime.WorkerSandboxBackends;
import com.minos.runtime.WorkerSandboxSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 / ADR 0041: {@code remote index} refuses <em>before</em> materializing the revision, taking
 * the lease, registering the project or pinning the source, and the refusal is diagnosable.
 */
class LocalRemoteIndexOperationsRefusalTest {

    private static final String BYTES_UNMET =
            "FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL";
    private static final String ENTRIES_UNMET =
            "FILESYSTEM_WRITE_ENTRIES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL";

    @Test
    void refusesBeforeAnyMaterializationWhenNoSandboxIsQualifiedForUntrustedCode(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        AtomicInteger materializeCalls = new AtomicInteger();
        RemoteRepositoryMaterializer materializer = new RemoteRepositoryMaterializer() {
            @Override
            public RemoteMaterialization materialize(RemoteRepositoryRequest ignored) {
                materializeCalls.incrementAndGet();
                throw new AssertionError("the revision must never be materialized when remote indexing is refused");
            }

            @Override
            public void pin(RemoteMaterialization ignored) {
                throw new AssertionError("nothing may be pinned when remote indexing is refused");
            }

            @Override
            public void release(RemoteMaterialization ignored) {
                throw new AssertionError("nothing was materialized, so nothing may be released");
            }
        };
        WorkerSandboxSelection rejected = new WorkerSandboxSelection(
                WorkerSandboxBackend.nativeEphemeralWorkspace(),
                WorkerSandboxSelection.Cause.REJECTED_BY_DECISION,
                Optional.of("windows-appcontainer-job-v3"),
                List.of(BYTES_UNMET, ENTRIES_UNMET));
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            LocalRemoteIndexOperations operations = new LocalRemoteIndexOperations(
                    application, materializer, LocalRemoteIndexingRuntimeFixtures.withSelection(new DistributedArtifactBundleStore(home),
                            (workerId, delegate, store) -> {
                                throw new AssertionError("no worker may be created when remote indexing is refused");
                            },
                            () -> rejected));
            RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                    "https://github.com/acme/remote-fixture", "main", "a".repeat(40), null, null);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.index(
                    request, "remote-fixture", null, "worker-one", WorkerNetworkPolicy.ALLOW));

            String message = failure.getMessage();
            assertTrue(message.contains("remote index is refused before any materialization"), message);
            assertTrue(message.contains("native-process-ephemeral-workspace-v1"), message);
            assertTrue(message.contains("ADR 0041"), message);
            assertTrue(message.contains("windows-appcontainer-job-v3"), message);
            assertTrue(message.contains(BYTES_UNMET), message);
            assertTrue(message.contains(ENTRIES_UNMET), message);
            assertFalse(message.contains("/") || message.contains("\\"), "no filesystem path in the refusal: " + message);
            assertEquals(0, materializeCalls.get());
            assertFalse(Files.exists(home.resolve("remote-index-leases")), "no lease may be taken");
            assertFalse(Files.exists(home.resolve("remote-cache")), "no remote cache may be created");
            assertTrue(application.projectRegistry().listProjects().isEmpty(), "no project may be registered");
        }
    }

    /**
     * V31: the production wiring itself refuses early. {@code MinosCliRunner} builds
     * {@code new LocalRemoteIndexOperations(app)}, which takes the JGit materializer and the remote
     * indexing runtime from the composition root (ADR 0042); the two-argument constructor exercised
     * here substitutes only the materializer and keeps the production runtime, whose sandbox selection
     * is the real host one. No selection is injected: the refusal must come from
     * {@link WorkerSandboxBackends#selectForUntrustedCode} on the current host, whatever its OS.
     */
    @Test
    void productionWiringRefusesBeforeAnyMaterializationOnTheCurrentHost(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        AtomicInteger materializeCalls = new AtomicInteger();
        RemoteRepositoryMaterializer materializer = refusingMaterializer(materializeCalls);
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            LocalRemoteIndexOperations operations = new LocalRemoteIndexOperations(application, materializer);
            RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                    "https://github.com/acme/remote-fixture", "main", "a".repeat(40), null, null);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.index(
                    request, "remote-fixture", null, "worker-one", WorkerNetworkPolicy.ALLOW));

            // ADR 0041: no integrated backend is qualified for untrusted code on any OS today, so the
            // host selection is a refusal (by decision, or for a missing prerequisite).
            WorkerSandboxSelection host = WorkerSandboxBackends.selectForUntrustedCode(home);
            assertFalse(host.supportsUntrustedCode(), "ADR 0041 keeps untrusted remote code closed: " + host);
            String message = failure.getMessage();
            assertTrue(message.startsWith("remote index is refused before any materialization"), message);
            assertTrue(message.contains(host.refusalReport()), message);
            assertFalse(message.contains("/") || message.contains("\\"), "no filesystem path in the refusal: " + message);
            assertEquals(0, materializeCalls.get(), "the revision must never be materialized");
            assertFalse(Files.exists(home.resolve("remote-index-leases")), "no lease may be taken");
            assertFalse(Files.exists(home.resolve("remote-cache")), "no remote cache may be created");
            assertTrue(application.projectRegistry().listProjects().isEmpty(), "no project may be registered");
        }
    }

    /**
     * V31: there is no "no selection" value that silently skips the early refusal. A composition that
     * wants the transport to reach its worker must inject a qualified selection explicitly.
     */
    @Test
    void aMissingSandboxSelectionIsRejectedInsteadOfDisablingTheEarlyRefusal(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            NullPointerException failure = assertThrows(NullPointerException.class, () -> LocalRemoteIndexingRuntimeFixtures.withSelection(
                    new DistributedArtifactBundleStore(home),
                    (workerId, delegate, store) -> {
                        throw new AssertionError("no worker may be created");
                    },
                    null));
            assertEquals("untrustedCodeSandbox", failure.getMessage());
            // Nor can the runtime itself be left out of the operations.
            NullPointerException noRuntime = assertThrows(NullPointerException.class, () -> new LocalRemoteIndexOperations(
                    application, refusingMaterializer(new AtomicInteger()), (RemoteIndexingRuntime) null));
            assertEquals("remoteIndexingRuntime", noRuntime.getMessage());
        }
    }

    @Test
    void aQualifiedSelectionLetsTheRequestReachTheMaterializer(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        AtomicInteger materializeCalls = new AtomicInteger();
        RemoteRepositoryMaterializer materializer = new RemoteRepositoryMaterializer() {
            @Override
            public RemoteMaterialization materialize(RemoteRepositoryRequest ignored) {
                materializeCalls.incrementAndGet();
                throw new IllegalStateException("materializer reached");
            }

            @Override public void pin(RemoteMaterialization ignored) { }
            @Override public void release(RemoteMaterialization ignored) { }
        };
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            LocalRemoteIndexOperations operations = new LocalRemoteIndexOperations(
                    application, materializer, LocalRemoteIndexingRuntimeFixtures.withSelection(new DistributedArtifactBundleStore(home),
                            (workerId, delegate, store) -> {
                                throw new AssertionError("not reached in this test");
                            },
                            QualifiedSandboxForTests.selection()));
            RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                    "https://github.com/acme/remote-fixture", "main", "a".repeat(40), null, null);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.index(
                    request, "remote-fixture", null, "worker-one", WorkerNetworkPolicy.ALLOW));

            assertEquals("materializer reached", failure.getMessage());
            assertEquals(1, materializeCalls.get());
        }
    }

    /**
     * A2 / ADR 0042 — the true production constructor {@code new LocalRemoteIndexOperations(app)}, with
     * nothing substituted (JGit materializer and remote indexing runtime from the composition root),
     * refuses before any lease, registration or pin on the current host.
     */
    @Test
    void productionConstructorRefusesBeforeAnySideEffectOnTheCurrentHost(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            LocalRemoteIndexOperations operations = new LocalRemoteIndexOperations(application);
            RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                    "https://github.com/acme/remote-fixture", "main", "a".repeat(40), null, null);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.index(
                    request, "remote-fixture", null, "worker-one", WorkerNetworkPolicy.ALLOW));

            WorkerSandboxSelection host = WorkerSandboxBackends.selectForUntrustedCode(home);
            assertFalse(host.supportsUntrustedCode(), "ADR 0041 keeps untrusted remote code closed: " + host);
            String message = failure.getMessage();
            assertTrue(message.startsWith("remote index is refused before any materialization"), message);
            assertTrue(message.contains(host.refusalReport()), message);
            assertFalse(message.contains("/") || message.contains("\\"), "no filesystem path in the refusal: " + message);
            assertFalse(Files.exists(home.resolve("remote-index-leases")), "no lease may be taken");
            assertTrue(application.projectRegistry().listProjects().isEmpty(), "no project may be registered");
        }
    }

    /** Counts calls, then fails the test: under a refusal nothing may be materialized, pinned or released. */
    private static RemoteRepositoryMaterializer refusingMaterializer(AtomicInteger materializeCalls) {
        return new RemoteRepositoryMaterializer() {
            @Override
            public RemoteMaterialization materialize(RemoteRepositoryRequest ignored) {
                materializeCalls.incrementAndGet();
                throw new AssertionError("the revision must never be materialized when remote indexing is refused");
            }

            @Override
            public void pin(RemoteMaterialization ignored) {
                throw new AssertionError("nothing may be pinned when remote indexing is refused");
            }

            @Override
            public void release(RemoteMaterialization ignored) {
                throw new AssertionError("nothing was materialized, so nothing may be released");
            }
        };
    }
}

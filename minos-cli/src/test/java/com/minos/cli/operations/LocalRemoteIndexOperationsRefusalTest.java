package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.application.remote.RemoteIndexingUseCase;
import com.minos.application.remote.RemoteSourceRequest;
import com.minos.application.remote.WorkerNetworkChoice;
import com.minos.bootstrap.LocalRemoteIndexingRuntimeFixtures;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import com.minos.runtime.local.sandbox.WorkerSandboxBackends;
import com.minos.runtime.local.sandbox.WorkerSandboxSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 / ADR 0041, end to end through the command operations: with the real host selection, {@code remote index}
 * refuses <em>before</em> materializing the revision, taking the lease, registering the project or pinning the
 * source. The refusal for an injected selection and every cleanup path are proved on the application use case
 * ({@code RemoteIndexingUseCaseRefusalTest}, {@code RemoteIndexingUseCaseLifecycleTest}).
 */
class LocalRemoteIndexOperationsRefusalTest {

    /**
     * V31: the production wiring itself refuses early. {@code MinosCliRunner} builds
     * {@code new LocalRemoteIndexOperations(app)}, which takes the JGit materializer and the remote
     * indexing runtime from the composition root (ADR 0042); the use case exercised here substitutes
     * only the materializer and keeps the production runtime, whose sandbox selection is the real
     * host one. No selection is injected: the refusal must come from
     * {@link WorkerSandboxBackends#selectForUntrustedCode} on the current host, whatever its OS.
     */
    @Test
    void productionWiringRefusesBeforeAnyMaterializationOnTheCurrentHost(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        AtomicInteger materializeCalls = new AtomicInteger();
        RemoteRepositoryMaterializer materializer = refusingMaterializer(materializeCalls);
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            LocalRemoteIndexOperations operations = new LocalRemoteIndexOperations(RemoteIndexingUseCase.of(
                    application, materializer, application.compositionRoot().remoteIndexingRuntime(application.home())));
            RemoteSourceRequest request = RemoteSourceRequest.of(
                    "https://github.com/acme/remote-fixture", "main", "a".repeat(40), null, null);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.index(
                    request, "remote-fixture", null, "worker-one", WorkerNetworkChoice.ALLOW));

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
            // Nor can the runtime itself be left out of the remote indexing.
            NullPointerException noRuntime = assertThrows(NullPointerException.class, () -> RemoteIndexingUseCase.of(
                    application, refusingMaterializer(new AtomicInteger()), (RemoteIndexingRuntime) null));
            assertEquals("remoteIndexingRuntime", noRuntime.getMessage());
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
            RemoteSourceRequest request = RemoteSourceRequest.of(
                    "https://github.com/acme/remote-fixture", "main", "a".repeat(40), null, null);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> operations.index(
                    request, "remote-fixture", null, "worker-one", WorkerNetworkChoice.ALLOW));

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

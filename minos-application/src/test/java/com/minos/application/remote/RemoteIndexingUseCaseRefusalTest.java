package com.minos.application.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 / ADR 0041 in the application: {@code index} refuses <em>before</em> materializing the revision, taking the
 * lease, registering the project or pinning the source, and the refusal names the backend and its codes, no path.
 */
class RemoteIndexingUseCaseRefusalTest {

    @TempDir Path temp;

    @Test
    void refusesBeforeAnyEffectWhenNoSandboxIsQualifiedForUntrustedCode() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        fixture.runtime.selection = RemoteIndexingFixture.selection(false);
        RemoteIndexingUseCase useCase = fixture.useCase();
        RemoteSourceRequest request = RemoteIndexingFixture.request();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> useCase.index(
                request, "remote-fixture", null, "worker-one", WorkerNetworkChoice.ALLOW));

        String message = failure.getMessage();
        assertEquals("remote index is refused before any materialization: sandbox backend "
                + RemoteIndexingFixture.BACKEND + " is not qualified for untrusted remote code on the current "
                + "platform; " + RemoteIndexingFixture.REFUSAL, message);
        assertFalse(message.contains("/") || message.contains("\\"), "no filesystem path in the refusal: " + message);
        assertEquals(0, fixture.materializer.materialized.get(), "the revision must never be materialized");
        assertEquals(0, fixture.materializer.pinned.get());
        assertEquals(0, fixture.materializer.released.get());
        assertFalse(Files.exists(fixture.home.resolve("remote-index-leases")), "no lease may be taken");
        assertTrue(fixture.registry.projects.isEmpty(), "no project may be registered");
        assertEquals(0, fixture.indexings.get());
        assertEquals(List.of(), fixture.runtime.distributed);
    }

    @Test
    void aQualifiedSelectionLetsTheRequestReachTheMaterializer() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        fixture.materializer.materializeFailure = new IllegalStateException("materializer reached");
        RemoteIndexingUseCase useCase = fixture.useCase();
        RemoteSourceRequest request = RemoteIndexingFixture.request();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> useCase.index(
                request, "remote-fixture", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals("materializer reached", failure.getMessage());
        assertEquals(1, fixture.materializer.materialized.get());
        assertFalse(Files.exists(fixture.home.resolve("remote-index-leases")), "nothing to lease without a source");
    }

    @Test
    void aMissingRuntimeOrSelectionNeverSkipsTheEarlyRefusal() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        NullPointerException noRuntime = assertThrows(NullPointerException.class,
                () -> new RemoteIndexingUseCase.Dependencies(fixture.home, fixture.registry, fixture.materializer,
                        null, fixture.descriptors, (projectId, provider, decorator) -> null));
        assertEquals("remoteIndexingRuntime", noRuntime.getMessage());

        fixture.runtime.selection = null;
        RemoteIndexingUseCase useCase = fixture.useCase();
        RemoteSourceRequest request = RemoteIndexingFixture.request();
        NullPointerException noSelection = assertThrows(NullPointerException.class, () -> useCase.index(
                request, "remote-fixture", null, "worker-one", WorkerNetworkChoice.DENY));
        assertEquals("untrusted-code sandbox selection", noSelection.getMessage());
        assertEquals(0, fixture.materializer.materialized.get());
    }

    @Test
    void aBlankNameOrAMissingNetworkChoiceIsRefusedBeforeAnyEffect() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        RemoteIndexingUseCase useCase = fixture.useCase();
        RemoteSourceRequest request = RemoteIndexingFixture.request();

        assertEquals("displayName must not be blank", assertThrows(IllegalArgumentException.class,
                () -> useCase.index(request, " ", null, "worker-one", WorkerNetworkChoice.DENY)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> useCase.index(request, null, null, "worker-one", WorkerNetworkChoice.DENY));
        assertThrows(NullPointerException.class, () -> useCase.index(request, "demo", null, "worker-one", null));
        assertThrows(NullPointerException.class,
                () -> useCase.index(null, "demo", null, "worker-one", WorkerNetworkChoice.DENY));
        assertEquals(0, fixture.materializer.materialized.get());
    }
}

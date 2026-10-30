package com.minos.application.remote;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The post-materialization lifecycle of a remote indexing: every path out (lease, registration, pin, execution,
 * missing evidence, success) releases the source exactly once and the lease, and rolls back the registration and the
 * pin of a project this call created.
 */
class RemoteIndexingUseCaseLifecycleTest {

    @TempDir Path temp;

    @Test
    void aSuccessReturnsSortedEvidenceKeepsTheProjectAndReleasesTheSourceAndTheLease() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        fixture.descriptors.add(RemoteIndexingFixture.descriptor("scip-go"));
        fixture.runtime.evidence.put(RemoteIndexingFixture.PROVIDER, List.of(
                RemoteIndexingFixture.evidence(RemoteIndexingFixture.PROVIDER, "module-b"),
                RemoteIndexingFixture.evidence(RemoteIndexingFixture.PROVIDER, "module-a")));
        fixture.runtime.evidence.put("scip-go", List.of(RemoteIndexingFixture.evidence("scip-go", "")));

        RemoteIndexSummary summary = fixture.useCase().index(RemoteIndexingFixture.request(), "remote-demo", null,
                "worker-one", WorkerNetworkChoice.DENY);

        assertEquals("remote-demo", summary.projectName());
        assertTrue(fixture.registry.findProject(java.util.UUID.fromString(summary.projectId())).isPresent());
        assertEquals("SUCCEEDED", summary.execution().status());
        assertEquals(List.of("scip-go/", "scip-java/module-a", "scip-java/module-b"), summary.artifacts().stream()
                .map(artifact -> artifact.providerId() + "/" + artifact.projectRelativeRoot()).toList());
        ArtifactEvidenceSummary first = summary.artifacts().get(1);
        assertEquals("0.10.0", first.providerVersion());
        assertEquals("JAVA", first.language());
        assertEquals("worker-one", first.workerId());
        assertEquals("PROCESS_EPHEMERAL_WORKSPACE", first.isolation());
        assertEquals("ALLOW", first.networkPolicy());
        assertEquals("a".repeat(64), first.artifactSha256());
        assertEquals("b".repeat(64), first.bundleSha256());
        RemoteMaterializationSummary materialization = summary.materialization();
        assertEquals("GITHUB", materialization.host());
        assertEquals("https://github.com/acme/demo.git", materialization.repository());
        assertEquals("refs/heads/main", materialization.reference());
        assertEquals(RemoteIndexingFixture.COMMIT, materialization.commit());
        assertEquals(fixture.repositoryRoot.toAbsolutePath().normalize().toString(), materialization.projectRoot());
        assertEquals("cache-key-1", materialization.cacheKey());
        assertEquals(List.of(WorkerNetworkPolicy.DENY, WorkerNetworkPolicy.DENY), fixture.runtime.policies);
        assertEquals(1, fixture.materializer.pinned.get());
        assertEquals(0, fixture.materializer.unpinned.get());
        assertReleasedOnce(fixture);
        fixture.runtime.distributed.forEach(execution -> assertEquals(1, execution.closed.get()));
    }

    @Test
    void anExecutionFailureAfterRegistrationRollsBackAndReleasesOnce() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        IOException providerFailure = new IOException("provider failed");
        fixture.indexingStep = () -> { throw providerFailure; };

        IOException failure = assertThrows(IOException.class, () -> fixture.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertSame(providerFailure, failure);
        assertTrue(fixture.registry.projects.isEmpty(), "the project created by this call is removed");
        assertEquals(1, fixture.materializer.pinned.get());
        assertEquals(1, fixture.materializer.unpinned.get(), "the source is unpinned once the project is gone");
        assertReleasedOnce(fixture);
        assertEquals(1, fixture.runtime.distributed.getFirst().closed.get());
    }

    @Test
    void aProviderWithoutVerifiedEvidenceIsAFailureThatRollsBack() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> fixture.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals("distributed provider completed without verified artifact evidence", failure.getMessage());
        assertTrue(fixture.registry.projects.isEmpty());
        assertEquals(1, fixture.materializer.unpinned.get());
        assertReleasedOnce(fixture);
    }

    @Test
    void aProviderWithoutDescriptorIsRefusedAndRolledBack() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        fixture.executedProviders = List.of("unknown-provider");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> fixture.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals("remote execution has no descriptor for provider: unknown-provider", failure.getMessage());
        assertTrue(fixture.registry.projects.isEmpty());
        assertEquals(List.of(), fixture.runtime.distributed);
        assertReleasedOnce(fixture);
    }

    @Test
    void aPinFailureRemovesTheProjectWithoutUnpinning() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        fixture.materializer.pinFailure = new IOException("pin refused");

        IOException failure = assertThrows(IOException.class, () -> fixture.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals("pin refused", failure.getMessage());
        assertTrue(fixture.registry.projects.isEmpty());
        assertEquals(0, fixture.materializer.unpinned.get(), "nothing was pinned");
        assertEquals(0, fixture.indexings.get());
        assertReleasedOnce(fixture);
    }

    @Test
    void aRegistrationFailureReleasesTheSourceAndTheLease() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        fixture.registry.registrationFailure = new IOException("registry unavailable");

        IOException failure = assertThrows(IOException.class, () -> fixture.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals("registry unavailable", failure.getMessage());
        assertEquals(0, fixture.materializer.pinned.get());
        assertReleasedOnce(fixture);
    }

    @Test
    void aLeaseThatCannotBeTakenReleasesTheSourceAndRegistersNothing() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        Files.createDirectories(fixture.home);
        Files.writeString(fixture.home.resolve("remote-index-leases"), "not a directory");

        assertThrows(Exception.class, () -> fixture.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertTrue(fixture.registry.projects.isEmpty());
        assertEquals(0, fixture.materializer.pinned.get());
        assertEquals(1, fixture.materializer.released.get(), "the source is released exactly once");
    }

    @Test
    void aFailureOnAProjectThatWasAlreadyRegisteredKeepsItAndItsPin() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);
        fixture.registry.alreadyRegistered = true;
        fixture.indexingStep = () -> { throw new IOException("provider failed"); };

        assertThrows(IOException.class, () -> fixture.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals(1, fixture.registry.projects.size(), "a project this call did not create is kept");
        assertEquals(0, fixture.materializer.unpinned.get());
        assertReleasedOnce(fixture);
    }

    @Test
    void cleanupFailuresAreSuppressedUnderThePrimaryFailureOrThrownAfterASuccess() throws Exception {
        RemoteIndexingFixture failing = new RemoteIndexingFixture(temp.resolve("failing"));
        IOException closeFailure = new IOException("worker close failed");
        IOException releaseFailure = new IOException("release failed");
        failing.runtime.closeFailure = closeFailure;
        failing.materializer.releaseFailure = releaseFailure;
        failing.indexingStep = () -> { throw new IOException("provider failed"); };

        IOException primary = assertThrows(IOException.class, () -> failing.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals("provider failed", primary.getMessage());
        assertSame(closeFailure, primary.getSuppressed()[0]);
        assertSame(releaseFailure, closeFailure.getSuppressed()[0]);

        RemoteIndexingFixture succeeding = new RemoteIndexingFixture(temp.resolve("succeeding"));
        succeeding.runtime.closeFailure = new IOException("worker close failed");
        succeeding.runtime.evidence.put(RemoteIndexingFixture.PROVIDER,
                List.of(RemoteIndexingFixture.evidence(RemoteIndexingFixture.PROVIDER, "")));

        IOException afterSuccess = assertThrows(IOException.class, () -> succeeding.useCase().index(
                RemoteIndexingFixture.request(), "remote-demo", null, "worker-one", WorkerNetworkChoice.ALLOW));

        assertEquals("worker close failed", afterSuccess.getMessage());
        assertEquals(1, succeeding.registry.projects.size(), "a completed indexing is not rolled back");
        assertReleasedOnce(succeeding);
    }

    @Test
    void materializeDescribesTheRevisionAndReleasesIt() throws Exception {
        RemoteIndexingFixture fixture = new RemoteIndexingFixture(temp);

        RemoteMaterializationSummary summary = fixture.useCase().materialize(RemoteIndexingFixture.request());

        assertEquals("https://github.com/acme/demo.git", summary.repository());
        assertEquals(false, summary.cacheHit());
        assertEquals("2026-10-23T08:00:00Z", summary.materializedAt());
        assertEquals(1, fixture.materializer.released.get());
        assertEquals(0, fixture.materializer.pinned.get());
        assertThrows(NullPointerException.class, () -> fixture.useCase().materialize(null));
    }

    @Test
    void aRemoteIndexSummaryRequiresEvidence() {
        RemoteMaterializationSummary materialization = new RemoteMaterializationSummary("GITHUB",
                "https://github.com/acme/demo.git", "refs/heads/main", RemoteIndexingFixture.COMMIT, "/r", "/r",
                "key", false, "2026-10-23T08:00:00Z");

        assertEquals("remote indexing must expose artifact evidence", assertThrows(IllegalArgumentException.class,
                () -> new RemoteIndexSummary(materialization, "p", "demo", RemoteIndexingFixture.execution("p"),
                        List.of())).getMessage());
        assertThrows(NullPointerException.class, () -> new ArtifactEvidenceSummary("scip-java", "1", "JAVA", "w",
                "PROCESS_EPHEMERAL_WORKSPACE", "ALLOW", false, "a", "b", "k", false, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMaterializationSummary(" ", "r", "ref", "c",
                "/r", "/r", "key", false, "t"));
    }

    /** The source went back to the materializer once and the lease is free again. */
    private static void assertReleasedOnce(RemoteIndexingFixture fixture) throws IOException {
        assertEquals(1, fixture.materializer.released.get(), "the source is released exactly once");
        try (RemoteIndexLease ignored = RemoteIndexLease.acquire(fixture.home, "cache-key-1")) {
            assertTrue(Files.isDirectory(fixture.home.resolve("remote-index-leases")));
        }
    }
}

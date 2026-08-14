package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingLifecycleLeaseTest {

    @Test
    void twoLifecycleInstancesCannotExecuteSameProjectConcurrently(@TempDir Path temp) throws Exception {
        UUID projectId = UUID.randomUUID();
        Path root = Files.createDirectories(temp.resolve("project"));
        Path artifact = Files.writeString(temp.resolve("index.scip"), "fixture");
        InMemoryIndexStateStore stateStore = new InMemoryIndexStateStore();
        CountDownLatch firstExecutorStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstExecutor = new CountDownLatch(1);
        CountDownLatch bothLeaseAttemptsObserved = new CountDownLatch(2);
        AtomicInteger activeExecutors = new AtomicInteger();
        AtomicInteger maxActiveExecutors = new AtomicInteger();
        AtomicInteger secondExecutorCalls = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();

        ProjectIndexLeaseProvider delegate = ProjectIndexLeaseProvider.file(temp.resolve("minos-home"));
        ProjectIndexLeaseProvider observedLease = id -> {
            bothLeaseAttemptsObserved.countDown();
            return delegate.acquire(id);
        };

        IndexingLifecycleService first = lifecycle(
                blockingExecutor("lease-indexer", artifact, firstExecutorStarted, releaseFirstExecutor,
                        activeExecutors, maxActiveExecutors),
                stateStore, snapshots, observedLease);
        IndexingLifecycleService second = lifecycle(
                countingExecutor("lease-indexer", artifact, secondExecutorCalls,
                        activeExecutors, maxActiveExecutors),
                stateStore, snapshots, observedLease);
        IndexerNegotiationResult negotiation = negotiation("lease-indexer");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstRun = executor.submit(() -> first.execute(projectId, root, negotiation));
            assertTrue(firstExecutorStarted.await(5, TimeUnit.SECONDS));

            var secondRun = executor.submit(() -> second.execute(projectId, root, negotiation));
            assertTrue(bothLeaseAttemptsObserved.await(5, TimeUnit.SECONDS));
            assertEquals(0, secondExecutorCalls.get(),
                    "second lifecycle reached provider execution before the first lease was released");

            releaseFirstExecutor.countDown();
            assertEquals(IndexingRun.Status.SUCCEEDED, firstRun.get(10, TimeUnit.SECONDS).status());
            assertEquals(IndexingRun.Status.SUCCEEDED, secondRun.get(10, TimeUnit.SECONDS).status());
        }

        assertEquals(1, secondExecutorCalls.get());
        assertEquals(1, maxActiveExecutors.get(), "provider executions overlapped despite project lease");
    }

    private static IndexingLifecycleService lifecycle(
            IndexerExecutor executor,
            InMemoryIndexStateStore stateStore,
            AtomicInteger snapshots,
            ProjectIndexLeaseProvider leaseProvider
    ) {
        return new IndexingLifecycleService(
                List.of(executor),
                request -> "snapshot-" + snapshots.incrementAndGet(),
                (project, run, snapshot) -> { },
                stateStore,
                leaseProvider);
    }

    private static IndexerExecutor blockingExecutor(
            String id,
            Path artifact,
            CountDownLatch started,
            CountDownLatch release,
            AtomicInteger active,
            AtomicInteger maxActive
    ) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return id; }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release first provider");
                    }
                    return new IndexingArtifact(Language.JAVA, id, artifact);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
    }

    private static IndexerExecutor countingExecutor(
            String id,
            Path artifact,
            AtomicInteger calls,
            AtomicInteger active,
            AtomicInteger maxActive
    ) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return id; }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) {
                calls.incrementAndGet();
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                try {
                    return new IndexingArtifact(Language.JAVA, id, artifact);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
    }

    private static IndexerNegotiationResult negotiation(String id) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                id,
                "1.0",
                id,
                Set.of(Language.JAVA),
                Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED,
                100,
                List.of());
        return new IndexerNegotiationResult(
                List.of(new IndexerSelection(Language.JAVA, descriptor)),
                Set.of(),
                List.of());
    }
}

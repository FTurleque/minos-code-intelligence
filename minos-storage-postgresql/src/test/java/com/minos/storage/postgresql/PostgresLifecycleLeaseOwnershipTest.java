package com.minos.storage.postgresql;

import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PostgresLifecycleLeaseOwnershipTest extends PostgresTestSupport {

    @Test
    void wrongThreadCloseDoesNotPoisonLifecycleLease() throws Exception {
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(
                connections, new InMemoryIndexStateStore());
        UUID projectId = UUID.randomUUID();
        IndexStateStore.ProjectLease lease = store.acquireProjectLease(projectId);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                lease.close();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());

        lease.close();
        lease.close();
        try (IndexStateStore.ProjectLease reacquired = store.acquireProjectLease(projectId)) {
            assertNotNull(reacquired);
        }
    }
}

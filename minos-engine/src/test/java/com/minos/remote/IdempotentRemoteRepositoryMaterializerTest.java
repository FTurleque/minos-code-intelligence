package com.minos.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class IdempotentRemoteRepositoryMaterializerTest {

    @Test
    void doubleReleaseCannotReleaseAnotherAcquisition(@TempDir Path temp) throws Exception {
        CountingMaterializer delegate = new CountingMaterializer(temp);
        RemoteRepositoryMaterializer guarded = IdempotentRemoteRepositoryMaterializer.wrap(delegate);
        RemoteRepositoryRequest request = request();

        var first = guarded.materialize(request);
        var second = guarded.materialize(request);
        assertNotSame(first, second);
        assertEquals(2, delegate.active());

        guarded.release(first);
        guarded.release(first);
        assertEquals(1, delegate.active());
        assertEquals(1, delegate.releases.get());

        guarded.release(second);
        assertEquals(0, delegate.active());
        assertEquals(2, delegate.releases.get());
    }

    @Test
    void structurallyEqualForgedHandleCannotReleaseAnOwnedAcquisition(@TempDir Path temp) throws Exception {
        CountingMaterializer delegate = new CountingMaterializer(temp);
        RemoteRepositoryMaterializer guarded = IdempotentRemoteRepositoryMaterializer.wrap(delegate);
        var owned = guarded.materialize(request());
        var forged = new RemoteRepositoryMaterializer.RemoteMaterialization(
                owned.request(), owned.repositoryRoot(), owned.projectRoot(), owned.cacheKey(),
                owned.cacheHit(), owned.materializedAt());

        guarded.release(forged);
        assertEquals(1, delegate.active());
        assertEquals(0, delegate.releases.get());

        guarded.release(owned);
        assertEquals(0, delegate.active());
        assertEquals(1, delegate.releases.get());
    }

    private static RemoteRepositoryRequest request() {
        return RemoteRepositoryRequest.of("https://github.com/acme/demo", "main", "a".repeat(40), null, null);
    }

    private static final class CountingMaterializer implements RemoteRepositoryMaterializer {
        private final Path root;
        private final Map<RemoteMaterialization, Boolean> active = new IdentityHashMap<>();
        private final AtomicInteger releases = new AtomicInteger();

        private CountingMaterializer(Path root) throws Exception {
            this.root = root.resolve("repo");
            Files.createDirectories(this.root);
        }

        @Override
        public RemoteMaterialization materialize(RemoteRepositoryRequest request) {
            var value = new RemoteMaterialization(request, root, root, "cache-key", true, Instant.EPOCH);
            active.put(value, Boolean.TRUE);
            return value;
        }

        @Override
        public void release(RemoteMaterialization materialization) {
            if (active.remove(materialization) != null) releases.incrementAndGet();
        }

        private int active() { return active.size(); }
    }
}

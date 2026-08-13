package com.minos.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotentRemoteRepositoryMaterializerTest {

    @Test
    void doubleReleaseCannotReleaseAnotherAcquisition(@TempDir Path temp) throws Exception {
        CacheKeyRefcountMaterializer delegate = new CacheKeyRefcountMaterializer(temp);
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
    void structurallyEqualForgedHandleCannotMutateAnOwnedAcquisition(@TempDir Path temp) throws Exception {
        CacheKeyRefcountMaterializer delegate = new CacheKeyRefcountMaterializer(temp);
        RemoteRepositoryMaterializer guarded = IdempotentRemoteRepositoryMaterializer.wrap(delegate);
        var owned = guarded.materialize(request());
        var forged = new RemoteRepositoryMaterializer.RemoteMaterialization(
                owned.request(), owned.repositoryRoot(), owned.projectRoot(), owned.cacheKey(),
                owned.cacheHit(), owned.materializedAt());

        guarded.release(forged);
        assertThrows(IllegalArgumentException.class, () -> guarded.pin(forged));
        assertThrows(IllegalArgumentException.class, () -> guarded.unpin(forged));
        assertEquals(1, delegate.active());
        assertEquals(0, delegate.releases.get());
        assertEquals(0, delegate.pinMutations.get());

        guarded.pin(owned);
        guarded.unpin(owned);
        assertEquals(2, delegate.pinMutations.get());
        guarded.release(owned);
        assertEquals(0, delegate.active());
        assertEquals(1, delegate.releases.get());
        assertThrows(IllegalArgumentException.class, () -> guarded.pin(owned));
    }

    private static RemoteRepositoryRequest request() {
        return RemoteRepositoryRequest.of("https://github.com/acme/demo", "main", "a".repeat(40), null, null);
    }

    /** Deliberately models the vulnerable cache-key-only release contract. */
    private static final class CacheKeyRefcountMaterializer implements RemoteRepositoryMaterializer {
        private final Path root;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();
        private final AtomicInteger pinMutations = new AtomicInteger();

        private CacheKeyRefcountMaterializer(Path root) throws Exception {
            this.root = root.resolve("repo");
            Files.createDirectories(this.root);
        }

        @Override
        public RemoteMaterialization materialize(RemoteRepositoryRequest request) {
            active.incrementAndGet();
            return new RemoteMaterialization(request, root, root, "cache-key", true, Instant.EPOCH);
        }

        @Override
        public void pin(RemoteMaterialization materialization) {
            if ("cache-key".equals(materialization.cacheKey())) pinMutations.incrementAndGet();
        }

        @Override
        public void unpin(RemoteMaterialization materialization) {
            if ("cache-key".equals(materialization.cacheKey())) pinMutations.incrementAndGet();
        }

        @Override
        public void release(RemoteMaterialization materialization) {
            if ("cache-key".equals(materialization.cacheKey()) && active.get() > 0) {
                active.decrementAndGet();
                releases.incrementAndGet();
            }
        }

        private int active() { return active.get(); }
    }
}

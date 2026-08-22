package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedCacheLeaseRegistryTest {

    private static SharedCacheLeaseRegistry registry(Path root) {
        return new SharedCacheLeaseRegistry(root, Duration.ofSeconds(5), "test cache");
    }

    @Test
    void acquireCreatesTheLeaseFileAndReleaseDropsThePin(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry = registry(root);
        registry.acquire("entry");
        assertTrue(registry.isHeld("entry"));
        assertTrue(Files.isRegularFile(root.resolve("entry.lease")));
        registry.release("entry");
        assertFalse(registry.isHeld("entry"));
    }

    @Test
    void leasesAreReferenceCountedSoNestedHoldersDoNotReleaseEachOther(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry = registry(root);
        registry.acquire("entry");
        registry.acquire("entry");

        registry.release("entry");
        assertTrue(registry.isHeld("entry"), "the outer holder still pins the entry");

        registry.release("entry");
        assertFalse(registry.isHeld("entry"));
    }

    @Test
    void releasingAKeyThatIsNotHeldIsANoOpSoCleanupCanRunUnconditionally(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry = registry(root);
        registry.release("never-acquired");
        assertFalse(registry.isHeld("never-acquired"));
    }

    @Test
    void aPinnedEntryIsNeverEvictable(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry = registry(root);
        registry.acquire("entry");
        assertNull(registry.tryAcquireEviction("entry"), "a pinned entry must not be evictable");
        registry.release("entry");
    }

    @Test
    void anUnpinnedEntryIsEvictableAndTheLeaseIsReleasedOnClose(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry = registry(root);
        try (SharedCacheLeaseRegistry.EvictionLease lease = registry.tryAcquireEviction("entry")) {
            assertNotNull(lease);
        }
        // The eviction lease is gone, so the entry can be pinned again.
        registry.acquire("entry");
        assertTrue(registry.isHeld("entry"));
        registry.release("entry");
    }

    @Test
    void evictionIsExclusiveWithinTheJvm(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry = registry(root);
        try (SharedCacheLeaseRegistry.EvictionLease first = registry.tryAcquireEviction("entry")) {
            assertNotNull(first);
            assertNull(registry.tryAcquireEviction("entry"),
                    "a second eviction of the same entry must not be granted");
        }
    }

    @Test
    void acquisitionIsBoundedRatherThanBlockingForeverOnAContendedEntry(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry =
                new SharedCacheLeaseRegistry(root, Duration.ofMillis(300), "test cache");
        // An eviction lease holds the OS lock through a different channel, so the registry must
        // time out instead of waiting indefinitely.
        try (SharedCacheLeaseRegistry.EvictionLease held = registry.tryAcquireEviction("entry")) {
            assertNotNull(held);
            long startedAt = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> registry.acquire("entry"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
            assertTrue(elapsedMillis < 30_000L, "acquisition must fail fast, took " + elapsedMillis + "ms");
        }
    }

    @Test
    void aFailedAcquisitionLeavesNoPinBehind(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry =
                new SharedCacheLeaseRegistry(root, Duration.ofMillis(200), "test cache");
        try (SharedCacheLeaseRegistry.EvictionLease held = registry.tryAcquireEviction("entry")) {
            assertNotNull(held);
            assertThrows(IOException.class, () -> registry.acquire("entry"));
        }
        assertFalse(registry.isHeld("entry"), "a timed-out acquisition must not register a lease");
        registry.acquire("entry");
        registry.release("entry");
    }

    @Test
    void leaseKeysCannotEscapeTheLeaseDirectory(@TempDir Path root) {
        SharedCacheLeaseRegistry registry = registry(root);
        assertThrows(IOException.class, () -> registry.acquire("../escaped"));
        assertThrows(IOException.class, () -> registry.tryAcquireEviction("nested/key"));
    }

    @Test
    void blankKeysAndNonPositiveTimeoutsAreRejected(@TempDir Path root) {
        SharedCacheLeaseRegistry registry = registry(root);
        assertThrows(IllegalArgumentException.class, () -> registry.acquire(" "));
        assertThrows(IllegalArgumentException.class, () -> registry.acquire(null));
        assertThrows(IllegalArgumentException.class,
                () -> new SharedCacheLeaseRegistry(root, Duration.ZERO, "test cache"));
        assertThrows(IllegalArgumentException.class,
                () -> new SharedCacheLeaseRegistry(root, Duration.ofSeconds(-1), "test cache"));
    }

    @Test
    void areferenceTakenOnOneThreadIsVisibleToAnother(@TempDir Path root) throws Exception {
        SharedCacheLeaseRegistry registry = registry(root);
        registry.acquire("entry");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> observed = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                observed.set(registry.tryAcquireEviction("entry"));
            } catch (IOException failure) {
                observed.set(failure);
            } finally {
                done.countDown();
            }
        });
        other.start();
        assertTrue(done.await(10, TimeUnit.SECONDS), "eviction probe must not block");
        assertNull(observed.get(), "another thread must also see the entry as pinned");

        registry.release("entry");
        assertEquals(0L, done.getCount());
    }
}

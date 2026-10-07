package com.minos.storage.local.store;

import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantState;
import com.minos.io.PrivateLocalStorage;
import com.minos.testsupport.DerivedTenantKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S5/S6: the tenant lock is bounded, its file is owner-only and never followed through a link. */
class FileHostedControlPlaneStoreLockTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

    private static FileHostedControlPlaneStore store(Path root, Duration lockTimeout) throws IOException {
        return new FileHostedControlPlaneStore(
                root, DerivedTenantKeys.provider(),
                FileHostedControlPlaneStore.DEFAULT_MAX_TENANT_BYTES, new SecureRandom(), lockTimeout);
    }

    @Test
    void aTenantLockHeldElsewhereFailsClosedAfterTheDeadlineWithoutLeakingAPath(@TempDir Path root)
            throws Exception {
        UUID tenant = UUID.randomUUID();
        FileHostedControlPlaneStore store = store(root, Duration.ofMillis(300));
        try (FileChannel holder = FileChannel.open(
                root.resolve(tenant + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = holder.lock()) {
            long started = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> store.find(tenant));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
            assertFalse(failure.getMessage().contains(root.toString()), "no absolute path in the message");
            assertTrue(waitedMillis < 5_000);
        }
    }

    @Test
    void aWriterWaitsForTheHolderThenProceedsInsteadOfFailingImmediately(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        FileHostedControlPlaneStore store = store(root, Duration.ofSeconds(10));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (FileChannel holder = FileChannel.open(
                root.resolve(tenant + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = holder.lock();
            Future<?> creation = pool.submit(() -> {
                store.create(state(tenant));
                return null;
            });
            Thread.sleep(300);
            assertFalse(creation.isDone(), "the creation waits for the lock holder");
            held.release();
            creation.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(tenant, store.find(tenant).orElseThrow().tenantId());
    }

    @Test
    void theLockFileAndTheTenantFileAreOwnerOnly(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        FileHostedControlPlaneStore store = store(root, Duration.ofSeconds(10));
        store.create(state(tenant));

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED,
                PrivateLocalStorage.privacyOf(root.resolve(tenant + ".lock")));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED,
                PrivateLocalStorage.privacyOf(root.resolve(tenant + ".mht")));
    }

    @Test
    void aLockFileReplacedByASymbolicLinkIsRefusedNotFollowed(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        Path outside = Files.writeString(root.resolve("outside.txt"), "outside");
        Path store = Files.createDirectories(root.resolve("store"));
        Files.createSymbolicLink(store.resolve(tenant + ".lock"), outside);
        FileHostedControlPlaneStore hosted = store(store, Duration.ofSeconds(10));

        assertThrows(IOException.class, () -> hosted.create(state(tenant)));
        assertEquals("outside", Files.readString(outside));
    }

    private static HostedTenantState state(UUID tenant) {
        HostedPrincipal owner = new HostedPrincipal("owner", "Owner Display", HostedRole.OWNER, NOW);
        return new HostedTenantState(tenant, "Tenant", "primary", 0, NOW, NOW,
                HostedRetentionPolicy.defaults(), List.of(owner), List.of(), 0,
                HostedAuditEvent.GENESIS_HASH, List.of());
    }
}

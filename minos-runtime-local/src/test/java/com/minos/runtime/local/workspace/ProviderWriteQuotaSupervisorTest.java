package com.minos.runtime.local.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProviderWriteQuotaSupervisorTest {

    private static final Duration FAST_SAMPLE = Duration.ofMillis(20L);
    private static final Duration SAMPLE_DEADLINE = Duration.ofSeconds(10L);

    @Test
    void aProviderThatFillsTheDiskIsDetectedAndTheJobIsDestroyed(@TempDir Path workspace) throws Exception {
        AtomicInteger kills = new AtomicInteger();
        byte[] payload = new byte[16 * 1024];
        try (ProviderWriteQuotaSupervisor supervisor = ProviderWriteQuotaSupervisor.start(
                Set.of(workspace),
                new ProviderWriteQuota(64L * 1024L, 1_000_000L, FAST_SAMPLE),
                kills::incrementAndGet)) {
            for (int index = 0; index < 64 && supervisor.breach().isEmpty(); index++) {
                Files.write(workspace.resolve("payload-" + index + ".bin"), payload);
                Thread.sleep(20L);
            }
            assertTrue(awaitBreach(supervisor), "byte quota breach must be observed during execution");
        }
        assertEquals(1, kills.get(), "the OS job boundary must be destroyed exactly once on breach");
    }

    @Test
    void aProviderThatExhaustsInodesIsDetectedAndTheJobIsDestroyed(@TempDir Path workspace) throws Exception {
        AtomicInteger kills = new AtomicInteger();
        try (ProviderWriteQuotaSupervisor supervisor = ProviderWriteQuotaSupervisor.start(
                Set.of(workspace),
                new ProviderWriteQuota(1L << 40, 32L, FAST_SAMPLE),
                kills::incrementAndGet)) {
            for (int index = 0; index < 512 && supervisor.breach().isEmpty(); index++) {
                Files.writeString(workspace.resolve("entry-" + index), "x", StandardCharsets.UTF_8);
                if (index % 32 == 0) Thread.sleep(20L);
            }
            assertTrue(awaitBreach(supervisor), "entry quota breach must be observed during execution");
        }
        assertEquals(1, kills.get());
    }

    @Test
    void aProviderStayingInsideItsBudgetIsNeverKilled(@TempDir Path workspace) throws Exception {
        AtomicInteger kills = new AtomicInteger();
        Files.writeString(workspace.resolve("index.scip"), "artifact", StandardCharsets.UTF_8);
        try (ProviderWriteQuotaSupervisor supervisor = ProviderWriteQuotaSupervisor.start(
                Set.of(workspace),
                new ProviderWriteQuota(1L << 30, 10_000L, FAST_SAMPLE),
                kills::incrementAndGet)) {
            awaitTwoSamples(supervisor, SAMPLE_DEADLINE);
            assertTrue(supervisor.breach().isEmpty(), "a compliant provider must not be terminated");
        }
        assertEquals(0, kills.get());
    }

    @Test
    void nestedRootsAreAccountedOnceAndSubRootsNeverDoubleCount(@TempDir Path workspace) throws Exception {
        Path nested = Files.createDirectories(workspace.resolve("run"));
        Files.write(nested.resolve("payload.bin"), new byte[8 * 1024]);
        try (ProviderWriteQuotaSupervisor supervisor = ProviderWriteQuotaSupervisor.start(
                Set.of(workspace, nested),
                new ProviderWriteQuota(1L << 30, 10_000L, FAST_SAMPLE),
                () -> {
                })) {
            awaitTwoSamples(supervisor, SAMPLE_DEADLINE);
            assertTrue(supervisor.observedBytes() >= 8L * 1024L);
            assertTrue(supervisor.observedBytes() < 16L * 1024L,
                    "a nested writable root must not be measured twice: " + supervisor.observedBytes());
        }
    }

    @Test
    void aSupervisorThatNeverSamplesFailsTheSampleWait(@TempDir Path workspace) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ProviderWriteQuotaSupervisor.RootSampler blocked = (root, quota) -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ProviderWriteQuotaSupervisor.RootSample(0L, 0L, null);
        };
        try (ProviderWriteQuotaSupervisor supervisor = ProviderWriteQuotaSupervisor.startForTest(
                Set.of(workspace),
                new ProviderWriteQuota(1L << 30, 10_000L, FAST_SAMPLE),
                () -> {
                },
                blocked)) {
            try {
                AssertionError error = assertThrows(AssertionError.class,
                        () -> awaitTwoSamples(supervisor, Duration.ofMillis(200L)));
                assertTrue(error.getMessage().contains("missing samples before the deadline: sampleCount=0"),
                        error.getMessage());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void lossOfVisibilityIsAContainmentBreach(@TempDir Path workspace) throws Exception {
        assumeTrue(Files.getFileStore(workspace).supportsFileAttributeView("posix"),
                "POSIX permissions are required for the unreadable-subtree adversarial fixture");
        AtomicInteger kills = new AtomicInteger();
        Path hidden = Files.createDirectory(workspace.resolve("hidden"));
        Path payload = hidden.resolve("payload.bin");

        try (OutputStream alreadyOpen = Files.newOutputStream(payload);
             ProviderWriteQuotaSupervisor supervisor = ProviderWriteQuotaSupervisor.start(
                     Set.of(workspace),
                     new ProviderWriteQuota(1L << 30, 10_000L, FAST_SAMPLE),
                     kills::incrementAndGet)) {
            // Simulate a hostile descendant that keeps an already-open descriptor but removes the
            // host supervisor's ability to enumerate the directory containing it.
            Files.setPosixFilePermissions(hidden, Set.of());
            alreadyOpen.write(new byte[64 * 1024]);
            alreadyOpen.flush();

            assertTrue(awaitBreach(supervisor),
                    "losing visibility of a writable subtree must fail closed instead of counting zero bytes");
            assertTrue(supervisor.breach().orElseThrow().contains("visibility lost"));
        } finally {
            Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("rwx------"));
        }
        assertEquals(1, kills.get(), "visibility loss must destroy the contained job exactly once");
    }

    /**
     * Waits for two counted samples: the first one may have started before the test wrote anything, the second
     * one is a complete sample taken after the supervisor started. Fails at the deadline.
     */
    private static void awaitTwoSamples(ProviderWriteQuotaSupervisor supervisor, Duration deadline)
            throws InterruptedException {
        long expiresAt = System.nanoTime() + deadline.toNanos();
        while (supervisor.sampleCount() < 2L) {
            if (System.nanoTime() - expiresAt > 0L) {
                throw new AssertionError("missing samples before the deadline: sampleCount="
                        + supervisor.sampleCount() + " after " + deadline.toMillis() + " ms");
            }
            Thread.sleep(5L);
        }
    }

    private static boolean awaitBreach(ProviderWriteQuotaSupervisor supervisor) throws InterruptedException {
        for (int poll = 0; poll < 200; poll++) {
            if (supervisor.breach().isPresent()) return true;
            Thread.sleep(20L);
        }
        return false;
    }
}

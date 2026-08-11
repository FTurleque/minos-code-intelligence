package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderWriteQuotaSupervisorTest {

    private static final Duration FAST_SAMPLE = Duration.ofMillis(20L);

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
            Thread.sleep(200L);
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
            Thread.sleep(200L);
            assertTrue(supervisor.observedBytes() >= 8L * 1024L);
            assertTrue(supervisor.observedBytes() < 16L * 1024L,
                    "a nested writable root must not be measured twice: " + supervisor.observedBytes());
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

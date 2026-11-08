package com.minos.runtime.local.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JVM run by a provider in the Linux sandbox sizes its heap and its threads on the job limits: with the host
 * memory, its reservations alone reach the per-process address space limit and a later allocation fails.
 */
class LinuxSandboxJvmBoundsTest {

    private static final long JOB = LinuxBubblewrapWorkerSandboxBackend.MAX_JOB_MEMORY_BYTES;
    private static final int JOB_CPUS = (int) (LinuxBubblewrapWorkerSandboxBackend.MAX_CPU_MICROS_PER_PERIOD
            / LinuxCgroupJob.CPU_PERIOD_MICROS);

    @Test
    void theJvmsSeeTheJobMemoryAndAtMostTheJobProcessors() {
        assertEquals("-XX:MaxRAM=" + JOB + " -XX:ActiveProcessorCount=" + JOB_CPUS,
                LinuxBubblewrapWorkerSandboxBackend.boundedJavaToolOptions(null, 16));
        assertEquals("-XX:MaxRAM=" + JOB + " -XX:ActiveProcessorCount=2",
                LinuxBubblewrapWorkerSandboxBackend.boundedJavaToolOptions("", 2),
                "a smaller host keeps its own processor count");
    }

    @Test
    void declaredOptionsAreKeptAndTheBoundsComeLastSoTheyWin() {
        String options = LinuxBubblewrapWorkerSandboxBackend.boundedJavaToolOptions("-Dfile.encoding=UTF-8 -XX:MaxRAM=64g", 16);

        assertTrue(options.startsWith("-Dfile.encoding=UTF-8 -XX:MaxRAM=64g "), options);
        assertTrue(options.endsWith("-XX:MaxRAM=" + JOB + " -XX:ActiveProcessorCount=" + JOB_CPUS), options);
    }
}

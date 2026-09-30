package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real kernel, real JDK: a MINOS process that has run out of file descriptors cannot read {@code /proc}.
 * The JDK then answers that <em>no</em> process exists ({@code ProcessHandle.of} is empty even for the
 * caller itself), which is not the death of the owners that MINOS instance is sweeping after. The
 * lookup must say it cannot verify, never that the owner is gone.
 *
 * <p>The exhaustion happens in a child JVM started under a low {@code ulimit -n}
 * ({@link OwnerLookupDescriptorExhaustionProbe}); the test JVM is never short of descriptors.</p>
 */
class OwnerLookupDescriptorExhaustionTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    void aLiveProcessIsNeverReportedGoneBecauseTheTableCouldNotBeRead(@TempDir Path cgroup) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.real.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(List.of(
                        "sh", "-c", "ulimit -n 160 && exec \"$@\"", "sh", java, "-cp", classPath,
                        OwnerLookupDescriptorExhaustionProbe.class.getName(), cgroup.toString(), "2147000001"))
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(120, TimeUnit.SECONDS), output);

        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("BEFORE=PRESENT"), "with descriptors available the process is seen: " + output);
        assertTrue(output.contains("EXHAUSTED_AFTER="), "the probe must have run out of descriptors: " + output);
        assertNotEquals("GONE", value(output, "LIVE_SELF"),
                "the caller itself is alive: an unreadable table must not say otherwise: " + output);
        assertNotEquals("GONE", value(output, "LIVE_INIT"),
                "init is alive: an unreadable table must not say otherwise: " + output);
    }

    private static String value(String output, String key) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        throw new AssertionError(key + " missing from the probe output: " + output);
    }
}

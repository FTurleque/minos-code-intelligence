package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.Language;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol minos-wsl-worker-v1: mount paths, request round trip and the refusal of a probe report. */
class WslWorkerProtocolTest {

    @Test
    void aLocalDrivePathMapsToItsMountAndOtherPathsAreRefused() {
        assertEquals("/mnt/c/Users/dev/project", WslPaths.toDistribution("C:\\Users\\dev\\project\\"));
        assertEquals("/mnt/d", WslPaths.toDistribution("D:\\"));
        assertEquals("/mnt/n/work/app", WslPaths.toDistribution("N:/work/app"));
        for (String refused : new String[]{"\\\\server\\share\\project", "project", "C:\\a\\..\\b", "C:\\a\\\\b",
                "/home/user/project", null}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> WslPaths.toDistribution(refused));
            assertFalse(refusal.getMessage().contains("server"), refusal.getMessage());
        }
    }

    @Test
    void distributionPathsAndScopesAreValidated() {
        assertEquals("/mnt/c/project", WslPaths.requireDistributionPath("/mnt/c/project"));
        for (String invalid : new String[]{null, "", "relative", "/a/../b", "/a//b", "/a\\b"}) {
            assertThrows(IllegalArgumentException.class, () -> WslPaths.requireDistributionPath(invalid));
        }
        assertEquals("", WslPaths.requireRelativeScope(null));
        assertEquals("", WslPaths.requireRelativeScope("."));
        assertEquals("module/a", WslPaths.requireRelativeScope("module/a/"));
        for (String invalid : new String[]{"/module", "../escape", "a\\b", "a//b"}) {
            assertThrows(IllegalArgumentException.class, () -> WslPaths.requireRelativeScope(invalid));
        }
    }

    @Test
    void aRequestRoundTripsAndAnyOtherProtocolOrFieldIsRefused() throws IOException {
        WslWorkerRequest request = request("module");
        byte[] encoded = request.encode();
        assertEquals(request, WslWorkerRequest.decode(encoded));
        String text = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(text.contains("protocol=" + WslWorkerRequest.PROTOCOL), text);

        IOException protocol = assertThrows(IOException.class, () -> WslWorkerRequest.decode(
                text.replace(WslWorkerRequest.PROTOCOL, "minos-wsl-worker-v9").getBytes(StandardCharsets.UTF_8)));
        assertTrue(protocol.getMessage().startsWith("unsupported WSL worker protocol"));
        assertThrows(IOException.class, () -> WslWorkerRequest.decode(
                (text + "extra=value\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> WslWorkerRequest.decode(
                text.replace("language=JAVA", "language=COBOL").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aRequestRefusesUnsafeIdentifiersAndAnotherWorker() {
        assertThrows(IllegalArgumentException.class, () -> new WslWorkerRequest(UUID.randomUUID(), UUID.randomUUID(),
                "/mnt/c/p", "", Language.JAVA, "scip java", "1", "a".repeat(64), "wsl:Ubuntu"));
        assertThrows(IllegalArgumentException.class, () -> new WslWorkerRequest(UUID.randomUUID(), UUID.randomUUID(),
                "/mnt/c/p", "", Language.JAVA, "scip-java", "1", "a".repeat(40), "wsl:Ubuntu"));
        assertThrows(IllegalArgumentException.class, () -> new WslWorkerRequest(UUID.randomUUID(), UUID.randomUUID(),
                "/mnt/c/p", "", Language.JAVA, "scip-java", "1", "a".repeat(64), "remote-worker"));
    }

    @Test
    void aProbeReportRefusesAMissingOrUnobservedHostCheckButNotAnUnknownJarCheck() {
        List<WslCheck> ready = WslCheck.IDENTIFIERS.stream().map(WslCheck::ok).toList();
        assertTrue(new WslProbeReport("Ubuntu", ready).runRefusal().isEmpty());

        List<WslCheck> jarUnknown = WslCheck.IDENTIFIERS.stream()
                .map(id -> List.of(WslCheck.MAVEN, WslCheck.PROJECT_JDK, WslCheck.MAVEN_REPOSITORY).contains(id)
                        ? WslCheck.unknown(id, "checked by the worker") : WslCheck.ok(id))
                .toList();
        assertTrue(new WslProbeReport("Ubuntu", jarUnknown).runRefusal().isEmpty(),
                "the worker applies the jar-level checks itself");

        List<WslCheck> bubblewrap = WslCheck.IDENTIFIERS.stream()
                .map(id -> WslCheck.BUBBLEWRAP.equals(id)
                        ? WslCheck.missing(id, "install bubblewrap in the distribution") : WslCheck.ok(id))
                .toList();
        assertEquals("WSL route prerequisite bubblewrap is missing: install bubblewrap in the distribution",
                new WslProbeReport("Ubuntu", bubblewrap).runRefusal().orElseThrow());

        List<WslCheck> systemdUnknown = WslCheck.IDENTIFIERS.stream()
                .map(id -> WslCheck.SYSTEMD.equals(id) ? WslCheck.unknown(id, "rerun") : WslCheck.ok(id))
                .toList();
        assertEquals("WSL route prerequisite systemd is unknown: rerun",
                new WslProbeReport("Ubuntu", systemdUnknown).runRefusal().orElseThrow());
    }

    private static WslWorkerRequest request(String scope) {
        return new WslWorkerRequest(UUID.randomUUID(), UUID.randomUUID(), "/mnt/c/project", scope, Language.JAVA,
                "scip-java", "0.13.2", "a".repeat(64), WslIndexWorker.WORKER_ID_PREFIX + "Ubuntu");
    }
}

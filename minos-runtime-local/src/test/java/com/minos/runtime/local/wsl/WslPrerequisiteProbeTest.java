package com.minos.runtime.local.wsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WslPrerequisiteProbeTest {

    private static final Path WSL = Path.of("wsl.exe");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(^|[\\s'\"(=])(/[A-Za-z]|[A-Za-z]:[\\\\/]|\\\\\\\\)");

    private static final String LIST_HEADER = "  NAME      STATE           VERSION\r\n";
    private static final String HEALTHY_DISTRIBUTION = """
            systemd=yes
            userbus=yes
            cgroup2=yes
            controllers=cpuset cpu io memory hugetlb pids rdma
            bwrap=yes
            jvm=java.specification.version = 21
            end=ok
            """;

    /** Fake wsl.exe: the listing answers {@code -l -v}, the distribution output answers the constant script. */
    private static final class FakeWsl implements WslCommandRunner {
        private final byte[] listing;
        private final int listingExit;
        private final String distributionOutput;
        private final List<List<String>> commands = new ArrayList<>();

        FakeWsl(byte[] listing, int listingExit, String distributionOutput) {
            this.listing = listing;
            this.listingExit = listingExit;
            this.distributionOutput = distributionOutput;
        }

        @Override
        public WslCommandResult run(List<String> command, Duration timeout) throws IOException {
            commands.add(List.copyOf(command));
            if (command.contains("-l")) {
                return new WslCommandResult(listingExit, listing);
            }
            if (distributionOutput == null) {
                throw new IOException("C:\\Users\\someone\\secret path failure");
            }
            return new WslCommandResult(0, distributionOutput.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] utf16(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        return withBom;
    }

    private static byte[] listing(String rows) {
        return utf16(LIST_HEADER + rows);
    }

    private static WslPrerequisiteProbe probe(FakeWsl wsl) {
        return new WslPrerequisiteProbe(WSL, wsl, WslJarProbe.NOT_AVAILABLE);
    }

    private static WslCheck check(WslProbeReport report, String id) {
        return report.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertNoAbsolutePath(WslProbeReport report) {
        for (WslCheck check : report.checks()) {
            assertFalse(ABSOLUTE_PATH.matcher(check.action()).find(), check.id() + ": " + check.action());
        }
    }

    @Test
    void everyContractIdentifierIsReportedInOrder() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu    Running         2\r\n"), 0, HEALTHY_DISTRIBUTION))
                .probe(null);

        assertEquals(List.of("wsl-version", "systemd", "cgroup-delegation", "bubblewrap", "minos-jvm",
                "maven", "project-jdk", "maven-repository"), report.checks().stream().map(WslCheck::id).toList());
        assertEquals("Ubuntu", report.distribution().orElseThrow());
        for (String id : List.of("wsl-version", "systemd", "cgroup-delegation", "bubblewrap", "minos-jvm")) {
            assertEquals(WslCheckState.OK, check(report, id).state(), id);
            assertEquals("", check(report, id).action(), id);
        }
        assertNoAbsolutePath(report);
    }

    @Test
    void theJarStepIsAnExtensionPointReportedUnknownUntilProvided() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu    Running         2\r\n"), 0, HEALTHY_DISTRIBUTION))
                .probe(null);

        for (String id : List.of("maven", "project-jdk", "maven-repository")) {
            assertEquals(WslCheckState.UNKNOWN, check(report, id).state(), id);
        }
        assertFalse(report.ready(), "UNKNOWN never counts as ready");
        assertTrue(report.firstMissing().isEmpty());
    }

    @Test
    void aProvidedJarProbeCompletesTheReport() {
        WslJarProbe jar = distribution -> List.of(
                new WslCheck("maven", WslCheckState.OK, ""),
                new WslCheck("project-jdk", WslCheckState.OK, ""),
                new WslCheck("maven-repository", WslCheckState.MISSING, "designate the Maven repository"),
                new WslCheck("bubblewrap", WslCheckState.MISSING, "ignored: not a jar check"));
        WslProbeReport report = new WslPrerequisiteProbe(WSL,
                new FakeWsl(listing("* Ubuntu    Running         2\r\n"), 0, HEALTHY_DISTRIBUTION), jar).probe(null);

        assertEquals(WslCheckState.OK, check(report, "bubblewrap").state());
        assertEquals(WslCheckState.OK, check(report, "maven").state());
        assertEquals("maven-repository", report.firstMissing().orElseThrow().id());
        assertFalse(report.ready());
    }

    @Test
    void allChecksOkIsReady() {
        WslJarProbe jar = distribution -> List.of(
                new WslCheck("maven", WslCheckState.OK, ""),
                new WslCheck("project-jdk", WslCheckState.OK, ""),
                new WslCheck("maven-repository", WslCheckState.OK, ""));
        WslProbeReport report = new WslPrerequisiteProbe(WSL,
                new FakeWsl(listing("* Ubuntu    Running         2\r\n"), 0, HEALTHY_DISTRIBUTION), jar).probe(null);

        assertTrue(report.ready());
    }

    @Test
    void noWslExecutableNamesWsl2AndRunsNothing() {
        FakeWsl wsl = new FakeWsl(new byte[0], 0, HEALTHY_DISTRIBUTION);
        WslProbeReport report = new WslPrerequisiteProbe(null, wsl, WslJarProbe.NOT_AVAILABLE).probe(null);

        WslCheck version = check(report, "wsl-version");
        assertEquals(WslCheckState.MISSING, version.state());
        assertTrue(version.action().contains("WSL2"), version.action());
        assertTrue(wsl.commands.isEmpty());
        assertTrue(report.distribution().isEmpty());
        assertNoAbsolutePath(report);
    }

    @Test
    void noDistributionInstalledNamesWsl2() {
        FakeWsl wsl = new FakeWsl(utf16("Windows Subsystem for Linux has no installed distributions.\r\n"), 1,
                HEALTHY_DISTRIBUTION);
        WslProbeReport report = probe(wsl).probe(null);

        WslCheck version = check(report, "wsl-version");
        assertEquals(WslCheckState.MISSING, version.state());
        assertTrue(version.action().contains("WSL2"), version.action());
        assertEquals(1, wsl.commands.size(), "nothing is launched in a distribution");
        for (WslCheck later : report.checks().subList(1, report.checks().size())) {
            assertEquals(WslCheckState.UNKNOWN, later.state(), later.id());
        }
        assertNoAbsolutePath(report);
    }

    @Test
    void wsl1DistributionNamesVersion2AndLaunchesNothingInside() {
        FakeWsl wsl = new FakeWsl(listing("* Ubuntu    Running   2\r\n  Legacy    Stopped   1\r\n"), 0,
                HEALTHY_DISTRIBUTION);
        WslProbeReport report = probe(wsl).probe("Legacy");

        WslCheck version = check(report, "wsl-version");
        assertEquals(WslCheckState.MISSING, version.state());
        assertTrue(version.action().contains("version 2"), version.action());
        assertEquals("Legacy", report.distribution().orElseThrow());
        assertEquals(1, wsl.commands.size(), "no process is started in the distribution");
        assertNoAbsolutePath(report);
    }

    @Test
    void unknownRequestedDistributionIsMissing() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu    Running   2\r\n"), 0, HEALTHY_DISTRIBUTION))
                .probe("Debian");

        assertEquals(WslCheckState.MISSING, check(report, "wsl-version").state());
    }

    @Test
    void noDefaultDistributionIsMissing() {
        WslProbeReport report = probe(new FakeWsl(listing("  Ubuntu    Running   2\r\n"), 0, HEALTHY_DISTRIBUTION))
                .probe(" ");

        assertEquals(WslCheckState.MISSING, check(report, "wsl-version").state());
        assertTrue(report.distribution().isEmpty());
    }

    @Test
    void localizedListingWithMultiWordStateIsParsed() {
        WslProbeReport report = probe(new FakeWsl(
                utf16("  NOM      ÉTAT                     VERSION\r\n* Ubuntu   En cours d'exécution     2\r\n"), 0,
                HEALTHY_DISTRIBUTION)).probe(null);

        assertEquals(WslCheckState.OK, check(report, "wsl-version").state());
    }

    @Test
    void invalidDistributionNameIsRefusedBeforeAnyCommand() {
        FakeWsl wsl = new FakeWsl(listing("* Ubuntu    Running   2\r\n"), 0, HEALTHY_DISTRIBUTION);
        WslPrerequisiteProbe probe = probe(wsl);

        for (String hostile : List.of("Ubuntu; rm -rf ~", "-d", "../x", "a b", "x".repeat(65), "Ubu\"ntu")) {
            assertThrows(IllegalArgumentException.class, () -> probe.probe(hostile), hostile);
        }
        assertTrue(wsl.commands.isEmpty());
    }

    @Test
    void theDistributionCommandIsConstantAndOnlyCarriesTheValidatedName() {
        FakeWsl wsl = new FakeWsl(listing("* Ubuntu-24.04    Running   2\r\n"), 0, HEALTHY_DISTRIBUTION);
        probe(wsl).probe("Ubuntu-24.04");

        assertEquals(List.of("wsl.exe", "-l", "-v"), wsl.commands.get(0));
        List<String> inside = wsl.commands.get(1);
        assertEquals(List.of("wsl.exe", "-d", "Ubuntu-24.04", "--exec", "/bin/sh", "-c",
                WslPrerequisiteProbe.DISTRIBUTION_SCRIPT), inside);
        assertFalse(WslPrerequisiteProbe.DISTRIBUTION_SCRIPT.contains("\""),
                "no double quote: Windows argument quoting would alter it");
        assertFalse(WslPrerequisiteProbe.DISTRIBUTION_SCRIPT.endsWith("\\"));
    }

    @Test
    void systemdInactiveIsMissing() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0,
                HEALTHY_DISTRIBUTION.replace("systemd=yes", "systemd=no"))).probe(null);

        WslCheck systemd = check(report, "systemd");
        assertEquals(WslCheckState.MISSING, systemd.state());
        assertTrue(systemd.action().contains("systemd"), systemd.action());
        assertNoAbsolutePath(report);
        assertEquals(WslCheckState.UNKNOWN, check(report, "maven").state(), "the jar step is not reached");
    }

    @Test
    void missingUserBusIsReportedOnSystemd() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0,
                HEALTHY_DISTRIBUTION.replace("userbus=yes", "userbus=no"))).probe(null);

        WslCheck systemd = check(report, "systemd");
        assertEquals(WslCheckState.MISSING, systemd.state());
        assertTrue(systemd.action().contains("XDG_RUNTIME_DIR"), systemd.action());
    }

    @Test
    void nonDelegableControllersAreMissingAndNamed() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0,
                HEALTHY_DISTRIBUTION.replace("controllers=cpuset cpu io memory hugetlb pids rdma",
                        "controllers=cpuset io memory"))).probe(null);

        WslCheck delegation = check(report, "cgroup-delegation");
        assertEquals(WslCheckState.MISSING, delegation.state());
        assertTrue(delegation.action().contains("pids"), delegation.action());
        assertTrue(delegation.action().contains("cpu"), delegation.action());
        assertNoAbsolutePath(report);
    }

    @Test
    void cgroupV1IsMissing() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0,
                HEALTHY_DISTRIBUTION.replace("cgroup2=yes", "cgroup2=no"))).probe(null);

        WslCheck delegation = check(report, "cgroup-delegation");
        assertEquals(WslCheckState.MISSING, delegation.state());
        assertTrue(delegation.action().contains("cgroup v2"), delegation.action());
    }

    @Test
    void bubblewrapAbsentIsMissingWithoutPath() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0,
                HEALTHY_DISTRIBUTION.replace("bwrap=yes", "bwrap=no"))).probe(null);

        WslCheck bubblewrap = check(report, "bubblewrap");
        assertEquals(WslCheckState.MISSING, bubblewrap.state());
        assertEquals("install bubblewrap in the distribution", bubblewrap.action());
        assertEquals("bubblewrap", report.firstMissing().orElseThrow().id());
        assertNoAbsolutePath(report);
    }

    @Test
    void jvmAbsentOrTooOldIsMissing() {
        for (String jvm : List.of("jvm=", "jvm=java.specification.version = 17", "jvm=java.specification.version = 1.8")) {
            WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0,
                    HEALTHY_DISTRIBUTION.replace("jvm=java.specification.version = 21", jvm))).probe(null);

            WslCheck java = check(report, "minos-jvm");
            assertEquals(WslCheckState.MISSING, java.state(), jvm);
            assertTrue(java.action().contains("21"), java.action());
        }
    }

    @Test
    void newerJvmIsAccepted() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0,
                HEALTHY_DISTRIBUTION.replace("= 21", "= 25"))).probe(null);

        assertEquals(WslCheckState.OK, check(report, "minos-jvm").state());
    }

    @Test
    void unreadableDistributionOutputIsUnknownNotOk() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0, "garbage\n"))
                .probe(null);

        for (String id : List.of("systemd", "cgroup-delegation", "bubblewrap", "minos-jvm")) {
            assertEquals(WslCheckState.UNKNOWN, check(report, id).state(), id);
        }
        assertFalse(report.ready());
    }

    @Test
    void failingDistributionCommandIsUnknownWithoutCause() {
        WslProbeReport report = probe(new FakeWsl(listing("* Ubuntu  Running  2\r\n"), 0, null)).probe(null);

        assertEquals(WslCheckState.UNKNOWN, check(report, "systemd").state());
        assertNoAbsolutePath(report);
        assertTrue(report.checks().stream().noneMatch(c -> c.action().contains("secret")));
    }

    @Test
    void failingListingCommandIsUnknown() {
        WslCommandRunner broken = (command, timeout) -> {
            throw new IOException("D:\\private\\wsl failure");
        };
        WslProbeReport report = new WslPrerequisiteProbe(WSL, broken, WslJarProbe.NOT_AVAILABLE).probe(null);

        assertEquals(WslCheckState.UNKNOWN, check(report, "wsl-version").state());
        assertNoAbsolutePath(report);
    }

    @Test
    void wslIsResolvedInTheSystemDirectoryOnly(@TempDir Path root) throws IOException {
        Path system32 = Files.createDirectories(root.resolve("System32"));
        Files.writeString(system32.resolve("wsl.exe"), "fake");

        Path resolved = WslPrerequisiteProbe.systemWslExecutable(Map.of("SystemRoot", root.toString())).orElseThrow();
        assertEquals(system32.resolve("wsl.exe").toRealPath(), resolved);
        assertEquals(resolved, WslPrerequisiteProbe.systemWslExecutable(
                Map.of("SYSTEMROOT", root.toString())).orElseThrow(), "Windows names ignore case");

        assertTrue(WslPrerequisiteProbe.systemWslExecutable(Map.of("PATH", system32.toString())).isEmpty(),
                "PATH is never a trust source");
        assertTrue(WslPrerequisiteProbe.systemWslExecutable(Map.of("SystemRoot", "relative")).isEmpty());
        assertTrue(WslPrerequisiteProbe.systemWslExecutable(Map.of("SystemRoot", " ")).isEmpty());
        assertTrue(WslPrerequisiteProbe.systemWslExecutable(
                Map.of("SystemRoot", root.resolve("absent").toString())).isEmpty());
    }

    @Test
    void checkRejectsUnknownIdentifiersAndOkWithAction() {
        assertThrows(IllegalArgumentException.class, () -> new WslCheck("other", WslCheckState.OK, ""));
        assertThrows(IllegalArgumentException.class, () -> new WslCheck("maven", WslCheckState.OK, "do something"));
        assertThrows(IllegalArgumentException.class, () -> new WslCheck("maven", WslCheckState.MISSING, " "));
    }
}

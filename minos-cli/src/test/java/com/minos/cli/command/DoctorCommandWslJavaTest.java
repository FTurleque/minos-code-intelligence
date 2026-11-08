package com.minos.cli.command;

import com.minos.application.WslJavaDiagnosis;
import com.minos.application.WslJavaDiagnosis.Check;
import com.minos.application.WslJavaDiagnosis.State;
import com.minos.cli.operations.AutonomousIndexOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * indexation-java-via-wsl: under Windows, {@code minos doctor} reports the prerequisites of the WSL2 route in a
 * {@code wslJava} section, in text and in JSON, without any absolute path; the section is absent under Linux.
 */
class DoctorCommandWslJavaTest {

    private static final List<String> IDENTIFIERS = List.of(
            "wsl-version", "systemd", "cgroup-delegation", "bubblewrap",
            "minos-jvm", "maven", "project-jdk", "maven-repository");

    @Test
    void aDistributionWithEveryPrerequisiteListsEachCheckOk(@TempDir Path home) throws Exception {
        WslJavaDiagnosis ready = new WslJavaDiagnosis(Optional.of("Ubuntu"),
                IDENTIFIERS.stream().map(id -> new Check(id, State.OK, "")).toList());
        DoctorCommand doctor = doctor(home, () -> Optional.of(ready));
        StringBuilder json = new StringBuilder();
        StringBuilder text = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[]{"--format", "json"}, json, new StringBuilder()));
        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[0], text, new StringBuilder()));

        assertTrue(json.toString().contains("\"wslJava\":{\"distribution\":\"Ubuntu\",\"checks\":["
                + "{\"id\":\"wsl-version\",\"state\":\"OK\"},{\"id\":\"systemd\",\"state\":\"OK\"},"
                + "{\"id\":\"cgroup-delegation\",\"state\":\"OK\"},{\"id\":\"bubblewrap\",\"state\":\"OK\"},"
                + "{\"id\":\"minos-jvm\",\"state\":\"OK\"},{\"id\":\"maven\",\"state\":\"OK\"},"
                + "{\"id\":\"project-jdk\",\"state\":\"OK\"},{\"id\":\"maven-repository\",\"state\":\"OK\"}]}"),
                json.toString());
        assertTrue(text.toString().contains("wslJava[distribution]: Ubuntu\n"), text.toString());
        for (String id : IDENTIFIERS) {
            assertTrue(text.toString().contains("wslJava[" + id + "]: OK\n"), text.toString());
        }
    }

    @Test
    void anUnmetPrerequisiteNamesItsActionWithoutAnyPathAndLeavesTheVerdictAlone(@TempDir Path home)
            throws Exception {
        WslJavaDiagnosis missing = new WslJavaDiagnosis(Optional.of("Ubuntu-24.04"), List.of(
                new Check("wsl-version", State.OK, ""),
                new Check("systemd", State.OK, ""),
                new Check("cgroup-delegation", State.OK, ""),
                new Check("bubblewrap", State.MISSING, "install bubblewrap in the distribution"),
                new Check("minos-jvm", State.UNKNOWN, "fix the previous prerequisite first"),
                new Check("maven", State.UNKNOWN, "fix the previous prerequisite first"),
                new Check("project-jdk", State.UNKNOWN, "fix the previous prerequisite first"),
                new Check("maven-repository", State.UNKNOWN, "fix the previous prerequisite first")));
        DoctorCommand doctor = doctor(home, () -> Optional.of(missing));
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        StringBuilder text = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[]{"--format", "json"}, first, new StringBuilder()));
        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[]{"--format", "json"}, second, new StringBuilder()));
        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[0], text, new StringBuilder()),
                "the WSL route is optional: an unmet prerequisite is not an ACTION_REQUIRED");

        String json = first.toString();
        assertEquals(json, second.toString(), "doctor JSON must be deterministic");
        assertTrue(json.contains("{\"id\":\"bubblewrap\",\"state\":\"MISSING\","
                + "\"action\":\"install bubblewrap in the distribution\"}"), json);
        assertTrue(json.contains("{\"id\":\"maven\",\"state\":\"UNKNOWN\","
                + "\"action\":\"fix the previous prerequisite first\"}"), json);
        assertTrue(json.contains("\"ready\":true"), json);
        assertTrue(text.toString().contains(
                "wslJava[bubblewrap]: MISSING (install bubblewrap in the distribution)\n"), text.toString());
        assertTrue(text.toString().contains("verdict: READY"), text.toString());
        assertNoPathInSection(text.toString(), home);
    }

    @Test
    void anUnresolvedDistributionIsReportedAsNone(@TempDir Path home) throws Exception {
        WslJavaDiagnosis noWsl = new WslJavaDiagnosis(Optional.empty(), IDENTIFIERS.stream()
                .map(id -> id.equals("wsl-version")
                        ? new Check(id, State.MISSING, "install WSL2 and a distribution")
                        : new Check(id, State.UNKNOWN, "fix the previous prerequisite first"))
                .toList());
        DoctorCommand doctor = doctor(home, () -> Optional.of(noWsl));
        StringBuilder json = new StringBuilder();
        StringBuilder text = new StringBuilder();

        doctor.run(new String[]{"--format", "json"}, json, new StringBuilder());
        doctor.run(new String[0], text, new StringBuilder());

        assertTrue(json.toString().contains("\"wslJava\":{\"distribution\":null,\"checks\":["
                + "{\"id\":\"wsl-version\",\"state\":\"MISSING\",\"action\":\"install WSL2 and a distribution\"}"),
                json.toString());
        assertTrue(text.toString().contains("wslJava[distribution]: NONE\n"), text.toString());
    }

    @Test
    void aHostWithoutTheRouteHasNoWslJavaSection(@TempDir Path home) throws Exception {
        DoctorCommand doctor = doctor(home, Optional::empty);
        StringBuilder json = new StringBuilder();
        StringBuilder text = new StringBuilder();

        doctor.run(new String[]{"--format", "json"}, json, new StringBuilder());
        doctor.run(new String[0], text, new StringBuilder());

        assertFalse(json.toString().contains("wslJava"), json.toString());
        assertFalse(text.toString().contains("wslJava"), text.toString());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void theRealHostHasNoWslJavaSectionUnderLinux(@TempDir Path home) throws Exception {
        StringBuilder json = new StringBuilder();

        new DoctorCommand(home, operations()).run(new String[]{"--format", "json"}, json, new StringBuilder());

        assertFalse(json.toString().contains("\"wslJava\""), json.toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theRealHostReportsEveryWslPrerequisiteUnderWindows(@TempDir Path home) throws Exception {
        StringBuilder text = new StringBuilder();

        new DoctorCommand(home, operations()).run(new String[0], text, new StringBuilder());

        assertTrue(text.toString().contains("wslJava[distribution]: "), text.toString());
        for (String id : IDENTIFIERS) {
            assertTrue(text.toString().matches("(?s).*wslJava\\[" + id + "]: (OK|MISSING|UNKNOWN).*"),
                    text.toString());
        }
        assertNoPathInSection(text.toString(), home);
    }

    private static void assertNoPathInSection(String text, Path home) {
        String section = text.lines().filter(line -> line.startsWith("wslJava["))
                .reduce("", (a, b) -> a + b + "\n");
        assertFalse(section.isEmpty(), text);
        assertFalse(section.contains(home.toString()), "no MINOS_HOME in the wslJava section: " + section);
        assertFalse(section.contains(":\\") || section.contains("\\\\") || section.matches("(?s).*[ (]/\\w.*"),
                "no absolute path in the wslJava section: " + section);
    }

    private static DoctorCommand doctor(Path home, Supplier<Optional<WslJavaDiagnosis>> wslJava) {
        DoctorCommand.WorkerSandboxReport sandbox = new DoctorCommand.WorkerSandboxReport(
                "fake-hard-backend", true, "fake-hard-backend", true, "QUALIFIED", Optional.empty(), List.of(), "");
        return new DoctorCommand(home, operations(), ignored -> sandbox, wslJava);
    }

    private static AutonomousIndexOperations operations() {
        List<AutonomousIndexOperations.ProviderView> providers = List.of(
                new AutonomousIndexOperations.ProviderView("scip-java", "1.0", "READY", null, List.of(), true));
        return new AutonomousIndexOperations() {
            @Override
            public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("plan must not be called");
            }

            @Override
            public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("execute must not be called");
            }

            @Override
            public List<ProviderView> providers() {
                return providers;
            }

            @Override
            public ProviderView installProvider(String providerId) {
                throw new AssertionError("installProvider must not be called");
            }
        };
    }
}

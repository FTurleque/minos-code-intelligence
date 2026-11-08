package com.minos.bootstrap;

import com.minos.application.WslJavaDiagnosis;
import com.minos.runtime.local.wsl.WslCheckState;
import com.minos.runtime.local.wsl.WslJarProbe;
import com.minos.runtime.local.wsl.WslPrerequisiteProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The composition root offers the WSL2 route probe under Windows only, and maps its report check by check. */
class LocalWslPrerequisiteDiagnosticsTest {

    @Test
    void theProbeIsOfferedUnderWindowsOnly() {
        assertInstanceOf(LocalWslPrerequisiteDiagnostics.class,
                DefaultMinosApplicationComposer.wslPrerequisiteDiagnostics("Windows Server 2022").orElseThrow());
        assertInstanceOf(LocalWslPrerequisiteDiagnostics.class,
                DefaultMinosApplicationComposer.wslPrerequisiteDiagnostics("windows 10").orElseThrow());
        assertEquals(Optional.empty(), DefaultMinosApplicationComposer.wslPrerequisiteDiagnostics("Linux"));
        assertEquals(Optional.empty(), DefaultMinosApplicationComposer.wslPrerequisiteDiagnostics("Mac OS X"));
        assertEquals(Optional.empty(), DefaultMinosApplicationComposer.wslPrerequisiteDiagnostics(""));
    }

    @Test
    void theCompositionRootFollowsTheHostOperatingSystem() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

        assertEquals(windows, new DefaultMinosApplicationComposer().wslPrerequisiteDiagnostics().isPresent());
    }

    @Test
    void aHostWithoutWslReportsEveryCheckInContractOrderWithoutADistribution() {
        LocalWslPrerequisiteDiagnostics diagnostics = new LocalWslPrerequisiteDiagnostics(new WslPrerequisiteProbe(
                null, (command, timeout) -> {
                    throw new AssertionError("no command without wsl.exe");
                }, WslJarProbe.NOT_AVAILABLE));

        WslJavaDiagnosis diagnosis = diagnostics.diagnose();

        assertEquals(Optional.empty(), diagnosis.distribution());
        assertEquals(List.of("wsl-version", "systemd", "cgroup-delegation", "bubblewrap",
                        "minos-jvm", "maven", "project-jdk", "maven-repository"),
                diagnosis.checks().stream().map(WslJavaDiagnosis.Check::id).toList());
        assertEquals(WslJavaDiagnosis.State.MISSING, diagnosis.checks().get(0).state());
        assertTrue(diagnosis.checks().stream().skip(1)
                .allMatch(check -> check.state() == WslJavaDiagnosis.State.UNKNOWN), diagnosis.toString());
        assertTrue(diagnosis.checks().stream().noneMatch(check -> check.action().isBlank()), diagnosis.toString());
        assertFalse(diagnosis.checks().stream().anyMatch(check -> check.action().contains(":\\")),
                diagnosis.toString());
        assertThrows(NullPointerException.class, () -> new LocalWslPrerequisiteDiagnostics(null));
    }

    @Test
    void anUnexpectedProbeFailureReportsEveryCheckUnknownInsteadOfFailingDoctor(@TempDir Path temp)
            throws Exception {
        Path wsl = Files.writeString(temp.resolve("wsl.exe"), "");
        LocalWslPrerequisiteDiagnostics diagnostics = new LocalWslPrerequisiteDiagnostics(new WslPrerequisiteProbe(
                wsl, (command, timeout) -> {
                    throw new IllegalStateException("runner broken");
                }, WslJarProbe.NOT_AVAILABLE));

        WslJavaDiagnosis diagnosis = diagnostics.diagnose();

        assertEquals(Optional.empty(), diagnosis.distribution());
        assertEquals(8, diagnosis.checks().size());
        assertTrue(diagnosis.checks().stream().allMatch(check -> check.state() == WslJavaDiagnosis.State.UNKNOWN
                && check.action().equals(LocalWslPrerequisiteDiagnostics.PROBE_FAILED)), diagnosis.toString());
    }

    @Test
    void everyProbeStateHasItsDiagnosisState() {
        for (WslCheckState state : WslCheckState.values()) {
            assertEquals(state.name(), LocalWslPrerequisiteDiagnostics.state(state).name());
        }
    }
}

package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a containment launcher into shared fragments is only safe if assembly reproduces the
 * script that was reviewed and qualified. The golden copies remain the pre-remediation qualified
 * baseline; after normalizing platform line endings, the only accepted drift is the explicit
 * one-shot deletion of the credential-bearing plan immediately before {@code Read-Plan} returns.
 * Any other content drift remains a test failure.
 */
class WindowsContainmentScriptTest {

    private static final String QUALIFIED_PLAN_RETURN = "    return $values\n}";
    private static final String APPROVED_PLAN_CONSUMPTION = """
            # The plan contains the provider argv and explicit environment, both of which may carry
            # credentials. Consume it exactly once and remove it before any untrusted provider process is
            # resumed so secrets never become retained run diagnostics.
            Remove-Item -LiteralPath $Path -Force -ErrorAction Stop
            return $values
        }""";

    @Test
    void jobObjectLauncherMatchesQualifiedBaselineExceptForApprovedPlanConsumption() throws Exception {
        assertAssemblesToQualifiedBaseline("windows-job-object-owner-v1.ps1");
    }

    @Test
    void appContainerLauncherMatchesQualifiedBaselineExceptForApprovedPlanConsumption() throws Exception {
        assertAssemblesToQualifiedBaseline("windows-appcontainer-sandbox-v4.ps1");
    }

    @Test
    void assembledLaunchersCarryNoUnresolvedIncludeDirective() throws Exception {
        for (String launcher : new String[]{
                "windows-job-object-owner-v1.ps1", "windows-appcontainer-sandbox-v4.ps1"}) {
            assertFalse(WindowsContainmentScript.assemble(launcher).contains("#minos-include:"),
                    launcher + " still contains an unresolved include directive");
        }
    }

    @Test
    void bothLaunchersKeepTheWin32ContainmentSurfaceTheyAreQualifiedOn() throws Exception {
        String jobObject = WindowsContainmentScript.assemble("windows-job-object-owner-v1.ps1");
        String appContainer = WindowsContainmentScript.assemble("windows-appcontainer-sandbox-v4.ps1");

        // The suspended-create / assign / prove-membership / resume sequence is what makes the
        // boundary real; it must survive assembly in both launchers.
        for (String required : new String[]{
                "CREATE_SUSPENDED", "AssignProcessToJobObject", "IsProcessInJob",
                "TerminateJobObject", "JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE", "ResumeThread"}) {
            assertTrue(jobObject.contains(required), "job object launcher lost " + required);
            assertTrue(appContainer.contains(required), "appcontainer launcher lost " + required);
        }
        assertTrue(appContainer.contains("JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP"),
                "appcontainer launcher lost its CPU hard cap");
    }

    @Test
    void aMissingTemplateOrFragmentFailsClosed() {
        assertThrows(IOException.class, () -> WindowsContainmentScript.assemble("no-such-launcher.ps1"));
    }

    @Test
    void assemblyIsDeterministic() throws Exception {
        String first = WindowsContainmentScript.assemble("windows-job-object-owner-v1.ps1");
        String second = WindowsContainmentScript.assemble("windows-job-object-owner-v1.ps1");
        assertEquals(first, second);
    }

    private static void assertAssemblesToQualifiedBaseline(String launcher) throws Exception {
        String golden = normalizeLineEndings(new String(readGolden(launcher), StandardCharsets.UTF_8));
        int marker = golden.indexOf(QUALIFIED_PLAN_RETURN);
        assertTrue(marker >= 0, launcher + " qualified baseline lost the plan return marker");
        assertEquals(marker, golden.lastIndexOf(QUALIFIED_PLAN_RETURN),
                launcher + " qualified baseline has an ambiguous plan return marker");
        String expected = golden.substring(0, marker)
                + APPROVED_PLAN_CONSUMPTION
                + golden.substring(marker + QUALIFIED_PLAN_RETURN.length());
        String assembled = normalizeLineEndings(WindowsContainmentScript.assemble(launcher));
        assertEquals(expected, assembled,
                launcher + " drifted beyond the approved one-shot plan-consumption remediation");
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static byte[] readGolden(String launcher) throws IOException {
        String resource = "/com/minos/runtime/golden/" + launcher;
        try (InputStream input = WindowsContainmentScriptTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing golden launcher: " + resource);
            return input.readAllBytes();
        }
    }
}

package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a containment launcher into shared fragments is only safe if assembly reproduces the
 * script that was reviewed and qualified. These tests compare the assembled bytes against a golden
 * copy of each launcher taken before the split, so any drift in a fragment, a template or the
 * assembler is a test failure rather than a silent change to a security boundary.
 */
class WindowsContainmentScriptTest {

    @Test
    void jobObjectLauncherAssemblesByteIdenticalToItsQualifiedForm() throws Exception {
        assertAssemblesToGolden("windows-job-object-owner-v1.ps1");
    }

    @Test
    void appContainerLauncherAssemblesByteIdenticalToItsQualifiedForm() throws Exception {
        assertAssemblesToGolden("windows-appcontainer-sandbox-v4.ps1");
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

    private static void assertAssemblesToGolden(String launcher) throws Exception {
        byte[] assembled = WindowsContainmentScript.assemble(launcher).getBytes(StandardCharsets.UTF_8);
        byte[] golden = readGolden(launcher);
        assertArrayEquals(golden, assembled,
                launcher + " no longer assembles to the qualified script (length "
                        + assembled.length + " vs golden " + golden.length + ")");
    }

    private static byte[] readGolden(String launcher) throws IOException {
        String resource = "/com/minos/runtime/golden/" + launcher;
        try (InputStream input = WindowsContainmentScriptTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing golden launcher: " + resource);
            return input.readAllBytes();
        }
    }
}

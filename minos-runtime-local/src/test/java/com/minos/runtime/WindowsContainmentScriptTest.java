package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a containment launcher into shared fragments is only safe if assembly reproduces the
 * script that was reviewed and qualified. The golden copies remain the pre-remediation qualified
 * baseline. After normalizing platform and terminal line endings, the accepted drift is limited to
 * the explicit one-shot deletion of the credential-bearing plan and, for AppContainer only, the
 * separately fingerprinted private-storage hardening introduced after that baseline. Any other
 * content drift remains a test failure.
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
    private static final String PRIVATE_STORAGE_USINGS_SHA256 =
            "29b40f2d578a3e9a0d191e72d48dd93b16296b8efc512f0bd35f9d307fbad6ad";
    private static final String PRIVATE_STORAGE_SHA256 =
            "fa5ed8c517237ca180652403cb6a812cbb6233f7023cde773f91809c06999ef7";

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
        String golden = normalizeScriptText(new String(readGolden(launcher), StandardCharsets.UTF_8));
        int marker = golden.indexOf(QUALIFIED_PLAN_RETURN);
        assertTrue(marker >= 0, launcher + " qualified baseline lost the plan return marker");
        assertEquals(marker, golden.lastIndexOf(QUALIFIED_PLAN_RETURN),
                launcher + " qualified baseline has an ambiguous plan return marker");
        String expected = golden.substring(0, marker)
                + APPROVED_PLAN_CONSUMPTION
                + golden.substring(marker + QUALIFIED_PLAN_RETURN.length());
        String assembled = normalizeScriptText(WindowsContainmentScript.assemble(launcher));
        if ("windows-appcontainer-sandbox-v4.ps1".equals(launcher)) {
            assembled = removeApprovedPrivateStorageHardening(assembled);
        }
        assertEquals(expected, assembled,
                launcher + " drifted beyond the approved containment remediations");
    }

    private static String removeApprovedPrivateStorageHardening(String assembled) throws Exception {
        String usings = approvedFragment(
                "appcontainer-private-storage-usings", PRIVATE_STORAGE_USINGS_SHA256);
        String privateStorage = approvedFragment(
                "appcontainer-private-storage", PRIVATE_STORAGE_SHA256);

        String value = replaceExactlyOnce(
                assembled, "\n" + usings + "\n", "\n", "private-storage using fragment");
        value = replaceExactlyOnce(
                value, privateStorage + "\n", "", "private-storage implementation fragment");
        value = replaceExactlyOnce(
                value,
                """
                        string profileName,
                        string profileSid,
                        string[] command,\
                """,
                """
                        string profileName,
                        string[] command,\
                """,
                "profile SID RunSandbox parameter");
        value = replaceExactlyOnce(
                value,
                """
                        ulong jobCpuSeconds,
                        ulong privateStorageMaxBytes,
                        ulong privateStorageMaxEntries,
                        uint privateStorageSampleMillis,
                        bool allowNetwork) {\
                """,
                """
                        ulong jobCpuSeconds,
                        bool allowNetwork) {\
                """,
                "private-storage RunSandbox parameters");
        value = replaceExactlyOnce(
                value,
                """
                        if (String.IsNullOrWhiteSpace(profileSid)) throw new ArgumentException("profile SID is blank");
                """,
                "",
                "profile SID validation");
        value = replaceExactlyOnce(
                value,
                """
                        ManualResetEvent privateStorageStop = new ManualResetEvent(false);
                        Thread privateStorageThread = null;
                """,
                "",
                "private-storage supervisor state");
        value = replaceExactlyOnce(
                value,
                "            DenyPrivateRegistryWrites(profileName, profileSid);\n",
                "",
                "private registry deny invocation");
        value = replaceExactlyOnce(
                value,
                """
                            privateStorageThread = StartPrivateFileStorageSupervisor(
                                    profileSid,
                                    job,
                                    privateStorageMaxBytes,
                                    privateStorageMaxEntries,
                                    privateStorageSampleMillis,
                                    privateStorageStop);
                """,
                "",
                "private-storage supervisor startup");
        value = replaceExactlyOnce(
                value, "                privateStorageStop.Set();\n", "", "private-storage stop signal");
        value = replaceExactlyOnce(
                value,
                "                if (privateStorageThread != null) privateStorageThread.Join(1000);\n",
                "",
                "private-storage supervisor join");
        value = replaceExactlyOnce(
                value, "            privateStorageStop.Dispose();\n", "", "private-storage stop disposal");
        value = replaceExactlyOnce(
                value,
                """
                $jobCpuSeconds = [UInt64]$values['jobCpuSeconds']
                $privateStorageMaxBytes = [UInt64]$values['privateStorageMaxBytes']
                $privateStorageMaxEntries = [UInt64]$values['privateStorageMaxEntries']
                $privateStorageSampleMillis = [UInt32]$values['privateStorageSampleMillis']
                """,
                "$jobCpuSeconds = [UInt64]$values['jobCpuSeconds']\n",
                "private-storage plan values");
        value = replaceExactlyOnce(
                value,
                """
                        $containerProfile,
                        $sid,
                        $command,\
                """,
                """
                        $containerProfile,
                        $command,\
                """,
                "profile SID launcher argument");
        return replaceExactlyOnce(
                value,
                """
                        $jobCpuSeconds,
                        $privateStorageMaxBytes,
                        $privateStorageMaxEntries,
                        $privateStorageSampleMillis,
                        ($networkPolicy -eq 'ALLOW'))\
                """,
                """
                        $jobCpuSeconds,
                        ($networkPolicy -eq 'ALLOW'))\
                """,
                "private-storage launcher arguments");
    }

    private static String approvedFragment(String fragment, String expectedSha256) throws Exception {
        String resource = "/com/minos/runtime/windows-fragments/" + fragment + ".ps1frag";
        String value = normalizeLineEndings(
                new String(readResource(resource), StandardCharsets.UTF_8));
        assertEquals(expectedSha256, sha256(value), fragment + " drifted from its approved hardening bytes");
        return value;
    }

    private static String replaceExactlyOnce(
            String value,
            String target,
            String replacement,
            String description
    ) {
        int marker = value.indexOf(target);
        assertTrue(marker >= 0, "approved AppContainer hardening lost " + description);
        assertEquals(marker, value.lastIndexOf(target),
                "approved AppContainer hardening made " + description + " ambiguous");
        return value.substring(0, marker)
                + replacement
                + value.substring(marker + target.length());
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            hex.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(item & 0x0f, 16));
        }
        return hex.toString();
    }

    private static String normalizeScriptText(String value) {
        String normalized = normalizeLineEndings(value);
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static byte[] readGolden(String launcher) throws IOException {
        return readResource("/com/minos/runtime/golden/" + launcher);
    }

    private static byte[] readResource(String resource) throws IOException {
        try (InputStream input = WindowsContainmentScriptTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing containment resource: " + resource);
            return input.readAllBytes();
        }
    }
}

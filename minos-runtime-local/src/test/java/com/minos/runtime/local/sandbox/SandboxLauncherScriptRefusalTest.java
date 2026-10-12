package com.minos.runtime.local.sandbox;

import com.minos.io.PrivateLocalStorage;
import com.minos.runtime.local.process.CommandLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A launcher script is run outside the sandbox it creates: every doubt about the place it is read
 * from, or about what it holds, refuses it. These are the refusals the other launcher tests do not
 * reach, and none of their messages names a path.
 */
class SandboxLauncherScriptRefusalTest {

    private static final String LAUNCHER = "windows-job-object-owner-v1.ps1";
    private static final String INTEGRITY_FAILURE = "sandbox launcher integrity check failed";

    @TempDir
    Path temporary;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRootReachedThroughAJunctionIsRefusedAndNothingIsMaterialisedBehindIt() throws Exception {
        Path target = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("junction-target"));
        Path junction = temporary.resolve("junction");
        assertEquals(0, run("cmd.exe", "/c", "mklink", "/J", junction.toString(), target.toString()),
                "the junction must be created");
        try {
            Path root = junction.resolve("launchers");

            IOException refusal = assertThrows(IOException.class,
                    () -> SandboxLauncherScript.materialize(root, LAUNCHER));

            assertEquals("sandbox launcher root is reached through a link or a reparse point", refusal.getMessage());
            assertEquals(List.of(), entriesOf(target), "nothing is written behind a link");
        } finally {
            Files.delete(junction);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLauncherLargerThanItsBoundIsRefusedBeforeUse() throws Exception {
        Path parent = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("oversized-parent"));
        SandboxLauncherScript script = SandboxLauncherScript.materialize(parent.resolve("launchers"), LAUNCHER);
        script.verify();
        Files.setAttribute(script.file(), "dos:readonly", false);
        Files.write(script.file(), new byte[1024 * 1024 + 1]);

        IOException refusal = assertThrows(IOException.class, script::verify);

        assertEquals(INTEGRITY_FAILURE, refusal.getMessage());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aContainingDirectoryWhoseAccessRightsChangedSinceMaterialisationIsRefusedBeforeUse() throws Exception {
        Path parent = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("changing-parent"));
        SandboxLauncherScript script = SandboxLauncherScript.materialize(parent.resolve("launchers"), LAUNCHER);
        script.verify();
        // A read entry for Guests: harmless in itself, but it is not the ACL that was validated.
        assertEquals(0, run(icacls(), parent.toString(), "/grant", "*S-1-5-32-546:(RX)", "/q"),
                "the fixture entry must be added");

        IOException refusal = assertThrows(IOException.class, script::verify);

        assertEquals(INTEGRITY_FAILURE, refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), "no absolute path in the message");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRootThatCannotBeCreatedIsReportedWithoutItsPath() throws Exception {
        Path regularFile = Files.writeString(temporary.resolve("not-a-directory"), "file", StandardCharsets.US_ASCII);
        Path root = regularFile.resolve("launchers");

        IOException refusal = assertThrows(IOException.class,
                () -> SandboxLauncherScript.materialize(root, LAUNCHER));

        assertTrue(refusal.getMessage().startsWith("sandbox launcher is unavailable ("), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), "no absolute path in the message");
        assertFalse(refusal.getMessage().contains("not-a-directory"), "no file name in the message");
        assertTrue(Files.isRegularFile(regularFile), "what was in the way is left untouched");
    }

    @Test
    void aLegacyCopyThatCannotBeRemovedIsNotAFailure() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        // A non-empty directory where the old script used to be: it cannot be deleted as a file.
        Path legacy = Files.createDirectories(home.resolve("sandbox").resolve(LAUNCHER));
        Path kept = Files.writeString(legacy.resolve("kept.txt"), "kept", StandardCharsets.US_ASCII);

        assertDoesNotThrow(() -> SandboxLauncherScript.removeLegacyCopy(home, LAUNCHER));

        assertTrue(Files.isRegularFile(kept), "what is not the legacy script is never removed");
    }

    @Test
    void anAbsentLegacyCopyLeavesMinosHomeUntouched() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));

        SandboxLauncherScript.removeLegacyCopy(home, LAUNCHER);

        assertEquals(List.of(), entriesOf(home), "looking for a legacy copy creates nothing");
    }

    @Test
    void onlyTheBuiltInAdministratorOfADomainOrMachineIsWrittenAsTheLocalAdministratorAlias() {
        // A SID that ends with the RID 500 but is not a machine or domain account (S-1-5-21-…).
        Set<String> foreignAuthority = SandboxLauncherScript.trustedSids("S-1-12-1-1111-2222-3333-500");
        Set<String> ordinaryAccount = SandboxLauncherScript.trustedSids("S-1-5-21-1111-2222-3333-1001");
        Set<String> builtInAdministrator = SandboxLauncherScript.trustedSids("S-1-5-21-1111-2222-3333-500");

        assertEquals(Set.of("S-1-12-1-1111-2222-3333-500", "S-1-5-18", "S-1-5-32-544"), foreignAuthority);
        assertEquals(Set.of("S-1-5-21-1111-2222-3333-1001", "S-1-5-18", "S-1-5-32-544"), ordinaryAccount);
        assertEquals(Set.of("S-1-5-21-1111-2222-3333-500", "S-1-5-18", "S-1-5-32-544", "LA"), builtInAdministrator);
    }

    // ------------------------------------------------------------------------------ helpers

    private static List<Path> entriesOf(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.toList();
        }
    }

    /** {@code icacls.exe} next to the PowerShell MINOS resolves in the Windows directory, not through PATH. */
    private static String icacls() {
        return CommandLocator.windowsPowerShell().orElseThrow()
                .getParent().getParent().getParent().resolve("icacls.exe").toString();
    }

    private static int run(String... command) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(List.of(command))).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        int exit = process.waitFor();
        assertEquals(0, exit, () -> String.join(" ", command) + " failed: " + output);
        return exit;
    }
}

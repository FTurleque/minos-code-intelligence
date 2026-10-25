package com.minos.runtime.local.acl;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single {@code icacls} launcher of the local runtime: the extended-length form of the target, the refusals
 * that start no process, and, on Windows, a private directory under a path longer than 260 characters.
 */
class WindowsIcaclsTest {

    @TempDir
    Path temporary;

    @Test
    void aDrivePathTakesTheExtendedLengthPrefix() {
        assertEquals("\\\\?\\C:\\Users\\me\\.minos", WindowsIcacls.extendedLength("C:\\Users\\me\\.minos"));
    }

    @Test
    void aUncPathTakesTheUncExtendedLengthPrefix() {
        assertEquals("\\\\?\\UNC\\server\\share\\minos", WindowsIcacls.extendedLength("\\\\server\\share\\minos"));
    }

    @Test
    void aPathAlreadyExtendedOrNotInAWindowsFormIsUnchanged() {
        assertEquals("\\\\?\\C:\\x", WindowsIcacls.extendedLength("\\\\?\\C:\\x"));
        assertEquals("/tmp/minos", WindowsIcacls.extendedLength("/tmp/minos"));
        assertEquals("C:relative", WindowsIcacls.extendedLength("C:relative"));
    }

    @Test
    void theTargetIsPassedInItsExtendedLengthForm() throws Exception {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        Path target = temporary.resolve("object").toAbsolutePath();

        icacls("SYSROOT", command -> {
            seen.set(command);
            return new FinishedProcess(0);
        }).run(target, List.of("/inheritance:d", "/L", "/q"), "refused");

        assertEquals(List.of(Path.of("SYSROOT", "System32", "icacls.exe").toString(),
                WindowsIcacls.extendedLength(target.toString()), "/inheritance:d", "/L", "/q"), seen.get());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows refuses such a path before it reaches icacls")
    void aForbiddenCharacterIsRefusedBeforeAnyProcessWithoutNamingThePath() {
        WindowsIcacls never = icacls("SYSROOT", command -> {
            throw new AssertionError("no process may start");
        });

        for (String name : new String[]{"a|b", "a\u0001b", "a\"b"}) {
            IOException refusal = assertThrows(IOException.class,
                    () -> never.run(temporary.toAbsolutePath().resolve(name), List.of("/q"), "refused"));
            assertEquals("refused: unsupported path", refusal.getMessage());
        }
    }

    @Test
    void aNameTheExtendedLengthFormWouldReadDifferentlyIsRefusedBeforeAnyProcess() {
        // Without Win32 normalization, "foo." is another object than the "foo" Java rewrote, and "CON" a file.
        WindowsIcacls never = icacls("SYSROOT", command -> {
            throw new AssertionError("no process may start");
        });

        for (String name : new String[]{"foo.", "CON", "nul.txt", "Com1", "lpt9.log"}) {
            Path target = temporary.toAbsolutePath().resolve(name).resolve("child");
            IOException refusal = assertThrows(IOException.class, () -> never.run(target, List.of("/q"), "refused"),
                    name);
            assertEquals("refused: unsupported path", refusal.getMessage());
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows refuses a name ending with a space before it reaches icacls")
    void aNameEndingWithASpaceIsRefusedBeforeAnyProcess() {
        WindowsIcacls never = icacls("SYSROOT", command -> {
            throw new AssertionError("no process may start");
        });

        assertThrows(IOException.class, () -> never.run(temporary.toAbsolutePath().resolve("foo "), List.of("/q"), "r"));
    }

    @Test
    void ordinaryNamesThatOnlyLookLikeDeviceNamesAreAccepted() throws Exception {
        WindowsIcacls finished = icacls("SYSROOT", command -> new FinishedProcess(0));

        for (String name : new String[]{"console", "com10", "lpt", "nul-data", ".minos", "a.b"}) {
            Path target = temporary.toAbsolutePath().resolve(name);
            assertDoesNotThrow(() -> finished.run(target, List.of("/q"), "refused"), name);
        }
    }

    @Test
    void icaclsReceivesNoEnvironmentButTheWindowsDirectory() {
        ProcessBuilder builder = WindowsIcacls.builderWithoutInheritedEnvironment(List.of("icacls.exe"));

        assertTrue(builder.environment().keySet().stream().allMatch(name -> name.equalsIgnoreCase("SystemRoot")),
                builder.environment().keySet().toString());
        assertEquals(System.getenv("SystemRoot"), builder.environment().get("SystemRoot"));
    }

    @Test
    void aRelativeTargetIsRefusedBeforeAnyProcess() {
        WindowsIcacls never = icacls("SYSROOT", command -> {
            throw new AssertionError("no process may start");
        });

        IOException refusal = assertThrows(IOException.class,
                () -> never.run(Path.of("relative"), List.of("/q"), "refused"));

        assertEquals("refused: unsupported path", refusal.getMessage());
    }

    @Test
    void aLauncherThatCannotStartIsRefusedWithoutItsMessage() {
        WindowsIcacls broken = icacls("SYSROOT", command -> {
            throw new IOException("Cannot run program \"C:\\Windows\\System32\\icacls.exe\"");
        });

        IOException refusal = assertThrows(IOException.class,
                () -> broken.run(temporary.toAbsolutePath(), List.of("/q"), "refused"));

        assertEquals("refused: icacls cannot be started", refusal.getMessage());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aPrivateDirectoryUnderAPathLongerThan260CharactersIsCreatedAndProtected() throws Exception {
        Path deep = temporary.toAbsolutePath();
        while (deep.toString().length() < 300) deep = deep.resolve("segment-" + "x".repeat(40));
        Path directory = deep.resolve("private");

        PrivateLocalStorage.ensurePrivateDirectory(directory);

        assertTrue(Files.isDirectory(directory));
        assertTrue(directory.toString().length() > 260, "fixture: " + directory.toString().length());
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(directory));
        new WindowsIcaclsAccessControl().requireNoConditionalDeny(directory);
    }

    // ------------------------------------------------------------------------------ helpers

    private static WindowsIcacls icacls(String systemRoot, WindowsIcacls.Launcher launcher) {
        return new WindowsIcacls(() -> systemRoot, launcher, Duration.ofSeconds(5));
    }

    private static final class FinishedProcess extends Process {
        private final int exit;

        FinishedProcess(int exit) {
            this.exit = exit;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exit; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return exit; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroy() { /* already finished */ }
    }
}

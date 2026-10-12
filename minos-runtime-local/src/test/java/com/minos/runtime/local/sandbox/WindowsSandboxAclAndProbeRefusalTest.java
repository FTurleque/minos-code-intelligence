package com.minos.runtime.local.sandbox;

import com.minos.io.PrivateLocalStorage;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The access-control primitives of the Windows sandbox and its capability probe, on their refusal
 * paths: a root that cannot be secured fails the plan, an entry that does not really give the owner
 * inheritable Full Control is not taken for one, and a probe that does not finish is a failed probe
 * whose process is not left behind. Only {@code icacls} and PowerShell are started, never an
 * AppContainer.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxAclAndProbeRefusalTest {

    private static final String INHERITABLE_FULL_CONTROL = "(OI)(CI)(F)";

    @TempDir
    Path temporary;

    /** Directories whose ACL a test replaced: restored so that the temporary directory can be removed. */
    private final List<Path> rewritten = new ArrayList<>();

    @AfterEach
    void restoreInheritedAccess() throws Exception {
        for (Path directory : rewritten) {
            assertEquals(0, icacls(directory, "/reset"), "the inherited ACL must be restored");
        }
    }

    @Test
    void aMissingPathIsNotGrantableAndFailsThePlanClosed() {
        Path missing = temporary.resolve("no-such-runtime");

        boolean grantable = WindowsAppContainerWorkerSandboxBackend.isAclGrantable(missing);
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> WindowsAppContainerWorkerSandboxBackend.requireAclGrantable(missing));

        assertFalse(grantable);
        assertTrue(refusal.getMessage().contains("without administrator privileges"), refusal.getMessage());
        assertFalse(Files.exists(missing), "probing a path never creates it");
    }

    @Test
    void aWriteRootThatCannotBeSecuredIsRefusedWithoutNamingItsPath() {
        Path missing = temporary.resolve("no-such-write-root");

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> WindowsAppContainerWorkerSandboxBackend.requireInheritableOwnerAccess(missing));

        assertEquals("Unable to secure inheritable owner access on a sandbox write root", refusal.getMessage());
        assertFalse(Files.exists(missing), "securing a write root never creates it");
    }

    @Test
    void aDirectoryMadeByPrivateStorageAlreadyGivesItsOwnerInheritableFullControl() throws Exception {
        Path directory = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("private"));
        List<AclEntry> before = acl(directory);

        assertTrue(WindowsAppContainerWorkerSandboxBackend.ownerHoldsInheritableFullControl(directory));
        WindowsAppContainerWorkerSandboxBackend.requireInheritableOwnerAccess(directory);

        assertEquals(before, acl(directory),
                "a root that is already covered gets no second entry: private storage would rewrite the ACL");
    }

    @Test
    void anUnreadableOrMissingDirectoryIsNotTakenForACoveredOne() {
        assertFalse(WindowsAppContainerWorkerSandboxBackend.ownerHoldsInheritableFullControl(
                temporary.resolve("no-such-directory")));
    }

    @Test
    void anExplicitInheritableFullControlEntryOfTheOwnerIsRecognized() throws Exception {
        Path directory = ownerOnly("explicit", INHERITABLE_FULL_CONTROL);

        assertTrue(WindowsAppContainerWorkerSandboxBackend.ownerHoldsInheritableFullControl(directory));
    }

    @Test
    void aDenyEntryForAnotherPrincipalDoesNotHideTheOwnersEntry() throws Exception {
        Path directory = ownerOnly("with-deny", INHERITABLE_FULL_CONTROL);
        assertEquals(0, icacls(directory, "/deny", "*S-1-5-7:(OI)(CI)(R)"));

        assertTrue(WindowsAppContainerWorkerSandboxBackend.ownerHoldsInheritableFullControl(directory));
    }

    @Test
    void anInheritableOwnerEntryWithoutFullControlIsNotEnough() throws Exception {
        Path directory = ownerOnly("read-only", "(OI)(CI)(RX)");

        assertFalse(WindowsAppContainerWorkerSandboxBackend.ownerHoldsInheritableFullControl(directory));
    }

    @Test
    void aProbeThatDoesNotFinishIsAFailedProbeAndItsProcessIsTerminated() throws Exception {
        Path powershell = CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new AssertionError("Windows PowerShell is part of every supported Windows"));
        ProcessBuilder neverEnding = new ProcessBuilder(List.of(powershell.toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 300"));
        Set<Long> before = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());

        try (LogCapture logs = new LogCapture(WindowsAppContainerWorkerSandboxBackend.class)) {
            boolean succeeded = WindowsAppContainerWorkerSandboxBackend.probeSucceeds(
                    neverEnding, temporary.resolve("probe-output.log"), Duration.ofMillis(500));

            assertFalse(succeeded, "a probe that times out never qualifies the host");
            assertEquals(List.of("MINOS Windows AppContainer capability probe timed out"), logs.warnings());
        }
        List<Long> survivors = ProcessHandle.current().children()
                .filter(handle -> !before.contains(handle.pid()))
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .toList();
        assertEquals(List.of(), survivors, "the timed-out probe process must not outlive the probe");
    }

    // ------------------------------------------------------------------------------ helpers

    /** A directory whose only entry is the given right for its owner; inheritance from the parent is removed. */
    private Path ownerOnly(String name, String rights) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve(name));
        rewritten.add(directory);
        String owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).getName();
        assertEquals(0, icacls(directory, "/inheritance:r", "/grant:r", owner + ":" + rights),
                "the fixture ACL must be applied");
        return directory;
    }

    private static List<AclEntry> acl(Path directory) throws IOException {
        return Files.getFileAttributeView(directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl();
    }

    private static int icacls(Path target, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(CommandLocator.windowsPowerShell().orElseThrow()
                .getParent().getParent().getParent().resolve("icacls.exe").toString());
        command.add(target.toString());
        command.addAll(List.of(arguments));
        command.add("/q");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        int exit = process.waitFor();
        assertEquals(0, exit, () -> "icacls " + List.of(arguments) + " failed: " + output);
        return exit;
    }
}

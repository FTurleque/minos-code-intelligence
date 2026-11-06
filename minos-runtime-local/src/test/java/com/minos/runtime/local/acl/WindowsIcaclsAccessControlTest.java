package com.minos.runtime.local.acl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusals of the Windows DACL reader (AUD-SEC-06), exercised with an injected launcher so that they run on
 * every system: the DACL is read before it is rewritten, and whatever stops the read stops the rewrite. The
 * implementation is the Windows platform access control of private storage (AUD-ARC-05).
 */
class WindowsIcaclsAccessControlTest {

    private static final String SYSTEM_ROOT = "SYSROOT";
    private static final String ORDINARY = "D:PAI(A;OICI;FA;;;S-1-5-21-1-2-3-1001)";

    @TempDir
    Path temporary;

    private Path target() {
        return temporary.resolve("storage").toAbsolutePath();
    }

    @Test
    void anOrdinaryDaclPassesAndThePathIsPassedToIcaclsOnce() throws Exception {
        AtomicReference<List<String>> command = new AtomicReference<>();

        read(target(), SYSTEM_ROOT, directories(), launcherWriting(ORDINARY, 0, command));

        assertEquals(Path.of(SYSTEM_ROOT, "System32", "icacls.exe").toString(), command.get().get(0));
        assertEquals(WindowsIcacls.extendedLength(target().toString()), command.get().get(1));
        assertEquals("/save", command.get().get(2));
        assertFalse(Files.exists(Path.of(command.get().get(3))), "the saved text is deleted once read");
    }

    @Test
    void aConditionalDenyIsRefusedWithoutNamingThePath() {
        IOException refusal = assertThrows(IOException.class, () ->
                read(target(), SYSTEM_ROOT, directories(), launcherWriting(ORDINARY + "(XD;OICI;0x1301bf;;;WD;(Member_of {SID(BG)}))", 0, null)));

        assertTrue(refusal.getMessage().startsWith("a conditional deny entry on private storage"));
        assertFalse(refusal.getMessage().contains(temporary.toString()));
    }

    @Test
    void aByteOrderMarkBeforeTheDaclLineIsTolerated() {
        assertDoesNotThrow(() -> read(target(), SYSTEM_ROOT, directories(),
                launcherWriting((char) 0xFEFF + ORDINARY, 0, null)));
    }

    @Test
    void anAnswerWithoutADaclLineIsRefused() {
        IOException refusal = assertThrows(IOException.class, () ->
                read(target(), SYSTEM_ROOT, directories(), launcherWriting("S:AI", 0, null)));

        assertTrue(refusal.getMessage().endsWith("no DACL in the answer"), refusal.getMessage());
    }

    @Test
    void aNonZeroExitCodeIsRefused() {
        IOException refusal = assertThrows(IOException.class, () ->
                read(target(), SYSTEM_ROOT, directories(), launcherWriting(ORDINARY, 87, null)));

        assertTrue(refusal.getMessage().endsWith("(exit 87)"), refusal.getMessage());
    }

    @Test
    void aProcessThatDoesNotEndIsKilledAndRefused() {
        FakeProcess process = new FakeProcess(0, false, false);

        IOException refusal = assertThrows(IOException.class, () ->
                read(target(), SYSTEM_ROOT, directories(), command -> process));

        assertTrue(refusal.getMessage().endsWith("timed out"), refusal.getMessage());
        assertTrue(process.killed.get());
    }

    @Test
    void anInterruptedWaitIsRefusedAndKeepsTheInterruption() {
        FakeProcess process = new FakeProcess(0, true, true);
        try {
            IOException refusal = assertThrows(IOException.class, () ->
                    read(target(), SYSTEM_ROOT, directories(), command -> process));

            assertTrue(refusal.getMessage().endsWith("interrupted"), refusal.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(process.killed.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aMissingWindowsDirectoryOrARelativePathIsRefusedBeforeAnyProcess() {
        AtomicBoolean launched = new AtomicBoolean();
        WindowsIcacls.Launcher never = command -> {
            launched.set(true);
            return new FakeProcess(0, true, false);
        };

        for (String systemRoot : new String[]{null, " "}) {
            IOException refusal = assertThrows(IOException.class, () ->
                    read(target(), systemRoot, directories(), never));
            assertTrue(refusal.getMessage().endsWith("Windows directory unknown"), refusal.getMessage());
        }
        assertThrows(IOException.class, () -> read(Path.of("relative"), SYSTEM_ROOT, directories(), never));
        assertFalse(launched.get());
    }

    @Test
    void theSaveFileGoesToTheFirstUsableDirectoryAndNeverToAnUnusableOne() throws Exception {
        Path usable = Files.createDirectory(temporary.resolve("profile"));
        AtomicReference<List<String>> command = new AtomicReference<>();
        List<String> candidates = java.util.Arrays.asList(
                null, " ", "relative", temporary.resolve("absent").toString(), usable.toString());

        read(target(), SYSTEM_ROOT, candidates, launcherWriting(ORDINARY, 0, command));

        assertEquals(usable, Path.of(command.get().get(3)).getParent());
    }

    @Test
    void withoutAnyUsableDirectoryTheReadIsRefusedBeforeAnyProcess() {
        AtomicBoolean launched = new AtomicBoolean();

        IOException refusal = assertThrows(IOException.class, () ->
                read(target(), SYSTEM_ROOT, java.util.Arrays.asList(null, "relative", temporary.resolve("absent").toString()),
                        command -> {
                            launched.set(true);
                            return new FakeProcess(0, true, false);
                        }));

        assertTrue(refusal.getMessage().endsWith("no place to save them"), refusal.getMessage());
        assertFalse(launched.get());
    }

    @Test
    void aSaveDirectoryThatRefusesTheNewFileIsRefusedBeforeAnyProcessWithoutNamingThePath() throws Exception {
        Path saves = Files.createDirectory(temporary.resolve("saves"));
        AtomicBoolean launched = new AtomicBoolean();
        Runnable restore = makeUnwritable(saves);
        try {
            IOException refusal = assertThrows(IOException.class, () ->
                    read(target(), SYSTEM_ROOT, List.of(saves.toString()), command -> {
                        launched.set(true);
                        return new FakeProcess(0, true, false);
                    }));

            assertTrue(refusal.getMessage().endsWith("no place to save them"), refusal.getMessage());
            assertFalse(refusal.getMessage().contains(temporary.toString()), "absolute path in the message");
            assertNull(refusal.getCause(), "an internal cause reaches the caller");
            assertFalse(launched.get(), "icacls was started although nothing could be saved");
        } finally {
            restore.run();
        }
    }

    @Test
    void aSavedFileThatCannotBeReadIsRefusedWithoutNamingThePathAndIsStillDeleted() {
        AtomicReference<Path> saved = new AtomicReference<>();
        // The "process" leaves a directory where the saved text should be: reading it fails.
        WindowsIcacls.Launcher unreadable = command -> {
            Path file = Path.of(command.get(3));
            saved.set(file);
            Files.delete(file);
            Files.createDirectory(file);
            return new FakeProcess(0, true, false);
        };

        IOException refusal = assertThrows(IOException.class, () ->
                read(target(), SYSTEM_ROOT, directories(), unreadable));

        assertTrue(refusal.getMessage().endsWith("the saved rules cannot be read"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), "absolute path in the message");
        assertNull(refusal.getCause(), "an internal cause reaches the caller");
        assertFalse(Files.exists(saved.get()), "what the refused read left behind is removed");
    }

    @Test
    void aSavedFileThatCannotBeDeletedDoesNotHideAnOrdinaryDacl() throws Exception {
        AtomicReference<Runnable> restore = new AtomicReference<>();
        Path saves = Files.createDirectory(temporary.resolve("saves"));
        AtomicReference<Path> saved = new AtomicReference<>();
        try {
            read(target(), SYSTEM_ROOT, List.of(saves.toString()), command -> {
                Path file = Path.of(command.get(3));
                saved.set(file);
                Files.write(file, ("name\r\n" + ORDINARY + "\r\n").getBytes(StandardCharsets.UTF_16LE));
                restore.set(makeUndeletable(file));
                return new FakeProcess(0, true, false);
            });

            assertTrue(Files.exists(saved.get()), "the file was expected to survive: deletion must have been refused");
        } finally {
            undo(restore);
        }
    }

    @Test
    void aSavedFileThatCannotBeDeletedDoesNotHideAConditionalDenyRefusal() {
        AtomicReference<Runnable> restore = new AtomicReference<>();
        try {
            IOException refusal = assertThrows(IOException.class, () -> read(target(), SYSTEM_ROOT, directories(),
                    command -> {
                        Path file = Path.of(command.get(3));
                        Files.write(file, ("name\r\n" + ORDINARY + "(XD;OICI;0x1301bf;;;WD;(Member_of {SID(BG)}))\r\n")
                                .getBytes(StandardCharsets.UTF_16LE));
                        restore.set(makeUndeletable(file));
                        return new FakeProcess(0, true, false);
                    }));

            assertTrue(refusal.getMessage().startsWith("a conditional deny entry on private storage"),
                    refusal.getMessage());
        } finally {
            undo(restore);
        }
    }

    // ------------------------------------------------------------------------------ helpers

    /**
     * Makes {@code file} readable but impossible to delete, by what the file system offers: removing the write right
     * of its directory where POSIX permissions exist, the read-only attribute (which Windows refuses to delete)
     * otherwise. Answers the action that undoes it, so that the temporary directory can be cleaned.
     */
    private static Runnable makeUndeletable(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Path directory = file.getParent();
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            return () -> restorePermissions(directory, original);
        }
        Files.getFileAttributeView(file, DosFileAttributeView.class).setReadOnly(true);
        return () -> restoreWritable(file);
    }

    /**
     * Makes {@code directory} refuse a new file, by what the file system offers: removing its write right where POSIX
     * permissions exist, a DENY entry for its owner (added before the others) otherwise. Answers the action that
     * undoes it, so that the temporary directory can be cleaned.
     */
    private static Runnable makeUnwritable(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            return () -> restorePermissions(directory, original);
        }
        AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        List<AclEntry> original = view.getAcl();
        List<AclEntry> denying = new ArrayList<>();
        denying.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(view.getOwner())
                .setPermissions(AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY).build());
        denying.addAll(original);
        view.setAcl(denying);
        return () -> restoreAcl(view, original);
    }

    private static void restoreAcl(AclFileAttributeView view, List<AclEntry> original) {
        try {
            view.setAcl(original);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void undo(AtomicReference<Runnable> restore) {
        Runnable action = restore.get();
        if (action != null) action.run();
    }

    private static void restorePermissions(Path directory, Set<PosixFilePermission> original) {
        try {
            Files.setPosixFilePermissions(directory, original);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void restoreWritable(Path file) {
        try {
            Files.getFileAttributeView(file, DosFileAttributeView.class).setReadOnly(false);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Reads the DACL of {@code target} as on Windows, with the given Windows directory, save places and launcher. */
    private static void read(Path target, String systemRoot, List<String> saveDirectories,
                             WindowsIcacls.Launcher launcher) throws IOException {
        new WindowsIcaclsAccessControl(new WindowsIcacls(() -> systemRoot, launcher, Duration.ofSeconds(5)),
                () -> saveDirectories, () -> true).requireNoConditionalDeny(target);
    }

    private List<String> directories() {
        return List.of(temporary.toString());
    }

    /** A launcher whose process "writes" the given SDDL text where icacls was told to, then exits with the code. */
    private static WindowsIcacls.Launcher launcherWriting(
            String sddl, int exitCode, AtomicReference<List<String>> seen) {
        return command -> {
            if (seen != null) seen.set(command);
            Files.write(Path.of(command.get(3)), ("name\r\n" + sddl + "\r\n").getBytes(StandardCharsets.UTF_16LE));
            return new FakeProcess(exitCode, true, false);
        };
    }

    private static final class FakeProcess extends Process {
        private final int exit;
        private final boolean ends;
        private final boolean interrupted;
        private final AtomicBoolean killed = new AtomicBoolean();

        FakeProcess(int exit, boolean ends, boolean interrupted) {
            this.exit = exit;
            this.ends = ends;
            this.interrupted = interrupted;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exit; }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interrupted) throw new InterruptedException("interrupted");
            return ends;
        }

        @Override public int exitValue() { return exit; }
        @Override public boolean isAlive() { return !ends; }
        @Override public void destroy() { killed.set(true); }
        @Override public Process destroyForcibly() { killed.set(true); return this; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}

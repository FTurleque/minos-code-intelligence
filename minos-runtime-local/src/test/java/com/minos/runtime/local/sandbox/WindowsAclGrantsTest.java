package com.minos.runtime.local.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one place the Windows sandbox backend launches {@code icacls} (AUD-SEC-06). The refusals are exercised
 * with an injected launcher, so they run on every system; the effect on a real DACL is a Windows-only test.
 */
class WindowsAclGrantsTest {

    private static final String SID = "S-1-5-21-1111111111-2222222222-3333333333-1001";
    private static final String SYSTEM_ROOT = "SYSROOT";

    @Test
    void aGrantIsAdditiveAndNamesTheIdentityBySid() {
        List<List<String>> launched = new ArrayList<>();
        WindowsAclGrants grants = grants(SYSTEM_ROOT, command -> {
            launched.add(command);
            return new FinishedProcess(0);
        });

        assertTrue(grants.grant(Path.of("target-dir"), SID, "(OI)(CI)F"));

        assertEquals(List.of(
                Path.of(SYSTEM_ROOT, "System32", "icacls.exe").toString(),
                Path.of("target-dir").toString(), "/grant", "*" + SID + ":(OI)(CI)F", "/q"), launched.getFirst());
    }

    @Test
    void aRemovalTouchesOnlyTheGrantsOfOneIdentity() {
        List<List<String>> launched = new ArrayList<>();
        WindowsAclGrants grants = grants(SYSTEM_ROOT, command -> {
            launched.add(command);
            return new FinishedProcess(0);
        });

        assertTrue(grants.remove(Path.of("target-dir"), SID));

        assertEquals(List.of(
                Path.of(SYSTEM_ROOT, "System32", "icacls.exe").toString(),
                Path.of("target-dir").toString(), "/remove:g", "*" + SID, "/q"), launched.getFirst());
    }

    @Test
    void aMissingWindowsDirectoryIsNotGrantedAndStartsNoProcess() {
        for (String systemRoot : new String[]{null, "", "   "}) {
            AtomicBoolean launched = new AtomicBoolean();
            WindowsAclGrants grants = grants(systemRoot, command -> {
                launched.set(true);
                return new FinishedProcess(0);
            });

            assertFalse(grants.grant(Path.of("target-dir"), SID, "(RX)"), "SystemRoot=" + systemRoot);
            assertFalse(grants.remove(Path.of("target-dir"), SID), "SystemRoot=" + systemRoot);
            assertFalse(launched.get(), "no process may start without a known Windows directory");
        }
    }

    @Test
    void anIcaclsThatNeverEndsIsKilledAtTheDeadlineAndNothingIsGranted() {
        NeverEndingProcess process = new NeverEndingProcess();
        WindowsAclGrants grants = new WindowsAclGrants(() -> SYSTEM_ROOT, command -> process, Duration.ofMillis(100));

        long started = System.nanoTime();
        boolean granted = grants.grant(Path.of("target-dir"), SID, "(RX)");

        assertFalse(granted);
        assertTrue(process.killed.get(), "the process must be killed once the deadline has passed");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "the wait is bounded by the deadline");
    }

    @Test
    void aNonZeroExitCodeIsNotGranted() {
        assertFalse(grants(SYSTEM_ROOT, command -> new FinishedProcess(5)).grant(Path.of("d"), SID, "(RX)"));
        assertFalse(grants(SYSTEM_ROOT, command -> new FinishedProcess(5)).remove(Path.of("d"), SID));
    }

    @Test
    void aLauncherThatCannotStartIsNotGranted() {
        WindowsAclGrants grants = grants(SYSTEM_ROOT, command -> {
            throw new IOException("cannot start");
        });

        assertFalse(grants.grant(Path.of("d"), SID, "(RX)"));
    }

    @Test
    void anInterruptedWaitIsNotGrantedAndKeepsTheInterruption() {
        WindowsAclGrants grants = grants(SYSTEM_ROOT, command -> new InterruptedProcess());
        try {
            assertFalse(grants.grant(Path.of("d"), SID, "(RX)"));
            assertTrue(Thread.currentThread().isInterrupted(), "the interruption must be preserved");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anIdentityOrRightsThatAreNotWhatTheyClaimToBeAreRefusedWithoutAProcess() {
        AtomicBoolean launched = new AtomicBoolean();
        WindowsAclGrants grants = grants(SYSTEM_ROOT, command -> {
            launched.set(true);
            return new FinishedProcess(0);
        });

        assertFalse(grants.grant(Path.of("d"), "Everyone", "(RX)"));
        assertFalse(grants.grant(Path.of("d"), "*S-1-1-0", "(RX)"));
        assertFalse(grants.grant(Path.of("d"), SID, "F /T"));
        assertFalse(grants.remove(Path.of("d"), "S-1-1-0 /T"));
        assertFalse(launched.get());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aGrantOnADirectoryHoldingADenyChangesNothingButTheAddedEntry(@TempDir Path temporary) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("guarded"));
        String owner = currentUserSid();
        // The condition is false for any normal account (BUILTIN\Guests), so the fixture never locks the test out.
        setSddl(directory, "D:PAI(A;OICI;FA;;;" + owner + ")(XD;OICI;0x1301bf;;;WD;(Member_of {SID(BG)}))");
        String before = rawDacl(directory);
        assertTrue(before.contains("(XD;"), "fixture: the directory holds a conditional deny: " + before);

        assertTrue(WindowsAclGrants.system().grant(directory, "S-1-5-32-546", "(RX)"));

        String after = rawDacl(directory);
        assertTrue(after.contains("(XD;"), "the conditional deny must survive an additive grant: " + after);
        // icacls writes the well-known alias (BG) for S-1-5-32-546 in the text it saves.
        assertTrue(after.contains("(A;;0x1200a9;;;BG)"), "the added entry must be there: " + after);

        assertTrue(WindowsAclGrants.system().remove(directory, "S-1-5-32-546"));
        assertEquals(before, rawDacl(directory), "removing the one identity restores the DACL as it was");
    }

    // ------------------------------------------------------------------------------ helpers

    private static WindowsAclGrants grants(String systemRoot, WindowsAclGrants.Launcher launcher) {
        return new WindowsAclGrants(() -> systemRoot, launcher, Duration.ofSeconds(5));
    }

    private static String currentUserSid() throws Exception {
        Process process = new ProcessBuilder("whoami", "/user", "/fo", "csv", "/nh").redirectErrorStream(true).start();
        String line = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
        assertEquals(0, process.waitFor());
        return line.substring(line.lastIndexOf(',') + 1).replace("\"", "").trim();
    }

    private static void setSddl(Path path, String sddl) throws Exception {
        String script = "$a = New-Object System.Security.AccessControl.DirectorySecurity;"
                + "$a.SetSecurityDescriptorSddlForm('" + sddl + "');"
                + "Set-Acl -LiteralPath '" + path + "' -AclObject $a";
        Process process = new ProcessBuilder(
                Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertEquals(0, process.waitFor(), "Set-Acl failed: " + output);
    }

    /** The DACL line of {@code icacls /save}. */
    private static String rawDacl(Path path) throws Exception {
        Path saved = Files.createTempFile("minos-dacl-test-", ".sddl");
        try {
            Process process = new ProcessBuilder(
                    Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString(),
                    path.toString(), "/save", saved.toString(), "/q")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
            String text = new String(Files.readAllBytes(saved), StandardCharsets.UTF_16LE);
            return text.lines().map(line -> line.replace("﻿", "")).filter(line -> line.startsWith("D:"))
                    .findFirst().orElseThrow();
        } finally {
            Files.deleteIfExists(saved);
        }
    }

    private static class FinishedProcess extends Process {
        private final int exit;

        FinishedProcess(int exit) {
            this.exit = exit;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exit; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return true; }
        @Override public int exitValue() { return exit; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroy() { /* the fake process is already finished */ }
    }

    private static final class NeverEndingProcess extends FinishedProcess {
        private final AtomicBoolean killed = new AtomicBoolean();

        NeverEndingProcess() {
            super(0);
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            LockSupport.parkNanos(unit.toNanos(Math.min(timeout, 50)));
            return killed.get();
        }
        @Override public boolean isAlive() { return !killed.get(); }
        @Override public int exitValue() { throw new IllegalThreadStateException("still running"); }
        @Override public Process destroyForcibly() { killed.set(true); return this; }
    }

    private static final class InterruptedProcess extends FinishedProcess {
        InterruptedProcess() {
            super(0);
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("interrupted");
        }
        @Override public Process destroyForcibly() { return this; }
    }
}

package com.minos.intellij.protocol;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosStrongProcessLauncherTest {

    @TempDir
    Path temp;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void ownershipPlanIsDeletedBeforeOwnedChildCompletes() throws Exception {
        Path home = temp.resolve("plan-home");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", "Start-Sleep -Seconds 30"));
        builder.directory(temp.toFile());
        builder.environment().put("MINOS_TEST_SECRET", "must-not-remain-on-disk");

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(builder, home.toString());
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            Path ownership = home.resolve("intellij/process-ownership");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (hasPlans(ownership) && System.nanoTime() < deadline) Thread.sleep(20L);
            assertFalse(hasPlans(ownership),
                    "the environment-bearing ownership plan must be deleted before the child exits");
            assertTrue(launch.process().isAlive(), "fixture child must still be alive after plan deletion");
            supervisor.stop(null);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsOwnershipInfrastructureAndLauncherAreOwnerOnly() throws Exception {
        Path home = temp.resolve("secure-home");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", "Start-Sleep -Seconds 30"));
        builder.directory(temp.toFile());

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(builder, home.toString());
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            Path intellijHome = home.resolve("intellij");
            Path ownership = intellijHome.resolve("process-ownership");
            Path launcher = ownership.resolve("windows-cli-job-owner-v1.ps1");

            assertOwnerOnlyAcl(home);
            assertOwnerOnlyAcl(intellijHome);
            assertOwnerOnlyAcl(ownership);
            assertTrue(Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.isSymbolicLink(launcher));
            assertOwnerOnlyAcl(launcher);

            supervisor.stop(null);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void configuredOwnershipHomeRejectsAWindowsJunctionBeforeAclMutation() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside-home"));
        Path junction = temp.resolve("junction-home");
        Process mklink = new ProcessBuilder(
                "cmd.exe", "/d", "/s", "/c",
                "mklink /J \"" + junction + "\" \"" + outside + "\"")
                .redirectErrorStream(true)
                .start();
        int exit = mklink.waitFor();
        Assumptions.assumeTrue(exit == 0,
                () -> "mklink /J unavailable: " + new String(mklink.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", "Start-Sleep -Seconds 30"));
        builder.directory(temp.toFile());

        assertThrows(IOException.class,
                () -> MinosStrongProcessLauncher.start(builder, junction.toString()));
        assertFalse(Files.exists(outside.resolve("intellij")),
                "rejected reparse homes must not receive ownership infrastructure");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void ownershipPlanAclIsExplicitlyRestrictedToItsOwner() throws Exception {
        Path plan = Files.writeString(temp.resolve("cli-test.plan"), "secret");

        MinosStrongProcessLauncher.restrictWindowsPlan(plan);

        assertOwnerOnlyAcl(plan);
    }

    @Test
    void staleOwnershipCleanupKeepsRecentPlansAndRemovesOnlyOldRegularFiles() throws Exception {
        Path ownership = Files.createDirectories(temp.resolve("cleanup"));
        Path stale = Files.writeString(ownership.resolve("cli-stale.plan"), "old-secret");
        Path recent = Files.writeString(ownership.resolve("cli-recent.plan"), "current-secret");
        Path unrelated = Files.writeString(ownership.resolve("other.plan"), "other");
        Instant cutoff = Instant.parse("2026-08-15T00:00:00Z");
        Files.setLastModifiedTime(stale, FileTime.from(cutoff.minusSeconds(1)));
        Files.setLastModifiedTime(recent, FileTime.from(cutoff.plusSeconds(1)));

        MinosStrongProcessLauncher.cleanupStaleWindowsPlans(ownership, cutoff);

        assertFalse(Files.exists(stale));
        assertTrue(Files.isRegularFile(recent));
        assertTrue(Files.isRegularFile(unrelated));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void immediateDetachedChildCannotEscapeWindowsJobObjectOnStop() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        String escaped = pidFile.toString().replace("'", "''");
        String command = "$p=Start-Process -PassThru ping -ArgumentList '-n','3600','127.0.0.1';"
                + "Set-Content -NoNewline -LiteralPath '" + escaped + "' -Value $p.Id;"
                + "Start-Sleep -Seconds 5";
        ProcessBuilder builder = new ProcessBuilder(List.of(
                powershell().toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command));
        builder.directory(temp.toFile());

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(
                builder, temp.resolve("home").toString());
        long pid;
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            pid = awaitPid(pidFile);
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "fixture child must be alive before strong-boundary termination");
            supervisor.stop(null);
        }

        awaitDead(pid);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "child detached before any ProcessHandle poll must still be killed by the Job Object");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void immediateSetsidChildCannotEscapeQualifiedLinuxScopeOnStop() throws Exception {
        Assumptions.assumeTrue(MinosStrongProcessLauncher.linuxOwnershipAvailableForTests(),
                "runner has no qualified systemd user scope");
        Path pidFile = temp.resolve("child.pid");
        Path script = temp.resolve("detach.sh");
        String escaped = pidFile.toString().replace("'", "'\\''");
        Files.writeString(script, "#!/bin/sh\n"
                + "setsid sh -c 'sleep 300' >/dev/null 2>&1 &\n"
                + "printf '%s' \"$!\" > '" + escaped + "'\n"
                + "sleep 5\n");
        script.toFile().setExecutable(true);
        ProcessBuilder builder = new ProcessBuilder(script.toString());
        builder.directory(temp.toFile());

        MinosStrongProcessLauncher.Launch launch = MinosStrongProcessLauncher.start(
                builder, temp.resolve("home").toString());
        long pid;
        try (MinosProcessSupervisor supervisor = new MinosProcessSupervisor(launch)) {
            pid = awaitPid(pidFile);
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "fixture setsid child must be alive before strong-boundary termination");
            supervisor.stop(null);
        }

        awaitDead(pid);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "setsid child must remain owned by the systemd cgroup scope");
    }

    private static void assertOwnerOnlyAcl(Path path) throws Exception {
        assertFalse(Files.isSymbolicLink(path), "secured ownership entry must not be a symbolic link");
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertNotNull(view, "Windows ownership entry must expose an ACL view");
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> acl = view.getAcl();
        assertFalse(acl.isEmpty(), "Windows ownership entry ACL must not be empty");
        assertTrue(acl.stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW
                        && entry.principal().equals(owner)),
                "Windows ownership entry ACL must contain only owner ALLOW entries");
    }

    private static boolean hasPlans(Path ownership) throws Exception {
        if (!Files.isDirectory(ownership)) return false;
        try (var plans = Files.newDirectoryStream(ownership, "cli-*.plan")) {
            return plans.iterator().hasNext();
        }
    }

    private static Path powershell() {
        return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    /**
     * Waits until the fixture child has published a complete, readable PID.
     *
     * <p>Waiting for the file to merely exist is not enough. The writer creates it before it has
     * finished writing, and on Windows it still holds the handle open while doing so, so a read
     * that races that window fails with a {@link java.nio.file.FileSystemException} ("used by
     * another process") or observes an empty/partial value. Poll until the content can actually be
     * read and parsed, which covers both the not-yet-created and the not-yet-complete states.</p>
     */
    private static long awaitPid(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                String published = Files.readString(file).trim();
                if (!published.isEmpty()) return Long.parseLong(published);
                lastFailure = null;
            } catch (IOException | NumberFormatException notReadyYet) {
                lastFailure = notReadyYet;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("timed out waiting for detached child PID from " + file, lastFailure);
    }

    private static void awaitDead(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
    }
}

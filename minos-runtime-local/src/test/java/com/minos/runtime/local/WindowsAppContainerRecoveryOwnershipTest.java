package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MINOS-AUD-A01 : le balayage de reprise du lanceur AppContainer ne récupère que les sandbox dont le
 * propriétaire est prouvé mort. Ces tests lancent le vrai lanceur sous Windows : ils sont la seule preuve de ce
 * comportement (le test d'assemblage du script ne prouve que la fidélité du texte). Le saut d'horloge et la
 * réutilisation d'un identifiant de processus ne sont pas reproductibles sur un poste : ils sont écartés par
 * construction, ce que fixe la garde statique de {@code WindowsContainmentScriptTest}.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsAppContainerRecoveryOwnershipTest {
    private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(120);
    private static final String CURRENT_RECOVERY = "appcontainer-recovery-v2";
    private static final String LEGACY_RECOVERY = "appcontainer-recovery";
    /** A well-formed AppContainer SID that no sandbox owns, granted and tracked by the test (icacls caps sub-authorities at 32 bits). */
    private static final String FOREIGN_SID = "S-1-15-2-1111111111-2222222222-3333333333-1444444444-1555555555-1666666666-1777777777";

    @Test
    void aLiveSandboxIsNotAlteredWhenAnotherLauncherStartsAndRunsToCompletion() throws Exception {
        Fixture fixture = fixture("live");
        Sandbox first = fixture.start("first", """
                param([string] $Out)
                Start-Sleep -Seconds 20
                [System.IO.File]::WriteAllText($Out, 'ok')
                exit 0
                """);
        try {
            Journal journal = fixture.awaitJournalWithGrants(first, Duration.ofSeconds(60));

            Sandbox second = fixture.start("second", "exit 0\n");
            assertTrue(second.process().waitFor(90, TimeUnit.SECONDS), "second launcher timed out: " + second.output());
            assertEquals(0, second.process().exitValue(), second.output());

            assertTrue(Files.exists(journal.file()), "the live sandbox lost its recovery journal: " + second.output());
            for (String path : journal.paths()) {
                assertTrue(fixture.acl(path).contains(journal.sid()),
                        "the live sandbox lost its ACL on " + path + ": " + second.output());
            }
            assertTrue(first.process().waitFor(90, TimeUnit.SECONDS), "first launcher timed out: " + first.output());
            assertEquals(0, first.process().exitValue(), first.output());
            assertEquals("ok", Files.readString(first.marker(), StandardCharsets.UTF_8), first.output());
        } finally {
            first.destroy();
        }
    }

    @Test
    void aSandboxWhoseOwnerWasKilledIsRecoveredByTheNextLauncher() throws Exception {
        Fixture fixture = fixture("dead");
        Sandbox first = fixture.start("first", """
                param([string] $Out)
                Start-Sleep -Seconds 60
                [System.IO.File]::WriteAllText($Out, 'ok')
                exit 0
                """);
        Journal journal;
        try {
            journal = fixture.awaitJournalWithGrants(first, Duration.ofSeconds(60));
            first.process().descendants().forEach(ProcessHandle::destroyForcibly);
            first.process().destroyForcibly();
            assertTrue(first.process().waitFor(30, TimeUnit.SECONDS), "the killed launcher did not exit");
        } finally {
            first.destroy();
        }
        assertTrue(Files.exists(journal.file()), "a killed launcher is expected to leave its journal behind");
        Path lock = lockOf(journal.file());
        assertTrue(Files.exists(lock), "a killed launcher is expected to leave its lock file behind");

        Sandbox second = fixture.start("second", "exit 0\n");
        assertTrue(second.process().waitFor(90, TimeUnit.SECONDS), "second launcher timed out: " + second.output());
        assertEquals(0, second.process().exitValue(), second.output());

        assertFalse(Files.exists(journal.file()), "the dead sandbox journal was not recovered: " + second.output());
        assertFalse(Files.exists(lock), "the dead sandbox lock file was not removed: " + second.output());
        for (String path : journal.paths()) {
            assertFalse(fixture.acl(path).contains(journal.sid()),
                    "the dead sandbox kept its ACL on " + path + ": " + second.output());
        }
    }

    @Test
    void aNormalExitLeavesNeitherJournalNorLockNorGrant() throws Exception {
        Fixture fixture = fixture("normal");
        Sandbox only = fixture.start("only", "exit 0\n");
        assertTrue(only.process().waitFor(90, TimeUnit.SECONDS), "launcher timed out: " + only.output());
        assertEquals(0, only.process().exitValue(), only.output());

        assertEquals(List.of(), fixture.recoveryFiles(CURRENT_RECOVERY), only.output());
    }

    @Test
    void aJournalWithoutAnOwnerLockIsLeftInPlaceAndReported() throws Exception {
        Fixture fixture = fixture("nolock");
        Foreign foreign = Foreign.create(fixture, CURRENT_RECOVERY, "nolock", false);
        try {
            Sandbox second = fixture.start("second", "exit 0\n");
            assertTrue(second.process().waitFor(90, TimeUnit.SECONDS), "launcher timed out: " + second.output());
            assertEquals(0, second.process().exitValue(), second.output());

            assertTrue(Files.exists(foreign.journal()), "a journal without proof of death was recovered");
            assertTrue(fixture.acl(foreign.grantRoot().toString()).contains(FOREIGN_SID),
                    "a journal without proof of death lost its grant");
            assertTrue(second.output().contains(foreign.journal().getFileName().toString()),
                    "the unrecoverable journal was not reported: " + second.output());
        } finally {
            foreign.cleanup(fixture);
        }
    }

    @Test
    void anUnreadableOwnerLockIsNeverTreatedAsProofOfDeath() throws Exception {
        Fixture fixture = fixture("unreadable");
        Foreign foreign = Foreign.create(fixture, CURRENT_RECOVERY, "unreadable", true);
        try {
            fixture.run("icacls.exe", foreign.lock().toString(), "/deny", fixture.user() + ":(R,W)");
            assumeTrue(!canOpenForWriting(foreign.lock()),
                    "this account can still open the lock file despite the deny ACE");

            Sandbox second = fixture.start("second", "exit 0\n");
            assertTrue(second.process().waitFor(90, TimeUnit.SECONDS), "launcher timed out: " + second.output());
            assertEquals(0, second.process().exitValue(), second.output());

            assertTrue(Files.exists(foreign.journal()), "an unreadable lock was treated as proof of death");
            assertTrue(fixture.acl(foreign.grantRoot().toString()).contains(FOREIGN_SID),
                    "an unreadable lock let the grant be removed");
            assertTrue(second.output().contains(foreign.journal().getFileName().toString()),
                    "the unrecoverable journal was not reported: " + second.output());
        } finally {
            fixture.run("icacls.exe", foreign.lock().toString(), "/remove:d", fixture.user());
            foreign.cleanup(fixture);
        }
    }

    @Test
    void aLauncherThatCannotCreateItsOwnLockRefusesToStartAndLeavesNothing() throws Exception {
        Fixture fixture = fixture("refuse");
        Prepared prepared = fixture.prepare("refused", "param([string] $Out)\n[System.IO.File]::WriteAllText($Out, 'ran')\nexit 0\n");
        Path recovery = fixture.home().resolve("sandbox").resolve(CURRENT_RECOVERY);
        assertTrue(Files.isDirectory(recovery), "the plan is expected to create its recovery directory");
        String before = fixture.acl(prepared.working().toString());
        fixture.run("icacls.exe", recovery.toString(), "/deny", fixture.user() + ":(WD,AD)");
        try {
            Sandbox refused = prepared.launch();
            assertTrue(refused.process().waitFor(90, TimeUnit.SECONDS), "launcher timed out: " + refused.output());

            assertNotEquals(0, refused.process().exitValue(), "a launcher without its lock must fail: " + refused.output());
            assertFalse(Files.exists(refused.marker()), "a provider ran without a proven owner: " + refused.output());
            assertEquals(before, fixture.acl(prepared.working().toString()),
                    "a refused launcher left a grant behind: " + refused.output());
        } finally {
            fixture.run("icacls.exe", recovery.toString(), "/remove:d", fixture.user());
        }
        assertEquals(List.of(), fixture.recoveryFiles(CURRENT_RECOVERY), "a refused launcher left recovery state");
    }

    @Test
    void aJournalOfThePreviousFormatIsNeverExaminedAndTheNewJournalGoesElsewhere() throws Exception {
        Fixture fixture = fixture("legacy");
        Foreign legacy = Foreign.create(fixture, LEGACY_RECOVERY, "legacy", false);
        Sandbox second = fixture.start("second", """
                param([string] $Out)
                Start-Sleep -Seconds 8
                [System.IO.File]::WriteAllText($Out, 'ok')
                exit 0
                """);
        try {
            Journal current = fixture.awaitJournalWithGrants(second, Duration.ofSeconds(60), CURRENT_RECOVERY);
            assertEquals(CURRENT_RECOVERY, current.file().getParent().getFileName().toString());
            assertEquals(List.of(legacy.journal().getFileName().toString()), fixture.recoveryFiles(LEGACY_RECOVERY),
                    "the new launcher wrote into, or removed from, the previous recovery directory");
            assertTrue(second.process().waitFor(90, TimeUnit.SECONDS), "launcher timed out: " + second.output());
            assertEquals(0, second.process().exitValue(), second.output());

            assertTrue(Files.exists(legacy.journal()), "the previous format journal was recovered");
            assertTrue(fixture.acl(legacy.grantRoot().toString()).contains(FOREIGN_SID),
                    "the previous format journal lost its grant");
            assertFalse(second.output().contains(legacy.journal().getFileName().toString()),
                    "the previous format journal was examined: " + second.output());
        } finally {
            second.destroy();
            legacy.cleanup(fixture);
        }
    }

    @Test
    void anOrphanLockThatNobodyHoldsIsRemoved() throws Exception {
        Fixture fixture = fixture("orphan");
        Path recovery = Files.createDirectories(fixture.home().resolve("sandbox").resolve(CURRENT_RECOVERY));
        Path orphan = Files.createFile(recovery.resolve("Minos.Worker.orphan.lock"));

        Sandbox first = fixture.start("first", "exit 0\n");
        assertTrue(first.process().waitFor(90, TimeUnit.SECONDS), "launcher timed out: " + first.output());
        assertEquals(0, first.process().exitValue(), first.output());

        assertFalse(Files.exists(orphan), "an orphan lock that nobody holds must be removed: " + first.output());
    }

    private static boolean canOpenForWriting(Path file) {
        try (var ignored = Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static Path lockOf(Path journal) {
        String name = journal.getFileName().toString();
        return journal.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".lock");
    }

    private static Fixture fixture(String name) throws IOException {
        Path home = Files.createTempDirectory("minos-appcontainer-owner-" + name + "-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows AppContainer backend is required");
        return new Fixture(home, discovered.orElseThrow(),
                CommandLocator.windowsPowerShell().orElseThrow(() -> new AssertionError("PowerShell is unavailable")));
    }

    private record Fixture(Path home, WindowsAppContainerWorkerSandboxBackend backend, Path powershell) {

        /** Plans and starts the real launcher. */
        Sandbox start(String label, String body) throws Exception {
            return prepare(label, body).launch();
        }

        /** Plans the real launcher around a provider script or a bare command, without starting it. */
        Prepared prepare(String label, String body) throws Exception {
            Path working = Files.createTempDirectory("minos-appcontainer-owner-" + label + "-working-");
            Path run = Files.createTempDirectory("minos-appcontainer-owner-" + label + "-run-");
            Path marker = working.resolve("ok.txt");
            List<String> command = new ArrayList<>(List.of(
                    powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive"));
            if (body.startsWith("param(")) {
                Path script = working.resolve("provider.ps1");
                Files.writeString(script, body, StandardCharsets.US_ASCII);
                command.addAll(List.of("-ExecutionPolicy", "Bypass", "-File", script.toString(), marker.toString()));
            } else {
                command.addAll(List.of("-Command", body.strip()));
            }
            IndexerProcessPlan original = new IndexerProcessPlan(
                    command, working, Map.of(), run.resolve("index.scip"), PROVIDER_TIMEOUT);
            IndexerProcessPlan sandboxed = backend.sandboxPlan(original, run);
            return new Prepared(sandboxed.command(), working, run, marker);
        }

        Journal awaitJournalWithGrants(Sandbox sandbox, Duration timeout) throws Exception {
            return awaitJournalWithGrants(sandbox, timeout, null);
        }

        Journal awaitJournalWithGrants(Sandbox sandbox, Duration timeout, String directory) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (!sandbox.process().isAlive()) {
                    throw new AssertionError("the launcher exited before granting its sandbox: " + sandbox.output());
                }
                Optional<Journal> journal = findJournal(directory);
                if (journal.isPresent() && !journal.get().paths().isEmpty()) return journal.get();
                Thread.sleep(200);
            }
            throw new AssertionError("no recovery journal with grants appeared: " + sandbox.output());
        }

        Optional<Journal> findJournal(String onlyDirectory) throws IOException {
            Path sandboxRoot = home.resolve("sandbox");
            if (!Files.isDirectory(sandboxRoot)) return Optional.empty();
            String glob = onlyDirectory == null ? "appcontainer-recovery*" : onlyDirectory;
            try (DirectoryStream<Path> directories = Files.newDirectoryStream(sandboxRoot, glob)) {
                for (Path directory : directories) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "Minos.Worker.*.json")) {
                        for (Path file : files) {
                            Journal journal = Journal.read(file);
                            if (journal != null) return Optional.of(journal);
                        }
                    }
                }
            }
            return Optional.empty();
        }

        /** Names of every file left in a recovery directory, sorted. */
        List<String> recoveryFiles(String directory) throws IOException {
            Path path = home.resolve("sandbox").resolve(directory);
            if (!Files.isDirectory(path)) return List.of();
            try (var stream = Files.list(path)) {
                return stream.map(file -> file.getFileName().toString()).sorted().toList();
            }
        }

        String user() {
            String name = System.getProperty("user.name");
            String domain = System.getenv("USERDOMAIN");
            return domain == null || domain.isBlank() ? name : domain + "\\" + name;
        }

        String acl(String path) throws Exception {
            return run("icacls.exe", path);
        }

        String run(String... command) throws Exception {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            return output;
        }
    }

    private record Prepared(List<String> command, Path working, Path run, Path marker) {
        Sandbox launch() throws IOException {
            Path log = run.resolve("launcher.log");
            Process process = new ProcessBuilder(command)
                    .directory(working.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            return new Sandbox(process, marker, log);
        }
    }

    private record Sandbox(Process process, Path marker, Path log) {
        String output() {
            try {
                // Console output uses the OEM code page: decode leniently, never fail on an accented character.
                return Files.exists(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
            } catch (IOException exception) {
                return "<unreadable launcher log>";
            }
        }

        void destroy() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /**
     * A recovery journal planted by the test, with a real grant of a foreign SID on a real directory, so that a
     * wrongful recovery is observable: the journal disappears and the grant is removed.
     */
    private record Foreign(Path journal, Path lock, Path grantRoot) {
        static Foreign create(Fixture fixture, String directory, String name, boolean withLock) throws Exception {
            Path recovery = Files.createDirectories(fixture.home().resolve("sandbox").resolve(directory));
            Path grantRoot = Files.createTempDirectory("minos-appcontainer-owner-" + name + "-grant-");
            fixture.run("icacls.exe", grantRoot.toString(), "/grant", "*" + FOREIGN_SID + ":(OI)(CI)R", "/q");
            assertTrue(fixture.acl(grantRoot.toString()).contains(FOREIGN_SID), "the foreign grant could not be set up");
            Path journal = recovery.resolve("Minos.Worker." + name + ".json");
            Files.writeString(journal, "{\"profile\":\"Minos.Worker." + name + "\",\"sid\":\"" + FOREIGN_SID
                    + "\",\"paths\":[\"" + grantRoot.toString().replace("\\", "\\\\") + "\"]}", StandardCharsets.UTF_8);
            Path lock = recovery.resolve("Minos.Worker." + name + ".lock");
            if (withLock) Files.createFile(lock);
            return new Foreign(journal, lock, grantRoot);
        }

        void cleanup(Fixture fixture) throws Exception {
            fixture.run("icacls.exe", grantRoot.toString(), "/remove:g", "*" + FOREIGN_SID, "/q");
        }
    }

    private record Journal(Path file, String profile, String sid, List<String> paths) {
        static Journal read(Path file) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String profile = field(text, "profile");
                String sid = field(text, "sid");
                if (profile == null || sid == null) return null;
                List<String> paths = new ArrayList<>();
                Matcher array = Pattern.compile("\"paths\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(text);
                if (array.find()) {
                    Matcher item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(array.group(1));
                    while (item.find()) paths.add(item.group(1).replace("\\\\", "\\"));
                }
                return new Journal(file, profile, sid, paths);
            } catch (IOException exception) {
                return null;
            }
        }

        private static String field(String text, String name) {
            Matcher matcher = Pattern.compile(String.format(STRING_FIELD.pattern(), name)).matcher(text);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}

package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A read-only root a plan declares is checked again by the Linux sandbox, then mounted exactly, alone and after
 * every writable root; a masked file is presented empty. Nothing here runs a provider: the command is the contract.
 */
@EnabledOnOs(OS.LINUX)
class LinuxSandboxReadOnlyRootTest {

    private static final Path TRUE = Path.of("/bin/true");

    @TempDir Path temporary;

    @Test
    void aDeclaredRootIsMountedReadOnlyAloneAfterTheWritableRootsWithoutTheNetwork() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("profile"));
        Files.writeString(parent.resolve("secret.txt"), "secret");
        Path repository = Files.createDirectory(parent.resolve("repository"));

        IndexerProcessPlan sandboxed = sandboxed(List.of(repository), List.of());
        List<String> command = sandboxed.command();

        String real = repository.toRealPath().toString();
        int readOnly = indexOf(command, "--ro-bind", real, real);
        assertTrue(readOnly >= 0, command::toString);
        assertTrue(readOnly > lastIndexOf(command, "--bind"), "the read-only root comes after every writable bind");
        assertFalse(mountsSource(command, parent.toRealPath().toString()), "the parent is never mounted");
        assertFalse(command.contains("--share-net"), "the network stays DENY");
        assertEquals(List.of(repository), sandboxed.readOnlyRoots(), "the sandboxed plan keeps its declaration");
    }

    @Test
    void aMaskedFileIsBoundToDevNullAfterTheSystemRoots() throws Exception {
        Path conf = Files.createDirectories(temporary.resolve("maven/conf"));
        Path settings = Files.writeString(conf.resolve("settings.xml"), "<settings><servers/></settings>");

        List<String> command = sandboxed(List.of(), List.of(settings, conf.resolve("absent.xml"))).command();

        String real = settings.toRealPath().toString();
        int mask = indexOf(command, "--ro-bind", "/dev/null", real);
        assertTrue(mask >= 0, command::toString);
        assertTrue(mask > indexOf(command, "--ro-bind", "/usr", "/usr"), "the mask comes after the system roots");
        assertFalse(command.contains(conf.resolve("absent.xml").toString()), "an absent file needs no mask");
    }

    @Test
    void aRelativeRootIsRefused() {
        assertRefused(List.of(Path.of("relative/repository")));
    }

    @Test
    void anAbsentRootIsRefused() {
        assertRefused(List.of(temporary.resolve("absent")));
    }

    @Test
    void aRootReachedThroughALinkedComponentIsRefusedWithoutAPath() throws Exception {
        Path repository = Files.createDirectories(temporary.resolve("data/repository"));
        Path link = Files.createSymbolicLink(temporary.resolve("linked"), repository.getParent());

        IllegalStateException refused = assertRefused(List.of(link.resolve("repository")));

        assertTrue(refused.getMessage().contains("symbolic link"), refused.getMessage());
        assertFalse(refused.getMessage().contains(temporary.toString()), refused.getMessage());
    }

    @Test
    void aRootUnderADirectoryWritableByOtherAccountsIsRefused() throws Exception {
        Path shared = Files.createDirectory(temporary.resolve("shared"));
        Path repository = Files.createDirectory(shared.resolve("repository"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));

        IllegalStateException refused = assertRefused(List.of(repository));

        assertTrue(refused.getMessage().contains("writable by other accounts"), refused.getMessage());
    }

    @Test
    void aRootThatOverlapsTheRunOrTheWorkspaceIsRefused() throws Exception {
        Path run = Files.createDirectories(temporary.resolve("run"));
        Path insideRun = Files.createDirectories(run.resolve("maven"));
        Path working = Files.createDirectories(temporary.resolve("workspace"));

        assertRefused(List.of(insideRun));
        assertRefused(List.of(working));
        assertRefused(List.of(temporary));
    }

    @Test
    void aRootInsideMinosHomeIsRefused() throws Exception {
        Path inside = Files.createDirectories(temporary.resolve("minos-home/tools/maven-repository"));
        assertRefused(List.of(inside));
    }

    @Test
    void aPlanWithoutMountsGetsExactlyTheCommandOfAPlanThatNeverDeclaredThem() throws Exception {
        IndexerProcessPlan plain = rawPlan(List.of(), List.of());
        IndexerProcessPlan legacy = new IndexerProcessPlan(plain.command(), plain.workingDirectory(),
                plain.environment(), plain.generatedArtifact(), plain.timeout());

        assertEquals(backend().sandboxPlan(legacy, run(), WorkerNetworkPolicy.DENY).command(),
                backend().sandboxPlan(plain, run(), WorkerNetworkPolicy.DENY).command());
        assertFalse(backend().sandboxPlan(plain, run(), WorkerNetworkPolicy.DENY).command().contains("/dev/null"));
    }

    @Test
    void qualificationDeclaresTheReadOnlyRootRule() {
        assertTrue(new LinuxBubblewrapWorkerSandboxBackend(TRUE, TRUE).qualification().limitations()
                .contains("LINUX_PLAN_DECLARED_READ_ONLY_ROOTS_MOUNTED_EXACTLY_AFTER_WRITABLE_ROOTS"));
    }

    private IllegalStateException assertRefused(List<Path> roots) {
        return assertThrows(IllegalStateException.class, () -> sandboxed(roots, List.of()));
    }

    private IndexerProcessPlan sandboxed(List<Path> roots, List<Path> masked) throws Exception {
        return backend().sandboxPlan(rawPlan(roots, masked), run(), WorkerNetworkPolicy.DENY);
    }

    private IndexerProcessPlan rawPlan(List<Path> roots, List<Path> masked) throws Exception {
        Path working = Files.createDirectories(temporary.resolve("workspace"));
        return new IndexerProcessPlan(List.of(TRUE.toString()), working, Map.of(), working.resolve("index.scip"),
                Duration.ofSeconds(10), roots, masked);
    }

    private Path run() throws Exception {
        return Files.createDirectories(temporary.resolve("run"));
    }

    private LinuxBubblewrapWorkerSandboxBackend backend() throws Exception {
        return new LinuxBubblewrapWorkerSandboxBackend(
                Files.createDirectories(temporary.resolve("minos-home")), TRUE, TRUE);
    }

    private static int indexOf(List<String> command, String option, String source, String destination) {
        for (int index = 0; index + 2 < command.size(); index++) {
            if (option.equals(command.get(index)) && source.equals(command.get(index + 1))
                    && destination.equals(command.get(index + 2))) return index;
        }
        return -1;
    }

    private static int lastIndexOf(List<String> command, String option) {
        return command.lastIndexOf(option);
    }

    private static boolean mountsSource(List<String> command, String source) {
        for (int index = 0; index + 1 < command.size(); index++) {
            String option = command.get(index);
            if (("--ro-bind".equals(option) || "--bind".equals(option)) && source.equals(command.get(index + 1))) {
                return true;
            }
        }
        return false;
    }
}

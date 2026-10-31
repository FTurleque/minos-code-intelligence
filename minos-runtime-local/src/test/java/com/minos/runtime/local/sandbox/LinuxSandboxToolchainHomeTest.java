package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The project JDK named by JAVA_HOME must be usable inside the Linux sandbox: its whole root is
 * mounted read-only, its configuration links reach only allow-listed Java configuration under
 * /etc, and an over-broad root is refused before anything is launched.
 */
@EnabledOnOs(OS.LINUX)
class LinuxSandboxToolchainHomeTest {

    private static final Path HOST_CA_BUNDLE = Path.of("/etc/ssl/certs/ca-certificates.crt");

    @TempDir Path temporary;

    @Test
    void selfContainedJdkOutsideUsrRunsInsideTheSandbox() throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        List<String> output = runSandboxed(javaHome, List.of("-version"));
        assertEquals("0", output.get(0), () -> "java -version failed in the sandbox: " + output);
    }

    @Test
    void distributionPackagedJdkReadsItsSecurityConfigurationInsideTheSandbox() throws Exception {
        Optional<Path> packaged = packagedJdk();
        assumeTrue(packaged.isPresent(),
                "hypothesis: a distribution-packaged JDK whose conf/security/java.security links under /etc "
                        + "is installed under /usr/lib/jvm");
        List<String> output = runSandboxed(packaged.orElseThrow(),
                List.of("-XshowSettings:security:properties", "-version"));
        assertEquals("0", output.get(0), () -> "packaged JDK failed in the sandbox: " + output);
        assertFalse(output.get(1).contains("Error loading java.security file"), output.get(1));
    }

    @Test
    void jdkRootIsMountedReadOnlyButNotItsParent() throws Exception {
        Path profile = Files.createDirectory(temporary.resolve("profile"));
        Files.writeString(profile.resolve("secret.txt"), "secret");
        Path jdk = fakeJdk(profile.resolve("jdk"));

        List<String> command = plan(jdk).command();

        assertTrue(hasBind(command, "--ro-bind", jdk.toRealPath().toString(), jdk.toRealPath().toString()), command::toString);
        assertFalse(mountsSource(command, profile.toRealPath().toString()), command::toString);
        assertFalse(mountsSource(command, profile.resolve("secret.txt").toRealPath().toString()), command::toString);
        assertFalse(hasBind(command, "--bind", jdk.toRealPath().toString(), jdk.toRealPath().toString()), command::toString);
    }

    @Test
    void jdkReachedThroughASymbolicLinkIsVisibleAtTheNamedPath() throws Exception {
        Path jdk = fakeJdk(temporary.resolve("jdks/temurin-25"));
        Path link = Files.createSymbolicLink(temporary.resolve("current-jdk"), jdk);

        List<String> command = plan(link).command();

        assertTrue(hasBind(command, "--ro-bind", jdk.toRealPath().toString(), jdk.toRealPath().toString()), command::toString);
        assertTrue(hasBind(command, "--symlink", jdk.toString(), link.toString())
                || hasBind(command, "--ro-bind", jdk.toRealPath().toString(), link.toString()), command::toString);
    }

    @Test
    void allowListedEtcLinkTargetIsMountedExactlyNeverItsDirectory() throws Exception {
        assumeTrue(Files.isRegularFile(HOST_CA_BUNDLE),
                "hypothesis: the host ships the CA bundle " + HOST_CA_BUNDLE);
        Path jdk = fakeJdk(temporary.resolve("jdk"));
        Path security = Files.createDirectories(jdk.resolve("lib/security"));
        Files.createSymbolicLink(security.resolve("cacerts"), HOST_CA_BUNDLE);

        List<String> command = plan(jdk).command();

        String target = HOST_CA_BUNDLE.toRealPath().toString();
        assertTrue(hasBind(command, "--ro-bind", target, target), command::toString);
        assertFalse(mountsSource(command, "/etc"), command::toString);
        assertFalse(mountsSource(command, "/etc/ssl"), command::toString);
        assertFalse(mountsSource(command, "/etc/ssl/certs"), command::toString);
    }

    @Test
    void plantedLinksToTheProfileOrToEtcSecretsAreNeverMounted() throws Exception {
        Path profile = Files.createDirectory(temporary.resolve("profile"));
        Path secret = Files.writeString(profile.resolve("id_ed25519"), "private key");
        Path jdk = fakeJdk(temporary.resolve("jdk"));
        Path conf = Files.createDirectories(jdk.resolve("conf"));
        Files.createSymbolicLink(conf.resolve("leak.properties"), secret);
        Files.createSymbolicLink(conf.resolve("shadow.properties"), Path.of("/etc/shadow"));
        Files.createSymbolicLink(conf.resolve("ssh"), Path.of("/etc/ssh"));

        List<String> command = plan(jdk).command();

        assertFalse(mountsSource(command, secret.toRealPath().toString()), command::toString);
        assertFalse(mountsSource(command, profile.toRealPath().toString()), command::toString);
        assertFalse(mountsSource(command, "/etc/shadow"), command::toString);
        assertFalse(mountsSource(command, "/etc/ssh"), command::toString);
        assertFalse(mountsSource(command, "/etc"), command::toString);
    }

    @Test
    void filesystemRootAsJavaHomeIsRefusedWithoutAPath() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> plan(Path.of("/")));
        assertTrue(refused.getMessage().contains("JAVA_HOME"), refused.getMessage());
        assertFalse(refused.getMessage().contains("/"), refused.getMessage());
    }

    @Test
    void homeDirectoryAsJavaHomeIsRefusedBeforeLaunch() throws Exception {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(home), "hypothesis: user.home names an existing directory");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> plan(home));
        assertTrue(refused.getMessage().contains("JAVA_HOME"), refused.getMessage());
        assertFalse(refused.getMessage().contains(home.toString()), refused.getMessage());
    }

    @Test
    void javaHomeLinkedToANonJdkProfileDirectoryIsRefusedAndNotMounted() throws Exception {
        Path ssh = Files.createDirectories(temporary.resolve("profile/.ssh"));
        Files.writeString(ssh.resolve("id_ed25519"), "private key");
        Path planted = Files.createSymbolicLink(temporary.resolve("project-jdk"), ssh);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> plan(planted));

        assertTrue(refused.getMessage().contains("JAVA_HOME"), refused.getMessage());
        assertFalse(refused.getMessage().contains(ssh.toString()), refused.getMessage());
    }

    @Test
    void jdkInsideMinosHomeIsRefusedUnlessItIsAManagedTool() throws Exception {
        Path minosHome = temporary.resolve("minos-home");
        Path privateJdk = fakeJdk(minosHome.resolve("registry/jdk"));
        assertThrows(IllegalStateException.class, () -> plan(privateJdk));

        Path managedJdk = fakeJdk(minosHome.resolve("tools/jdk/25.0.4"));
        List<String> command = plan(managedJdk).command();
        assertTrue(hasBind(command, "--ro-bind", managedJdk.toRealPath().toString(),
                managedJdk.toRealPath().toString()), command::toString);
    }

    @Test
    void etcDirectoryAsJavaHomeIsRefused() {
        assertThrows(IllegalStateException.class, () -> plan(Path.of("/etc/ssl")));
    }

    @Test
    void qualificationDeclaresTheToolchainMountRule() {
        Path executable = Path.of("/bin/true");
        LinuxBubblewrapWorkerSandboxBackend backend = new LinuxBubblewrapWorkerSandboxBackend(executable, executable);
        assertTrue(backend.qualification().limitations()
                .contains("LINUX_TOOLCHAIN_HOME_READ_ONLY_ROOT_AND_ALLOWLISTED_ETC_LINK_TARGETS"));
    }

    private IndexerProcessPlan plan(Path javaHome) throws Exception {
        return plan(javaHome, new LinuxBubblewrapWorkerSandboxBackend(
                Files.createDirectories(temporary.resolve("minos-home")), Path.of("/bin/true"), Path.of("/bin/true")),
                List.of(javaHome.resolve("bin/java").toString(), "-version"));
    }

    private IndexerProcessPlan plan(Path javaHome, LinuxBubblewrapWorkerSandboxBackend backend, List<String> command)
            throws Exception {
        Path working = Files.createDirectories(temporary.resolve("workspace"));
        Path run = Files.createDirectories(temporary.resolve("run"));
        IndexerProcessPlan raw = new IndexerProcessPlan(
                command, working, Map.of("JAVA_HOME", javaHome.toString()),
                working.resolve("index.scip"), Duration.ofSeconds(60));
        return backend.sandboxPlan(raw, run, WorkerNetworkPolicy.DENY);
    }

    /** Runs {@code <javaHome>/bin/java <arguments>} in the real sandbox; returns [exit code, output]. */
    private List<String> runSandboxed(Path javaHome, List<String> arguments) throws Exception {
        Optional<Path> bwrap = CommandLocator.find("bwrap");
        Optional<Path> prlimit = CommandLocator.find("prlimit");
        SandboxTestSupport.decide(SandboxTestSupport.required(), bwrap.isPresent() && prlimit.isPresent(),
                () -> "bubblewrap and prlimit are required to run a JVM in the Linux sandbox");
        Path minosHome = Files.createDirectories(temporary.resolve("minos-home"));
        LinuxBubblewrapWorkerSandboxBackend backend =
                new LinuxBubblewrapWorkerSandboxBackend(minosHome, bwrap.orElseThrow(), prlimit.orElseThrow());
        List<String> command = new ArrayList<>();
        command.add(javaHome.resolve("bin/java").toString());
        command.addAll(arguments);
        IndexerProcessPlan sandboxed = plan(javaHome, backend, command);

        ProcessBuilder builder = new ProcessBuilder(sandboxed.command())
                .directory(sandboxed.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("JAVA_HOME", javaHome.toString());
        Process process = builder.start();
        byte[] bytes = process.getInputStream().readNBytes(64 * 1024);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("sandboxed JVM did not finish in time");
        }
        return List.of(Integer.toString(process.exitValue()), new String(bytes, StandardCharsets.UTF_8));
    }

    private static Optional<Path> packagedJdk() throws IOException {
        Path jvm = Path.of("/usr/lib/jvm");
        if (!Files.isDirectory(jvm)) return Optional.empty();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(jvm, "java-*-openjdk-*")) {
            for (Path entry : entries) {
                Path security = entry.resolve("conf/security/java.security");
                if (!Files.isSymbolicLink(entry) && Files.isSymbolicLink(security)
                        && Files.readSymbolicLink(security).startsWith("/etc")
                        && Files.isRegularFile(entry.resolve("bin/java"))) {
                    return Optional.of(entry.toRealPath());
                }
            }
        }
        return Optional.empty();
    }

    private static Path fakeJdk(Path root) throws IOException {
        Path bin = Files.createDirectories(root.resolve("bin"));
        Files.writeString(bin.resolve("java"), "#!/bin/sh\n");
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/libjli.so"), "lib");
        Files.writeString(root.resolve("release"), "JAVA_VERSION=\"25\"\n");
        return root;
    }

    private static boolean hasBind(List<String> command, String option, String source, String destination) {
        for (int index = 0; index + 2 < command.size(); index++) {
            if (option.equals(command.get(index))
                    && source.equals(command.get(index + 1))
                    && destination.equals(command.get(index + 2))) return true;
        }
        return false;
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

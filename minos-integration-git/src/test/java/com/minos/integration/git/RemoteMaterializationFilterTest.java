package com.minos.integration.git;

import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.remote.RemoteRepositoryRequest.RemoteHost;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.attributes.FilterCommand;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repository fetched on request is not trusted to run a command: no {@code filter.*} driver of any
 * configuration (repository, user, system) is executed while it is cloned, checked out or revalidated, and
 * the content that lands on disk is the raw content of the Git objects.
 *
 * <p>The user configuration is the one thing the repository cannot write, so the tests redirect it: each
 * scenario that needs a driver runs in a child JVM whose home directory holds the configuration under test.
 * The trap driver is {@link TrustWitness}, started by {@code java} (no shell); its witness file proves it ran.</p>
 */
class RemoteMaterializationFilterTest {

    private static final String TRACKED = "fixtures/java/pom.xml";
    private static final String CONTENT = "<project>raw</project>\n";
    private static final String ATTRIBUTES = "* filter=piege\n";

    @Test
    void aUserSmudgeFilterIsNotRunWhenARemoteRepositoryIsCloned(@TempDir Path temp) throws Exception {
        Path source = sourceRepository(temp.resolve("source"));
        Path witness = temp.resolve("witness-smudge");
        Path home = userHome(temp.resolve("home"), "\tsmudge = " + command(witness) + "\n\tclean = " + command(witness) + "\n");
        Path destination = temp.resolve("clone").resolve("repository");

        Result result = runChild(home, "clone", source.toString(), destination.toString());

        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(witness), "a filter of the user configuration ran at the clone: " + result.output());
        assertEquals(CONTENT, rawContent(destination.resolve(TRACKED)), "the content is not the raw content");
    }

    @Test
    void aDriverThatAsksForABuiltInFilterIsNotRunEither(@TempDir Path temp) throws Exception {
        Path source = sourceRepository(temp.resolve("source"));
        Path witness = temp.resolve("witness-builtin");
        Path home = userHome(temp.resolve("home"),
                "\tuseJGitBuiltin = true\n\tsmudge = " + command(temp.resolve("witness-command")) + "\n");
        Path destination = temp.resolve("clone").resolve("repository");

        Result result = runChild(home, "builtin-clone", source.toString(), destination.toString(), witness.toString());

        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(witness), "a registered built-in filter ran at the clone: " + result.output());
        assertFalse(Files.exists(temp.resolve("witness-command")), result.output());
        assertEquals(CONTENT, rawContent(destination.resolve(TRACKED)));
    }

    @Test
    void aCachedEntryIsRevalidatedWithoutRunningAUserFilter(@TempDir Path temp) throws Exception {
        Path source = sourceRepository(temp.resolve("source"));
        Path minosHome = Files.createDirectories(temp.resolve("minos-home"));
        Path plainHome = Files.createDirectories(temp.resolve("home-plain"));
        Result built = runChild(plainHome, "materialize", source.toString(), minosHome.toString(), "miss");
        assertEquals(0, built.exit(), built.output());
        touchTrackedFileOfTheEntry(minosHome);

        Path witness = temp.resolve("witness-clean");
        Path trapHome = userHome(temp.resolve("home-trap"), "\tclean = " + command(witness) + "\n");
        Result reused = runChild(trapHome, "materialize", source.toString(), minosHome.toString(), "hit");

        assertEquals(0, reused.exit(), reused.output());
        assertFalse(Files.exists(witness), "a filter of the user configuration ran at the revalidation: " + reused.output());
    }

    @Test
    void anEntryOfAnotherAccountIsRefusedAtRevalidation(@TempDir Path temp) throws Exception {
        Path source = sourceRepository(temp.resolve("source"));
        Path home = Files.createDirectories(temp.resolve("minos-home"));
        RemoteRepositoryRequest request = request(head(source));
        JGitRemoteRepositoryMaterializer owner = materializer(home, source, null);
        RemoteMaterialization first = owner.materialize(request);
        owner.release(first);
        JGitRemoteRepositoryMaterializer stranger = materializer(home, source, () -> principal("autre-compte"));

        IOException refused = assertThrows(IOException.class, () -> stranger.materialize(request));

        assertTrue(refused.getMessage().contains("autre-compte"), refused.getMessage());
        assertFalse(refused.getMessage().contains(temp.toString()), "absolute path in the refusal");
    }

    // -- helpers ----------------------------------------------------------------------------

    /** Clones a local repository through the production clone code, whose transport check only knows HTTPS. */
    public static final class LocalSourceClient implements JGitRemoteRepositoryMaterializer.RemoteGitClient {
        private final Path source;

        LocalSourceClient(Path source) {
            this.source = source;
        }

        @Override
        public void cloneRepository(
                RemoteRepositoryRequest request,
                Path destination,
                char[] secret,
                JGitRemoteRepositoryMaterializer.CloneBudget budget
        ) throws Exception {
            new JGitRemoteGitClient().cloneFrom(
                    source.toUri().toString(), request.reference(), request.host(), destination, secret, budget,
                    transport -> { });
            try (Git cloned = Git.open(destination.toFile())) {
                cloned.getRepository().getConfig().setString(
                        "remote", "origin", "url", request.canonicalRepositoryUri());
                cloned.getRepository().getConfig().save();
            }
        }
    }

    /** A built-in filter that records its run, registered under the name a driver asks for. */
    private static final class WitnessBuiltin extends FilterCommand {
        private final Path witness;
        private final byte[] buffer = new byte[8192];

        private WitnessBuiltin(InputStream in, OutputStream out, Path witness) {
            super(in, out);
            this.witness = witness;
        }

        @Override
        public int run() throws IOException {
            Files.writeString(witness, "executed", StandardCharsets.UTF_8);
            int read = in.read(buffer);
            if (read < 0) {
                in.close();
                out.close();
                return -1;
            }
            out.write(buffer, 0, read);
            return read;
        }
    }

    /** Runs in a child JVM so that the user configuration really is the redirected one. */
    public static final class Probe {
        public static void main(String[] arguments) throws Exception {
            String mode = arguments[0];
            Path source = Path.of(arguments[1]);
            switch (mode) {
                case "clone" -> clone(source, Path.of(arguments[2]));
                case "builtin-clone" -> {
                    Path witness = Path.of(arguments[3]);
                    FilterCommandRegistry.register("jgit://builtin/piege/smudge",
                            (repository, in, out) -> new WitnessBuiltin(in, out, witness));
                    FilterCommandRegistry.register("jgit://builtin/piege/clean",
                            (repository, in, out) -> new WitnessBuiltin(in, out, witness));
                    clone(source, Path.of(arguments[2]));
                }
                case "materialize" -> {
                    JGitRemoteRepositoryMaterializer materializer = materializer(Path.of(arguments[2]), source, null);
                    RemoteMaterialization materialization = materializer.materialize(request(head(source)));
                    materializer.release(materialization);
                    if (materialization.cacheHit() != "hit".equals(arguments[3])) {
                        System.err.println("unexpected cache state: hit=" + materialization.cacheHit());
                        System.exit(2);
                    }
                }
                default -> throw new IllegalArgumentException(mode);
            }
        }

        private static void clone(Path source, Path destination) throws Exception {
            new JGitRemoteGitClient().cloneFrom(
                    source.toUri().toString(), "refs/heads/main", RemoteHost.GITHUB, destination, null,
                    new JGitRemoteRepositoryMaterializer.CloneBudget(destination, RemoteRepositoryCachePolicy.DEFAULT),
                    transport -> { });
        }
    }

    private record Result(int exit, String output) {
    }

    private static Result runChild(Path home, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("USERPROFILE", home.toString());
        builder.environment().put("XDG_CONFIG_HOME", home.resolve("xdg").toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    /** The checked-out text with line endings normalised: {@code core.autocrlf} of the host is not under test. */
    private static String rawContent(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }

    private static Path userHome(Path home, String driverBody) throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve(".gitconfig"), "[filter \"piege\"]\n" + driverBody, StandardCharsets.UTF_8);
        return home;
    }

    private static Path sourceRepository(Path root) throws Exception {
        JGitRemoteRepositoryMaterializerTest.createRepository(root);
        Files.writeString(root.resolve(TRACKED), CONTENT, StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".gitattributes"), ATTRIBUTES, StandardCharsets.UTF_8);
        try (Git git = Git.open(root.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("filters").setAuthor("Dev", "dev@example.com")
                    .setCommitter("Dev", "dev@example.com").call();
        }
        return root;
    }

    private static void touchTrackedFileOfTheEntry(Path minosHome) throws IOException {
        try (Stream<Path> entries = Files.list(minosHome.resolve("remote-cache/repositories"))) {
            Path entry = entries.filter(Files::isDirectory).filter(path -> !path.getFileName().toString().startsWith("."))
                    .findFirst().orElseThrow();
            Files.setLastModifiedTime(entry.resolve("repository").resolve(TRACKED),
                    FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        }
    }

    private static JGitRemoteRepositoryMaterializer materializer(
            Path home, Path source, java.util.function.Supplier<UserPrincipal> expectedOwner) throws Exception {
        return new JGitRemoteRepositoryMaterializer(
                home,
                RemoteRepositoryCachePolicy.DEFAULT,
                new LocalSourceClient(source),
                name -> Optional.empty(),
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
                JGitRemoteRepositoryMaterializer.LOCK_ACQUIRE_TIMEOUT,
                JGitRemoteRepositoryMaterializer.DirectoryPublisher.ATOMIC,
                expectedOwner);
    }

    private static RemoteRepositoryRequest request(String commit) {
        return JGitRemoteRepositoryMaterializerTest.request(commit, null);
    }

    private static String head(Path repository) throws Exception {
        return JGitRemoteRepositoryMaterializerTest.head(repository);
    }

    private static String command(Path witness) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
        String classes;
        try {
            classes = Path.of(TrustWitness.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString().replace('\\', '/');
        } catch (java.net.URISyntaxException exception) {
            throw new IOException(exception);
        }
        return "\"" + java + "\" -cp \"" + classes + "\" " + TrustWitness.class.getName()
                + " \"" + witness.toString().replace('\\', '/') + "\"";
    }

    private static UserPrincipal principal(String name) {
        return new UserPrincipal() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}

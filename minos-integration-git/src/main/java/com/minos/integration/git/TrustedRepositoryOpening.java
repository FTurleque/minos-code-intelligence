package com.minos.integration.git;

import com.minos.io.PrivateLocalStorage;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * The only place where this module opens a Git repository it analyses or materializes. A repository found
 * on disk, or cloned from a remote, is not trusted: the directories that hold its configuration must belong
 * to the account running this process, and no {@code filter.*} command it can reach is ever run.
 *
 * <p>Two policies say which filter drivers are set aside. {@link FilterPolicy#REPOSITORY_DECLARED} (a local
 * repository the user already owns) neutralises the drivers the repository's own configuration declares and
 * leaves the user's and the system's alone. {@link FilterPolicy#EVERY_DRIVER} (a repository fetched on
 * request) neutralises every driver of the effective configuration, so the content that is checked out and
 * compared is the raw content of the Git objects. In both, each driver is pointed at a built-in
 * pass-through in memory only; no configuration file is written.</p>
 */
final class TrustedRepositoryOpening {

    /** Which {@code filter.*} drivers are set aside when a repository is opened. */
    enum FilterPolicy {
        /** The drivers declared by the repository's own configuration (included files comprised). */
        REPOSITORY_DECLARED,
        /** Every driver of the effective configuration: repository, user and system. */
        EVERY_DRIVER
    }

    /** A repository opened under the trust rules, and what had to be set aside to open it. */
    record Opened(Repository repository, Path root, boolean filtersNotApplied) {
    }

    private static final String FILTER_SECTION = "filter";
    private static final String USE_BUILTIN_KEY = "useJGitBuiltin";
    private static final String INVALID_CONFIGURATION = "The Git repository configuration is invalid";
    private static final String OWNER_PROBE_DIRECTORY = "owner-probe";
    private static final AtomicReference<UserPrincipal> PROCESS_OWNER = new AtomicReference<>();

    private final Supplier<UserPrincipal> expectedOwner;

    /** {@code expectedOwner} {@code null} means the owner the system gives a file this process creates. */
    TrustedRepositoryOpening(Supplier<UserPrincipal> expectedOwner) {
        this.expectedOwner = expectedOwner;
    }

    /**
     * Opens the repository that contains {@code projectRoot}, found by walking up from it, with the filters
     * of the repository's own configuration set aside.
     */
    Opened openContaining(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + leafName(projectRoot));
        }
        // No readEnvironment(): GIT_DIR, GIT_WORK_TREE and GIT_CEILING_DIRECTORIES must not redirect
        // the analysis to a repository other than the one under the requested root.
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .findGitDir(root.toFile());
        if (builder.getGitDir() == null) {
            throw new IllegalArgumentException("projectRoot is not inside a Git repository: " + leafName(root));
        }
        return adopt(builder, root, FilterPolicy.REPOSITORY_DECLARED);
    }

    /**
     * Opens the repository whose work tree is exactly {@code repositoryRoot}, with every filter driver set
     * aside. A root without its own {@code .git} directory is refused, never searched upward.
     */
    Opened openExact(Path repositoryRoot) throws IOException {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Path root = repositoryRoot.toRealPath();
        Path gitDirectory = root.resolve(".git");
        if (!Files.isDirectory(gitDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The materialized repository has no Git directory: " + leafName(root));
        }
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .setWorkTree(root.toFile())
                .setGitDir(gitDirectory.toFile());
        return adopt(builder, root, FilterPolicy.EVERY_DRIVER);
    }

    private Opened adopt(FileRepositoryBuilder builder, Path root, FilterPolicy policy) throws IOException {
        Repository repository;
        try {
            repository = builder.build();
        } catch (IllegalArgumentException ignored) {
            // JGit reports an unreadable configuration with the absolute path of the file, and the
            // repository is not trusted: the refusal names neither the path nor the parser failure.
            throw new IOException(INVALID_CONFIGURATION);
        }
        try {
            // A linked worktree keeps its own git dir but shares configuration, objects and hooks with the
            // main repository (the common directory): both are written by whoever owns them.
            requireExpectedOwner(repository.getDirectory().toPath());
            requireExpectedOwner(repository.getCommonDirectory().toPath());
            return new Opened(repository, root, redirectFiltersToPassThrough(repository, policy));
        } catch (IOException | RuntimeException failure) {
            repository.close();
            throw failure;
        }
    }

    /**
     * A repository found on disk is not trusted: the directories the analysis opens, directly or by
     * walking up from the project root, must belong to the account this process runs as, otherwise
     * their configuration and attributes were written by someone else. The path is resolved first, so a
     * link owned by the user cannot stand for a directory owned by another account.
     */
    private void requireExpectedOwner(Path gitDirectory) throws IOException {
        UserPrincipal found;
        UserPrincipal expected;
        try {
            found = Files.getOwner(gitDirectory.toRealPath());
            expected = expectedOwner != null ? expectedOwner.get() : processOwner();
        } catch (IOException | UncheckedIOException | UnsupportedOperationException ignored) {
            throw new IOException(
                    "The owner of the Git repository cannot be read; the analysis is refused (fail-closed)");
        }
        if (!found.equals(expected)) {
            throw new IOException("The Git repository is owned by '" + found.getName()
                    + "', not by the account running this process ('" + expected.getName()
                    + "'); change the repository owner before analysing it");
        }
    }

    /**
     * The owner the system gives a file this process creates, read once from a throw-away file in the
     * private MINOS directory (never in the shared system temporary directory, and never inside the
     * analysed repository: an analysis writes nothing there and may not be allowed to). The owner of a new
     * file does not depend on the directory that holds it. The directory follows the rule of
     * {@code MinosHome} ({@code minos.home}, {@code MINOS_HOME}, then {@code ~/.minos}), which an adapter
     * cannot import from the application layer.
     */
    private static UserPrincipal processOwner() {
        UserPrincipal known = PROCESS_OWNER.get();
        if (known != null) {
            return known;
        }
        try {
            Path probe = PrivateLocalStorage.createPrivateTempFile(probeDirectory(), "minos-owner-", ".probe");
            try {
                PROCESS_OWNER.compareAndSet(null, Files.getOwner(probe));
                return PROCESS_OWNER.get();
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path probeDirectory() {
        String property = System.getProperty("minos.home");
        if (property != null && !property.isBlank()) {
            return Path.of(property).resolve(OWNER_PROBE_DIRECTORY);
        }
        String environment = System.getenv("MINOS_HOME");
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment).resolve(OWNER_PROBE_DIRECTORY);
        }
        return Path.of(System.getProperty("user.home")).resolve(".minos").resolve(OWNER_PROBE_DIRECTORY);
    }

    /**
     * Neutralises, in memory only, the {@code filter.*} drivers {@code policy} names: a {@code clean} or
     * {@code smudge} filter is a shell command, and the repository is not trusted to run one. Each such
     * driver is pointed at a built-in pass-through, so the working-tree comparison and the checkout are
     * still computed, on the raw content. {@code useJGitBuiltin} is turned off on each of them, because a
     * driver that asks for a registered built-in filter would otherwise replace the pass-through. JGit does
     * not drop lines that come from an included file, so overriding the value is what covers them. No
     * configuration file is written. Answers whether anything was set aside.
     */
    private static boolean redirectFiltersToPassThrough(Repository repository, FilterPolicy policy)
            throws IOException {
        Set<String> drivers = switch (policy) {
            case REPOSITORY_DECLARED -> declaredByTheRepository(repository);
            case EVERY_DRIVER -> repository.getConfig().getSubsections(FILTER_SECTION);
        };
        if (drivers.isEmpty()) {
            return false;
        }
        PassThroughFilter.register();
        for (String driver : drivers) {
            repository.getConfig().setString(FILTER_SECTION, driver, "clean", PassThroughFilter.COMMAND);
            repository.getConfig().setString(FILTER_SECTION, driver, "smudge", PassThroughFilter.COMMAND);
            repository.getConfig().setBoolean(FILTER_SECTION, driver, USE_BUILTIN_KEY, false);
        }
        return true;
    }

    private static Set<String> declaredByTheRepository(Repository repository) throws IOException {
        FileBasedConfig declared = new FileBasedConfig(
                new File(repository.getCommonDirectory(), "config"), repository.getFS());
        try {
            declared.load();
        } catch (ConfigInvalidException ignored) {
            throw new IOException(INVALID_CONFIGURATION);
        }
        return declared.getSubsections(FILTER_SECTION);
    }
}

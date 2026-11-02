package com.minos.adapter.scip.runtime.plan;

import com.minos.io.PrivateLocalStorage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The offline Maven build scip-java runs outside Windows (ADR SEC-0076).
 *
 * <p>The build of an analysed project is untrusted code and runs with the network denied, so its plugins and
 * dependencies come from a Maven repository the user designates explicitly; there is no default. This class
 * decides whether that designation may be mounted read-only in the sandbox, whether the Maven of the
 * {@code PATH} is a qualified 3.9.x installation visible from the sandbox (its version read on disk, Maven
 * never executed), and writes the fixed Maven line of one run: offline, MINOS settings without server, mirror
 * or proxy, an empty head repository in the run and the designated repository as read-only tail.</p>
 *
 * <p>No message, diagnostic or log written here carries the designated path: they name the setting and its
 * state.</p>
 */
public final class ScipJavaMavenOffline {

    /** The setting that designates the Maven repository (JVM property and configuration file key). */
    public static final String REPOSITORY_SETTING = "minos.scip.java.maven-repository";
    /** The environment variable of the same setting. */
    public static final String REPOSITORY_ENVIRONMENT = "MINOS_SCIP_JAVA_MAVEN_REPOSITORY";
    /** The only Maven versions qualified for the offline build. */
    public static final String QUALIFIED_MAVEN_RANGE = "3.9.x";

    /** Constant Maven settings of every run: no server, mirror, proxy, profile, local repository or offline flag. */
    static final String SETTINGS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
                      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
            </settings>
            """;

    static final String MAVEN_DIRECTORY = "maven";
    static final String SETTINGS_FILE = "settings.xml";
    static final String HEAD_REPOSITORY = "local";

    /** Root entries of a Maven repository besides its group directories. */
    static final Set<String> REPOSITORY_ROOT_METADATA = Set.of(
            "resolver-status.properties", "maven-metadata-local.xml", "_remote.repositories");
    /** Root entries that hold user settings or credentials, never part of a repository. */
    static final Set<String> USER_SETTINGS_FILES = Set.of(SETTINGS_FILE, "settings-security.xml", "toolchains.xml");
    /** System directories a designation may be neither, nor contain, nor lie under. */
    static final List<Path> SYSTEM_DIRECTORIES = List.of(
            Path.of("/etc"), Path.of("/proc"), Path.of("/sys"), Path.of("/dev"), Path.of("/root"), Path.of("/run"));
    /** Roots the Linux sandbox mounts read-only: a Maven installation elsewhere is invisible to the build. */
    static final List<Path> SANDBOX_SYSTEM_ROOTS = List.of(
            Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/sbin"));

    private static final Pattern MAVEN_CORE = Pattern.compile("maven-core-(\\d+)\\.(\\d+)\\.(\\d+)(?:[-.][A-Za-z0-9.-]+)?\\.jar");
    private static final String REFUSED = "the Maven repository designated by " + REPOSITORY_SETTING + " is refused: ";
    private static final String DOCUMENTATION = " (docs/user/troubleshooting.md)";

    /** What the designation of the Maven repository is, without its path. */
    enum RepositoryState { ABSENT, REFUSED, PRESENT }

    /** The decision about the designated repository: its state, the reason of a refusal, the mountable directory. */
    record Repository(RepositoryState state, String reason, Optional<Path> directory) {
        public Repository {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(directory, "directory");
        }

        /** Actionable, path-free text for a refusal or an absence; empty when the repository is usable. */
        Optional<String> problem() {
            return switch (state) {
                case PRESENT -> Optional.empty();
                case ABSENT -> Optional.of("scip-java builds Maven projects offline from a designated Maven "
                        + "repository and " + REPOSITORY_SETTING + " is not set; designate a dedicated "
                        + "repository populated without running Maven in the analysed project" + DOCUMENTATION);
                case REFUSED -> Optional.of(REFUSED + reason + DOCUMENTATION);
            };
        }
    }

    /** The Maven of the PATH: its real installation directory and version, or why it does not qualify. */
    record Maven(Optional<Path> home, Optional<String> version, Optional<String> problem) {
        public Maven {
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** What one run adds to the plan: the Maven arguments after {@code --}, the read-only root, the masked files. */
    record RunConfiguration(List<String> mavenArguments, List<Path> readOnlyRoots, List<Path> maskedFiles) {
        public RunConfiguration {
            mavenArguments = List.copyOf(mavenArguments);
            readOnlyRoots = List.copyOf(readOnlyRoots);
            maskedFiles = List.copyOf(maskedFiles);
        }
    }

    private final String designation;
    private final Path minosHome;
    private final Path userHome;
    private final Supplier<Optional<Path>> mavenLauncher;
    private final List<Path> sandboxRoots;

    private ScipJavaMavenOffline(String designation, Path minosHome, Path userHome,
                                 Supplier<Optional<Path>> mavenLauncher, List<Path> sandboxRoots) {
        this.designation = designation == null || designation.isBlank() ? null : designation.trim();
        this.minosHome = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.userHome = userHome == null ? null : userHome.toAbsolutePath().normalize();
        this.mavenLauncher = Objects.requireNonNull(mavenLauncher, "mavenLauncher");
        this.sandboxRoots = List.copyOf(sandboxRoots);
    }

    /**
     * The offline build of this installation: {@code designation} is the value of {@link #REPOSITORY_SETTING}
     * (null when unset), {@code mavenLauncher} finds the {@code mvn} of the PATH.
     */
    public static ScipJavaMavenOffline configured(
            String designation, Path minosHome, Supplier<Optional<Path>> mavenLauncher) {
        String home = System.getProperty("user.home");
        return new ScipJavaMavenOffline(designation, minosHome,
                home == null || home.isBlank() ? null : Path.of(home), mavenLauncher, SANDBOX_SYSTEM_ROOTS);
    }

    /** Test seam: an explicit user home, Maven launcher and sandbox system roots, nothing read from the host. */
    static ScipJavaMavenOffline of(String designation, Path minosHome, Path userHome, Path mavenLauncher,
                                   List<Path> sandboxRoots) {
        return new ScipJavaMavenOffline(designation, minosHome, userHome, () -> Optional.ofNullable(mavenLauncher),
                sandboxRoots);
    }

    /** Path-free diagnostics of everything the offline build lacks; empty when it can run. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        maven().problem().ifPresent(problems::add);
        repository().problem().ifPresent(problems::add);
        return problems;
    }

    /** Decides whether the designation may be mounted (ADR SEC-0076, decision 3). */
    Repository repository() {
        if (designation == null) return new Repository(RepositoryState.ABSENT, "", Optional.empty());
        Path named;
        try {
            named = Path.of(designation);
        } catch (InvalidPathException _) {
            return refused("it is not a path");
        }
        if (!named.isAbsolute()) return refused("it is not an absolute path");
        Path normalized = named.normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) return refused("it is not an existing directory");
        Path real;
        try {
            real = normalized.toRealPath();
        } catch (IOException _) {
            return refused("it cannot be resolved");
        }
        if (!real.equals(normalized)) return refused("a component of its path is a symbolic link");
        Optional<String> exposure = sensitiveExposure(real);
        if (exposure.isPresent()) return refused(exposure.orElseThrow());
        Optional<String> shape = repositoryShape(real);
        if (shape.isPresent()) return refused(shape.orElseThrow());
        return new Repository(RepositoryState.PRESENT, "", Optional.of(real));
    }

    /** The Maven of the PATH, qualified only when it is 3.9.x and installed under a root the sandbox mounts. */
    Maven maven() {
        Optional<Path> launcher = mavenLauncher.get();
        if (launcher.isEmpty()) {
            return mavenProblem(Optional.empty(), Optional.empty(), "Maven " + QUALIFIED_MAVEN_RANGE
                    + " is required in PATH for the offline scip-java build and none was found");
        }
        Path home;
        try {
            Path real = launcher.orElseThrow().toRealPath();
            home = real.getParent() == null ? null : real.getParent().getParent();
        } catch (IOException _) {
            home = null;
        }
        if (home == null) {
            return mavenProblem(Optional.empty(), Optional.empty(),
                    "the mvn of PATH does not resolve to a Maven installation");
        }
        if (sandboxRoots.stream().noneMatch(home::startsWith)) {
            return mavenProblem(Optional.of(home), Optional.empty(), "the mvn of PATH is installed outside the "
                    + "system directories the Linux sandbox mounts (/usr, /bin, /lib, /lib64, /sbin); install Maven "
                    + QUALIFIED_MAVEN_RANGE + " under /usr");
        }
        Optional<String> version = coreVersion(home);
        if (version.isEmpty()) {
            return mavenProblem(Optional.of(home), Optional.empty(),
                    "the version of the mvn of PATH cannot be read from its lib/maven-core jar");
        }
        if (!version.orElseThrow().startsWith("3.9.")) {
            return mavenProblem(Optional.of(home), version, "Maven " + version.orElseThrow() + " is outside the "
                    + "qualified range " + QUALIFIED_MAVEN_RANGE + " of the offline scip-java build");
        }
        return new Maven(Optional.of(home), version, Optional.empty());
    }

    /**
     * Checks everything before the project is staged, then prepares the Maven files of one run: the constant
     * settings, written by a private primitive, and an empty head repository created for this run alone.
     *
     * @throws IllegalStateException with an actionable, path-free message when the build cannot run offline
     */
    RunConfiguration prepare(Path projectRoot, Path runDirectory) throws IOException {
        Path project = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        Path run = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        Maven maven = maven();
        if (maven.problem().isPresent()) throw new IllegalStateException(maven.problem().orElseThrow() + DOCUMENTATION);
        Repository repository = repository();
        if (repository.problem().isPresent()) throw new IllegalStateException(repository.problem().orElseThrow());
        Path designated = repository.directory().orElseThrow();
        Path realProject = Files.exists(project) ? project.toRealPath() : project;
        if (overlaps(designated, realProject) || overlaps(designated, run)) {
            throw new IllegalStateException(REFUSED + "it overlaps the analysed project or the run" + DOCUMENTATION);
        }

        Path mavenDirectory = run.resolve(MAVEN_DIRECTORY);
        Path head = mavenDirectory.resolve(HEAD_REPOSITORY);
        if (Files.exists(head, LinkOption.NOFOLLOW_LINKS)) {
            // A head repository shared between runs would let one build plant a plugin for the next.
            throw new IllegalStateException("the head Maven repository of this run already exists; "
                    + "scip-java refuses to reuse it");
        }
        PrivateLocalStorage.ensurePrivateDirectory(mavenDirectory);
        Path settings = mavenDirectory.resolve(SETTINGS_FILE);
        try (OutputStream output = PrivateLocalStorage.createPrivateOutputStream(settings)) {
            output.write(SETTINGS_XML.getBytes(StandardCharsets.UTF_8));
        }
        PrivateLocalStorage.ensurePrivateDirectory(head);

        Path mavenHome = maven.home().orElseThrow();
        return new RunConfiguration(
                mavenArguments(settings, head, designated),
                List.of(designated),
                List.of(mavenHome.resolve("conf").resolve("settings.xml"),
                        mavenHome.resolve("conf").resolve("toolchains.xml")));
    }

    /** The fixed Maven line passed to scip-java after {@code --}. */
    static List<String> mavenArguments(Path settings, Path headRepository, Path designatedRepository) {
        String settingsFile = settings.toAbsolutePath().normalize().toString();
        return List.of(
                "--batch-mode", "--offline",
                "-s", settingsFile,
                "-gs", settingsFile,
                "-Dmaven.repo.local=" + headRepository.toAbsolutePath().normalize(),
                "-Dmaven.repo.local.tail=" + designatedRepository.toAbsolutePath().normalize(),
                "clean", "verify", "-DskipTests");
    }

    /** The version a Maven installation declares by the name of its {@code lib/maven-core} jar; Maven is not run. */
    static Optional<String> coreVersion(Path mavenHome) {
        Path lib = mavenHome.resolve("lib");
        if (!Files.isDirectory(lib)) return Optional.empty();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(lib, "maven-core-*.jar")) {
            for (Path jar : jars) {
                Matcher matcher = MAVEN_CORE.matcher(jar.getFileName().toString());
                if (matcher.matches()) {
                    return Optional.of(matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3));
                }
            }
        } catch (IOException _) {
            // An unreadable installation has no version that MINOS can vouch for.
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<String> sensitiveExposure(Path real) {
        // The designation is a real path: the home directory and MINOS_HOME are compared under both spellings,
        // so that a home reached through a link (/home -> /data/home) is still recognised.
        List<Path> homes = Stream.concat(spellings(minosHome), spellings(userHome)).toList();
        boolean containsProtectedLocation = Stream.concat(SYSTEM_DIRECTORIES.stream(), homes.stream())
                .anyMatch(location -> location.startsWith(real));
        if (containsProtectedLocation) {
            return Optional.of("it is, or contains, the home directory, MINOS_HOME or a system directory");
        }
        boolean underMinosHome = spellings(minosHome).anyMatch(real::startsWith);
        if (underMinosHome || SYSTEM_DIRECTORIES.stream().anyMatch(real::startsWith)) {
            return Optional.of("it lies under MINOS_HOME or a system directory");
        }
        if (isWindowsProfileOrAncestor(real)) {
            return Optional.of("it is, or contains, a Windows user profile seen from WSL");
        }
        String name = real.getFileName() == null ? "" : real.getFileName().toString();
        if (".m2".equals(name)) {
            return Optional.of("it is a .m2 directory, which holds Maven settings; designate its repository "
                    + "subdirectory instead");
        }
        if (name.startsWith(".")) {
            return Optional.of("it is a hidden directory, which holds settings rather than a Maven repository");
        }
        return Optional.empty();
    }

    /** The normalized spelling of {@code location} and, when it exists, its real path; nothing for null. */
    private static Stream<Path> spellings(Path location) {
        if (location == null) return Stream.empty();
        try {
            return Files.exists(location) ? Stream.of(location, location.toRealPath()).distinct() : Stream.of(location);
        } catch (IOException _) {
            // Unresolvable now: only the normalized spelling can be compared, which is never broader.
            return Stream.of(location);
        }
    }

    /** {@code /mnt}, {@code /mnt/<drive>}, {@code /mnt/<drive>/Users} and {@code /mnt/<drive>/Users/<account>}. */
    static boolean isWindowsProfileOrAncestor(Path real) {
        if (!real.startsWith(Path.of("/mnt"))) return false;
        int names = real.getNameCount();
        if (names <= 2) return true;
        return "users".equals(real.getName(2).toString().toLowerCase(Locale.ROOT)) && names <= 4;
    }

    /** Refuses a root that holds user settings, a hidden entry or a file a Maven repository does not have. */
    private static Optional<String> repositoryShape(Path real) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(real)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (USER_SETTINGS_FILES.contains(name)) {
                    return Optional.of("it contains Maven settings, which are never mounted");
                }
                if (name.startsWith(".")) {
                    return Optional.of("it contains a hidden entry, which a Maven repository does not have");
                }
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && !REPOSITORY_ROOT_METADATA.contains(name)) {
                    return Optional.of("its root holds a file that is not Maven repository metadata");
                }
            }
        } catch (IOException _) {
            return Optional.of("its content cannot be listed");
        }
        return Optional.empty();
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static Repository refused(String reason) {
        return new Repository(RepositoryState.REFUSED, reason, Optional.empty());
    }

    private static Maven mavenProblem(Optional<Path> home, Optional<String> version, String problem) {
        return new Maven(home, version, Optional.of(problem));
    }
}

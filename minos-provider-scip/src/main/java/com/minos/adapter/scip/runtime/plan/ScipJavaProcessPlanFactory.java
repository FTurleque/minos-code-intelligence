package com.minos.adapter.scip.runtime.plan;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.FileTreeOperations;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.IndexerProcessPlanFactory;
import com.minos.source.ProjectIgnoreRules;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * Local execution plan of scip-java.
 *
 * <p>The classpath that runs is the one the installation verified and stored in {@code MINOS_HOME/tools}
 * ({@link ScipJavaClasspath}): each jar is compared again with the lock before the plan, on Windows as on Linux.
 * Nothing is resolved by coordinate at indexing time. The only exception, outside Windows and without an installed
 * classpath: a standalone {@code scip-java} launcher found in the {@code PATH} (the one the Docker image builds, or a
 * tool chosen by the operator, like {@code node} for the other providers).</p>
 *
 * <p>Outside Windows the build runs offline ({@link ScipJavaMavenOffline}, ADR SEC-0076): the designated Maven
 * repository is a read-only root of the plan, the global settings of the host Maven are masked, and the plan is
 * refused before the project is staged when the repository or a qualified Maven is missing.</p>
 */
public final class ScipJavaProcessPlanFactory implements IndexerProcessPlanFactory {
    private static final String REPAIR = "run `minos tools install scip-java`";
    private static final String OUTPUT = "output";

    private static final Set<String> STAGING_EXCLUDED_DIRECTORIES = Set.of(
            ".gradle", ".cache", "build");
    private static final Set<String> STAGING_EXCLUDED_ROOT_FILES = Set.of("mvnw", "mvnw.cmd");

    private final Path coursier;
    private final String coordinate;
    private final Path windowsRunner;
    private final Path windowsManagedMaven;
    private final Path classpathFile;
    private final ScipJavaClasspath.Verifier classpathVerifier;
    private final ScipJavaMavenOffline mavenOffline;

    /**
     * @param coursier Coursier, handed to the Windows runner; unused off Windows.
     * @param windowsManagedMaven MINOS-managed Maven executable used on Windows so the sandboxed
     *     runner never has to discover a wrapper or host {@code mvn} itself — both are unreachable
     *     from inside the AppContainer sandbox in general. Unused off Windows.
     * @param classpathFile the listing of the scip-java classpath kept in {@code MINOS_HOME/tools}
     *     ({@value ScipJavaClasspath#LISTING}); null when it is not installed, which refuses the plan
     *     unless a standalone launcher is on the {@code PATH} off Windows.
     * @param classpathVerifier compares the jars of the listing with the pinned lock before every plan.
     * @param mavenOffline the offline Maven build outside Windows: designated repository and qualified Maven.
     */
    public ScipJavaProcessPlanFactory(
            Path coursier,
            String coordinate,
            Path windowsRunner,
            Path windowsManagedMaven,
            Path classpathFile,
            ScipJavaClasspath.Verifier classpathVerifier,
            ScipJavaMavenOffline mavenOffline
    ) {
        this.coursier = Objects.requireNonNull(coursier, "coursier").toAbsolutePath().normalize();
        if (coordinate == null || coordinate.isBlank()) {
            throw new IllegalArgumentException("coordinate must not be blank");
        }
        this.coordinate = coordinate;
        this.windowsRunner = Objects.requireNonNull(windowsRunner, "windowsRunner")
                .toAbsolutePath().normalize();
        this.windowsManagedMaven = windowsManagedMaven == null ? null : windowsManagedMaven.toAbsolutePath().normalize();
        this.classpathFile = classpathFile == null ? null : classpathFile.toAbsolutePath().normalize();
        this.classpathVerifier = Objects.requireNonNull(classpathVerifier, "classpathVerifier");
        this.mavenOffline = Objects.requireNonNull(mavenOffline, "mavenOffline");
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws IOException {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "qualified MINOS scip-java runtime currently requires Maven pom.xml: " + leafName(root));
        }
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-java incremental execution is not qualified");
        }
        requireProjectJdk();

        Path normalizedRunDirectory = runDirectory.toAbsolutePath().normalize();
        Path output = normalizedRunDirectory.resolve("index.scip");
        PrivateLocalStorage.ensurePrivateDirectory(output.getParent());
        return CommandLocator.isWindows()
                ? windowsPlan(request, root, normalizedRunDirectory)
                : portablePlan(root, normalizedRunDirectory, output);
    }

    /** Windows: the managed PowerShell runner drives Coursier and the managed Maven against the project in place. */
    private IndexerProcessPlan windowsPlan(IndexingExecutionRequest request, Path root, Path normalizedRunDirectory)
            throws IOException {
        if (!Files.isRegularFile(coursier)) {
            throw new IllegalStateException("Coursier executable is missing: " + leafName(coursier));
        }
        if (!Files.isRegularFile(windowsRunner)) {
            throw new IllegalStateException("managed scip-java Windows runner is missing: " + leafName(windowsRunner));
        }
        Path powershell = powerShellExecutable()
                .orElseThrow(() -> new IllegalStateException(
                        "Windows PowerShell 5.1 (System32\\WindowsPowerShell\\v1.0\\powershell.exe) is required "
                                + "for scip-java on Windows"));
        if (windowsManagedMaven == null || !Files.isRegularFile(windowsManagedMaven)) {
            throw new IllegalStateException(
                    "MINOS-managed Maven is missing; run `minos tools install scip-java`: " + leafName(windowsManagedMaven));
        }
        verifiedClasspath(false);
        Path providerOutput = normalizedRunDirectory.resolve("scip-java-output");
        Files.createDirectories(providerOutput);
        List<String> runner = List.of(
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", windowsRunner.toString(),
                "-ProjectPath", root.toString(),
                "-CoursierCommand", coursier.toString(),
                "-Coordinate", coordinate,
                "-Language", request.selection().language().name(),
                "-OutputDirectory", providerOutput.toString(),
                "-MavenCommand", windowsManagedMaven.toString(),
                "-ClasspathFile", classpathFile.toString());
        return new IndexerProcessPlan(
                CommandLocator.invocation(powershell, runner.toArray(String[]::new)),
                root,
                Map.of(),
                providerOutput.resolve("index.scip"),
                Duration.ofHours(1)
        );
    }

    /** Linux and macOS: the launcher runs against a writable staging copy of the project. */
    private IndexerProcessPlan portablePlan(Path root, Path normalizedRunDirectory, Path output) throws IOException {
        // Refused here, before anything is staged, when the build cannot run offline: no designated repository,
        // a refused designation, or no qualified Maven visible from the sandbox.
        ScipJavaMavenOffline.RunConfiguration maven = mavenOffline.prepare(root, normalizedRunDirectory);
        // The installed classpath comes first and is verified again, before anything is staged. Only without it
        // does a standalone launcher on the PATH run: the one the Docker image builds, or one the operator chose.
        boolean classpathInstalled = classpathFile != null && Files.isRegularFile(classpathFile, LinkOption.NOFOLLOW_LINKS);
        var standalone = classpathInstalled ? Optional.<Path>empty() : CommandLocator.find("scip-java");
        Path launcher = standalone.isPresent() ? null : verifiedClasspath(true);

        // scip-java's Maven build creates target/scip-targetroot and other compiler outputs in the
        // project tree. Linux/Docker runs therefore execute against a writable staging copy under
        // MINOS_HOME/runs. The staging copy uses the same bounded .gitignore/.minosignore contract
        // as discovery and additionally omits provider-specific cache/build directories and root
        // Maven wrappers.
        Path executionRoot = prepareWritableWorkspace(root, normalizedRunDirectory);
        Path targetRoot = targetRoot(output);
        List<String> command = new ArrayList<>(launcher == null
                ? standaloneCommand(standalone.orElseThrow(), output, targetRoot)
                : classpathCommand(projectJava(), launcher, output, targetRoot));
        command.add("--");
        command.addAll(maven.mavenArguments());
        return new IndexerProcessPlan(
                command,
                executionRoot,
                Map.of(),
                output,
                Duration.ofHours(1),
                maven.readOnlyRoots(),
                maven.maskedFiles()
        );
    }

    /**
     * The installed classpath, verified against the lock (and its launcher, when asked).
     *
     * @return the launcher, or null when it was not asked for
     * @throws IllegalStateException with the repair command when the classpath is absent, unreadable or altered
     */
    private Path verifiedClasspath(boolean withLauncher) {
        if (classpathFile == null || !Files.isRegularFile(classpathFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("managed scip-java classpath is not installed; " + REPAIR);
        }
        try {
            return ScipJavaClasspath.verify(classpathFile, classpathVerifier, withLauncher);
        } catch (IllegalStateException altered) {
            throw new IllegalStateException(altered.getMessage() + "; " + REPAIR, altered);
        } catch (IOException unreadable) {
            throw new IllegalStateException("managed scip-java classpath cannot be read; " + REPAIR, unreadable);
        }
    }

    /**
     * The directory where scip-java writes its intermediate files: beside the artifact, in the run, outside the
     * staged workspace. Left to its Maven default ({@code target/scip-targetroot} at the project root), a module
     * whose build directory is that root {@code target/} (as {@code minos-app}) deletes them in its {@code clean}
     * and the index keeps that module alone.
     */
    static Path targetRoot(Path output) {
        return Objects.requireNonNull(output, OUTPUT).toAbsolutePath().normalize().resolveSibling("scip-java-targetroot");
    }

    /** {@code java -jar <launcher> index --output <output> --targetroot <targetRoot>}: the launcher's manifest names the verified jars. */
    static List<String> classpathCommand(Path java, Path launcher, Path output, Path targetRoot) {
        return List.of(
                Objects.requireNonNull(java, "java").toAbsolutePath().normalize().toString(),
                "-jar", Objects.requireNonNull(launcher, "launcher").toAbsolutePath().normalize().toString(),
                "index", "--output", Objects.requireNonNull(output, OUTPUT).toAbsolutePath().normalize().toString(),
                "--targetroot", Objects.requireNonNull(targetRoot, "targetRoot").toAbsolutePath().normalize().toString()
        );
    }

    static List<String> standaloneCommand(Path launcher, Path output, Path targetRoot) {
        Path normalizedLauncher = Objects.requireNonNull(launcher, "launcher").toAbsolutePath().normalize();
        Path normalizedOutput = Objects.requireNonNull(output, OUTPUT).toAbsolutePath().normalize();
        Path normalizedTargetRoot = Objects.requireNonNull(targetRoot, "targetRoot").toAbsolutePath().normalize();
        return List.of(
                normalizedLauncher.toString(),
                "index", "--output", normalizedOutput.toString(),
                "--targetroot", normalizedTargetRoot.toString()
        );
    }

    static Path prepareWritableWorkspace(Path projectRoot, Path runDirectory) throws IOException {
        return prepareWritableWorkspace(projectRoot, runDirectory, SourceBudgetPolicy.DEFAULT);
    }

    static Path prepareWritableWorkspace(
            Path projectRoot,
            Path runDirectory,
            SourceBudgetPolicy budgetPolicy
    ) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        Path run = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        SourceBudgetPolicy policy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        BasicFileAttributes rootAttributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!FileTreeOperations.isRecursableDirectory(rootAttributes)) {
            throw new IOException("scip-java staging root is not a physical directory: " + leafName(root));
        }
        Path workspace = run.resolve("workspace").normalize();
        if (workspace.startsWith(root)) {
            throw new IllegalArgumentException("scip-java staging workspace must be outside the source project: " + leafName(workspace));
        }
        FileTreeOperations.deleteRecursively(workspace);
        Files.createDirectories(workspace);

        ProjectIgnoreRules ignoreRules = ProjectIgnoreRules.load(root);
        SourceBudgetPolicy.Tracker budget = policy.tracker("scip-java staging");
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    budget.accountTraversalEntry();
                    if (!directory.equals(root)) {
                        Path relative = root.relativize(directory);
                        if (ignoreRules.isHardIgnored(relative)
                                || STAGING_EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    if (!FileTreeOperations.isRecursableDirectory(attributes)) {
                        throw new IOException("scip-java staging rejects non-recursable directory: " + leafName(directory));
                    }
                    Path target = stagedTarget(root, workspace, directory);
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    budget.accountTraversalEntry();
                    if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = root.relativize(file);
                    if (root.equals(file.getParent())
                            && STAGING_EXCLUDED_ROOT_FILES.contains(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (ignoreRules.isIgnored(relative, false)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path target = stagedTarget(root, workspace, file);
                    Files.createDirectories(target.getParent());
                    copyRegularFileBounded(root, file, target, budget);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    budget.accountTraversalEntry();
                    throw exception;
                }
            });
        } catch (IOException | RuntimeException exception) {
            try {
                FileTreeOperations.deleteRecursively(workspace);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }

        if (!Files.isRegularFile(workspace.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            FileTreeOperations.deleteRecursively(workspace);
            throw new IllegalStateException("scip-java staging did not preserve the root pom.xml: " + leafName(workspace));
        }
        return workspace;
    }

    static void copyRegularFileBounded(
            Path sourceRoot,
            Path source,
            Path target,
            SourceBudgetPolicy.Tracker budget
    ) throws IOException {
        budget.accountFile();
        Path relative = sourceRoot.relativize(source);
        try (InputStream input = Channels.newInputStream(
                    ConfinedFileOpener.openConfinedRegularFile(sourceRoot, relative));
             OutputStream output = PrivateLocalStorage.openNewTreeFile(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                budget.accountBytes(read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
    }

    private static Path stagedTarget(Path root, Path workspace, Path source) {
        Path target = workspace.resolve(root.relativize(source)).normalize();
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("project staging path escapes workspace: " + leafName(source));
        }
        return target;
    }

    /** The {@code java} of the project JDK that {@link #requireProjectJdk()} accepted. */
    private static Path projectJava() {
        return Path.of(System.getenv("JAVA_HOME")).resolve("bin").resolve(CommandLocator.isWindows() ? "java.exe" : "java");
    }

    private static void requireProjectJdk() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalStateException("scip-java requires a project JDK: JAVA_HOME is not set");
        }
        Path bin = Path.of(javaHome).resolve("bin");
        Path javac = bin.resolve(CommandLocator.isWindows() ? "javac.exe" : "javac");
        if (!Files.isRegularFile(javac)) {
            throw new IllegalStateException("JAVA_HOME does not point to a JDK containing javac: " + javaHome);
        }
    }
    /**
     * The Windows PowerShell of the system directory, the one the AppContainer sandbox trusts. PATH is not a source:
     * a PowerShell 7 found there lives under Program Files, which the sandbox refuses to grant, and the runner targets
     * Windows PowerShell 5.1.
     */
    static java.util.Optional<Path> powerShellExecutable() {
        return CommandLocator.windowsPowerShell();
    }
}

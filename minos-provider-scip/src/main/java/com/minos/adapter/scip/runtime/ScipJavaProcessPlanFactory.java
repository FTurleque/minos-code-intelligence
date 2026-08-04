package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Plan d'exécution local de scip-java. */
public final class ScipJavaProcessPlanFactory implements IndexerProcessPlanFactory {

    private static final Set<String> STAGING_EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", ".cache", "target", "build", "out", "node_modules");
    private static final Set<String> STAGING_EXCLUDED_ROOT_FILES = Set.of("mvnw", "mvnw.cmd");

    private final Path coursier;
    private final String coordinate;
    private final Path windowsRunner;

    public ScipJavaProcessPlanFactory(Path coursier, String coordinate, Path windowsRunner) {
        this.coursier = Objects.requireNonNull(coursier, "coursier").toAbsolutePath().normalize();
        if (coordinate == null || coordinate.isBlank()) {
            throw new IllegalArgumentException("coordinate must not be blank");
        }
        this.coordinate = coordinate;
        this.windowsRunner = Objects.requireNonNull(windowsRunner, "windowsRunner")
                .toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws IOException {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalArgumentException(
                    "qualified MINOS scip-java runtime currently requires Maven pom.xml: " + root);
        }
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-java incremental execution is not qualified");
        }
        requireProjectJdk();

        Path normalizedRunDirectory = runDirectory.toAbsolutePath().normalize();
        Path output = normalizedRunDirectory.resolve("index.scip");
        Files.createDirectories(output.getParent());
        if (CommandLocator.isWindows()) {
            if (!Files.isRegularFile(coursier)) {
                throw new IllegalStateException("Coursier executable is missing: " + coursier);
            }
            if (!Files.isRegularFile(windowsRunner)) {
                throw new IllegalStateException("managed scip-java Windows runner is missing: " + windowsRunner);
            }
            Path powershell = ManagedScipProviderRuntimeManager.powerShellExecutable()
                    .orElseThrow(() -> new IllegalStateException(
                            "PowerShell (powershell.exe or pwsh.exe) is required for scip-java on Windows"));
            Path providerOutput = normalizedRunDirectory.resolve("scip-java-output");
            Files.createDirectories(providerOutput);
            return new IndexerProcessPlan(
                    CommandLocator.invocation(
                            powershell,
                            "-NoProfile",
                            "-NonInteractive",
                            "-ExecutionPolicy", "Bypass",
                            "-File", windowsRunner.toString(),
                            "-ProjectPath", root.toString(),
                            "-CoursierCommand", coursier.toString(),
                            "-Coordinate", coordinate,
                            "-Language", request.selection().language().name(),
                            "-OutputDirectory", providerOutput.toString()
                    ),
                    root,
                    Map.of(),
                    providerOutput.resolve("index.scip"),
                    Duration.ofHours(1)
            );
        }

        // scip-java's Maven build creates target/scip-targetroot and other compiler outputs in the
        // project tree. Docker project mounts are intentionally read-only, so Linux/Docker runs
        // execute against an isolated writable staging copy under MINOS_HOME/runs instead of ever
        // relaxing the source mount. Generated/build/cache directories are not copied into staging.
        // Root Maven wrapper launchers are deliberately omitted as well: scip-java prefers ./mvnw
        // when present, while a Windows host checkout may materialize it with Windows line endings.
        // Docker carries the qualified Maven 3.9.16 runtime in PATH, so staged indexing uses that
        // deterministic image-provided Maven instead of a host-dependent wrapper or wrapper download.
        Path executionRoot = prepareWritableWorkspace(root, normalizedRunDirectory);

        var standalone = CommandLocator.find("scip-java");
        if (standalone.isPresent()) {
            return new IndexerProcessPlan(
                    standaloneCommand(standalone.orElseThrow(), output),
                    executionRoot,
                    Map.of(),
                    output,
                    Duration.ofHours(1)
            );
        }

        if (!Files.isRegularFile(coursier)) {
            throw new IllegalStateException("scip-java standalone launcher and Coursier executable are missing");
        }
        return new IndexerProcessPlan(
                List.of(
                        coursier.toString(),
                        "launch", coordinate,
                        "--jvm", "system",
                        "--main", ManagedScipProviderRuntimeManager.SCIP_JAVA_MAIN_CLASS,
                        "--", "index", "--output", output.toString()
                ),
                executionRoot,
                Map.of(),
                output,
                Duration.ofHours(1)
        );
    }

    static List<String> standaloneCommand(Path launcher, Path output) {
        Path normalizedLauncher = Objects.requireNonNull(launcher, "launcher").toAbsolutePath().normalize();
        Path normalizedOutput = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        return List.of(
                normalizedLauncher.toString(),
                "index", "--output", normalizedOutput.toString()
        );
    }

    static Path prepareWritableWorkspace(Path projectRoot, Path runDirectory) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        Path run = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        Path workspace = run.resolve("workspace").normalize();
        if (workspace.startsWith(root)) {
            throw new IllegalArgumentException("scip-java staging workspace must be outside the source project: " + workspace);
        }
        deleteRecursively(workspace);
        Files.createDirectories(workspace);

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(root)
                        && STAGING_EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path target = stagedTarget(root, workspace, directory);
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE;
                }
                if (root.equals(file.getParent())
                        && STAGING_EXCLUDED_ROOT_FILES.contains(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = stagedTarget(root, workspace, file);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });

        if (!Files.isRegularFile(workspace.resolve("pom.xml"))) {
            throw new IllegalStateException("scip-java staging did not preserve the root pom.xml: " + workspace);
        }
        return workspace;
    }

    private static Path stagedTarget(Path root, Path workspace, Path source) {
        Path target = workspace.resolve(root.relativize(source)).normalize();
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("project staging path escapes workspace: " + source);
        }
        return target;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
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
}

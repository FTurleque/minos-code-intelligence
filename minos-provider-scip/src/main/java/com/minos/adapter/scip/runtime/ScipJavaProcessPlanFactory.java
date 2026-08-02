package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Plan d'exécution local de scip-java. */
public final class ScipJavaProcessPlanFactory implements IndexerProcessPlanFactory {

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
        if (!Files.isRegularFile(coursier)) {
            throw new IllegalStateException("Coursier executable is missing: " + coursier);
        }
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-java incremental execution is not qualified");
        }
        requireProjectJdk();

        Path output = runDirectory.toAbsolutePath().normalize().resolve("index.scip");
        Files.createDirectories(output.getParent());
        if (CommandLocator.isWindows()) {
            if (!Files.isRegularFile(windowsRunner)) {
                throw new IllegalStateException("managed scip-java Windows runner is missing: " + windowsRunner);
            }
            Path powershell = ManagedScipProviderRuntimeManager.powerShellExecutable()
                    .orElseThrow(() -> new IllegalStateException(
                            "PowerShell (powershell.exe or pwsh.exe) is required for scip-java on Windows"));
            Path providerOutput = runDirectory.resolve("scip-java-output").toAbsolutePath().normalize();
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

        return new IndexerProcessPlan(
                List.of(
                        coursier.toString(),
                        "launch", coordinate,
                        "--jvm", "system",
                        "--main", ManagedScipProviderRuntimeManager.SCIP_JAVA_MAIN_CLASS,
                        "--", "index", "--output", output.toString()
                ),
                root,
                Map.of(),
                output,
                Duration.ofHours(1)
        );
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

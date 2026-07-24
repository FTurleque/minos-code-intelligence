package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Plan d'exécution local de scip-java via Coursier. */
public final class ScipJavaProcessPlanFactory implements IndexerProcessPlanFactory {

    private final Path coursier;
    private final String coordinate;

    public ScipJavaProcessPlanFactory(Path coursier, String coordinate) {
        this.coursier = Objects.requireNonNull(coursier, "coursier").toAbsolutePath().normalize();
        if (coordinate == null || coordinate.isBlank()) {
            throw new IllegalArgumentException("coordinate must not be blank");
        }
        this.coordinate = coordinate;
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalArgumentException("qualified MINOS scip-java runtime currently requires Maven pom.xml: " + root);
        }
        if (!Files.isRegularFile(coursier)) {
            throw new IllegalStateException("Coursier executable is missing: " + coursier);
        }
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-java incremental execution is not qualified");
        }
        requireProjectJdk();

        return new IndexerProcessPlan(
                List.of(coursier.toString(), "launch", coordinate, "--", "index"),
                root,
                Map.of(),
                root.resolve("index.scip"),
                Duration.ofHours(1)
        );
    }

    private static void requireProjectJdk() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalStateException("scip-java requires a project JDK: JAVA_HOME is not set");
        }
        Path bin = Path.of(javaHome).resolve("bin");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javac = bin.resolve(windows ? "javac.exe" : "javac");
        if (!Files.isRegularFile(javac)) {
            throw new IllegalStateException("JAVA_HOME does not point to a JDK containing javac: " + javaHome);
        }
    }
}

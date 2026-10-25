package com.minos.adapter.scip.runtime.plan;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the scip-java plan factory refuses before it prepares anything: construction, project shape, run mode. */
class ScipJavaProcessPlanFactoryRefusalTest {

    private static final String COORDINATE = "org.scip-code:scip-java:0.13.1";

    @TempDir
    Path temporaryDirectory;

    private static final ScipJavaClasspath.Verifier ACCEPT = jars -> { };

    private ScipJavaProcessPlanFactory factory() {
        return factory(temporaryDirectory.resolve("cs"), COORDINATE, temporaryDirectory.resolve("runner.ps1"), ACCEPT);
    }

    private ScipJavaProcessPlanFactory factory(Path coursier, String coordinate, Path runner, ScipJavaClasspath.Verifier verifier) {
        return new ScipJavaProcessPlanFactory(coursier, coordinate, runner, temporaryDirectory.resolve("mvn"),
                temporaryDirectory.resolve("classpath.txt"), verifier);
    }

    private static IndexingExecutionRequest request(Path project, IndexingMode mode, List<String> changedFiles) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-java", "0.13.1", "scip-java", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), mode, changedFiles);
    }

    @Test
    void aValidConfigurationIsAcceptedWithOrWithoutTheOptionalPaths() {
        Path coursier = temporaryDirectory.resolve("cs");
        Path runner = temporaryDirectory.resolve("runner.ps1");

        assertDoesNotThrow(() -> factory(coursier, COORDINATE, runner, ACCEPT));
        assertDoesNotThrow(() -> new ScipJavaProcessPlanFactory(coursier, COORDINATE, runner, null, null, ACCEPT));
    }

    @Test
    void theCoursierRunnerAndClasspathVerifierAreMandatory() {
        Path runner = temporaryDirectory.resolve("runner.ps1");
        Path coursier = temporaryDirectory.resolve("cs");

        assertThrows(NullPointerException.class, () -> factory(null, COORDINATE, runner, ACCEPT));
        assertThrows(NullPointerException.class, () -> factory(coursier, COORDINATE, null, ACCEPT));
        assertThrows(NullPointerException.class, () -> factory(coursier, COORDINATE, runner, null));
    }

    @Test
    void theCoordinateMustNotBeBlank() {
        Path coursier = temporaryDirectory.resolve("cs");
        Path runner = temporaryDirectory.resolve("runner.ps1");

        assertThrows(IllegalArgumentException.class, () -> factory(coursier, null, runner, ACCEPT));
        assertThrows(IllegalArgumentException.class, () -> factory(coursier, "  ", runner, ACCEPT));
    }

    @Test
    void aProjectWithoutAMavenPomIsRefusedAndNoRunDirectoryIsCreated() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("gradle-project"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path run = temporaryDirectory.resolve("run");
        ScipJavaProcessPlanFactory factory = factory();
        IndexingExecutionRequest request = request(project, IndexingMode.FULL, List.of());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> factory.create(request, run));

        assertTrue(refusal.getMessage().contains("requires Maven pom.xml"), refusal.getMessage());
        assertTrue(refusal.getMessage().endsWith(": gradle-project"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporaryDirectory.toString()), refusal.getMessage());
        assertFalse(Files.exists(run), "a refused plan must not prepare the run directory");
    }

    @Test
    void aPomThatIsADirectoryDoesNotCountAsAMavenProject() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("odd-project"));
        Files.createDirectories(project.resolve("pom.xml"));
        ScipJavaProcessPlanFactory factory = factory();
        IndexingExecutionRequest request = request(project, IndexingMode.FULL, List.of());
        Path run = temporaryDirectory.resolve("run");

        assertThrows(IllegalArgumentException.class, () -> factory.create(request, run));
    }

    @Test
    void anIncrementalRunIsRefusedBeforeAnythingIsStaged() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("maven-project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path run = temporaryDirectory.resolve("run");
        ScipJavaProcessPlanFactory factory = factory();
        IndexingExecutionRequest request = request(project, IndexingMode.INCREMENTAL, List.of("src/Main.java"));

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> factory.create(request, run));

        assertTrue(refusal.getMessage().contains("incremental execution is not qualified"), refusal.getMessage());
        assertFalse(Files.exists(run), "nothing may be staged for a refused run");
    }

    @Test
    void stagingRefusesARootThatIsNotADirectory() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("not-a-directory"), "x");

        assertThrows(IOException.class,
                () -> ScipJavaProcessPlanFactory.prepareWritableWorkspace(file, temporaryDirectory.resolve("run")));
    }

    @Test
    void stagingRefusesAWorkspaceThatWouldLiveInsideTheSourceProject() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("maven-project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path insideProject = project.resolve("run");

        assertThrows(IllegalArgumentException.class,
                () -> ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, insideProject));
        assertFalse(Files.exists(insideProject.resolve("workspace")));
    }

    @Test
    void stagingRefusesAProjectWhoseRootPomWasIgnored() throws IOException {
        Path project = Files.createDirectories(temporaryDirectory.resolve("maven-project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve(".minosignore"), "pom.xml\n");
        Path run = temporaryDirectory.resolve("run");

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, run));

        assertTrue(refusal.getMessage().contains("did not preserve the root pom.xml"), refusal.getMessage());
        assertFalse(Files.exists(run.resolve("workspace")), "an unusable staging tree is removed");
    }
}

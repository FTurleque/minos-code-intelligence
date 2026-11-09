package com.minos.adapter.scip.runtime.plan;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.IndexerProcessPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The designated project JDK reaches the scip-java build by its {@code JAVA_HOME} and by the head of its
 * {@code PATH}: the compiler wrapper scip-java generates runs {@code java} from the {@code PATH}, which must not
 * resolve to a host JDK the sandbox does not mount.
 */
@DisabledOnOs(OS.WINDOWS)
class ScipJavaProcessPlanFactoryProjectJdkTest {

    private static final List<String> NAMES = List.of("scip-java-0.13.1.jar", "scala-library-2.13.16.jar");

    @TempDir
    Path temp;

    @Test
    void theDesignatedJdkIsTheJavaHomeAndTheFirstPathEntryOfTheLinuxPlan() throws IOException {
        Path jdk = Files.createDirectories(temp.resolve("jdk").resolve("bin")).getParent();
        Files.writeString(jdk.resolve("bin").resolve("javac"), "");
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");

        IndexerProcessPlan plan = factory().withProjectJdk(ScipJavaProjectJdk.configured(jdk.toString()))
                .create(request(project), temp.resolve("run"));

        String home = jdk.toAbsolutePath().normalize().toString();
        assertEquals(home, plan.environment().get("JAVA_HOME"));
        String path = plan.environment().get("PATH");
        assertTrue(path != null && path.split(File.pathSeparator, -1)[0].equals(home + File.separator + "bin"),
                "the designated JDK leads the PATH of the build: " + path);
    }

    private ScipJavaProcessPlanFactory factory() throws IOException {
        Path classpath = Files.createDirectories(temp.resolve("classpath"));
        for (String name : NAMES) Files.writeString(classpath.resolve(name), name);
        Files.write(classpath.resolve(ScipJavaClasspath.LAUNCHER), ScipJavaClasspath.launcherBytes(NAMES));
        Path listing = Files.write(classpath.resolve(ScipJavaClasspath.LISTING), ScipJavaClasspath.listingBytes(NAMES));
        return new ScipJavaProcessPlanFactory(
                temp.resolve("cs"), "org.scip-code:scip-java:0.13.1", temp.resolve("runner.ps1"),
                temp.resolve("mvn"), listing, jars -> { }, ScipJavaMavenOfflineFixtures.qualified(temp.resolve("maven")));
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-java", "0.13.1", "scip-java", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}

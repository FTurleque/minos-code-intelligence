package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q11 : {@code index --dry-run} n'a aucun effet de bord observable : ni bail de projet, ni état
 * sauvegardé, ni répertoire créé. L'arbre de {@code MINOS_HOME} (chemins, répertoires vides compris,
 * et empreinte du contenu de chaque fichier) est identique avant et après.
 */
class IndexDryRunSideEffectsTest {

    @TempDir Path temp;

    private RegisteredProject registerJavaProject(MinosApplication application) throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>demo</artifactId>
                <version>1</version></project>
                """);
        Path source = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("Demo.java"), "package demo; public class Demo { }\n");
        return application.projectRegistry().registerProject(project, "demo");
    }

    @Test
    void aDryRunOfANeverIndexedProjectLeavesTheHomeTreeUntouched() throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = registerJavaProject(application);
            // Any read of the registry takes its inter-process lock file, which it creates (storage, not the dry run):
            // one read takes it out of the comparison so that only what the dry run itself does is measured. The CLI
            // wiring itself creates nothing any more (LazyWiringGuardTest).
            application.projectRegistry().listProjects();
            Map<String, String> before = HomeTree.of(home);

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            int code = MinosCliRunner.run(application,
                    new String[]{"index", project.id().toString(), "--dry-run", "--format", "json"}, output, error);

            assertEquals(0, code, error.toString());
            assertEquals(true, output.toString().contains("\"mode\":"), output.toString());
            assertEquals(before, HomeTree.of(home), "index --dry-run changed MINOS_HOME");
        }
    }

    @Test
    void thePlanOperationItselfIsSideEffectFree() throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = registerJavaProject(application);
            Map<String, String> before = HomeTree.of(home);

            try (LocalAutonomousIndexOperations operations = new LocalAutonomousIndexOperations(application)) {
                operations.plan(project.id().toString(), null, false);
                operations.plan(project.id().toString(), null, true);
            }

            assertEquals(before, HomeTree.of(home), "plan() changed MINOS_HOME");
        }
    }
}

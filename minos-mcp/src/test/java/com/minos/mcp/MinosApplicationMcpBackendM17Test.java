package com.minos.mcp;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosApplicationMcpBackendM17Test {

    @Test
    void projectAndIndexDiagnosticsExposeProviderCapabilitiesAndLimitations(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname='fixture'\nversion='0.1.0'\n");
        Files.writeString(project.resolve("src/main.py"), "value = 1\n");

        MinosApplication application = MinosApplication.open(temp.resolve("home"));
        application.projectRegistry().registerProject(project, "python-fixture");
        MinosApplicationMcpBackend backend = new MinosApplicationMcpBackend(application);

        String structure = backend.projectStructure("python-fixture");
        assertTrue(structure.contains("providerProfiles"));
        assertTrue(structure.contains("scip-python"));
        assertTrue(structure.contains("limitations"));
        assertTrue(structure.contains("UNSUPPORTED"));

        String status = backend.indexStatus("python-fixture");
        assertTrue(status.contains("providerProfiles"));
        assertTrue(status.contains("Python 3.10+"));
    }
}

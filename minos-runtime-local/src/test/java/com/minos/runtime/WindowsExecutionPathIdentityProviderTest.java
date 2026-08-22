package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.ExecutionPathAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows proof that the runtime SPI closes the null-fileKey anti-TOCTOU gap. */
@EnabledOnOs(OS.WINDOWS)
class WindowsExecutionPathIdentityProviderTest {

    @TempDir
    Path temporary;

    @Test
    void registeredRuntimeProviderEstablishesStrongAuthorization() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));

        ExecutionPathAuthorization authorization = ExecutionPathAuthorization
                .tryCapture(project, module)
                .orElseThrow(() -> new AssertionError(
                        "Windows runtime must provide a strong filesystem identity when Java fileKey is unavailable"));

        assertTrue(authorization.registeredProjectFileKey().isPresent());
        assertTrue(authorization.projectFileKey().isPresent());
        authorization.verifyCurrent(project, module);
    }

    @Test
    void sameCanonicalPathReplacementFailsClosed() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));
        ExecutionPathAuthorization authorization = ExecutionPathAuthorization
                .tryCapture(project, module)
                .orElseThrow();

        Files.move(module, project.resolve("module-original"));
        Files.createDirectory(module);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> authorization.verifyCurrent(project, module));
        assertTrue(failure.getMessage().contains("identity changed after canonical authorization"));
    }
}

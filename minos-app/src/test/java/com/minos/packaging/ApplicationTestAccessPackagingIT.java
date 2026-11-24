package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-02 — packaging contract of the test access to the ports of MinosApplication.
 *
 * <p>{@code MinosApplicationTestAccess} lives in the test-jar of minos-application only: neither the jar of the module
 * nor the shaded distribution contains it, and the test-jar declares no service, so that a module using it never finds
 * a second implementation of a port.</p>
 */
class ApplicationTestAccessPackagingIT {

    private static final String TEST_ACCESS = "com/minos/application/MinosApplicationTestAccess.class";

    @Test
    void theTestJarOfTheApplicationCarriesTheTestAccessAndNoService() throws IOException {
        try (JarFile jar = new JarFile(jar("minos.application.tests.jar").toFile())) {
            assertNotNull(jar.getJarEntry(TEST_ACCESS), "the test-jar must publish the test access");
            List<String> services = jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.startsWith("META-INF/services/") && !name.endsWith("/"))
                    .toList();
            assertEquals(List.of(), services, "the test-jar must declare no service");
        }
    }

    @Test
    void neitherTheApplicationJarNorTheShadedJarCarriesTheTestAccess() throws IOException {
        for (String property : List.of("minos.application.jar", "minos.shaded.jar")) {
            try (JarFile jar = new JarFile(jar(property).toFile())) {
                assertNull(jar.getJarEntry(TEST_ACCESS), () -> property + " must not contain the test access");
            }
        }
    }

    private static Path jar(String property) {
        Path jar = Path.of(System.getProperty(property)).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jar), () -> "missing jar " + property + ": " + jar.getFileName());
        return jar;
    }
}

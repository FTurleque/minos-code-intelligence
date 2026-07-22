package com.minos.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java", "com", "minos");
    private static final List<String> CORE_PACKAGES = List.of("domain", "store", "query", "discovery", "orchestration");
    private static final List<String> FORBIDDEN_REFERENCES = List.of(
            "org.scip_code.scip",
            "com.google.protobuf",
            "glean.",
            "com.facebook.glean"
    );

    @Test
    void providerTypesDoNotLeakIntoCorePackages() throws IOException {
        for (String packageName : CORE_PACKAGES) {
            Path packageRoot = MAIN_JAVA.resolve(packageName);
            if (!Files.exists(packageRoot)) {
                continue;
            }

            try (var files = Files.walk(packageRoot)) {
                List<Path> violations = files
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(ProviderBoundaryTest::containsForbiddenReference)
                        .toList();

                assertTrue(
                        violations.isEmpty(),
                        () -> "Provider-specific types leaked into MINOS core package "
                                + packageName + ": " + violations
                );
            }
        }
    }

    private static boolean containsForbiddenReference(Path path) {
        try {
            String source = Files.readString(path);
            return FORBIDDEN_REFERENCES.stream().anyMatch(source::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect " + path, exception);
        }
    }
}

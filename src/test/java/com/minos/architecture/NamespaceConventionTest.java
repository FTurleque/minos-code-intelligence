package com.minos.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceConventionTest {

    private static final List<Path> ROOTS = List.of(Path.of("src"), Path.of("fixtures"));
    private static final String FORBIDDEN_NAMESPACE = "io.github.fturleque";
    private static final String FORBIDDEN_PATH = "io/github/fturleque";

    @Test
    void legacyFturlequeNamespaceCannotReappear() throws IOException {
        List<Path> pathViolations = new ArrayList<>();
        List<Path> contentViolations = new ArrayList<>();

        for (Path root : ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (portable(file).contains(FORBIDDEN_PATH)) {
                        pathViolations.add(file);
                    }
                    if (isCheckedTextFile(file) && Files.readString(file).contains(FORBIDDEN_NAMESPACE)) {
                        contentViolations.add(file);
                    }
                }
            }
        }

        assertTrue(pathViolations.isEmpty(),
                () -> "Legacy io/github/fturleque path found: " + pathViolations);
        assertTrue(contentViolations.isEmpty(),
                () -> "Legacy io.github.fturleque namespace found: " + contentViolations);
    }

    private static boolean isCheckedTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json");
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}

package com.minos.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceConventionTest {

    private static final List<Path> ROOTS = List.of(Path.of("src"), Path.of("fixtures"));
    private static final Set<String> GENERATED_DIRECTORIES = Set.of(
            ".minos-m0", "dist", "node_modules", "target");
    private static final String FORBIDDEN_NAMESPACE = String.join(".", "io", "github", "fturleque");
    private static final String FORBIDDEN_PATH = String.join("/", "io", "github", "fturleque");

    @Test
    void legacyFturlequeNamespaceCannotReappear() throws IOException {
        List<Path> pathViolations = new ArrayList<>();
        List<Path> contentViolations = new ArrayList<>();

        for (Path root : ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return isGeneratedDirectory(directory)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (portable(file).contains(FORBIDDEN_PATH)) {
                        pathViolations.add(file);
                    }
                    if (isCheckedTextFile(file) && Files.readString(file).contains(FORBIDDEN_NAMESPACE)) {
                        contentViolations.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        assertTrue(pathViolations.isEmpty(),
                () -> "Legacy namespace path found (" + FORBIDDEN_PATH + "): " + pathViolations);
        assertTrue(contentViolations.isEmpty(),
                () -> "Legacy namespace found (" + FORBIDDEN_NAMESPACE + "): " + contentViolations);
    }

    private static boolean isGeneratedDirectory(Path path) {
        Path name = path.getFileName();
        return name != null && GENERATED_DIRECTORIES.contains(name.toString());
    }

    private static boolean isCheckedTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json");
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}

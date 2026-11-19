package com.minos.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The use-case packages receive their collaborators: none imports a type of the facade package
 * {@code com.minos.application}, which builds them, so the facade adds no package cycle (A8).
 */
class UseCasePackagesDependencyTest {

    private static final Path SOURCES = Path.of("minos-application", "src", "main", "java", "com", "minos",
            "application");
    private static final List<String> USE_CASE_PACKAGES = List.of("indexing", "remote", "export");
    private static final Pattern FACADE_IMPORT = Pattern.compile("^import\\s+com\\.minos\\.application\\.([A-Z]\\w*)",
            Pattern.MULTILINE);

    @Test
    void noUseCasePackageImportsATypeOfTheFacadePackage() throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "run from the repository root: " + SOURCES);

        assertEquals(List.of(), facadeImports(SOURCES));
    }

    @Test
    void anImportOfTheFacadePackageIsReportedWithItsFileAndType(@org.junit.jupiter.api.io.TempDir Path sourceRoot)
            throws IOException {
        Path indexing = Files.createDirectories(sourceRoot.resolve("indexing"));
        Files.writeString(indexing.resolve("Sample.java"), """
                package com.minos.application.indexing;

                import com.minos.application.MinosApplication;
                import com.minos.application.semantic.SemanticIndexService;

                final class Sample { }
                """, StandardCharsets.UTF_8);

        assertEquals(List.of("indexing/Sample.java imports MinosApplication"), facadeImports(sourceRoot));
    }

    private static List<String> facadeImports(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        for (String name : USE_CASE_PACKAGES) {
            Path directory = root.resolve(name);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path source : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                    Matcher imports = FACADE_IMPORT.matcher(Files.readString(source, StandardCharsets.UTF_8));
                    while (imports.find()) {
                        found.add(root.relativize(source).toString().replace('\\', '/') + " imports "
                                + imports.group(1));
                    }
                }
            }
        }
        return List.copyOf(found);
    }
}

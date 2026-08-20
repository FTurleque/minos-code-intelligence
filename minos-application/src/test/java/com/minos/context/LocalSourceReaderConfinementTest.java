package com.minos.context;

import com.minos.domain.PositionEncoding;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The reader must serve bytes from the object it validated, not from whatever the pathname resolves
 * to when the read finally happens.
 *
 * <p>The previous implementation validated the path, called {@code toRealPath()}, and then opened
 * the resulting pathname with {@code Files.newInputStream}. A link planted under that name -- before
 * the request or between the check and the open -- was followed. The instant-precise substitution
 * is covered where the primitive lives ({@code ConfinedFileOpenerTest}); what this test pins down is
 * that the reader actually routes through it and keeps its own public behaviour: absent files stay
 * "no excerpt", refused ones stay an invalid request.</p>
 */
class LocalSourceReaderConfinementTest {

    private static final SymbolLocation LOCATION = new SymbolLocation(
            "src/Main.java", 1, 0, 1, 5, PositionEncoding.UTF16_CODE_UNITS);

    @Test
    void refusesASymlinkedSourcePointingOutsideTheProject(@TempDir Path root, @TempDir Path outside)
            throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "SECRET", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src"));
        assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");
        Files.createSymbolicLink(root.resolve("src/Main.java"), outside.resolve("secret.txt"));
        LocalSourceReader reader = new LocalSourceReader(root);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> reader.readFull("src/Main.java"));

        assertTrue(failure.getMessage().contains("confined") || failure.getMessage().contains("resolvable"),
                failure.getMessage());
        assertTrue(!failure.getMessage().contains("SECRET"), "no source content may leak into the message");
    }

    @Test
    void refusesASymlinkedSourceEvenWhenItsTargetIsInsideTheProject(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Real.java"), "class Real {}", StandardCharsets.UTF_8);
        assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");
        Files.createSymbolicLink(root.resolve("src/Main.java"), root.resolve("src/Real.java"));
        LocalSourceReader reader = new LocalSourceReader(root);

        // Fail-closed by design: accepting an in-project link would mean deciding containment from a
        // resolved pathname again, which is exactly the property being removed.
        assertThrows(IllegalArgumentException.class, () -> reader.readFull("src/Main.java"));
        assertEquals("class Real {}", reader.readFull("src/Real.java").content());
    }

    @Test
    void refusesASourceReachedThroughASymlinkedDirectory(@TempDir Path root, @TempDir Path outside)
            throws IOException {
        Files.writeString(outside.resolve("Main.java"), "SECRET", StandardCharsets.UTF_8);
        assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");
        Files.createSymbolicLink(root.resolve("src"), outside);
        LocalSourceReader reader = new LocalSourceReader(root);

        assertThrows(RuntimeException.class, () -> reader.readFull("src/Main.java"));
    }

    @Test
    void aMissingSourceStaysAnAbsentExcerptRatherThanAConfinementFailure(@TempDir Path root)
            throws IOException {
        LocalSourceReader reader = new LocalSourceReader(root);

        assertTrue(reader.readExcerpt(LOCATION, 0, 100).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reader.readFull("src/Main.java"));
    }

    @Test
    void nominalNestedSourcesStillReadNormally(@TempDir Path root) throws IOException {
        Path source = root.resolve("src/main/java/com/minos/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Main {}\nint tail;\n", StandardCharsets.UTF_8);
        LocalSourceReader reader = new LocalSourceReader(root);

        SourceExcerpt excerpt = reader.readExcerpt(new SymbolLocation(
                "src/main/java/com/minos/Main.java", 1, 0, 1, 5, PositionEncoding.UTF16_CODE_UNITS),
                0, 100).orElseThrow();

        assertEquals("class Main {}", excerpt.content());
        assertEquals("class Main {}\nint tail;\n",
                reader.readFull("src/main/java/com/minos/Main.java").content());
    }

    private static boolean canCreateSymbolicLinks(Path root) {
        Path probe = root.resolve("minos-symlink-probe");
        try {
            Files.createSymbolicLink(probe, root);
            return true;
        } catch (IOException | UnsupportedOperationException unsupported) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // Probe cleanup failure must not mask the capability answer.
            }
        }
    }
}

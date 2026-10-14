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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSourceReaderTest {

    @Test
    void readsRelevantRangeAndCompleteFileExplicitly(@TempDir Path root) throws IOException {
        Path source = root.resolve("src/main/java/com/minos/Greeting.java");
        Files.createDirectories(source.getParent());
        String content = String.join("\n",
                "package com.minos;",
                "",
                "public class Greeting {",
                "    String hello() { return \"hello\"; }",
                "}"
        );
        Files.writeString(source, content, StandardCharsets.UTF_8);
        LocalSourceReader reader = new LocalSourceReader(root);
        SymbolLocation location = new SymbolLocation(
                "src/main/java/com/minos/Greeting.java",
                3, 13, 3, 21, PositionEncoding.UTF16_CODE_UNITS);

        SourceExcerpt excerpt = reader.readExcerpt(location, 1, 100).orElseThrow();
        SourceExcerpt full = reader.readFull("src/main/java/com/minos/Greeting.java");

        assertEquals(2, excerpt.startLine());
        assertEquals(4, excerpt.endLine());
        assertTrue(excerpt.content().contains("public class Greeting"));
        assertFalse(excerpt.fullFile());
        assertEquals(content, full.content());
        assertTrue(full.fullFile());
        assertFalse(full.truncated());
        assertEquals(5, full.totalFileLines());
    }

    @Test
    void rereadsCurrentContentsWhenTheSameSourcePathChanges(@TempDir Path root) throws IOException {
        Path source = root.resolve("src/Mutable.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class VersionOne {}", StandardCharsets.UTF_8);
        LocalSourceReader reader = new LocalSourceReader(root);
        SymbolLocation location = new SymbolLocation(
                "src/Mutable.java", 1, 0, 1, 10, PositionEncoding.UTF16_CODE_UNITS);

        SourceExcerpt first = reader.readExcerpt(location, 0, 100).orElseThrow();
        Files.writeString(source, "class VersionTwoChanged {}", StandardCharsets.UTF_8);
        SourceExcerpt second = reader.readExcerpt(location, 0, 100).orElseThrow();

        assertTrue(first.content().contains("VersionOne"));
        assertTrue(second.content().contains("VersionTwoChanged"));
        assertFalse(second.content().contains("VersionOne"),
                "a long-lived source reader must never return stale cached source");
    }

    @Test
    void shrinksContextToBudgetAndTreatsOpaqueIdsAsUnavailable(@TempDir Path root)
            throws IOException {
        Path source = root.resolve("src/Long.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join("\n",
                "before-before-before-before",
                "class LongDeclarationWithManyCharacters {}",
                "after-after-after-after"
        ), StandardCharsets.UTF_8);
        LocalSourceReader reader = new LocalSourceReader(root);

        SourceExcerpt excerpt = reader.readExcerpt(new SymbolLocation(
                "src/Long.java", 2, 0, 2, 10, PositionEncoding.UTF16_CODE_UNITS),
                1,
                2
        ).orElseThrow();

        assertTrue(excerpt.truncated());
        assertTrue(excerpt.estimatedTokens() <= 2);
        assertTrue(reader.readExcerpt(new SymbolLocation(
                "file:opaque", 1, 0, 1, 1, PositionEncoding.UNKNOWN),
                0,
                10
        ).isEmpty());
    }

    @Test
    void rejectsTraversalAndAbsolutePaths(@TempDir Path root) throws IOException {
        LocalSourceReader reader = new LocalSourceReader(root);

        assertThrows(IllegalArgumentException.class, () -> reader.readFull("../outside.java"));
        assertThrows(IllegalArgumentException.class, () ->
                reader.readFull(root.resolve("absolute.java").toString()));
    }

    @Test
    void aFileReadOnceGivesTheExcerptsOfTheReadingPerRequest(@TempDir Path root) throws IOException {
        Path source = root.resolve("src/Twice.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Twice {\r\n  void a() {}\r\n  void b() {}\r\n}\r\n", StandardCharsets.UTF_8);
        LocalSourceReader reader = new LocalSourceReader(root);
        SymbolLocation first = new SymbolLocation("src/Twice.java", 2, 0, 2, 1, PositionEncoding.UTF16_CODE_UNITS);
        SymbolLocation second = new SymbolLocation("src/Twice.java", 3, 0, 3, 1, PositionEncoding.UTF16_CODE_UNITS);
        SymbolLocation pastEnd = new SymbolLocation("src/Twice.java", 9, 0, 9, 1, PositionEncoding.UTF16_CODE_UNITS);

        LocalSourceReader.SourceFile file = reader.read("src/Twice.java").orElseThrow();

        assertEquals("src/Twice.java", file.fileId());
        assertEquals(reader.readExcerpt(first, 1, 100), file.excerpt(first, 1, 100));
        assertEquals(reader.readExcerpt(second, 0, 100), file.excerpt(second, 0, 100));
        assertTrue(file.excerpt(pastEnd, 0, 100).isEmpty());
        assertTrue(reader.read("src/Missing.java").isEmpty());
        assertTrue(reader.read("file:opaque").isEmpty());
    }

    @Test
    void aFileReadOnceRefusesTheLocationOfAnotherFileAndInvalidBounds(@TempDir Path root) throws IOException {
        Path source = root.resolve("src/One.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class One {}\n", StandardCharsets.UTF_8);
        LocalSourceReader.SourceFile file = new LocalSourceReader(root).read("src/One.java").orElseThrow();
        SymbolLocation other = new SymbolLocation("src/Other.java", 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
        SymbolLocation own = new SymbolLocation("src/One.java", 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);

        assertThrows(IllegalArgumentException.class, () -> file.excerpt(other, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> file.excerpt(own, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> file.excerpt(own, 0, 0));
        assertThrows(NullPointerException.class, () -> file.excerpt(null, 0, 10));
    }
}

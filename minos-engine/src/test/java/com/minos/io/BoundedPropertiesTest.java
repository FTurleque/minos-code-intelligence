package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedPropertiesTest {

    /** The UTF-8 encoding of U+FEFF. Written byte by byte: no test depends on the charset of the host. */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    void loadsSmallUtf8Properties(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.properties");
        Files.writeString(file, "name=MINOS\nmode=local\n", StandardCharsets.UTF_8);

        var properties = BoundedProperties.load(file, 1024, 10, 64, 64, "test config");

        assertEquals("MINOS", properties.getProperty("name"));
        assertEquals("local", properties.getProperty("mode"));
    }

    @Test
    void rejectsOversizedPropertyPayload(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.properties");
        Files.write(file, new byte[128]);

        assertThrows(IOException.class,
                () -> BoundedProperties.load(file, 32, 10, 64, 64, "test config"));
    }

    @Test
    void rejectsOversizedPropertyValue(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.properties");
        Files.writeString(file, "name=" + "x".repeat(100), StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> BoundedProperties.load(file, 1024, 10, 64, 16, "test config"));
    }

    @Test
    void rejectsTooManyProperties(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.properties");
        Files.writeString(file, "a=1\nb=2\nc=3\n", StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> BoundedProperties.load(file, 1024, 2, 64, 64, "test config"));
    }

    @Test
    void rejectsMalformedUnicodeEscapeAsIoFailure(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.properties");
        Files.writeString(file, "name=\\u12xz\n", StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> BoundedProperties.load(file, 1024, 10, 64, 64, "test config"));
    }

    @Test
    void rejectsMalformedUtf8(@TempDir Path root) throws Exception {
        Path file = root.resolve("malformed-utf8.properties");
        Files.write(file, new byte[] {'k', '=', (byte) 0xc3, (byte) 0x28});

        assertThrows(IOException.class,
                () -> BoundedProperties.load(file, 1024, 10, 64, 64, "test config"));
    }

    @Test
    void strictTextReaderRejectsMalformedUtf8FromFileAndOpenStream(@TempDir Path root) throws Exception {
        byte[] malformed = new byte[] {(byte) 0xc3, (byte) 0x28};
        Path file = root.resolve("malformed-secret.txt");
        Files.write(file, malformed);

        assertThrows(IOException.class, () -> BoundedProperties.readUtf8(file, 32, "secret"));
        assertThrows(IOException.class, () -> BoundedProperties.readUtf8(
                new ByteArrayInputStream(malformed), 32, "secret"));
    }

    @Test
    void strictTextReaderKeepsByteLimitForOpenStreams() {
        assertThrows(IOException.class, () -> BoundedProperties.readUtf8(
                new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)), 3, "secret"));
    }

    @Test
    void appliesTheSameBoundsToAnInMemoryUtf8Envelope() throws Exception {
        byte[] bytes = "name=MINOS\nmode=local\n".getBytes(StandardCharsets.UTF_8);

        var properties = BoundedProperties.loadUtf8(bytes, 1024, 10, 64, 64, "test envelope");

        assertEquals("MINOS", properties.getProperty("name"));
        assertThrows(IOException.class,
                () -> BoundedProperties.loadUtf8(bytes, 8, 10, 64, 64, "test envelope"));
    }

    // MINOS-AUD-B08 : un BOM UTF-8 en tete (Windows PowerShell 5.1, anciens Bloc-notes) n'altere ni une propriete ni un secret.

    @Test
    void aLeadingByteOrderMarkIsRemovedFromTheFirstProperty(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.properties");
        Files.write(file, concat(BOM, "a=b\r\nc=d\n".getBytes(StandardCharsets.UTF_8)));

        var properties = BoundedProperties.load(file, 1024, 10, 64, 64, "test config");

        assertEquals("b", properties.getProperty("a"));
        assertEquals("d", properties.getProperty("c"));
        assertEquals(2, properties.size());
    }

    @Test
    void readUtf8RemovesASingleLeadingByteOrderMarkFromAFileAndFromAStream(@TempDir Path root) throws Exception {
        byte[] content = concat(BOM, "s3cret\r\n".getBytes(StandardCharsets.UTF_8));
        Path file = root.resolve("secret.txt");
        Files.write(file, content);

        assertEquals("s3cret\r\n", BoundedProperties.readUtf8(file, 64, "secret"));
        assertEquals("s3cret\r\n", BoundedProperties.readUtf8(new ByteArrayInputStream(content), 64, "secret"));
    }

    @Test
    void aRepeatedByteOrderMarkIsRefusedExplicitly(@TempDir Path root) throws Exception {
        byte[] content = concat(BOM, BOM, "a=b".getBytes(StandardCharsets.UTF_8));
        Path file = root.resolve("config.properties");
        Files.write(file, content);

        IOException failure = assertThrows(IOException.class,
                () -> BoundedProperties.load(file, 1024, 10, 64, 64, "test config"));
        assertTrue(failure.getMessage().contains("byte order mark"), failure.getMessage());
        assertThrows(IOException.class,
                () -> BoundedProperties.readUtf8(new ByteArrayInputStream(content), 64, "secret"));
        assertThrows(IOException.class, () -> BoundedProperties.readUtf8(file, 64, "secret"));
    }

    @Test
    void aByteOrderMarkInTheMiddleRemainsData(@TempDir Path root) throws Exception {
        byte[] content = concat("a=".getBytes(StandardCharsets.UTF_8), BOM, "b".getBytes(StandardCharsets.UTF_8));
        Path file = root.resolve("config.properties");
        Files.write(file, content);

        assertEquals("\uFEFFb", BoundedProperties.load(file, 1024, 10, 64, 64, "test config").getProperty("a"));
    }

    @Test
    void aFileWithoutAByteOrderMarkIsReadAsBefore(@TempDir Path root) throws Exception {
        Path file = root.resolve("config.properties");
        Files.writeString(file, "a=b\n", StandardCharsets.UTF_8);

        assertEquals("b", BoundedProperties.load(file, 1024, 10, 64, 64, "test config").getProperty("a"));
        assertEquals("", BoundedProperties.readUtf8(new ByteArrayInputStream(new byte[0]), 64, "secret"));
        assertEquals("", BoundedProperties.readUtf8(new ByteArrayInputStream(BOM), 64, "secret"));
    }

    @Test
    void theByteLimitStillCountsTheByteOrderMark(@TempDir Path root) throws Exception {
        byte[] content = concat(BOM, "a=b".getBytes(StandardCharsets.UTF_8));
        Path file = root.resolve("config.properties");
        Files.write(file, content);

        assertEquals("b", BoundedProperties.load(file, content.length, 10, 64, 64, "test config").getProperty("a"));
        assertThrows(IOException.class,
                () -> BoundedProperties.load(file, content.length - 1, 10, 64, 64, "test config"));
    }

    @Test
    void invalidUtf8AfterAByteOrderMarkIsStillRefused(@TempDir Path root) throws Exception {
        byte[] content = concat(BOM, new byte[] {'k', '=', (byte) 0xc3, (byte) 0x28});
        Path file = root.resolve("config.properties");
        Files.write(file, content);

        assertThrows(IOException.class, () -> BoundedProperties.load(file, 1024, 10, 64, 64, "test config"));
    }

    @Test
    void anInMemoryEnvelopeKeepsItsByteOrderMarkBehaviour() throws Exception {
        var properties = BoundedProperties.loadUtf8(
                concat(BOM, "a=b".getBytes(StandardCharsets.UTF_8)), 1024, 10, 64, 64, "test envelope");

        assertNull(properties.getProperty("a"));
        assertEquals("b", properties.getProperty("\uFEFFa"));
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] value = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, value, offset, part.length);
            offset += part.length;
        }
        return value;
    }
}

package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedPropertiesTest {

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
}

package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}

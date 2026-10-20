package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A properties document is written in one private piece, byte for byte as {@link Properties#store} renders it. */
class PrivatePropertiesTest {

    private static Properties sample() {
        Properties properties = new Properties();
        properties.setProperty("name", "caf\u00e9 \u20ac");
        properties.setProperty("plain", "value");
        return properties;
    }

    private static List<String> withoutDate(String content) {
        return content.lines().filter(line -> !line.startsWith("#") || line.contains("MINOS")).sorted().toList();
    }

    @Test
    void latin1KeepsTheStreamFormWithEscapedCharacters() throws Exception {
        Properties properties = sample();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        properties.store(expected, "MINOS test");

        byte[] rendered = PrivateProperties.render(properties, "MINOS test", StandardCharsets.ISO_8859_1);

        assertEquals(withoutDate(expected.toString(StandardCharsets.ISO_8859_1)),
                withoutDate(new String(rendered, StandardCharsets.ISO_8859_1)));
        assertTrue(new String(rendered, StandardCharsets.ISO_8859_1).contains("\\u00E9"));
    }

    @Test
    void utf8KeepsTheWriterFormWithTheCharactersAsTheyAre() throws Exception {
        byte[] rendered = PrivateProperties.render(sample(), "MINOS test", StandardCharsets.UTF_8);

        String text = new String(rendered, StandardCharsets.UTF_8);
        assertTrue(text.contains("caf\u00e9 \u20ac"), text);
        Properties reread = new Properties();
        reread.load(new StringReader(text));
        assertEquals(sample(), reread);
    }

    @Test
    void theFileIsWrittenOwnerOnlyAndReplacedInPlace(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.properties");

        PrivateProperties.write(file, sample(), "MINOS test", StandardCharsets.UTF_8);
        Properties replacement = new Properties();
        replacement.setProperty("plain", "second");
        PrivateProperties.write(file, replacement, "MINOS test", StandardCharsets.UTF_8);

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("plain=second"));
    }

    @Test
    void nullArgumentsAreRefused() {
        assertThrows(NullPointerException.class,
                () -> PrivateProperties.render(null, "c", StandardCharsets.UTF_8));
        assertThrows(NullPointerException.class,
                () -> PrivateProperties.render(new Properties(), "c", null));
    }
}

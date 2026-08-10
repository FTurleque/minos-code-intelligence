package com.minos.io;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** Shared bounded UTF-8 readers for small runtime configuration and metadata files. */
public final class BoundedProperties {

    private BoundedProperties() {
    }

    public static Properties load(
            Path file,
            long maximumBytes,
            int maximumEntries,
            int maximumKeyChars,
            int maximumValueChars,
            String boundary
    ) throws IOException {
        Objects.requireNonNull(file, "file");
        if (maximumEntries < 1 || maximumKeyChars < 1 || maximumValueChars < 1) {
            throw new IllegalArgumentException("properties limits must be positive");
        }
        Properties properties = new Properties();
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(file), maximumBytes, boundary);
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (properties.size() > maximumEntries) {
            throw new IOException(label(boundary) + " exceeds property count limit: "
                    + properties.size() + "/" + maximumEntries);
        }
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key, "");
            if (key.length() > maximumKeyChars) {
                throw new IOException(label(boundary) + " property key exceeds character limit");
            }
            if (value.length() > maximumValueChars) {
                throw new IOException(label(boundary) + " property value exceeds character limit for " + key);
            }
        }
        return properties;
    }

    public static String readUtf8(Path file, long maximumBytes, String boundary) throws IOException {
        Objects.requireNonNull(file, "file");
        try (BoundedInputStream input = new BoundedInputStream(
                Files.newInputStream(file), maximumBytes, boundary)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String label(String boundary) {
        return boundary == null || boundary.isBlank() ? "properties file" : boundary;
    }
}

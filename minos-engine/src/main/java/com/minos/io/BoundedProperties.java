package com.minos.io;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        Path source = requireRegularFile(file, boundary);
        if (maximumEntries < 1 || maximumKeyChars < 1 || maximumValueChars < 1) {
            throw new IllegalArgumentException("properties limits must be positive");
        }
        Properties properties = new Properties();
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                     maximumBytes, boundary);
             Reader reader = strictUtf8Reader(input)) {
            loadInto(properties, reader, boundary);
        }
        validate(properties, maximumEntries, maximumKeyChars, maximumValueChars, boundary);
        return properties;
    }

    /** Bounded variant for an already byte-capped UTF-8 envelope such as a ZIP manifest. */
    public static Properties loadUtf8(
            byte[] bytes,
            long maximumBytes,
            int maximumEntries,
            int maximumKeyChars,
            int maximumValueChars,
            String boundary
    ) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (maximumBytes < 1L || bytes.length > maximumBytes) {
            throw new IOException(label(boundary) + " exceeds byte limit: "
                    + bytes.length + "/" + maximumBytes);
        }
        if (maximumEntries < 1 || maximumKeyChars < 1 || maximumValueChars < 1) {
            throw new IllegalArgumentException("properties limits must be positive");
        }
        Properties properties = new Properties();
        try (Reader reader = strictUtf8Reader(new ByteArrayInputStream(bytes))) {
            loadInto(properties, reader, boundary);
        }
        validate(properties, maximumEntries, maximumKeyChars, maximumValueChars, boundary);
        return properties;
    }

    private static void loadInto(Properties properties, Reader reader, String boundary) throws IOException {
        try {
            properties.load(reader);
        } catch (IllegalArgumentException exception) {
            throw new IOException(label(boundary) + " is malformed", exception);
        }
    }

    private static Reader strictUtf8Reader(java.io.InputStream input) {
        return new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    }

    private static void validate(
            Properties properties,
            int maximumEntries,
            int maximumKeyChars,
            int maximumValueChars,
            String boundary
    ) throws IOException {
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
    }

    public static String readUtf8(Path file, long maximumBytes, String boundary) throws IOException {
        Path source = requireRegularFile(file, boundary);
        try (BoundedInputStream input = new BoundedInputStream(
                Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                maximumBytes, boundary)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path requireRegularFile(Path file, String boundary) throws IOException {
        Path source = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label(boundary) + " must be a regular non-symlink file: " + source);
        }
        return source;
    }

    private static String label(String boundary) {
        return boundary == null || boundary.isBlank() ? "properties file" : boundary;
    }
}

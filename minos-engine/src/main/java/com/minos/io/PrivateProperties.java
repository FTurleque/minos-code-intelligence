package com.minos.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * A {@link Properties} document written in one private piece: rendered in memory, then written by
 * {@link PrivateLocalStorage#writePrivateFile}, never through a link and never with a permissive mode.
 */
public final class PrivateProperties {

    private PrivateProperties() {
    }

    /**
     * Renders {@code properties} as {@link Properties#store} does: through the stream form, whose encoding is
     * ISO-8859-1 with Unicode escapes, for {@link StandardCharsets#ISO_8859_1}; through the writer form for any
     * other charset.
     */
    public static byte[] render(Properties properties, String comment, Charset charset) throws IOException {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(charset, "charset");
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        if (charset.equals(StandardCharsets.ISO_8859_1)) {
            properties.store(content, comment);
        } else {
            try (Writer writer = new OutputStreamWriter(content, charset)) {
                properties.store(writer, comment);
            }
        }
        return content.toByteArray();
    }

    /** Writes the rendered document as the whole content of the owner-only {@code file}. */
    public static void write(Path file, Properties properties, String comment, Charset charset) throws IOException {
        PrivateLocalStorage.writePrivateFile(file, render(properties, comment, charset));
    }
}

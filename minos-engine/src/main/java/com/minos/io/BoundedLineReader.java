package com.minos.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.Objects;

/** Reader that fails before materializing a text line beyond a configured character bound. */
public final class BoundedLineReader implements Closeable {
    private final PushbackReader reader;
    private final int maxLineChars;

    public BoundedLineReader(Reader reader, int maxLineChars) {
        this.reader = new PushbackReader(Objects.requireNonNull(reader, "reader"), 1);
        if (maxLineChars < 1) throw new IllegalArgumentException("maxLineChars must be positive");
        this.maxLineChars = maxLineChars;
    }

    public String readLine() throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maxLineChars, 4096));
        boolean sawAny = false;
        while (true) {
            int value = reader.read();
            if (value < 0) return sawAny ? line.toString() : null;
            sawAny = true;
            char current = (char) value;
            if (current == '\n') return line.toString();
            if (current == '\r') {
                int next = reader.read();
                if (next >= 0 && next != '\n') reader.unread(next);
                return line.toString();
            }
            if (line.length() >= maxLineChars) {
                throw new IOException("text line exceeds character limit: " + maxLineChars);
            }
            line.append(current);
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

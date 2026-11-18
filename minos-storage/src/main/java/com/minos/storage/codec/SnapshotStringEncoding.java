package com.minos.storage.codec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * How a snapshot format encodes its strings; everything else in the layout is shared. V1 and V2 write UTF-16 code
 * units, V3 writes UTF-8 (length in bytes, ADR 0046).
 */
enum SnapshotStringEncoding {
    UTF16_CHARS,
    UTF8;

    /** A string that holds an unpaired surrogate: it has no exact UTF-8 form. */
    static final class UnpairedSurrogateException extends IOException {
        UnpairedSurrogateException() {
            super("string holds an unpaired surrogate and has no exact UTF-8 form");
        }
    }

    /** Exact UTF-8 length; refuses an unpaired surrogate rather than let it degrade to a replacement byte. */
    static long utf8Length(String value) throws UnpairedSurrogateException {
        long length = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x80) {
                length += 1;
            } else if (current < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new UnpairedSurrogateException();
                }
                length += 4;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new UnpairedSurrogateException();
            } else {
                length += 3;
            }
        }
        return length;
    }

    /** Strict UTF-8 encoding: an unpaired surrogate is refused, never replaced by {@code '?'}. */
    static byte[] utf8(String value) throws UnpairedSurrogateException {
        utf8Length(value);
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

package com.minos.diagnostics;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/** The name of a journal file: a compact UTC instant that sorts chronologically, then the reference. */
final class EntryFileNames {

    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private EntryFileNames() {
    }

    static String of(Instant instant, String reference) {
        return INSTANT.format(Objects.requireNonNull(instant, "instant")) + "-" + reference + ".txt";
    }
}

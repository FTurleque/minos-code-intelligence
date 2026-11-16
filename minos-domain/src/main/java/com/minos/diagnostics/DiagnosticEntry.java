package com.minos.diagnostics;

import java.time.Instant;

/** What the private diagnostic journal writes for one internal failure: a reference, a file name and a text. */
public interface DiagnosticEntry {

    /** Eight hexadecimal digits that tie a response, a stderr line and a journal entry together. */
    String reference();

    /** The text of the journal entry written at {@code instant}. */
    String entry(Instant instant);

    /** The name of the journal file for an entry written at {@code instant}; names sort by instant. */
    default String fileName(Instant instant) {
        return EntryFileNames.of(instant, reference());
    }
}

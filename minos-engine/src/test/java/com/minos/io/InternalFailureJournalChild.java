package com.minos.io;

import com.minos.diagnostics.InternalFailureTrace;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Child JVM of {@link InternalFailureJournalTest}: journals {@code args[1]} failures into the home {@code args[0]}. */
public final class InternalFailureJournalChild {

    private InternalFailureJournalChild() {
    }

    public static void main(String[] args) {
        InternalFailureJournal journal = new InternalFailureJournal(Path.of(args[0]),
                Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, InternalFailureJournal.ENABLED), new Properties());
        int count = Integer.parseInt(args[1]);
        for (int index = 0; index < count; index++) {
            journal.append(InternalFailureTrace.describe("cli", "index",
                    new IllegalStateException("child failure " + index)));
        }
    }
}

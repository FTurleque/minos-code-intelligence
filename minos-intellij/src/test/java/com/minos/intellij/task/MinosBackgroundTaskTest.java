package com.minos.intellij.task;

import com.intellij.openapi.progress.ProcessCanceledException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosBackgroundTaskTest {

    private record Entry(String message, Throwable failure) {
    }

    private final List<Entry> journal = new ArrayList<>();
    private final MinosBackgroundTask task = new MinosBackgroundTask(
            (message, failure) -> journal.add(new Entry(message, failure)));

    @Test
    void aSuccessfulOperationReturnsItsResultAndLogsNothing() {
        MinosBackgroundTask.Outcome<String> outcome = task.run("Titre", () -> "résultat");

        assertEquals("résultat", outcome.result());
        assertNull(outcome.failure());
        assertTrue(journal.isEmpty());
    }

    @Test
    void anExceptionWithoutMessageIsLoggedWithItsStackAndItsType() {
        IllegalStateException cause = new IllegalStateException();

        MinosBackgroundTask.Outcome<String> outcome = task.run("Ouvrir le symbole", () -> {
            throw cause;
        });

        assertSame(cause, outcome.failure());
        assertNull(outcome.result());
        assertEquals(1, journal.size());
        assertSame(cause, journal.get(0).failure(), "la pile doit accompagner le journal");
        assertTrue(journal.get(0).message().contains("MINOS task failed"), journal.get(0).message());
        assertTrue(journal.get(0).message().contains("Ouvrir le symbole"), journal.get(0).message());
        assertTrue(journal.get(0).message().contains("IllegalStateException"), journal.get(0).message());
    }

    @Test
    void anExceptionWithAMessageIsLoggedAndReturned() {
        Exception cause = new Exception("le CLI a refusé la requête");

        MinosBackgroundTask.Outcome<String> outcome = task.run("Titre", () -> {
            throw cause;
        });

        assertSame(cause, outcome.failure());
        assertSame(cause, journal.get(0).failure());
    }

    @Test
    void anErrorIsRethrownAndNeverSwallowed() {
        OutOfMemoryError error = new OutOfMemoryError("test");

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> task.run("Titre", () -> {
            throw error;
        }));

        assertSame(error, thrown);
        assertTrue(journal.isEmpty());
    }

    @Test
    void aCancellationIsRethrownWithoutALogEntry() {
        ProcessCanceledException canceled = new ProcessCanceledException();

        ProcessCanceledException thrown = assertThrows(ProcessCanceledException.class, () -> task.run("Titre", () -> {
            throw canceled;
        }));

        assertSame(canceled, thrown);
        assertTrue(journal.isEmpty());
    }

    @Test
    void describeNamesTheTypeWhenTheMessageIsMissingOrBlank() {
        assertEquals("boom", MinosBackgroundTask.describe(new Exception("boom")));
        assertEquals("Unknown failure (IllegalStateException)", MinosBackgroundTask.describe(new IllegalStateException()));
        assertEquals("Unknown failure (IllegalStateException)", MinosBackgroundTask.describe(new IllegalStateException("  ")));
        assertEquals("Unknown failure", MinosBackgroundTask.describe(null));
    }
}

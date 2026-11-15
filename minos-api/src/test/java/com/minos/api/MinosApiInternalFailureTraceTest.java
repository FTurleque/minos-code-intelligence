package com.minos.api;

import com.minos.api.MinosApi.ErrorCode;
import com.minos.api.MinosApi.MinosApiException;
import com.minos.application.diagnostics.InternalFailureReporter;
import com.minos.io.InternalFailureJournal;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An execution or I/O failure of the Java API is traced with an origin and a reference; the exception is unchanged. */
class MinosApiInternalFailureTraceTest {

    @TempDir
    Path temp;

    @AfterEach
    void restoreTheDefaultReporter() {
        MinosApiSupport.useFailureReporter(null);
    }

    @Test
    void anExecutionFailureIsTracedAndTheCallerGetsACauseFreeException() {
        MinosApiSupport.useFailureReporter(InternalFailureReporter.withoutJournal("api"));

        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new RuntimeException("wrapper", new NullPointerException("secret=hunter2 at C:\\Users\\alice"));
            }));

            assertEquals(ErrorCode.EXECUTION_FAILURE, failure.code());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            String line = log.warnings().get(0);
            assertTrue(line.contains("code=EXECUTION_FAILURE"), line);
            assertTrue(line.contains("type=" + RuntimeException.class.getName()), line);
            assertTrue(line.contains("rootCause=" + NullPointerException.class.getName()), line);
            assertTrue(line.contains("at=com.minos.api.MinosApiInternalFailureTraceTest."), line);
            assertTrue(line.matches(".*, ref=[0-9a-f]{8}\\)"), line);
            assertFalse(line.contains("hunter2") || line.contains("alice"), line);
            assertFalse(failure.getMessage().contains("hunter2") || failure.getMessage().contains("alice"));
        }
    }

    @Test
    void anIoFailureIsTracedToo() {
        MinosApiSupport.useFailureReporter(InternalFailureReporter.withoutJournal("api"));

        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class,
                    () -> MinosApiSupport.execute(() -> {
                        throw new IOException("disk failure");
                    }));

            assertEquals(ErrorCode.IO_FAILURE, failure.code());
            assertTrue(log.warnings().get(0).contains("code=IO_FAILURE"), log.warnings().get(0));
            assertTrue(log.warnings().get(0).contains("ref="), log.warnings().get(0));
        }
    }

    @Test
    void anInvalidRequestIsNotTraced() throws Exception {
        MinosApiSupport.useFailureReporter(new InternalFailureReporter("api", temp,
                Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "enabled"), new Properties()));

        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class,
                    () -> MinosApiSupport.execute(() -> {
                        throw new IllegalArgumentException("limit must be positive");
                    }));

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertTrue(log.warnings().stream().noneMatch(line -> line.contains("ref=")), log.warnings().toString());
        }
        assertEquals(List.of(), entries());
    }

    @Test
    void anEnabledJournalKeepsTheStackUnderTheReferenceOfTheLine() throws Exception {
        MinosApiSupport.useFailureReporter(new InternalFailureReporter("api", temp,
                Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "enabled"), new Properties()));

        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new NullPointerException("limit must be positive");
            }));

            String line = log.warnings().get(0);
            String reference = line.substring(line.lastIndexOf("ref=") + 4, line.length() - 1);
            List<Path> entries = entries();
            assertEquals(1, entries.size());
            assertTrue(entries.get(0).getFileName().toString().endsWith("-" + reference + ".txt"));
            String entry = Files.readString(entries.get(0));
            assertTrue(entry.contains("surface=api operation=EXECUTION_FAILURE ref=" + reference), entry);
            assertTrue(entry.contains("java.lang.NullPointerException: limit must be positive"), entry);
        }
    }

    private List<Path> entries() throws IOException {
        Path directory = temp.resolve("logs").resolve("internal-failures");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.toList();
        }
    }
}

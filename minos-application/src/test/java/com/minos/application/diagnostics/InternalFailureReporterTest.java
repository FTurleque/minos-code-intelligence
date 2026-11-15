package com.minos.application.diagnostics;

import com.minos.diagnostics.InternalFailureTrace;
import com.minos.io.InternalFailureJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalFailureReporterTest {

    private static final Map<String, String> ENABLED = Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "enabled");

    @TempDir
    Path temp;

    @Test
    void anInactiveJournalStillDescribesTheFailureAndWritesNoFile() throws Exception {
        InternalFailureReporter reporter = new InternalFailureReporter("mcp", temp, Map.of(), new Properties());

        InternalFailureTrace trace = reporter.report("minos_find_symbols", new IllegalStateException("boom"));

        assertFalse(reporter.journalEnabled());
        assertEquals(8, trace.reference().length());
        assertTrue(trace.line("MCP tool execution failed", "tool", "minos_find_symbols")
                .contains("ref=" + trace.reference()));
        assertFalse(Files.exists(temp.resolve("logs")));
    }

    @Test
    void anActiveJournalWritesTheEntryUnderTheReferenceOfTheReturnedTrace() throws Exception {
        InternalFailureReporter reporter = new InternalFailureReporter("cli", temp, ENABLED, new Properties());

        InternalFailureTrace trace = reporter.report("index", new IllegalStateException("limit must be positive"));

        assertTrue(reporter.journalEnabled());
        Path directory = temp.resolve("logs").resolve("internal-failures");
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> files = entries.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().endsWith("-" + trace.reference() + ".txt"));
            assertTrue(Files.readString(files.get(0)).contains("surface=cli operation=index ref=" + trace.reference()));
        }
    }

    @Test
    void anUnavailableJournalNeverThrowsAndTheTraceIsStillReturned() throws Exception {
        Files.writeString(temp.resolve("logs"), "a file where the journal directory should be");
        InternalFailureReporter reporter = new InternalFailureReporter("api", temp, ENABLED, new Properties());

        InternalFailureTrace trace = reporter.report("listProjects", new IllegalStateException("boom"));

        assertTrue(trace.line("API call failed", "operation", "listProjects").contains("ref=" + trace.reference()));
        assertTrue(Files.isRegularFile(temp.resolve("logs")));
    }

    @Test
    void aReporterWithoutJournalWritesNothingAnywhere() {
        InternalFailureReporter reporter = InternalFailureReporter.withoutJournal("mcp");

        InternalFailureTrace trace = reporter.report("tool", new RuntimeException());

        assertFalse(reporter.journalEnabled());
        assertEquals(8, trace.reference().length());
        assertFalse(Files.exists(Path.of("logs").resolve("internal-failures")));
    }
}

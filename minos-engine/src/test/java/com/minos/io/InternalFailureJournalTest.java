package com.minos.io;

import com.minos.diagnostics.InternalFailureTrace;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalFailureJournalTest {

    private static final Map<String, String> ENABLED = Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "enabled");

    @TempDir
    Path temp;

    @Test
    void withoutTheSettingNothingIsCreated() throws Exception {
        List<String> before = listing(temp);
        InternalFailureJournal journal = new InternalFailureJournal(temp, Map.of(), new Properties());

        journal.append(trace());

        assertFalse(journal.isEnabled());
        assertFalse(Files.exists(temp.resolve("logs")));
        assertEquals(before, listing(temp));
    }

    @Test
    void anUnrecognisedValueWritesNothingAndIsReportedOnceWithoutBeingCopied() {
        InternalFailureJournal journal = new InternalFailureJournal(temp,
                Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "oui-secret-value"), new Properties());

        try (LogCapture log = new LogCapture(InternalFailureJournal.class)) {
            journal.append(trace());
            journal.append(trace());

            assertEquals(1, log.warnings().size(), log.warnings().toString());
            assertFalse(log.warnings().get(0).contains("oui-secret-value"), log.warnings().get(0));
        }
        assertFalse(Files.exists(temp.resolve("logs")));
    }

    @Test
    void theSystemPropertyTakesPrecedenceOverTheEnvironment() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(InternalFailureJournal.SYSTEM_PROPERTY, "enabled");

        new InternalFailureJournal(temp, Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "no"), properties)
                .append(trace());

        assertEquals(1, entries().size());
    }

    @Test
    void anEntryIsOnePrivateFileUnderThePrivateDirectoryWithTheReferenceOfTheTrace() throws Exception {
        InternalFailureTrace trace = trace();

        new InternalFailureJournal(temp, ENABLED, new Properties()).append(trace);

        List<Path> entries = entries();
        assertEquals(1, entries.size());
        Path entry = entries.get(0);
        assertTrue(entry.getFileName().toString().endsWith("-" + trace.reference() + ".txt"));
        String content = Files.readString(entry, StandardCharsets.UTF_8);
        assertTrue(content.contains("ref=" + trace.reference()), content);
        assertTrue(content.contains("java.lang.IllegalStateException: limit must be positive"), content);
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(entry));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(entry.getParent()));
        assertFalse(content.contains(temp.toString()), "no absolute path in an entry");
    }

    @Test
    void theOldestEntriesAreRemovedBeyondTheBoundAndTheNewestRemain() throws Exception {
        AtomicLong tick = new AtomicLong();
        InternalFailureJournal journal = new InternalFailureJournal(temp, ENABLED, new Properties(), 3,
                () -> Instant.parse("2026-11-15T10:00:00Z").plusMillis(tick.incrementAndGet()));
        List<String> references = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            InternalFailureTrace trace = trace();
            references.add(trace.reference());
            journal.append(trace);
        }

        List<String> kept = entries().stream().map(path -> path.getFileName().toString()).toList();

        assertEquals(3, kept.size(), kept.toString());
        for (String reference : references.subList(4, 7)) {
            assertTrue(kept.stream().anyMatch(name -> name.contains(reference)), reference + " in " + kept);
        }
    }

    @Test
    void aBurstBeyondTheWindowBoundIsDroppedAndSaidOnceThenTheNextWindowWritesAgain() throws Exception {
        AtomicLong tick = new AtomicLong();
        InternalFailureJournal journal = new InternalFailureJournal(temp, ENABLED, new Properties(), 200,
                () -> Instant.parse("2026-11-15T10:00:00Z").plusSeconds(tick.get()));

        try (LogCapture log = new LogCapture(InternalFailureJournal.class)) {
            for (int index = 0; index < InternalFailureJournal.MAX_ENTRIES_PER_WINDOW + 10; index++) {
                journal.append(trace());
            }

            assertEquals(InternalFailureJournal.MAX_ENTRIES_PER_WINDOW, entries().size());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            assertTrue(log.warnings().get(0).contains("rate limited"), log.warnings().get(0));

            tick.set(InternalFailureJournal.WINDOW.toSeconds() + 1);
            journal.append(trace());

            assertEquals(InternalFailureJournal.MAX_ENTRIES_PER_WINDOW + 1, entries().size());
        }
    }

    @Test
    void thePurgeLeavesAFileThatIsNotAnEntryAlone() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("logs").resolve("internal-failures"));
        Files.writeString(directory.resolve("notes.txt"), "kept");
        AtomicLong tick = new AtomicLong();
        InternalFailureJournal journal = new InternalFailureJournal(temp, ENABLED, new Properties(), 2,
                () -> Instant.parse("2026-11-15T10:00:00Z").plusMillis(tick.incrementAndGet()));

        for (int index = 0; index < 5; index++) journal.append(trace());

        assertTrue(Files.exists(directory.resolve("notes.txt")));
        assertEquals(3, entries().size(), "two entries and the unrelated file");
    }

    @Test
    void anUnavailableJournalDropsTheEntryReportsOnceAndNeverThrows() throws Exception {
        Files.writeString(temp.resolve("logs"), "a file where the directory should be");
        List<String> before = listing(temp);
        InternalFailureJournal journal = new InternalFailureJournal(temp, ENABLED, new Properties());

        try (LogCapture log = new LogCapture(InternalFailureJournal.class)) {
            journal.append(trace());
            journal.append(trace());

            assertEquals(1, log.warnings().size(), log.warnings().toString());
            assertTrue(log.warnings().get(0).startsWith("diagnostic journal unavailable ("), log.warnings().get(0));
            assertFalse(log.warnings().get(0).contains(temp.getFileName().toString()), log.warnings().get(0));
        }
        assertEquals(before, listing(temp), "an unavailable journal changes nothing");
    }

    @Test
    void twoProcessesJournalingIntoTheSameHomeLeaveWholeEntriesUnderTheirOwnReferences() throws Exception {
        Process first = child();
        Process second = child();
        assertTrue(first.waitFor(120, TimeUnit.SECONDS), "first child");
        assertTrue(second.waitFor(120, TimeUnit.SECONDS), "second child");
        assertEquals(0, first.exitValue());
        assertEquals(0, second.exitValue());

        List<Path> entries = entries();

        assertEquals(100, entries.size());
        List<String> references = new ArrayList<>();
        for (Path entry : entries) {
            String content = Files.readString(entry, StandardCharsets.UTF_8);
            String name = entry.getFileName().toString();
            String reference = name.substring(name.lastIndexOf('-') + 1, name.length() - ".txt".length());
            assertTrue(content.lines().findFirst().orElseThrow().endsWith(" ref=" + reference), content);
            assertTrue(content.contains("caused by") || content.contains("at "), content);
            references.add(reference);
        }
        assertEquals(100, references.stream().distinct().count());
    }

    private Process child() throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                InternalFailureJournalChild.class.getName(), temp.toString(), "50"))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static InternalFailureTrace trace() {
        return InternalFailureTrace.describe("mcp", "minos_find_symbols",
                new IllegalStateException("limit must be positive"));
    }

    private List<Path> entries() throws IOException {
        Path directory = temp.resolve("logs").resolve("internal-failures");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.sorted().toList();
        }
    }

    private static List<String> listing(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.map(path -> root.relativize(path).toString()).sorted().toList();
        }
    }
}

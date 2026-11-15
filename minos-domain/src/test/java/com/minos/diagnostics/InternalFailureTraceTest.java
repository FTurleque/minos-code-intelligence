package com.minos.diagnostics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalFailureTraceTest {

    private static final Pattern REFERENCE = Pattern.compile("[0-9a-f]{8}");
    private static final Instant INSTANT = Instant.parse("2026-11-15T10:20:30.123Z");

    @Test
    void theReferenceIsEightHexadecimalDigitsAndDiffersFromOneFailureToTheNext() {
        Set<String> references = new HashSet<>();
        for (int index = 0; index < 50; index++) {
            String reference = InternalFailureTrace.describe("mcp", "tool", new IllegalStateException()).reference();
            assertTrue(REFERENCE.matcher(reference).matches(), reference);
            references.add(reference);
        }
        assertTrue(references.size() > 40, "references must be drawn at random: " + references.size());
    }

    @Test
    void theOriginIsTheFirstMinosFrameOfTheRootCause() {
        Exception failure = new RuntimeException("wrapper", framed(
                new NullPointerException("inner"),
                frame("java.util.Objects", "requireNonNull", "Objects.java", 233),
                frame("com.minos.demo.Widget", "render", "Widget.java", 42),
                frame("com.minos.demo.Caller", "call", "Caller.java", 7)));

        InternalFailureTrace trace = InternalFailureTrace.describe("mcp", "minos_find_symbols", failure);

        assertEquals("com.minos.demo.Widget.render:42", trace.origin());
        assertEquals(RuntimeException.class.getName(), trace.type());
        assertEquals(NullPointerException.class.getName(), trace.rootCauseType());
        String line = trace.line("MCP tool execution failed", "tool", "minos_find_symbols");
        assertEquals("MCP tool execution failed (tool=minos_find_symbols, type=java.lang.RuntimeException,"
                + " rootCause=java.lang.NullPointerException, at=com.minos.demo.Widget.render:42, ref="
                + trace.reference() + ")", line);
    }

    @Test
    void withoutAnyMinosFrameTheOriginIsUnknownAndTheClassesAndReferenceRemain() {
        Exception failure = framed(new IllegalStateException("boom"),
                frame("java.util.ArrayList", "get", "ArrayList.java", 427));

        InternalFailureTrace trace = InternalFailureTrace.describe("api", "listProjects", failure);

        assertEquals("unknown", trace.origin());
        String line = trace.line("API call failed", "operation", "listProjects");
        assertTrue(line.contains("at=unknown"), line);
        assertTrue(line.contains("rootCause=java.lang.IllegalStateException"), line);
        assertTrue(line.contains("ref=" + trace.reference()), line);
    }

    @Test
    void theLineNeverCarriesTheMessageOfTheException() {
        Exception failure = new IllegalStateException(
                "cannot read C:\\Users\\alice\\.minos\\registry.json with token=abcdef123456");

        InternalFailureTrace trace = InternalFailureTrace.describe("mcp", "tool", failure);
        String line = trace.line("MCP tool execution failed", "tool", "tool");

        assertFalse(line.contains("alice"), line);
        assertFalse(line.contains("abcdef123456"), line);
        assertFalse(line.contains("registry.json"), line);
    }

    @Test
    void aSensitiveMessageIsWithheldFromTheEntryAndAFixedTextTakesItsPlace() {
        Exception failure = new IllegalStateException("open C:\\Users\\alice\\.minos failed: password=hunter2 for "
                + "jdbc:postgresql://db.internal/minos and /home/alice/project");

        String entry = InternalFailureTrace.describe("mcp", "tool", failure).entry(INSTANT);

        assertFalse(entry.contains("alice"), entry);
        assertFalse(entry.contains("hunter2"), entry);
        assertFalse(entry.contains("jdbc:"), entry);
        assertFalse(entry.contains("db.internal"), entry);
        assertTrue(entry.contains(InternalFailureTrace.WITHHELD_MESSAGE), entry);
    }

    @Test
    void aMessageThatIsNotSensitiveIsKept() {
        String entry = InternalFailureTrace.describe("mcp", "tool", new IllegalStateException("limit must be positive"))
                .entry(INSTANT);

        assertTrue(entry.contains("java.lang.IllegalStateException: limit must be positive"), entry);
    }

    @Test
    void theEntryHeaderNamesTheInstantTheSurfaceTheOperationAndTheReference() {
        InternalFailureTrace trace = InternalFailureTrace.describe("explorer", "GET /api/search", new RuntimeException());

        String header = trace.entry(INSTANT).lines().findFirst().orElseThrow();

        assertEquals("2026-11-15T10:20:30.123Z surface=explorer operation=GET /api/search ref=" + trace.reference(),
                header);
    }

    @Test
    void theEntryListsTheCauseChainWithTheirFrames() {
        Exception failure = new RuntimeException("outer", framed(new IllegalArgumentException("inner"),
                frame("com.minos.demo.Widget", "render", "Widget.java", 42)));

        String entry = InternalFailureTrace.describe("cli", "index", failure).entry(INSTANT);

        assertTrue(entry.contains("java.lang.RuntimeException: outer"), entry);
        assertTrue(entry.contains("caused by java.lang.IllegalArgumentException: inner"), entry);
        assertTrue(entry.contains("at com.minos.demo.Widget.render(Widget.java:42)"), entry);
    }

    @Test
    void aDeepStackStopsAtTheBoundAndSaysHowManyFramesWereOmitted() {
        StackTraceElement[] frames = new StackTraceElement[InternalFailureTrace.MAX_FRAMES + 11];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = frame("com.minos.demo.Deep", "level" + index, "Deep.java", index + 1);
        }
        Exception failure = framed(new IllegalStateException("deep"), frames);

        String entry = InternalFailureTrace.describe("mcp", "tool", failure).entry(INSTANT);

        long written = entry.lines().filter(line -> line.startsWith("  at ")).count();
        assertEquals(InternalFailureTrace.MAX_FRAMES, written);
        assertTrue(entry.contains("11 more frames omitted"), entry);
    }

    @Test
    void aLongCauseChainStopsAtTheBoundAndSaysHowManyCausesWereOmitted() {
        Throwable failure = new RuntimeException("level 0");
        for (int index = 1; index < InternalFailureTrace.MAX_CAUSES + 3; index++) {
            failure = new RuntimeException("level " + index, failure);
        }

        String entry = InternalFailureTrace.describe("mcp", "tool", failure).entry(INSTANT);

        assertTrue(entry.contains("3 more causes omitted"), entry);
    }

    @Test
    void aCyclicCauseChainTerminates() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        InternalFailureTrace trace = InternalFailureTrace.describe("mcp", "tool", first);

        assertTrue(trace.entry(INSTANT).contains("first"));
        assertTrue(trace.line("x", "tool", "t").contains("ref="));
    }

    @Test
    void aMessageIsBoundedAndFlattenedOnOneLine() {
        String message = "a".repeat(InternalFailureTrace.MAX_MESSAGE_CHARS + 50) + "\nforged line\u001b[31m";

        String entry = InternalFailureTrace.describe("mcp", "tool", new IllegalStateException(message)).entry(INSTANT);

        assertFalse(entry.contains("forged line"), entry);
        assertFalse(entry.contains("\u001b"), entry);
        assertTrue(entry.lines().anyMatch(line -> line.startsWith("java.lang.IllegalStateException: aaa")
                && line.length() < InternalFailureTrace.MAX_MESSAGE_CHARS + 100));
    }

    @Test
    void theFileNameSortsByInstantAndEndsWithTheReference() {
        InternalFailureTrace trace = InternalFailureTrace.describe("mcp", "tool", new RuntimeException());

        String earlier = trace.fileName(Instant.parse("2026-11-15T10:20:30.123Z"));
        String later = trace.fileName(Instant.parse("2026-11-15T10:20:30.124Z"));

        assertEquals("20261115T102030123Z-" + trace.reference() + ".txt", earlier);
        assertTrue(earlier.compareTo(later) < 0);
    }

    @Test
    void anOperationNameThatLooksSensitiveIsReplacedByAFixedLabel() {
        InternalFailureTrace trace = InternalFailureTrace.describe("mcp", "C:\\Users\\alice\\x token=zzz",
                new RuntimeException());

        String entry = trace.entry(INSTANT);

        assertFalse(entry.contains("alice"), entry);
        assertFalse(entry.contains("zzz"), entry);
        assertFalse(trace.line("failed", "tool", trace.operation()).contains("alice"));
    }

    private static Exception framed(Exception failure, StackTraceElement... frames) {
        failure.setStackTrace(frames);
        return failure;
    }

    private static StackTraceElement frame(String type, String method, String file, int line) {
        return new StackTraceElement(type, method, file, line);
    }
}

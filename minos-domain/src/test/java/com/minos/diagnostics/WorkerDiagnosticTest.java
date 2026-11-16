package com.minos.diagnostics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerDiagnosticTest {

    private static final Instant INSTANT = Instant.parse("2026-11-15T10:20:30.123Z");

    @Test
    void thePayloadOfATraceCarriesTheReferenceTheClassesTheOriginAndFramesAndNothingElse() {
        Exception failure = new IllegalStateException("cannot read /mnt/c/Users/alice/secret.txt",
                new NullPointerException("C:\\Users\\alice token=abc"));
        failure.getCause().setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.minos.demo.Widget", "<init>", "Widget.java", 7),
                new StackTraceElement("java.lang.Thread", "run", "Thread.java", 1583)});
        InternalFailureTrace trace = InternalFailureTrace.describe("wsl-worker", "index", failure);

        String payload = WorkerDiagnostic.of(trace).payload();

        assertEquals("DIAGNOSTIC ref=" + trace.reference() + " types=java.lang.IllegalStateException,"
                + "java.lang.NullPointerException at=com.minos.demo.Widget._init_:7 frames="
                + "com.minos.demo.Widget._init_:7,java.lang.Thread.run:1583", payload);
        assertFalse(payload.contains("/") || payload.contains("\\") || payload.contains("alice"), payload);
    }

    @Test
    void aPayloadOfTheGrammarRoundTrips() {
        InternalFailureTrace trace = InternalFailureTrace.describe("wsl-worker", "index", new RuntimeException("x"));
        String payload = WorkerDiagnostic.of(trace).payload();

        WorkerDiagnostic parsed = WorkerDiagnostic.parse(payload).orElseThrow();

        assertEquals(trace.reference(), parsed.reference());
        assertEquals(List.of("java.lang.RuntimeException"), parsed.types());
        assertEquals(payload, parsed.payload());
        assertTrue(parsed.line("WSL worker failed internally", "operation", "index")
                .contains("ref=" + trace.reference()));
    }

    @Test
    void aPayloadOutsideTheGrammarIsIgnored() {
        String valid = "DIAGNOSTIC ref=0123abcd types=a.B at=a.B.m:3 frames=a.B.m:3";
        assertTrue(WorkerDiagnostic.parse(valid).isPresent());

        for (String bad : List.of(
                "DIAGNOSTIC ref=0123abcd types=a.B at=/mnt/c/x frames=a.B.m:3",
                "DIAGNOSTIC ref=0123abcd types=a.B at=C:\\Users\\x frames=a.B.m:3",
                "DIAGNOSTIC ref=0123ABCD types=a.B at=a.B.m:3 frames=a.B.m:3",
                "DIAGNOSTIC ref=0123abc types=a.B at=a.B.m:3 frames=a.B.m:3",
                "DIAGNOSTIC ref=0123abcd types=a.B at=a.B.m:3 frames=a.B.m:3 extra=1",
                "DIAGNOSTIC ref=0123abcd types=a.B at=a b frames=a.B.m:3",
                "DIAGNOSTIC ref=0123abcd types=a.B at=a.B.m:3 frames=a.B.m:3\nforged line",
                "FAILED ref=0123abcd types=a.B at=a.B.m:3 frames=a.B.m:3")) {
            assertEquals(Optional.empty(), WorkerDiagnostic.parse(bad), bad);
        }
        assertEquals(Optional.empty(), WorkerDiagnostic.parse(null));
    }

    @Test
    void aPayloadLongerThanTheBoundIsIgnored() {
        String longType = "a".repeat(WorkerDiagnostic.MAX_PAYLOAD_CHARS);

        assertEquals(Optional.empty(),
                WorkerDiagnostic.parse("DIAGNOSTIC ref=0123abcd types=" + longType + " at=u frames=none"));
    }

    @Test
    void theEntryNamesTheReferenceAndHoldsNoMessageNorPath() {
        WorkerDiagnostic diagnostic = WorkerDiagnostic.parse(
                "DIAGNOSTIC ref=0123abcd types=a.B,c.D at=a.B.m:3 frames=a.B.m:3,c.D.n:9").orElseThrow();

        String entry = diagnostic.entry(INSTANT);

        assertTrue(entry.startsWith("2026-11-15T10:20:30.123Z surface=wsl-worker operation=index ref=0123abcd\n"),
                entry);
        assertTrue(entry.contains("caused by c.D"), entry);
        assertTrue(entry.contains("  at c.D.n:9"), entry);
        assertEquals("20261115T102030123Z-0123abcd.txt", diagnostic.fileName(INSTANT));
    }
}

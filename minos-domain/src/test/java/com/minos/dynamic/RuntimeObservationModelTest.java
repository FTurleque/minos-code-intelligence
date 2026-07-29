package com.minos.dynamic;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeObservationModelTest {

    @Test
    void normalizesConfinedReferencesAndKeepsPartialSemanticsExplicit() {
        RuntimeSymbolReference reference = new RuntimeSymbolReference(
                " key:service ", " com.acme.Service ", "src\\Service.java", 12);
        RuntimeObservation observation = new RuntimeObservation(
                RuntimeObservationType.SYMBOL_EXECUTION, reference, null, 7, 42);
        RuntimeObservationSession session = new RuntimeObservationSession(
                RuntimeObservationSession.FORMAT, "session-1", UUID.randomUUID(), "snapshot-1",
                Instant.parse("2026-07-29T06:00:00Z"), Instant.parse("2026-07-29T06:01:00Z"),
                "otel", "1.2.3", "test", RuntimeObservationCompleteness.PARTIAL, List.of(observation));

        assertEquals("src/Service.java", reference.fileId());
        assertEquals("key:service", reference.symbolKey());
        assertEquals(RuntimeObservationCompleteness.PARTIAL, session.completeness());
    }

    @Test
    void rejectsUnconfinedOrIncompleteObservationsAndOversizedWindows() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeSymbolReference(null, null, "../outside.java", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeSymbolReference(null, "com.acme.Service", null, 1));
        RuntimeSymbolReference reference = new RuntimeSymbolReference("key", null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeObservation(RuntimeObservationType.CALL, reference, null, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeObservation(RuntimeObservationType.LINE_COVERAGE, reference, null, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeObservationSession(
                RuntimeObservationSession.FORMAT, "session", UUID.randomUUID(), "snapshot",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(367L * 86_400L),
                "collector", "1", "test", RuntimeObservationCompleteness.PARTIAL,
                List.of(new RuntimeObservation(RuntimeObservationType.SYMBOL_EXECUTION, reference, null, 1, 0))));
    }

    @Test
    void correlationMustDescribeTheExactObservedReference() {
        RuntimeSymbolReference observed = new RuntimeSymbolReference("key:a", null, null, null);
        RuntimeSymbolReference different = new RuntimeSymbolReference("key:b", null, null, null);
        RuntimeObservation observation = new RuntimeObservation(
                RuntimeObservationType.SYMBOL_EXECUTION, observed, null, 1, 0);
        RuntimeSymbolResolution wrong = new RuntimeSymbolResolution(
                RuntimeResolutionStatus.UNRESOLVED, different, null, null, null, List.of(), false);

        assertThrows(IllegalArgumentException.class,
                () -> new CorrelatedRuntimeObservation(observation, wrong, null));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeSymbolResolution(
                RuntimeResolutionStatus.AMBIGUOUS, observed, null, null, null,
                List.of("duplicate", "duplicate"), false));
    }
}

package com.minos.storage.local;

import com.minos.dynamic.CorrelatedRuntimeObservation;
import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservation;
import com.minos.dynamic.RuntimeObservationCompleteness;
import com.minos.dynamic.RuntimeObservationSession;
import com.minos.dynamic.RuntimeObservationType;
import com.minos.dynamic.RuntimeResolutionStatus;
import com.minos.dynamic.RuntimeSymbolReference;
import com.minos.dynamic.RuntimeSymbolResolution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One small correlated runtime session, valid for every runtime observation store. */
final class RuntimeSessionFixtures {

    private RuntimeSessionFixtures() {
    }

    static CorrelatedRuntimeSession session(UUID projectId, String sessionId) {
        RuntimeSymbolReference reference = new RuntimeSymbolReference("key:service", null, null, null);
        RuntimeObservation observation = new RuntimeObservation(
                RuntimeObservationType.SYMBOL_EXECUTION, reference, null, 3, 100);
        RuntimeObservationSession raw = new RuntimeObservationSession(
                RuntimeObservationSession.FORMAT, sessionId, projectId, "snapshot-1",
                Instant.parse("2026-07-29T06:00:00Z"), Instant.parse("2026-07-29T06:01:00Z"),
                "fixture", "1", "test", RuntimeObservationCompleteness.PARTIAL, List.of(observation));
        RuntimeSymbolResolution resolution = new RuntimeSymbolResolution(
                RuntimeResolutionStatus.RESOLVED, reference, "symbol-1", "key:service",
                "com.acme.Service", List.of(), false);
        return new CorrelatedRuntimeSession(raw, Instant.parse("2026-07-29T07:00:00Z"), "a".repeat(64),
                List.of(new CorrelatedRuntimeObservation(observation, resolution, null)));
    }
}

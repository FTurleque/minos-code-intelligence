package com.minos.dynamic;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable accepted runtime session, aligned to one exact static snapshot. */
public record CorrelatedRuntimeSession(
        RuntimeObservationSession session,
        Instant importedAt,
        String sourceSha256,
        List<CorrelatedRuntimeObservation> observations
) {
    public CorrelatedRuntimeSession {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(importedAt, "importedAt");
        if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be lowercase SHA-256");
        }
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (observations.size() != session.observations().size()) {
            throw new IllegalArgumentException("correlated observation count must match session");
        }
    }
}

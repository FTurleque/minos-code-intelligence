package com.minos.dynamic;

import java.util.Objects;

/** One bounded observation. Counts and durations are factual only within their session window. */
public record RuntimeObservation(
        RuntimeObservationType type,
        RuntimeSymbolReference source,
        RuntimeSymbolReference target,
        long hits,
        long totalDurationNanos
) {
    public static final long MAX_HITS = 1_000_000_000_000L;
    public static final long MAX_DURATION_NANOS = 31_536_000_000_000_000L;

    public RuntimeObservation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        if (hits < 1 || hits > MAX_HITS) {
            throw new IllegalArgumentException("hits must be between 1 and " + MAX_HITS);
        }
        if (totalDurationNanos < 0 || totalDurationNanos > MAX_DURATION_NANOS) {
            throw new IllegalArgumentException("totalDurationNanos is outside the supported range");
        }
        if (type == RuntimeObservationType.CALL && target == null) {
            throw new IllegalArgumentException("CALL observation requires a target");
        }
        if (type != RuntimeObservationType.CALL && target != null) {
            throw new IllegalArgumentException(type + " observation must not declare a target");
        }
        if (type == RuntimeObservationType.LINE_COVERAGE
                && (source.fileId() == null || source.line() == null)) {
            throw new IllegalArgumentException("LINE_COVERAGE requires fileId and line");
        }
    }
}

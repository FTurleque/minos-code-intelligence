package com.minos.dynamic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, explicitly partial runtime observation envelope. */
public record RuntimeObservationSession(
        String format,
        String sessionId,
        UUID projectId,
        String snapshotId,
        Instant startedAt,
        Instant endedAt,
        String collectorId,
        String collectorVersion,
        String environment,
        RuntimeObservationCompleteness completeness,
        List<RuntimeObservation> observations
) {
    public static final String FORMAT = "minos-runtime-observation-v1";
    public static final int MAX_OBSERVATIONS = 1_000_000;
    private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}");

    public RuntimeObservationSession {
        if (!FORMAT.equals(format)) throw new IllegalArgumentException("unsupported runtime observation format: " + format);
        sessionId = safeLabel(sessionId, "sessionId");
        Objects.requireNonNull(projectId, "projectId");
        snapshotId = requiredText(snapshotId, "snapshotId", 4096);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) throw new IllegalArgumentException("endedAt must not be before startedAt");
        if (Duration.between(startedAt, endedAt).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("runtime observation window exceeds 366 days");
        }
        collectorId = safeLabel(collectorId, "collectorId");
        collectorVersion = safeLabel(collectorVersion, "collectorVersion");
        environment = safeLabel(environment, "environment");
        if (completeness != RuntimeObservationCompleteness.PARTIAL) {
            throw new IllegalArgumentException("M26 accepts PARTIAL runtime observations only");
        }
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (observations.isEmpty()) throw new IllegalArgumentException("observations must not be empty");
        if (observations.size() > MAX_OBSERVATIONS) throw new IllegalArgumentException("runtime observation count exceeds limit");
        if (observations.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("observations must not contain null");
    }

    private static String safeLabel(String value, String field) {
        String normalized = requiredText(value, field, 256);
        if (!SAFE_LABEL.matcher(normalized).matches()) throw new IllegalArgumentException(field + " contains unsupported characters");
        return normalized;
    }

    private static String requiredText(String value, String field, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0 || normalized.indexOf('\t') >= 0) {
            throw new IllegalArgumentException(field + " is invalid or exceeds its limit");
        }
        return normalized;
    }
}

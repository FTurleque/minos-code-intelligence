package com.minos.dynamic;

import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.FixedTsv;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Strict UTF-8 TSV codec for {@code minos-runtime-observation-v1}. */
public final class RuntimeObservationEnvelopeCodec {

    public static final long MAX_INPUT_BYTES = 64L * 1024L * 1024L;
    public static final int MAX_LINE_CHARS = 1024 * 1024;
    private static final int METADATA_LINES = 9;

    public DecodedSession read(Path source) throws IOException {
        Path path = source == null ? null : source.toAbsolutePath().normalize();
        if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("runtime observation input must be a regular non-symlink file");
        }
        MessageDigest digest = digest();
        String[] metadata = new String[METADATA_LINES];
        List<RuntimeObservation> observations = new ArrayList<>();
        long sourceBytes;
        try (DigestInputStream digestInput = new DigestInputStream(Files.newInputStream(path), digest);
             BoundedInputStream bounded = new BoundedInputStream(
                     digestInput, MAX_INPUT_BYTES, "runtime observation input");
             BoundedLineReader reader = new BoundedLineReader(new InputStreamReader(
                     bounded,
                     StandardCharsets.UTF_8.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)), MAX_LINE_CHARS)) {
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\ufeff")) {
                    throw new IOException("runtime observation input must not contain a BOM");
                }
                if (line.isEmpty()) throw new IOException("blank runtime observation line at " + lineNumber);
                if (lineNumber <= METADATA_LINES) {
                    metadata[lineNumber - 1] = line;
                    continue;
                }
                if (observations.size() >= RuntimeObservationSession.MAX_OBSERVATIONS) {
                    throw new IOException("runtime observation count exceeds limit");
                }
                observations.add(parseObservation(line, lineNumber));
            }
            sourceBytes = bounded.consumedBytes();
        }
        if (sourceBytes < 1L) throw new IOException("runtime observation input must not be empty");
        for (int index = 0; index < METADATA_LINES; index++) {
            if (metadata[index] == null) throw new IOException("runtime observation input has incomplete metadata");
        }
        if (observations.isEmpty()) throw new IOException("runtime observation input has no observations");

        expectExact(metadata[0], RuntimeObservationSession.FORMAT, 1);
        String sessionId = singleValue(metadata[1], "session", 2);
        UUID projectId = uuid(singleValue(metadata[2], "project", 3), 3);
        String snapshotId = singleValue(metadata[3], "snapshot", 4);
        Instant startedAt = instant(singleValue(metadata[4], "started", 5), 5);
        Instant endedAt = instant(singleValue(metadata[5], "ended", 6), 6);
        String[] collector = fields(metadata[6], 3, 7);
        expectToken(collector[0], "collector", 7);
        String environment = singleValue(metadata[7], "environment", 8);
        RuntimeObservationCompleteness completeness;
        try {
            completeness = RuntimeObservationCompleteness.valueOf(singleValue(metadata[8], "completeness", 9));
        } catch (IllegalArgumentException exception) {
            throw new IOException("line 9: completeness must be PARTIAL", exception);
        }
        RuntimeObservationSession session = new RuntimeObservationSession(
                RuntimeObservationSession.FORMAT, sessionId, projectId, snapshotId,
                startedAt, endedAt, collector[1], collector[2], environment, completeness, observations);
        return new DecodedSession(session, sha256(digest), sourceBytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(MessageDigest digest) {
        return HexFormat.of().formatHex(java.util.Objects.requireNonNull(digest, "digest").digest());
    }

    private static RuntimeObservation parseObservation(String line, int lineNumber) throws IOException {
        String kind = FixedTsv.firstField(line, lineNumber);
        try {
            return switch (kind) {
                case "symbol" -> {
                    String[] values = FixedTsv.splitExact(line, 7, lineNumber);
                    RuntimeSymbolReference source = reference(values, 1);
                    yield new RuntimeObservation(RuntimeObservationType.SYMBOL_EXECUTION, source, null,
                            positiveLong(values[5], "hits", lineNumber),
                            nonNegativeLong(values[6], "durationNanos", lineNumber));
                }
                case "call" -> {
                    String[] values = FixedTsv.splitExact(line, 11, lineNumber);
                    RuntimeSymbolReference source = reference(values, 1);
                    RuntimeSymbolReference target = reference(values, 5);
                    yield new RuntimeObservation(RuntimeObservationType.CALL, source, target,
                            positiveLong(values[9], "hits", lineNumber),
                            nonNegativeLong(values[10], "durationNanos", lineNumber));
                }
                case "line" -> {
                    String[] values = FixedTsv.splitExact(line, 4, lineNumber);
                    RuntimeSymbolReference source = new RuntimeSymbolReference(
                            null, null, required(values[1], "fileId", lineNumber),
                            positiveInt(values[2], "line", lineNumber));
                    yield new RuntimeObservation(RuntimeObservationType.LINE_COVERAGE, source, null,
                            positiveLong(values[3], "hits", lineNumber), 0);
                }
                default -> throw new IOException("line " + lineNumber + ": unknown runtime observation kind: " + kind);
            };
        } catch (IllegalArgumentException exception) {
            throw new IOException("line " + lineNumber + ": " + exception.getMessage(), exception);
        }
    }

    private static RuntimeSymbolReference reference(String[] values, int offset) {
        return new RuntimeSymbolReference(
                optional(values[offset]), optional(values[offset + 1]), optional(values[offset + 2]),
                optional(values[offset + 3]) == null ? null : positiveInt(values[offset + 3], "line", -1));
    }

    private static String singleValue(String line, String expected, int lineNumber) throws IOException {
        String[] values = fields(line, 2, lineNumber);
        expectToken(values[0], expected, lineNumber);
        return required(values[1], expected, lineNumber);
    }

    private static String[] fields(String line, int count, int lineNumber) throws IOException {
        return FixedTsv.splitExact(line, count, lineNumber);
    }

    private static void expectExact(String actual, String expected, int lineNumber) throws IOException {
        if (!expected.equals(actual)) throw new IOException("line " + lineNumber + ": expected " + expected);
    }

    private static void expectToken(String actual, String expected, int lineNumber) throws IOException {
        if (!expected.equals(actual)) throw new IOException("line " + lineNumber + ": expected field " + expected);
    }

    private static String required(String value, String field, int lineNumber) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException((lineNumber > 0 ? "line " + lineNumber + ": " : "")
                    + field + " must not be blank");
        }
        return value;
    }

    private static String optional(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static int positiveInt(String value, String field, int lineNumber) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException("not positive");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException((lineNumber > 0 ? "line " + lineNumber + ": " : "")
                    + field + " must be a positive integer");
        }
    }

    private static long positiveLong(String value, String field, int lineNumber) {
        long parsed = nonNegativeLong(value, field, lineNumber);
        if (parsed < 1) throw new IllegalArgumentException("line " + lineNumber + ": " + field + " must be positive");
        return parsed;
    }

    private static long nonNegativeLong(String value, String field, int lineNumber) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("line " + lineNumber + ": " + field + " must be a non-negative integer");
        }
    }

    private static UUID uuid(String value, int lineNumber) throws IOException {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("line " + lineNumber + ": project must be a UUID", exception);
        }
    }

    private static Instant instant(String value, int lineNumber) throws IOException {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IOException("line " + lineNumber + ": timestamp must be ISO-8601 UTC/offset", exception);
        }
    }

    public record DecodedSession(RuntimeObservationSession session, String sourceSha256, long sourceBytes) {
        public DecodedSession {
            java.util.Objects.requireNonNull(session, "session");
            if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sourceSha256 must be lowercase SHA-256");
            }
            if (sourceBytes < 1 || sourceBytes > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("sourceBytes is invalid");
            }
        }
    }
}

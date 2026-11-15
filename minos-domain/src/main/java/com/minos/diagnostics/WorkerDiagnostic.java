package com.minos.diagnostics;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The diagnostic that a worker running in another environment sends about its own internal failure: a reference, the
 * classes of its cause chain, the origin and a few frames. It carries no message and no path, and its grammar is
 * closed: the receiving side keeps only a payload that {@link #parse} accepts and ignores any other, so a line that
 * the worker (or anything printing on its output) controls cannot carry a path, a secret or free text into a journal.
 *
 * <p>The payload is {@code DIAGNOSTIC ref=<8 hex> types=<c1>,<c2> at=<origin> frames=<f1>,<f2>}; a token is made of
 * letters, digits and {@code . $ _ :}.</p>
 */
public final class WorkerDiagnostic implements DiagnosticEntry {

    /** First word of the payload. */
    public static final String KEYWORD = "DIAGNOSTIC";
    /** Frames of the root cause carried by a payload. */
    public static final int MAX_FRAMES = 8;
    /** Longest payload accepted. */
    public static final int MAX_PAYLOAD_CHARS = 1_024;
    /** Frames token when the root cause has no frame. */
    static final String NO_FRAMES = "none";

    private static final String TOKEN = "[A-Za-z0-9._$:]+";
    private static final Pattern PAYLOAD = Pattern.compile(KEYWORD + " ref=([0-9a-f]{8}) types=(" + TOKEN
            + "(?:," + TOKEN + "){0," + (InternalFailureTrace.MAX_CAUSES - 1) + "}) at=(" + TOKEN
            + ") frames=(" + TOKEN + "(?:," + TOKEN + "){0," + (MAX_FRAMES - 1) + "})");
    private static final String SURFACE = "wsl-worker";

    private final String reference;
    private final List<String> types;
    private final String origin;
    private final List<String> frames;

    private WorkerDiagnostic(String reference, List<String> types, String origin, List<String> frames) {
        this.reference = reference;
        this.types = List.copyOf(types);
        this.origin = origin;
        this.frames = List.copyOf(frames);
    }

    /** The diagnostic that describes {@code trace}, to be sent by the worker. */
    public static WorkerDiagnostic of(InternalFailureTrace trace) {
        Objects.requireNonNull(trace, "trace");
        List<String> frames = trace.rootFrames(MAX_FRAMES);
        return new WorkerDiagnostic(trace.reference(), trace.causeTypes(), trace.origin(),
                frames.isEmpty() ? List.of(NO_FRAMES) : frames);
    }

    /** The payload to put after the protocol prefix of an output line. */
    public String payload() {
        return KEYWORD + " ref=" + reference + " types=" + String.join(",", types) + " at=" + origin
                + " frames=" + String.join(",", frames);
    }

    /** The diagnostic in {@code payload}, or empty when it does not follow the grammar or is too long. */
    public static Optional<WorkerDiagnostic> parse(String payload) {
        if (payload == null || payload.length() > MAX_PAYLOAD_CHARS) return Optional.empty();
        Matcher matcher = PAYLOAD.matcher(payload);
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new WorkerDiagnostic(matcher.group(1), Arrays.asList(matcher.group(2).split(",")),
                matcher.group(3), Arrays.asList(matcher.group(4).split(","))));
    }

    @Override
    public String reference() {
        return reference;
    }

    public List<String> types() {
        return types;
    }

    public String origin() {
        return origin;
    }

    /** The stderr line of the receiving side: classes, origin and reference, no message. */
    public String line(String subject, String operationKey, String operationName) {
        return subject + " (" + operationKey + "=" + operationName + ", types=" + String.join(",", types)
                + ", at=" + origin + ", ref=" + reference + ")";
    }

    @Override
    public String entry(Instant instant) {
        StringBuilder text = new StringBuilder(512);
        text.append(Objects.requireNonNull(instant, "instant")).append(" surface=").append(SURFACE)
                .append(" operation=index ref=").append(reference).append('\n')
                .append("reported by the worker; it sends no message and no path\n");
        for (int index = 0; index < types.size(); index++) {
            text.append(index == 0 ? "" : "caused by ").append(types.get(index)).append('\n');
        }
        text.append("origin ").append(origin).append('\n');
        for (String frame : frames) {
            if (!NO_FRAMES.equals(frame)) text.append("  at ").append(frame).append('\n');
        }
        return text.toString();
    }
}

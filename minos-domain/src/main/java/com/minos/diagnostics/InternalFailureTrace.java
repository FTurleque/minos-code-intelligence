package com.minos.diagnostics;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure description of one internal failure: a correlation reference, the stderr line that names where it came from,
 * and the text of a private journal entry. It performs no I/O; the surfaces and the journal decide where each part
 * goes.
 *
 * <p>The stderr line never carries an exception message or an argument value. The journal entry carries the cause
 * chain and its frames (class names, method names, source file names and line numbers, which are code and not paths)
 * and an exception message only when {@link PublicErrorMessages} does not judge it sensitive. Anything that cannot be
 * proven safe is written less, never more.</p>
 */
public final class InternalFailureTrace implements DiagnosticEntry {

    /** Causes written to one entry. */
    public static final int MAX_CAUSES = 8;
    /** Frames written for one cause. */
    public static final int MAX_FRAMES = 64;
    /** Characters of one exception message written to an entry. */
    public static final int MAX_MESSAGE_CHARS = 300;
    /** Replaces the message of an exception whose message looks sensitive. */
    public static final String WITHHELD_MESSAGE = "[message withheld by the public redaction policy]";
    /** Replaces an operation name that is not a plain label. */
    public static final String UNNAMED_OPERATION = "unnamed";
    /** Origin reported when no frame of the root cause belongs to MINOS code. */
    public static final String UNKNOWN_ORIGIN = "unknown";

    private static final String MINOS_PACKAGE_PREFIX = "com.minos.";
    private static final int REFERENCE_BYTES = 4;
    private static final int MAX_OPERATION_CHARS = 80;
    private static final int MAX_CHAIN_STEPS = 1_000;
    private static final Pattern PLAIN_LABEL = Pattern.compile("[A-Za-z0-9_.:/ -]+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String surface;
    private final String operation;
    private final String reference;
    private final List<Throwable> chain;
    private final int omittedCauses;
    private final String type;
    private final String rootCauseType;
    private final String origin;
    private final StackTraceElement[] rootFrames;

    private InternalFailureTrace(String surface, String operation, String reference, Throwable failure) {
        this.surface = label(surface, "surface");
        this.operation = label(operation, "operation");
        this.reference = reference;
        List<Throwable> all = causeChain(failure);
        this.chain = List.copyOf(all.subList(0, Math.min(all.size(), MAX_CAUSES)));
        this.omittedCauses = Math.max(0, all.size() - MAX_CAUSES);
        this.type = failure.getClass().getName();
        Throwable root = all.get(all.size() - 1);
        this.rootCauseType = root.getClass().getName();
        this.rootFrames = root.getStackTrace();
        this.origin = originOf(root);
    }

    /**
     * Describes {@code failure} under a fresh random reference.
     *
     * @param surface   the entry point that failed ({@code mcp}, {@code cli}, {@code api}, {@code wsl-worker},
     *                  {@code explorer}); a fixed label, never caller input
     * @param operation the operation that failed (tool, command or route name); a fixed label, never an argument
     */
    public static InternalFailureTrace describe(String surface, String operation, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        byte[] bytes = new byte[REFERENCE_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder reference = new StringBuilder(REFERENCE_BYTES * 2);
        for (byte value : bytes) reference.append(String.format(Locale.ROOT, "%02x", value));
        return new InternalFailureTrace(surface, operation, reference.toString(), failure);
    }

    /** Eight hexadecimal digits that tie a response, a stderr line and a journal entry together. */
    @Override
    public String reference() {
        return reference;
    }

    public String operation() {
        return operation;
    }

    /** The class of the failure as caught. */
    public String type() {
        return type;
    }

    /** The class of the deepest cause. */
    public String rootCauseType() {
        return rootCauseType;
    }

    /** {@code Class.method:line} of the first MINOS frame of the root cause, or {@value #UNKNOWN_ORIGIN}. */
    public String origin() {
        return origin;
    }

    /**
     * The stderr line: {@code <subject> (<operationKey>=<operation>, type=…, rootCause=…, at=…, ref=…)}. It carries no
     * message and no argument value.
     */
    public String line(String subject, String operationKey, String operationName) {
        return subject + " (" + operationKey + "=" + label(operationName, operationKey) + ", type=" + type
                + ", rootCause=" + rootCauseType + ", at=" + origin + ", ref=" + reference + ")";
    }

    /** The classes of the cause chain, outermost first, as tokens safe for a protocol line; bounded. */
    List<String> causeTypes() {
        return chain.stream().map(cause -> token(cause.getClass().getName())).toList();
    }

    /** The first {@code limit} frames of the root cause as {@code Class.method:line} tokens; bounded. */
    List<String> rootFrames(int limit) {
        StackTraceElement[] frames = rootFrames;
        List<String> tokens = new ArrayList<>();
        for (int index = 0; index < frames.length && tokens.size() < limit; index++) {
            tokens.add(frameToken(frames[index]));
        }
        return List.copyOf(tokens);
    }

    /** The text of the journal entry written at {@code instant}. */
    @Override
    public String entry(Instant instant) {
        StringBuilder text = new StringBuilder(1_024);
        text.append(Objects.requireNonNull(instant, "instant")).append(" surface=").append(surface)
                .append(" operation=").append(operation).append(" ref=").append(reference).append('\n');
        for (int index = 0; index < chain.size(); index++) {
            Throwable cause = chain.get(index);
            text.append(index == 0 ? "" : "caused by ").append(cause.getClass().getName());
            String message = messageOf(cause);
            if (message != null) text.append(": ").append(message);
            text.append('\n');
            appendFrames(text, cause.getStackTrace());
        }
        if (omittedCauses > 0) text.append("... ").append(omittedCauses).append(" more causes omitted\n");
        return text.toString();
    }

    private static void appendFrames(StringBuilder text, StackTraceElement[] frames) {
        int written = Math.min(frames.length, MAX_FRAMES);
        for (int index = 0; index < written; index++) {
            StackTraceElement frame = frames[index];
            text.append("  at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append('(')
                    .append(frame.getFileName() == null ? "Unknown Source" : frame.getFileName());
            if (frame.getLineNumber() > 0) text.append(':').append(frame.getLineNumber());
            text.append(")\n");
        }
        if (frames.length > written) {
            text.append("  ... ").append(frames.length - written).append(" more frames omitted\n");
        }
    }

    /** The message to write for {@code cause}: {@code null} when it has none, a fixed text when it is sensitive. */
    private static String messageOf(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return null;
        String kept = PublicErrorMessages.sanitize(message, WITHHELD_MESSAGE);
        return kept.length() > MAX_MESSAGE_CHARS ? kept.substring(0, MAX_MESSAGE_CHARS) + "..." : kept;
    }

    private static List<Throwable> causeChain(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        Throwable current = failure;
        while (current != null && seen.add(current) && chain.size() < MAX_CHAIN_STEPS) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    private static String originOf(Throwable root) {
        for (StackTraceElement frame : root.getStackTrace()) {
            if (!frame.getClassName().startsWith(MINOS_PACKAGE_PREFIX)) continue;
            return frameToken(frame);
        }
        return UNKNOWN_ORIGIN;
    }

    /** {@code Class.method:line}, with every character outside letters, digits, {@code . $ _} replaced by an underscore. */
    private static String frameToken(StackTraceElement frame) {
        String name = token(frame.getClassName()) + "." + token(frame.getMethodName());
        return frame.getLineNumber() > 0 ? name + ":" + frame.getLineNumber() : name;
    }

    static String token(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean plain = (current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9') || current == '.' || current == '$' || current == '_';
            safe.append(plain ? current : '_');
        }
        return safe.toString();
    }

    /** A plain label is kept; anything else (a path, a secret, a control character) becomes a fixed text. */
    private static String label(String value, String fallbackName) {
        if (value == null || value.isBlank() || value.length() > MAX_OPERATION_CHARS
                || !PLAIN_LABEL.matcher(value).matches() || PublicErrorMessages.looksSensitive(value.replace('/', '_'))) {
            return "surface".equals(fallbackName) ? "unknown" : UNNAMED_OPERATION;
        }
        return value;
    }
}

package com.minos.domain.registry;

import com.minos.diagnostics.PublicErrorMessages;

import static com.minos.domain.Preconditions.requireText;

/**
 * An inventory entry that could not be read, or not assembled in full: it is counted and shown, never dropped
 * silently.
 *
 * <p>{@code entry} names the entry without a path (the project identifier, or the name of its registry file reduced
 * to safe characters); {@code reason} is a public message: it went through {@link PublicErrorMessages} and so carries
 * no absolute path. The two factories are the only place that decides these two forms, for the registry as for the
 * assembly of the view of a project.</p>
 */
public record DegradedEntry(String entry, String reason) {

    private static final int MAX_ENTRY_LENGTH = 64;
    private static final String UNNAMED = "unnamed";

    public DegradedEntry {
        requireText(entry, "entry");
        requireText(reason, "reason");
    }

    /** An entry unreadable for a reason that is not an exception (for example: not a regular file). */
    public static DegradedEntry of(String rawEntry, String what) {
        return new DegradedEntry(safeEntry(rawEntry), requireText(what, "what"));
    }

    /** An entry whose read failed: {@code what} says what could not be done, without a path. */
    public static DegradedEntry of(String rawEntry, String what, Throwable failure) {
        String detail = PublicErrorMessages.sanitize(failure.getMessage(), failure.getClass().getSimpleName());
        return new DegradedEntry(safeEntry(rawEntry), requireText(what, "what") + " (" + printable(detail) + ")");
    }

    /**
     * The message of an exception sometimes copies a value read from a file: {@code sanitize} flattens line breaks but
     * lets control sequences through (ESC, BEL, 8-bit CSI, bidirectional override), which a terminal would execute.
     * They are replaced here, once, by {@code _}.
     */
    public static String printable(String text) {
        StringBuilder printable = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> printable.appendCodePoint(isUnsafeForTerminal(codePoint) ? '_' : codePoint));
        return printable.toString();
    }

    private static boolean isUnsafeForTerminal(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static String safeEntry(String rawEntry) {
        if (rawEntry == null || rawEntry.isBlank()) return UNNAMED;
        StringBuilder safe = new StringBuilder(Math.min(rawEntry.length(), MAX_ENTRY_LENGTH));
        for (int index = 0; index < rawEntry.length() && safe.length() < MAX_ENTRY_LENGTH; index++) {
            char value = rawEntry.charAt(index);
            boolean plain = value < 128 && (Character.isLetterOrDigit(value) || value == '-' || value == '_' || value == '.');
            safe.append(plain ? value : '_');
        }
        return safe.toString();
    }
}

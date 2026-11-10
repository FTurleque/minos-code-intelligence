package com.minos.diagnostics;

import java.io.IOException;
import java.util.Objects;

/**
 * A snapshot view that cannot be loaded now because the memory budget of the snapshot views does not hold it.
 *
 * <p>Its message is public: it starts with {@value #MESSAGE_PREFIX}, carries only byte counts and the name of the
 * setting to change, never a path. The CLI and the MCP server render it as is, so a caller learns why the query was
 * refused and what to raise. It is an {@link IOException}: the signatures of the read ports do not change.</p>
 */
public final class SnapshotMemoryExhaustedException extends IOException {

    /** First words of every message of this refusal. */
    public static final String MESSAGE_PREFIX = "insufficient memory to load this snapshot";

    /** {@code message} must start with {@link #MESSAGE_PREFIX}. */
    public SnapshotMemoryExhaustedException(String message) {
        super(requirePrefix(message));
    }

    private static String requirePrefix(String message) {
        Objects.requireNonNull(message, "message");
        if (!message.startsWith(MESSAGE_PREFIX)) {
            throw new IllegalArgumentException("message must start with: " + MESSAGE_PREFIX);
        }
        return message;
    }
}

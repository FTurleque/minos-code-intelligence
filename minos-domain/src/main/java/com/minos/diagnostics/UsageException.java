package com.minos.diagnostics;

/**
 * An error that the caller of a surface caused and can correct: an argument out of range, a malformed identifier, an
 * unknown name. Its message is written for the caller.
 *
 * <p>A surface (the MCP server, the Java API, the explorer) reports a message to its caller only for this type, and
 * treats any other {@link IllegalArgumentException} as an internal failure: a precondition violated inside a service
 * is a defect, not advice to the caller. The type extends {@link IllegalArgumentException}, so every existing
 * {@code catch (IllegalArgumentException ...)} still applies. It is not final: a typed usage error (an unknown
 * document) extends it.</p>
 *
 * <p>The message is never empty (a blank one is replaced by {@link #DEFAULT_MESSAGE}) and names the argument. It
 * avoids copying a value that could be a path or a secret, and a surface passes it through
 * {@link PublicErrorMessages} anyway: a message judged sensitive is answered by the opaque error. There is no cause: a
 * cause marks an error as internal.</p>
 */
public class UsageException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The message of a usage error built without one: the caller is still told that the request is not valid. */
    public static final String DEFAULT_MESSAGE = "the request is not valid";

    public UsageException(String message) {
        super(message == null || message.isBlank() ? DEFAULT_MESSAGE : message);
    }
}

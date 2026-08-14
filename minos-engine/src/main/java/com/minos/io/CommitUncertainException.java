package com.minos.io;

import java.io.IOException;

/**
 * Signals that a durable mutation crossed its logical commit point but the caller did not receive
 * a conclusive durability acknowledgement.
 *
 * <p>Callers must never treat this as a proven pre-commit failure. They must re-observe their
 * authoritative state and either recover the committed result or fail closed if the outcome cannot
 * be established.</p>
 */
public final class CommitUncertainException extends IOException {

    public CommitUncertainException(String message, Throwable cause) {
        super(message, cause);
    }

    public CommitUncertainException(String message) {
        super(message);
    }
}

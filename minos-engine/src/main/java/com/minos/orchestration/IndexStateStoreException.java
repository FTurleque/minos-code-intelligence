package com.minos.orchestration;

/**
 * The single failure type of {@link IndexStateStore} (AUD-QUA-03): a storage failure of the index state (I/O, SQL,
 * unreadable or inconsistent content, lifecycle lease not obtained or not released). The cause is always kept, so an
 * interruption during a write is still recognized along the cause chain.
 *
 * <p>It is unchecked on purpose (owner decision D1 = A, 2026-10-21): no signature of the port, of the storage SPI, of
 * the persistence lambdas or of the lease's {@code close()} changes. A violation of an invariant (a lease released by
 * another thread, a negative depth, a store without a qualified lease) stays an {@link IllegalStateException}, and a
 * programming error stays what it is: callers catch this type and this type only.</p>
 *
 * <p>Its message never carries an absolute path: it names the kind of state and its identifier when known.</p>
 */
public class IndexStateStoreException extends RuntimeException {

    public IndexStateStoreException(String message) {
        super(message);
    }

    public IndexStateStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.minos.semantic;

import java.io.IOException;
import java.util.Optional;

/**
 * Derived cache of the projection of each project. A projection is rebuilt from the index at will: losing it
 * loses nothing but the time to prepare it again.
 */
public interface SemanticProjectionStore {

    /**
     * The published projection of the project, or empty when none was published. Reading creates, repairs and
     * locks nothing: it works on a storage the reader cannot write.
     *
     * @throws UnreadableProjectionException when what is stored is not a whole projection of this project
     */
    Optional<SemanticProjection> read(String projectId) throws IOException;

    /**
     * Starts the one preparation the project may have at a time. The caller computes, then
     * {@linkplain Preparation#publish publishes} or closes without publishing; either way the projection
     * published before stays whole and readable until it is replaced.
     *
     * @throws PreparationInProgressException when another preparation of the project is running
     */
    Preparation prepare(String projectId) throws IOException;

    /** The exclusive right to publish the projection of one project, released on close. */
    interface Preparation extends AutoCloseable {

        /** Replaces the published projection in one atomic step: a reader sees the previous one or this one. */
        void publish(SemanticProjection projection) throws IOException;

        @Override
        void close() throws IOException;
    }

    /** What is stored for the project cannot be read as a projection. The message is safe to show. */
    final class UnreadableProjectionException extends IOException {
        private static final long serialVersionUID = 1L;

        public UnreadableProjectionException(String message) {
            super(message);
        }

        public UnreadableProjectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Another preparation of the same project holds the exclusive right to publish. */
    final class PreparationInProgressException extends IOException {
        private static final long serialVersionUID = 1L;

        public PreparationInProgressException(Throwable cause) {
            super("another preparation of the projection of this project is running", cause);
        }
    }
}

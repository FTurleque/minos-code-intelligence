package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.api.MinosApi.ErrorCode;
import com.minos.api.MinosApi.MinosApiException;
import com.minos.diagnostics.PublicErrorMessages;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Shared argument checks and failure translation for the local MINOS API facades.
 *
 * <p>The mapping from a thrown failure to an {@link ErrorCode} <em>and</em> to a public message is
 * part of the published API contract, so it lives here once, in {@link #execute}. Every {@code
 * Local*Api} facade routes through it rather than building its own {@code catch} chain: a facade
 * that classified {@code IOException} differently from its siblings would make the same underlying
 * fault look like a different error to callers, and a facade that surfaced {@code
 * exception.getMessage()} directly could leak an absolute path, a JDBC URL or a credential that the
 * underlying implementation (storage, hosted control plane, git, PostgreSQL...) put in its internal
 * exception message. {@link #failureMessage} is the one place that decides what is safe to expose;
 * see {@link PublicErrorMessages} for the redaction policy itself.</p>
 */
final class MinosApiSupport {

    private MinosApiSupport() {
    }

    @FunctionalInterface
    interface ApiCall<T> {
        T call() throws Exception;
    }

    /**
     * Runs {@code call}, translating anything it throws into the published error taxonomy.
     * An exception already carrying an {@link ErrorCode} is rethrown unchanged so a nested facade
     * cannot have its classification overwritten by an outer frame.
     */
    static <T> T execute(ApiCall<T> call) throws MinosApiException {
        try {
            return call.call();
        } catch (MinosApiException exception) {
            throw exception;
        } catch (SecurityException exception) {
            throw new MinosApiException(ErrorCode.ACCESS_DENIED, failureMessage(exception), exception);
        } catch (AccessDeniedException exception) {
            throw new MinosApiException(ErrorCode.ACCESS_DENIED, failureMessage(exception), exception);
        } catch (IllegalArgumentException exception) {
            throw new MinosApiException(ErrorCode.INVALID_REQUEST, failureMessage(exception), exception);
        } catch (IllegalStateException exception) {
            throw new MinosApiException(ErrorCode.UNAVAILABLE, failureMessage(exception), exception);
        } catch (IOException exception) {
            throw new MinosApiException(ErrorCode.IO_FAILURE, failureMessage(exception), exception);
        } catch (Exception exception) {
            throw new MinosApiException(ErrorCode.EXECUTION_FAILURE, failureMessage(exception), exception);
        }
    }

    static MinosApplication openApplication(Path home, String bootstrapFailureMessage) throws MinosApiException {
        try {
            return MinosApplication.open(Objects.requireNonNull(home, "home"));
        } catch (IOException exception) {
            throw new MinosApiException(ErrorCode.IO_FAILURE, bootstrapFailureMessage, exception);
        }
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    /**
     * The message a caller of the public API is allowed to see: single-line, length-bounded, and
     * never containing a path, URL credential, token or other internal detail. The full exception
     * (message included) remains available as the {@link MinosApiException}'s cause for whatever a
     * process's own internal logging chooses to record -- only the published {@code getMessage()}
     * is redacted.
     */
    static String failureMessage(Exception exception) {
        return PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName());
    }
}

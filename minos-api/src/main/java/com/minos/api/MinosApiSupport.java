package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.api.MinosApi.ErrorCode;
import com.minos.api.MinosApi.MinosApiException;
import com.minos.diagnostics.PublicErrorMessages;

import java.io.IOException;
import java.lang.System.Logger.Level;
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
 *
 * <p><b>The original exception is never attached to the public {@link MinosApiException}.</b>
 * {@code MinosApiException} is part of the published contract: a caller holds the actual object, so
 * anything reachable from it -- {@code getCause()}, a nested cause chain, suppressed exceptions --
 * is just as observable as {@code getMessage()} itself. Attaching a redacted message while leaving
 * the raw cause attached would not actually close the leak, only rename it. The original exception
 * is logged here instead, for whatever internal diagnostics a process configures; only {@link
 * ErrorCode} and a redacted message cross into the object the public API hands back.</p>
 */
final class MinosApiSupport {

    private static final System.Logger LOGGER = System.getLogger(MinosApiSupport.class.getName());

    private MinosApiSupport() {
    }

    @FunctionalInterface
    interface ApiCall<T> {
        T call() throws Exception;
    }

    /**
     * Runs {@code call}, translating anything it throws into the published error taxonomy.
     * A {@link MinosApiException} that already crossed this boundary once is passed through as-is;
     * one that somehow still carries a cause (e.g. constructed directly by a call site instead of
     * through this method) is treated like any other internal failure rather than trusted, since a
     * {@code MinosApiException} must never carry a cause once it reaches a public caller.
     */
    static <T> T execute(ApiCall<T> call) throws MinosApiException {
        try {
            return call.call();
        } catch (MinosApiException exception) {
            throw exception.getCause() == null ? exception : republish(exception);
        } catch (SecurityException exception) {
            throw publicFailure(ErrorCode.ACCESS_DENIED, exception);
        } catch (AccessDeniedException exception) {
            throw publicFailure(ErrorCode.ACCESS_DENIED, exception);
        } catch (IllegalArgumentException exception) {
            throw publicFailure(ErrorCode.INVALID_REQUEST, exception);
        } catch (IllegalStateException exception) {
            throw publicFailure(ErrorCode.UNAVAILABLE, exception);
        } catch (IOException exception) {
            throw publicFailure(ErrorCode.IO_FAILURE, exception);
        } catch (Exception exception) {
            throw publicFailure(ErrorCode.EXECUTION_FAILURE, exception);
        }
    }

    static MinosApplication openApplication(Path home, String bootstrapFailureMessage) throws MinosApiException {
        try {
            return MinosApplication.open(Objects.requireNonNull(home, "home"));
        } catch (IOException exception) {
            throw publicFailure(ErrorCode.IO_FAILURE, bootstrapFailureMessage, exception);
        }
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    /**
     * Builds the {@link MinosApiException} a public caller receives for {@code exception}: {@code
     * code} plus a redacted message derived from it, with the original logged internally and never
     * attached as the public exception's cause.
     */
    static MinosApiException publicFailure(ErrorCode code, Exception exception) {
        return publicFailure(code, failureMessage(exception), exception);
    }

    /** As {@link #publicFailure(ErrorCode, Exception)}, but with an explicit, already-safe message. */
    static MinosApiException publicFailure(ErrorCode code, String safeMessage, Exception exception) {
        LOGGER.log(Level.WARNING, "MINOS API call failed internally (" + exception.getClass().getName() + ")", exception);
        return new MinosApiException(code, safeMessage);
    }

    /**
     * A {@link MinosApiException} reaching this point still carries a cause, which should not be
     * possible for anything built through {@link #publicFailure}: something constructed one
     * directly. Re-derive a clean public exception from it rather than propagating whatever it
     * holds -- its message is re-sanitized rather than trusted, since it did not necessarily come
     * from {@link #failureMessage}.
     */
    private static MinosApiException republish(MinosApiException exception) {
        LOGGER.log(Level.WARNING,
                "MINOS API produced a MinosApiException carrying a cause; stripping it before publication ("
                        + exception.getCause().getClass().getName() + ")", exception.getCause());
        return new MinosApiException(exception.code(),
                PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName()));
    }

    /**
     * The message a caller of the public API is allowed to see: single-line, length-bounded, and
     * never containing a path, URL credential, token or other internal detail.
     */
    static String failureMessage(Exception exception) {
        return PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName());
    }
}

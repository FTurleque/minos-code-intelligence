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
 * <p><b>The original exception is never attached to the public {@link MinosApiException} and is
 * never passed raw to the logger.</b> {@code MinosApiException} is part of the published contract: a
 * caller holds the actual object, so anything reachable from it -- {@code getCause()}, a nested
 * cause chain, suppressed exceptions -- is just as observable as {@code getMessage()} itself.
 * Likewise, moving a credential from the public exception into a warning log would merely relocate
 * the leak. Internal diagnostics therefore record only structural information that is independent
 * of exception messages: the stable {@link ErrorCode} and the exception type.</p>
 */
final class MinosApiSupport {

    private static final System.Logger LOGGER = System.getLogger(MinosApiSupport.class.getName());

    /** Fixed, path-free replacement for an internal diagnostic that fails the public policy. */
    private static final String REDACTED_DIAGNOSTIC = "internal diagnostic redacted";

    private MinosApiSupport() {
    }

    @FunctionalInterface
    interface ApiCall<T> {
        T call() throws Exception;
    }

    /**
     * Runs {@code call}, translating anything it throws into the published error taxonomy.
     *
     * <p>An already-classified {@link MinosApiException} is only passed through unchanged when the
     * entire observable exception object is already safe: no cause, no suppressed exceptions, and a
     * message that survives the public sanitizer byte-for-byte. Otherwise it is republished as a
     * clean cause-less, suppression-free exception with the same {@link ErrorCode}.</p>
     */
    static <T> T execute(ApiCall<T> call) throws MinosApiException {
        try {
            return call.call();
        } catch (MinosApiException exception) {
            throw publishClassified(exception);
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
     * The version of an internal diagnostic a public caller is allowed to see.
     *
     * <p>A diagnostic is not an exception message, but it comes from the same internal layers and
     * can carry exactly the same private detail (a staging path, a JDBC URL). It therefore crosses
     * the public boundary through {@link PublicErrorMessages}, the single redaction policy, rather
     * than being copied verbatim into a DTO. {@code null} stays {@code null}: "no diagnostic" is a
     * distinct fact from "a diagnostic that had to be redacted".</p>
     */
    static String publicDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return null;
        }
        return PublicErrorMessages.sanitize(diagnostic, REDACTED_DIAGNOSTIC);
    }

    /**
     * Builds the {@link MinosApiException} a public caller receives for {@code exception}: {@code
     * code} plus a redacted message, with no cause or inherited suppressed exceptions.
     */
    static MinosApiException publicFailure(ErrorCode code, Exception exception) {
        return publicFailure(code, failureMessage(exception), exception);
    }

    /**
     * As {@link #publicFailure(ErrorCode, Exception)}, but with an explicit caller-supplied message.
     * The message is still sanitized defensively so a future call site cannot accidentally publish
     * sensitive content merely by labelling it "safe".
     */
    static MinosApiException publicFailure(ErrorCode code, String safeMessage, Exception exception) {
        logFailure(code, exception);
        String published = PublicErrorMessages.sanitize(
                safeMessage,
                exception.getClass().getSimpleName());
        return new MinosApiException(code, published);
    }

    /**
     * Publishes an already-classified exception only after checking every observable channel. Safe,
     * cause-less and suppression-free instances retain identity for compatibility; everything else
     * is rebuilt through the same sanitizer and therefore cannot carry raw internal state across the
     * public boundary.
     */
    private static MinosApiException publishClassified(MinosApiException exception) {
        String sanitized = PublicErrorMessages.sanitize(
                exception.getMessage(),
                exception.getClass().getSimpleName());
        boolean messageAlreadySafe = Objects.equals(exception.getMessage(), sanitized);
        boolean observableStateAlreadySafe = exception.getCause() == null
                && exception.getSuppressed().length == 0
                && messageAlreadySafe;
        if (observableStateAlreadySafe) {
            return exception;
        }
        logFailure(exception.code(), exception);
        return new MinosApiException(exception.code(), sanitized);
    }

    /**
     * Logs only structural diagnostics. In particular, the raw {@link Throwable}, its message,
     * causes and suppressed exceptions are deliberately not handed to the logger because those
     * values may contain credentials, absolute paths or other private deployment details.
     */
    private static void logFailure(ErrorCode code, Exception exception) {
        LOGGER.log(Level.WARNING, diagnosticSummary(code, exception));
    }

    /** Package-private for deterministic tests of the no-secret logging invariant. */
    static String diagnosticSummary(ErrorCode code, Exception exception) {
        return "MINOS API call failed internally (code="
                + Objects.requireNonNull(code, "code")
                + ", type="
                + Objects.requireNonNull(exception, "exception").getClass().getName()
                + ")";
    }

    /**
     * The message a caller of the public API is allowed to see: single-line, length-bounded, and
     * never containing a path, URL credential, token or other internal detail.
     */
    static String failureMessage(Exception exception) {
        return PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName());
    }
}

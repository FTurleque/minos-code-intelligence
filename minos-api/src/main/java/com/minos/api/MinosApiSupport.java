package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.api.MinosApi.ErrorCode;
import com.minos.api.MinosApi.MinosApiException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Shared argument checks and failure translation for the local MINOS API facades.
 *
 * <p>The mapping from a thrown failure to a {@link ErrorCode} is part of the published API
 * contract, so it lives here once: a facade that classified {@code IOException} differently from
 * its siblings would make the same underlying fault look like a different error to callers.</p>
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

    /** Single-line failure text: a blank message degrades to the type, line breaks are flattened. */
    static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }
}

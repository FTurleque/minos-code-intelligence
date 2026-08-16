package com.minos.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public {@code MinosApi} contract is consumed by third parties that only see {@link
 * MinosApi.ErrorCode} and {@link MinosApi.MinosApiException#getMessage()} -- never the original
 * cause's message directly. These tests inject exceptions carrying exactly the kind of internal
 * detail a real failure could contain (absolute paths, JDBC URLs with credentials, bearer tokens)
 * and verify none of it survives into the public message, regardless of which internal layer threw
 * it or which of the local API facades it passed through.
 */
class MinosApiSupportTest {

    private static final String[] SENSITIVE_MESSAGES = {
            "cannot open MINOS_HOME at C:\\Users\\secret-user\\.minos\\data",
            "cannot open MINOS_HOME at /home/private-user/.minos/index",
            "connection failed: jdbc:postgresql://user:password@database.example/minos",
            "request failed: https://user:secret@example.org/private",
            "authentication rejected: token=super-secret-value",
    };

    @Test
    void sensitiveIoFailureMessagesAreNeverExposedPublicly() {
        for (String detail : SENSITIVE_MESSAGES) {
            MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
                throw new IOException(detail);
            });
            assertEquals(MinosApi.ErrorCode.IO_FAILURE, failure.code());
            assertPublicMessageIsSafe(failure, detail);
        }
    }

    @Test
    void sensitiveExecutionFailureMessagesAreNeverExposedPublicly() {
        for (String detail : SENSITIVE_MESSAGES) {
            MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
                throw new SQLException(detail);
            });
            assertEquals(MinosApi.ErrorCode.EXECUTION_FAILURE, failure.code());
            assertPublicMessageIsSafe(failure, detail);
        }
    }

    @Test
    void sensitiveInvalidRequestMessagesAreNeverExposedPublicly() {
        for (String detail : SENSITIVE_MESSAGES) {
            MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
                throw new IllegalArgumentException(detail);
            });
            assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, failure.code());
            assertPublicMessageIsSafe(failure, detail);
        }
    }

    @Test
    void theOriginalExceptionRemainsAvailableAsTheCauseForInternalDiagnosis() {
        String detail = "connection failed: jdbc:postgresql://user:password@database.example/minos";
        IOException original = new IOException(detail);
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw original;
        });
        assertSame(original, failure.getCause());
        assertEquals(detail, failure.getCause().getMessage());
    }

    @Test
    void safeMessagesAreNotAltered() {
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw new IllegalArgumentException("query must specify at least one criterion");
        });
        assertEquals("query must specify at least one criterion", failure.getMessage());
    }

    @Test
    void securityExceptionMapsToAccessDenied() {
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw new SecurityException("cannot open C:\\Users\\secret-user\\.minos\\credentials");
        });
        assertEquals(MinosApi.ErrorCode.ACCESS_DENIED, failure.code());
        assertPublicMessageIsSafe(failure, "cannot open C:\\Users\\secret-user\\.minos\\credentials");
    }

    @Test
    void accessDeniedIoExceptionMapsToAccessDeniedNotIoFailure() {
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw new AccessDeniedException("/home/private-user/.minos/index");
        });
        assertEquals(MinosApi.ErrorCode.ACCESS_DENIED, failure.code());
    }

    @Test
    void anAlreadyClassifiedApiExceptionPassesThroughUnchanged() {
        MinosApi.MinosApiException original =
                new MinosApi.MinosApiException(MinosApi.ErrorCode.UNAVAILABLE, "team mode is disabled");
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw original;
        });
        assertSame(original, failure);
    }

    @Test
    void blankMessageFallsBackToTheExceptionTypeName() {
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw new IOException();
        });
        assertEquals("IOException", failure.getMessage());
    }

    private static void assertPublicMessageIsSafe(MinosApi.MinosApiException failure, String originalDetail) {
        String message = failure.getMessage();
        assertFalse(message.contains(originalDetail), "public message must not repeat the internal detail: " + message);
        assertFalse(message.toLowerCase(java.util.Locale.ROOT).contains("password"), message);
        assertFalse(message.toLowerCase(java.util.Locale.ROOT).contains("secret"), message);
        assertFalse(message.contains("jdbc:"), message);
        assertFalse(message.contains("token="), message);
        assertTrue(message.equals(failure.getCause().getClass().getSimpleName()) || !message.isBlank());
    }

    private static MinosApi.MinosApiException assertThrowsApiException(MinosApiSupport.ApiCall<?> call) {
        try {
            MinosApiSupport.execute(call);
            throw new AssertionError("expected a MinosApiException");
        } catch (MinosApi.MinosApiException expected) {
            return expected;
        }
    }
}

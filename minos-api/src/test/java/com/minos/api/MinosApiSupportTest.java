package com.minos.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public {@code MinosApi} contract is consumed by third parties that hold the actual {@link
 * MinosApi.MinosApiException} object -- not just its {@code getMessage()}. Everything reachable
 * from that object is part of the observable surface: {@code getMessage()}, {@code getCause()}, a
 * cause's own cause, and suppressed exceptions. These tests inject exceptions carrying exactly the
 * kind of internal detail a real failure could contain (absolute paths, a UNC path, JDBC/URL
 * credentials, bearer tokens, ODBC-style connection strings) and verify none of it is reachable
 * through any of those paths, regardless of which internal layer threw it or which facade it passed
 * through.
 */
class MinosApiSupportTest {

    private static final String[] SENSITIVE_MESSAGES = {
            "cannot open MINOS_HOME at C:\\Users\\secret-user\\.minos\\data",
            "cannot open MINOS_HOME at /home/private-user/.minos/index",
            "cannot reach \\\\server\\private-share\\secret",
            "connection failed: jdbc:postgresql://user:password@database.example/minos",
            "request failed: https://user:secret@example.org/private",
            "authentication rejected: token=super-secret-value",
            "authentication rejected: password=super-secret-value",
            "authentication rejected: key=super-secret-value",
            "authentication rejected: pwd=super-secret-value",
    };

    @Test
    void sensitiveIoFailureMessagesAreNeverExposedPublicly() {
        for (String detail : SENSITIVE_MESSAGES) {
            MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
                throw new IOException(detail);
            });
            assertEquals(MinosApi.ErrorCode.IO_FAILURE, failure.code());
            assertNoSensitiveDetailIsReachable(failure, detail);
        }
    }

    @Test
    void sensitiveExecutionFailureMessagesAreNeverExposedPublicly() {
        for (String detail : SENSITIVE_MESSAGES) {
            MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
                throw new SQLException(detail);
            });
            assertEquals(MinosApi.ErrorCode.EXECUTION_FAILURE, failure.code());
            assertNoSensitiveDetailIsReachable(failure, detail);
        }
    }

    @Test
    void sensitiveInvalidRequestMessagesAreNeverExposedPublicly() {
        for (String detail : SENSITIVE_MESSAGES) {
            MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
                throw new IllegalArgumentException(detail);
            });
            assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, failure.code());
            assertNoSensitiveDetailIsReachable(failure, detail);
        }
    }

    @Test
    void theOriginalExceptionIsNeverAttachedAsThePublicCause() {
        String detail = "connection failed: jdbc:postgresql://user:password@database.example/minos";
        IOException original = new IOException(detail);

        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw original;
        });

        assertNull(failure.getCause(), "the public exception must not expose the internal exception as its cause");
    }

    @Test
    void aNestedCauseChainOnTheOriginalExceptionNeverReachesThePublicException() {
        IOException root = new IOException("connection reset by jdbc:postgresql://user:password@database.example/minos");
        SQLException wrapped = new SQLException("query failed", root);

        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw wrapped;
        });

        assertNull(failure.getCause(), "no cause -- nested or otherwise -- may be attached to the public exception");
    }

    @Test
    void suppressedExceptionsOnTheOriginalExceptionNeverReachThePublicException() {
        IOException primary = new IOException("primary failure");
        primary.addSuppressed(new IOException("cleanup also failed: token=super-secret-value"));

        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw primary;
        });

        assertEquals(0, failure.getSuppressed().length,
                "the public exception must not inherit suppressed exceptions from the internal failure");
    }

    @Test
    void aMinosApiExceptionConstructedDirectlyWithARawCauseIsStrippedByExecute() {
        IOException rawCause = new IOException("cannot open C:\\Users\\secret-user\\.minos\\data");
        MinosApi.MinosApiException constructedDirectly =
                new MinosApi.MinosApiException(MinosApi.ErrorCode.IO_FAILURE, "shutdown failed", rawCause);

        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw constructedDirectly;
        });

        assertEquals(MinosApi.ErrorCode.IO_FAILURE, failure.code());
        assertNotSame(constructedDirectly, failure);
        assertNull(failure.getCause());
        assertNoSensitiveDetailIsReachable(failure, "C:\\Users\\secret-user\\.minos\\data");
    }

    @Test
    void anAlreadyClassifiedApiExceptionWithSensitiveMessageIsRepublished() {
        String detail = "authentication rejected: token=super-secret-value";
        MinosApi.MinosApiException original =
                new MinosApi.MinosApiException(MinosApi.ErrorCode.ACCESS_DENIED, detail);

        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw original;
        });

        assertNotSame(original, failure, "an unsafe already-classified exception must not bypass sanitization");
        assertEquals(MinosApi.ErrorCode.ACCESS_DENIED, failure.code());
        assertNoSensitiveDetailIsReachable(failure, detail);
    }

    @Test
    void anAlreadyClassifiedApiExceptionWithSuppressedFailureIsRepublished() {
        String detail = "cleanup also failed: password=super-secret-value";
        MinosApi.MinosApiException original =
                new MinosApi.MinosApiException(MinosApi.ErrorCode.IO_FAILURE, "shutdown failed");
        original.addSuppressed(new IOException(detail));

        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw original;
        });

        assertNotSame(original, failure, "suppressed exceptions are part of the public observable surface");
        assertEquals(MinosApi.ErrorCode.IO_FAILURE, failure.code());
        assertEquals("shutdown failed", failure.getMessage());
        assertNoSensitiveDetailIsReachable(failure, detail);
    }

    @Test
    void anAlreadyClassifiedSafeApiExceptionPassesThroughUnchanged() {
        MinosApi.MinosApiException original =
                new MinosApi.MinosApiException(MinosApi.ErrorCode.UNAVAILABLE, "team mode is disabled");
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw original;
        });
        assertSame(original, failure);
    }

    @Test
    void internalDiagnosticSummaryNeverIncludesRawExceptionDetail() {
        for (String detail : SENSITIVE_MESSAGES) {
            IOException internal = new IOException(detail);
            String summary = MinosApiSupport.diagnosticSummary(MinosApi.ErrorCode.IO_FAILURE, internal);

            assertTrue(summary.contains("IO_FAILURE"), summary);
            assertTrue(summary.contains(IOException.class.getName()), summary);
            assertFalse(summary.contains(detail), summary);
            assertFalse(summary.toLowerCase(java.util.Locale.ROOT).contains("super-secret-value"), summary);
            assertFalse(summary.contains("jdbc:"), summary);
        }
    }

    @Test
    void explicitPublicFailureMessageIsStillSanitizedDefensively() {
        String detail = "token=super-secret-value";
        MinosApi.MinosApiException failure = MinosApiSupport.publicFailure(
                MinosApi.ErrorCode.EXECUTION_FAILURE,
                detail,
                new IOException("internal failure"));

        assertNoSensitiveDetailIsReachable(failure, detail);
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
        assertNoSensitiveDetailIsReachable(failure, "cannot open C:\\Users\\secret-user\\.minos\\credentials");
    }

    @Test
    void accessDeniedIoExceptionMapsToAccessDeniedNotIoFailure() {
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw new AccessDeniedException("/home/private-user/.minos/index");
        });
        assertEquals(MinosApi.ErrorCode.ACCESS_DENIED, failure.code());
    }

    @Test
    void blankMessageFallsBackToTheExceptionTypeName() {
        MinosApi.MinosApiException failure = assertThrowsApiException(() -> {
            throw new IOException();
        });
        assertEquals("IOException", failure.getMessage());
    }

    private static void assertNoSensitiveDetailIsReachable(MinosApi.MinosApiException failure, String originalDetail) {
        assertNull(failure.getCause(), "public exception must carry no cause at all");
        assertEquals(0, failure.getSuppressed().length, "public exception must carry no suppressed exceptions");
        String message = failure.getMessage();
        assertFalse(message.contains(originalDetail), "public message must not repeat the internal detail: " + message);
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("password"), message);
        assertFalse(lower.contains("secret"), message);
        assertFalse(message.contains("jdbc:"), message);
        assertFalse(message.contains("token="), message);
        assertFalse(message.contains("key="), message);
        assertFalse(message.contains("pwd="), message);
        assertTrue(!message.isBlank());
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

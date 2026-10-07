package com.minos.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-C06 : tout échec d'ouverture d'une façade de l'API Java est une {@link MinosApi.MinosApiException} classée
 * et redactée, jamais une exception d'exécution brute.
 */
class OpenFailureTranslationTest {
    private static final String BACKEND_PROPERTY = "minos.storage.backend";

    @TempDir
    Path temp;

    @Test
    void anUnsupportedStorageBackendIsAnInvalidRequestOnTheThreeFacades() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        withBackend("bogus", () -> {
            for (Opener opener : new Opener[]{
                    () -> new LocalMinosApi(home), () -> new LocalMinosMultiRepositoryApi(home),
                    () -> new LocalProviderPlatformApi(home)}) {
                MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class, opener::open);

                assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, failure.code(), failure.getMessage());
                assertNull(failure.getCause(), "no internal cause is exposed");
            }
        });
    }

    @Test
    void aConfigurationValueThatLooksLikeAPathIsNotEchoedInThePublicMessage() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        withBackend("/home/operator/secret-backend", () -> {
            MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                    () -> new LocalMinosApi(home));

            assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, failure.code());
            assertFalse(failure.getMessage().contains("/home/operator"), failure.getMessage());
            assertFalse(failure.getMessage().contains("secret-backend"), failure.getMessage());
        });
    }

    @Test
    void theOpeningFailuresAreClassifiedLikeEveryOtherCall() {
        Path home = temp.resolve("home");

        assertOpenFails(MinosApi.ErrorCode.INVALID_REQUEST, new IllegalArgumentException("unsupported storage backend: x"), home);
        assertOpenFails(MinosApi.ErrorCode.UNAVAILABLE, new IllegalStateException("composition root is absent"), home);
        assertOpenFails(MinosApi.ErrorCode.EXECUTION_FAILURE, new UnsupportedOperationException("boom"), home);

        MinosApi.MinosApiException io = assertThrows(MinosApi.MinosApiException.class,
                () -> MinosApiSupport.openApplication(home, "MINOS API bootstrap failed", path -> {
                    throw new java.io.IOException("cannot open /home/operator/minos");
                }));
        assertEquals(MinosApi.ErrorCode.IO_FAILURE, io.code());
        assertEquals("MINOS API bootstrap failed", io.getMessage(), "an I/O failure keeps the facade's own message");
        assertNull(io.getCause());
    }

    @Test
    void aSensitiveOpeningMessageIsRedactedAndNoCauseIsAttached() {
        MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                () -> MinosApiSupport.openApplication(temp.resolve("home"), "bootstrap failed", path -> {
                    throw new IllegalStateException("cannot read /home/operator/.minos/token=abc",
                            new java.io.IOException("jdbc:postgresql://db/minos password=hunter2"));
                }));

        assertEquals(MinosApi.ErrorCode.UNAVAILABLE, failure.code());
        assertFalse(failure.getMessage().contains("/home/operator"), failure.getMessage());
        assertFalse(failure.getMessage().contains("hunter2"), failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static void assertOpenFails(MinosApi.ErrorCode expected, RuntimeException failure, Path home) {
        MinosApi.MinosApiException thrown = assertThrows(MinosApi.MinosApiException.class,
                () -> MinosApiSupport.openApplication(home, "bootstrap failed", path -> {
                    throw failure;
                }));
        assertEquals(expected, thrown.code(), thrown.getMessage());
        assertNull(thrown.getCause());
    }

    private static void withBackend(String value, Body body) throws Exception {
        String previous = System.getProperty(BACKEND_PROPERTY);
        System.setProperty(BACKEND_PROPERTY, value);
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(BACKEND_PROPERTY);
            } else {
                System.setProperty(BACKEND_PROPERTY, previous);
            }
        }
    }

    @FunctionalInterface
    private interface Opener {
        AutoCloseable open() throws Exception;
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }
}

package com.minos.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C01 sur l'API Java : une référence de type chemin donne une erreur publique de requête invalide au
 * message actionnable, sans le chemin ni cause interne.
 */
class ProjectReferenceErrorApiTest {

    @TempDir
    Path temp;

    @Test
    void aPathShapedReferenceIsAnInvalidRequestWithAnActionableMessageAndNoCause() throws Exception {
        try (MinosApi api = new LocalMinosApi(Files.createDirectories(temp.resolve("home")))) {
            for (String reference : new String[]{"C:\\Users\\x\\proj", "/home/x/proj", "\\\\srv\\share\\p"}) {
                MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                        () -> api.getProject(reference));

                String context = reference + " -> " + failure.getMessage();
                assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, failure.code(), context);
                assertTrue(failure.getMessage().contains("unknown project"), context);
                assertTrue(failure.getMessage().contains("project name or UUID"), context);
                assertFalse(failure.getMessage().contains("ResolutionException"), context);
                assertFalse(failure.getMessage().contains("srv"), context);
                assertNull(failure.getCause(), context);
            }
        }
    }

    @Test
    void aPlainUnknownNameKeepsTheMessageItHadBeforeThisChange() throws Exception {
        try (MinosApi api = new LocalMinosApi(Files.createDirectories(temp.resolve("home")))) {
            MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                    () -> api.getProject("demo"));

            assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals("unknown project: demo", failure.getMessage());
        }
    }
}

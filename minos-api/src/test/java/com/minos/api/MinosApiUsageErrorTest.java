package com.minos.api;

import com.minos.api.MinosApi.ErrorCode;
import com.minos.api.MinosApi.MinosApiException;
import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationTestAccess;
import com.minos.application.diagnostics.InternalFailureReporter;
import com.minos.application.resolution.ProjectResolver;
import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.diagnostics.UsageException;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An invalid request is a {@link UsageException} or an unresolved project; any other argument error is a defect. */
class MinosApiUsageErrorTest {

    @TempDir
    Path temp;

    @BeforeEach
    void withoutJournal() {
        MinosApiSupport.useFailureReporter(InternalFailureReporter.withoutJournal("api"));
    }

    @AfterEach
    void restoreTheDefaultReporter() {
        MinosApiSupport.useFailureReporter(null);
    }

    @Test
    void aNullParameterIsAnInvalidRequestThatNamesTheParameter() {
        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class,
                    () -> MinosApiSupport.execute(() -> MinosApiSupport.required(null, "query")));

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals("query must not be null", failure.getMessage());
            assertEquals(List.of(), log.warnings());
        }
    }

    @Test
    void aPlainIllegalArgumentExceptionOfAServiceIsAnExecutionFailureWithoutCauseAndIsTraced() {
        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new IllegalArgumentException("limit must be positive");
            }));

            assertEquals(ErrorCode.EXECUTION_FAILURE, failure.code());
            assertNull(failure.getCause());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            String line = log.warnings().get(0);
            assertTrue(line.contains("type=" + IllegalArgumentException.class.getName()), line);
            assertTrue(line.matches(".*, ref=[0-9a-f]{8}\\)"), line);
            assertFalse(line.contains("limit must be positive"), line);
        }
    }

    @Test
    void aUsageErrorIsAnInvalidRequestAndLeavesNoDiagnosticLine() {
        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new UsageException("limit must be between 1 and 10");
            }));

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals("limit must be between 1 and 10", failure.getMessage());
            assertEquals(List.of(), log.warnings());
        }
    }

    @Test
    void anUnknownDocumentStaysAnInvalidRequestWithoutADiagnosticLine() {
        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new SemanticCorpusQueryService.DocumentNotFoundException();
            }));

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertEquals(List.of(), log.warnings());
        }
    }

    @Test
    void anUnknownProjectIsAnInvalidRequestWithThePublicMessage() throws Exception {
        try (MinosApplication application = MinosApplication.open(temp);
             LogCapture log = new LogCapture(MinosApiSupport.class)) {
            ProjectResolver resolver = new ProjectResolver(MinosApplicationTestAccess.projectRegistry(application));

            MinosApiException failure = assertThrows(MinosApiException.class,
                    () -> MinosApiSupport.execute(() -> resolver.resolve("no-such-project")));

            assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
            assertTrue(failure.getMessage().contains("no-such-project") || failure.getMessage().contains("unknown"),
                    failure.getMessage());
            assertEquals(List.of(), log.warnings());
        }
    }

    @Test
    void anIllegalStateOfAServiceIsUnavailableAndTracedWithoutItsMessage() {
        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new IllegalStateException("secret=hunter2 broken");
            }));

            assertEquals(ErrorCode.UNAVAILABLE, failure.code());
            assertNull(failure.getCause());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            String line = log.warnings().get(0);
            assertTrue(line.contains("type=" + IllegalStateException.class.getName()), line);
            assertTrue(line.contains("at=com.minos.api.MinosApiUsageErrorTest."), line);
            assertFalse(line.contains("hunter2"), line);
        }
    }

    @Test
    void theIndexReplacedDuringAReadIsUnavailableWithItsPublicMessageAndNoDiagnosticLine() {
        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new SemanticCorpusQueryService.IndexChangedException();
            }));

            assertEquals(ErrorCode.UNAVAILABLE, failure.code());
            assertTrue(failure.getMessage().contains("index changed"), failure.getMessage());
            assertEquals(List.of(), log.warnings());
        }
    }

    @Test
    void anUnavailabilityClassifiedByTheFacadeLeavesNoDiagnosticLine() {
        try (LogCapture log = new LogCapture(MinosApiSupport.class)) {
            MinosApiException failure = assertThrows(MinosApiException.class, () -> MinosApiSupport.execute(() -> {
                throw new MinosApiException(ErrorCode.UNAVAILABLE, "MINOS team mode is disabled");
            }));

            assertEquals(ErrorCode.UNAVAILABLE, failure.code());
            assertEquals(List.of(), log.warnings());
        }
    }

    @Test
    void aTeamFacadeWithoutTeamModeRendersTheClassifiedUnavailability() throws Exception {
        try (MinosApplication application = MinosApplication.open(temp);
             LogCapture log = new LogCapture(MinosApiSupport.class)) {
            LocalMinosTeamApi api = new LocalMinosTeamApi(application);

            MinosApiException failure = assertThrows(MinosApiException.class, () -> api.tenant("token"));

            assertEquals(ErrorCode.UNAVAILABLE, failure.code());
            assertEquals("MINOS team mode is disabled", failure.getMessage());
            assertEquals(List.of(), log.warnings());
        }
    }
}

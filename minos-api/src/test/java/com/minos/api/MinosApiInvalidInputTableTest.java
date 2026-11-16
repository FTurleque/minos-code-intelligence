package com.minos.api;

import com.minos.api.MinosApi.ErrorCode;
import com.minos.api.MinosApi.MinosApiException;
import com.minos.application.diagnostics.InternalFailureReporter;
import com.minos.diagnostics.UsageException;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documented invalid inputs of the Java API, table by table: each one is an invalid request that names what to
 * correct, raised on purpose as a {@link UsageException}, and none leaves an internal-failure line.
 */
class MinosApiInvalidInputTableTest {

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

    /** Request records validate where they are built, in the caller's thread: a {@link UsageException}. */
    @Test
    void everyInvalidRequestRecordRaisesAUsageException() {
        Map<String, Runnable> table = Map.of(
                "symbol query without criterion", () -> new MinosApi.SymbolQuery(null, null, null, null, 10),
                "symbol query limit 0", () -> new MinosApi.SymbolQuery("a", null, null, null, 0),
                "impact query depth 0", () -> new MinosApi.ImpactQuery("symbol", 0, 10),
                "impact query blank symbol", () -> new MinosApi.ImpactQuery(" ", 1, 10),
                "page limit 0", () -> new SemanticExplorationApi.DocumentPageQuery(null, 0, null, null, null, null),
                "page limit 201", () -> new SemanticExplorationApi.DocumentPageQuery(null, 201, null, null, null, null));

        table.forEach((label, build) -> assertThrows(UsageException.class, build::run, label));
    }

    /** Facade parameters are validated inside the call: an invalid request, with no diagnostic line. */
    @Test
    void everyInvalidFacadeParameterIsAnInvalidRequestThatNamesIt() throws Exception {
        try (LocalMinosApi api = new LocalMinosApi(temp.resolve("home"));
             LogCapture log = new LogCapture(MinosApiSupport.class)) {
            Map<String, Supplier<Object>> table = Map.of(
                    "usages limit 0", () -> call(() -> api.findUsages("p", "symbol", 0)),
                    "usages blank symbol", () -> call(() -> api.findUsages("p", " ", 5)),
                    "symbols null query", () -> call(() -> api.findSymbols("p", null)),
                    "impact null query", () -> call(() -> api.analyzeImpact("p", null)));

            for (Map.Entry<String, Supplier<Object>> entry : table.entrySet()) {
                MinosApiException failure = assertThrows(ApiFailure.class, entry.getValue()::get, entry.getKey()).api;

                assertEquals(ErrorCode.INVALID_REQUEST, failure.code(), entry.getKey());
                assertTrue(failure.getMessage().contains("must"), entry.getKey() + ": " + failure.getMessage());
            }
            assertEquals(List.of(), log.warnings());
        }
    }

    private static Object call(ApiCall call) {
        try {
            return call.run();
        } catch (MinosApiException failure) {
            throw new ApiFailure(failure);
        }
    }

    @FunctionalInterface
    private interface ApiCall {
        Object run() throws MinosApiException;
    }

    private static final class ApiFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient MinosApiException api;

        ApiFailure(MinosApiException api) {
            super(api.getMessage());
            this.api = api;
        }
    }
}

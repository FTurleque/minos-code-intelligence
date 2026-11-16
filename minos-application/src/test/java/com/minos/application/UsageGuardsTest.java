package com.minos.application;

import com.minos.application.semantic.HybridContextBuilder.ContextRequest;
import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.context.CodeSearchCriteria;
import com.minos.diagnostics.UsageException;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.impact.ImpactAnalysisRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** The argument checks of the request records are usage errors: the caller of a surface is told what to fix. */
class UsageGuardsTest {

    @Test
    void aContextRequestOutOfBoundsIsAUsageError() {
        assertThrows(UsageException.class, () -> new ContextRequest(" ", 5, 1_024, 256));
        assertThrows(UsageException.class, () -> new ContextRequest("q".repeat(70_000), 5, 1_024, 256));
        assertThrows(UsageException.class, () -> new ContextRequest("q", 0, 1_024, 256));
        assertThrows(UsageException.class, () -> new ContextRequest("q", 5, 10, 256));
        assertThrows(UsageException.class, () -> new ContextRequest("q", 5, 1_024, 4));
    }

    @Test
    void aCodeSearchCriteriaOutOfBoundsIsAUsageError() {
        SymbolSearchCriteria symbols = SymbolSearchCriteria.lexical("x", 1);
        assertThrows(UsageException.class, () -> new CodeSearchCriteria(symbols, 99, 1, 1, 1, 1_024, false));
        assertThrows(UsageException.class, () -> new CodeSearchCriteria(symbols, 1, 99_999, 1, 1, 1_024, false));
        assertThrows(UsageException.class, () -> new CodeSearchCriteria(symbols, 1, 1, 99_999, 1, 1_024, false));
        assertThrows(UsageException.class, () -> new CodeSearchCriteria(symbols, 1, 1, 1, 999, 1_024, false));
        assertThrows(UsageException.class, () -> new CodeSearchCriteria(symbols, 1, 1, 1, 1, 1, false));
    }

    @Test
    void anUnknownKindOrSearchModeIsAUsageError() {
        assertThrows(UsageException.class, () -> SemanticCorpusQueryService.parseKind("folder"));
        assertThrows(UsageException.class, () -> SemanticCorpusQueryService.SearchMode.parse("keyword"));
    }

    @Test
    void anImpactAnalysisRequestOutOfBoundsIsAUsageError() {
        assertThrows(UsageException.class, () -> new ImpactAnalysisRequest(" ", 1, 1));
        assertThrows(UsageException.class, () -> new ImpactAnalysisRequest("symbol", 99, 1));
        assertThrows(UsageException.class, () -> new ImpactAnalysisRequest("symbol", 1, 0));
    }
}

package com.minos.explorer;

import com.minos.application.resolution.ProjectResolver;
import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.application.semantic.SemanticIndexService;
import com.minos.application.semantic.SemanticProjectionService;
import com.minos.semantic.SemanticDocumentKind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The values contract version 1 of the API serializes, field by field (ARCH-0086): a value added, removed or renamed
 * fails here, naming the field. The eight French values form a closed list of asserted size; the {@code limitations}
 * field is an open list of strings and is not frozen.
 */
class ExplorerContractValuesTest {

    /** The eight French values of contract version 1; no French value is added in version 1. */
    static final List<String> FRENCH_VALUES = List.of(
            "ABSENTE", "PRETE", "PERIMEE", "ECHOUEE", "IDENTIQUE", "DIFFERENTE", "SOURCE_INTROUVABLE", "NON_COMPARABLE");

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    @Test
    void everySerializedFieldKeepsItsVersionOneValues() {
        assertEquals(List.of("DISABLED", "NO_ACTIVE_SNAPSHOT", "MISSING", "STALE", "READY"),
                names(SemanticIndexService.State.values()), "field indexState");
        assertEquals(List.of("ABSENTE", "PRETE", "PERIMEE", "ECHOUEE"),
                names(SemanticProjectionService.State.values()), "field projection state");
        assertEquals(List.of("IDENTIQUE", "DIFFERENTE", "SOURCE_INTROUVABLE", "NON_COMPARABLE"),
                names(SemanticCorpusQueryService.Divergence.values()), "field divergence");
        assertEquals(List.of("INDEX_NOT_READY", "NO_DOCUMENT_OF_THE_SELECTED_KINDS", "MEMORY_BUDGET_EXCEEDED",
                        "INDEX_CHANGED_DURING_PREPARATION", "PREPARATION_IN_PROGRESS"),
                names(SemanticProjectionService.Refusal.values()), "field refusal");
        assertEquals(List.of("SEMANTIC", "HYBRID"), names(SemanticCorpusQueryService.SearchMode.values()),
                "field search mode");
        assertEquals(List.of("COSINE_SIMILARITY", "HYBRID_SCORE"), names(SemanticCorpusQueryService.ScoreKind.values()),
                "field scoreKind");
        assertEquals(List.of("SYMBOL", "FILE", "CHUNK"), names(SemanticDocumentKind.values()), "field kind");
    }

    @Test
    void theFrenchValuesFormAClosedListOfEight() {
        assertEquals(8, FRENCH_VALUES.size(), "the closed list of French values counts exactly eight values");
        List<String> french = Stream.concat(names(SemanticProjectionService.State.values()).stream(),
                names(SemanticCorpusQueryService.Divergence.values()).stream()).toList();
        assertEquals(FRENCH_VALUES, french, "the French values of the projection state and of the divergence");
    }

    @Test
    void theErrorCodesAreTheClosedListOfVersionOne() {
        assertEquals(List.of("BUSY", "DOCUMENT_NOT_FOUND", "INDEX_CHANGED", "INTERNAL_ERROR", "INVALID_PROJECT_REFERENCE",
                        "INVALID_REQUEST", "METHOD_NOT_ALLOWED", "NOT_FOUND", "PROJECT_NOT_FOUND",
                        "PROJECT_REFERENCE_AMBIGUOUS", "TIMEOUT", "UNSUPPORTED_MEDIA_TYPE"),
                names(ExplorerErrorCode.values()), "closed list of error codes");
    }

    @Test
    void everyProjectResolutionCodeHasTheErrorCodeOfTheSameName() {
        for (ProjectResolver.ErrorCode resolution : ProjectResolver.ErrorCode.values()) {
            ExplorerErrorCode code = assertDoesNotThrow(() -> ExplorerErrorCode.of(resolution),
                    "no error code for the project resolution code " + resolution);
            assertEquals(resolution.name(), code.name());
        }
    }

    @Test
    void anErrorAnswerCarriesTheCodeByName() {
        ExplorerApi.Answer answer = ExplorerApi.Answer.error(503, ExplorerErrorCode.BUSY, "too many requests at once");

        assertEquals(503, answer.status());
        assertEquals("{\"error\":{\"code\":\"BUSY\",\"message\":\"too many requests at once\"}}", answer.json());
    }
}

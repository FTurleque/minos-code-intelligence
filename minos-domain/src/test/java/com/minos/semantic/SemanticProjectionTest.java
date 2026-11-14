package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a projection may hold: points of one selection, in order, inside the unit square. */
class SemanticProjectionTest {

    private static final SemanticProjection.IndexIdentity INDEX =
            new SemanticProjection.IndexIdentity("project", "snapshot", "provider", "model", 4, "fingerprint");
    private static final SemanticProjection.Method METHOD = new SemanticProjection.Method("pca", 1, "components=2", 42L);
    private static final SemanticProjection.Selection CHUNKS =
            new SemanticProjection.Selection(Set.of(SemanticDocumentKind.CHUNK), 2);

    @Test
    void aProjectionSaysWhetherItShowsASampleOfItsCandidates() {
        SemanticProjection whole = projection(10, 2, point("a", 0.0f), point("b", 1.0f));
        SemanticProjection sample = projection(10, 5, point("a", 0.0f), point("b", -1.0f));

        assertFalse(whole.sampled());
        assertTrue(sample.sampled());
        assertEquals(2, sample.points().size());
        assertEquals(List.of(0.5, 0.25), sample.explainedVariance());
    }

    @Test
    void theKindsOfASelectionAreKeptInOneOrderWhateverTheOrderTheyWereGivenIn() {
        SemanticProjection.Selection one = new SemanticProjection.Selection(
                new java.util.LinkedHashSet<>(List.of(SemanticDocumentKind.SYMBOL, SemanticDocumentKind.CHUNK)), 5);
        SemanticProjection.Selection other = new SemanticProjection.Selection(
                new java.util.LinkedHashSet<>(List.of(SemanticDocumentKind.CHUNK, SemanticDocumentKind.SYMBOL)), 5);

        assertEquals(one, other);
        assertEquals(List.of(SemanticDocumentKind.CHUNK, SemanticDocumentKind.SYMBOL), List.copyOf(one.kinds()));
        assertThrows(UnsupportedOperationException.class, () -> one.kinds().add(SemanticDocumentKind.FILE));
    }

    @Test
    void pointsOutOfOrderOfAnotherKindOrBeyondTheSelectionAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> projection(10, 2, point("b", 0.0f), point("a", 0.0f)));
        assertThrows(IllegalArgumentException.class, () -> projection(10, 2, point("a", 0.0f), point("a", 0.0f)));
        assertThrows(IllegalArgumentException.class, () -> projection(10, 1, point("a", 0.0f), point("b", 0.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> projection(10, 5, point("a", 0.0f), point("b", 0.0f), point("c", 0.0f)));
        assertThrows(IllegalArgumentException.class, () -> projection(3, 5, point("a", 0.0f)));
        assertThrows(IllegalArgumentException.class, () -> projection(10, 5,
                new SemanticProjection.Point("a", SemanticDocumentKind.FILE, 0.0f, 0.0f)));
    }

    @Test
    void coordinatesOutsideTheUnitSquareAndSharesOutsideZeroToOneAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> point("a", 1.5f));
        assertThrows(IllegalArgumentException.class, () -> point("a", Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticProjection.Point("a", SemanticDocumentKind.CHUNK, 0.0f, Float.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new SemanticProjection.Point(" ", SemanticDocumentKind.CHUNK, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new SemanticProjection(INDEX, METHOD, CHUNKS, 1, 1,
                List.of(0.5), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SemanticProjection(INDEX, METHOD, CHUNKS, 1, 1,
                List.of(0.5, 1.5), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SemanticProjection(INDEX, METHOD, CHUNKS, 1, 1,
                List.of(Double.NaN, 0.0), List.of()));
    }

    @Test
    void anIdentityAMethodAndASelectionRefuseWhatCannotNameThem() {
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticProjection.IndexIdentity("project", "snapshot", "provider", "model", 0, "f"));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticProjection.IndexIdentity("project", " ", "provider", "model", 4, "f"));
        assertThrows(IllegalArgumentException.class, () -> new SemanticProjection.Method("pca", 0, "", 1L));
        assertThrows(IllegalArgumentException.class, () -> new SemanticProjection.Selection(Set.of(), 5));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticProjection.Selection(Set.of(SemanticDocumentKind.CHUNK), 0));
    }

    @Test
    void theExceptionsOfTheStoreCarryAMessageThatCanBeShown() {
        SemanticProjectionStore.PreparationInProgressException busy =
                new SemanticProjectionStore.PreparationInProgressException(new java.io.IOException("lock"));
        SemanticProjectionStore.UnreadableProjectionException unreadable =
                new SemanticProjectionStore.UnreadableProjectionException("unreadable", busy);

        assertEquals("another preparation of the projection of this project is running", busy.getMessage());
        assertEquals("lock", busy.getCause().getMessage());
        assertEquals("unreadable", unreadable.getMessage());
        assertEquals("plain", new SemanticProjectionStore.UnreadableProjectionException("plain").getMessage());
    }

    private static SemanticProjection.Point point(String stableKey, float x) {
        return new SemanticProjection.Point(stableKey, SemanticDocumentKind.CHUNK, x, 0.0f);
    }

    private static SemanticProjection projection(int documents, int candidates, SemanticProjection.Point... points) {
        return new SemanticProjection(INDEX, METHOD, CHUNKS, documents, candidates, List.of(0.5, 0.25), List.of(points));
    }
}

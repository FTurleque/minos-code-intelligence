package com.minos.semantic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Weighted construction budget for one reconstructible semantic index. */
public record SemanticIndexBudget(
        int maxDocuments,
        long maxContentBytes,
        long maxVectorBytes
) {

    public static final SemanticIndexBudget DEFAULT = new SemanticIndexBudget(
            250_000,
            192L * 1024L * 1024L,
            192L * 1024L * 1024L
    );

    public SemanticIndexBudget {
        if (maxDocuments < 1 || maxDocuments > 1_000_000) {
            throw new IllegalArgumentException("maxDocuments must be between 1 and 1000000");
        }
        if (maxContentBytes < 1L || maxVectorBytes < 1L) {
            throw new IllegalArgumentException("semantic byte budgets must be positive");
        }
    }

    public Tracker tracker(int dimensions) {
        if (dimensions < 1 || dimensions > 16_384) {
            throw new IllegalArgumentException("semantic dimensions must be between 1 and 16384");
        }
        return new Tracker(this, dimensions);
    }

    public static final class Tracker {
        private final SemanticIndexBudget budget;
        private final int dimensions;
        private int documents;
        private long contentBytes;
        private long vectorBytes;

        private Tracker(SemanticIndexBudget budget, int dimensions) {
            this.budget = Objects.requireNonNull(budget, "budget");
            this.dimensions = dimensions;
        }

        public void account(SemanticDocument document) throws IOException {
            Objects.requireNonNull(document, "document");
            try {
                documents = Math.addExact(documents, 1);
                contentBytes = Math.addExact(
                        contentBytes,
                        document.content().getBytes(StandardCharsets.UTF_8).length);
                // SemanticVector retains a Java double[] in heap even though the local persistence
                // format compacts values to float32. Construction budgets must therefore account
                // for the representation that is actually live in memory.
                vectorBytes = Math.addExact(
                        vectorBytes,
                        Math.multiplyExact((long) dimensions, Double.BYTES));
            } catch (ArithmeticException exception) {
                throw new IOException("semantic index budget counter overflow", exception);
            }
            if (documents > budget.maxDocuments()) {
                throw new IOException("semantic document count exceeds budget: " + documents
                        + "/" + budget.maxDocuments());
            }
            if (contentBytes > budget.maxContentBytes()) {
                throw new IOException("semantic document content exceeds byte budget: " + contentBytes
                        + "/" + budget.maxContentBytes());
            }
            if (vectorBytes > budget.maxVectorBytes()) {
                throw new IOException("semantic vectors exceed heap byte budget: " + vectorBytes
                        + "/" + budget.maxVectorBytes());
            }
        }

        public int documents() { return documents; }
        public long contentBytes() { return contentBytes; }
        public long vectorBytes() { return vectorBytes; }
    }
}

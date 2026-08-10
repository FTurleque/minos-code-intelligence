package com.minos.adapter.scip;

import com.minos.orchestration.IndexArtifactLimits;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.SymbolInformation;

import java.io.IOException;
import java.util.Objects;

/** End-to-end safety limits for local and distributed SCIP ingestion. */
public record ScipIngestionLimits(
        long maxArtifactBytes,
        long maxDocuments,
        long maxSymbols,
        long maxOccurrences,
        long maxRelationshipFacts
) {
    public static final ScipIngestionLimits DEFAULT = new ScipIngestionLimits(
            IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES,
            250_000L,
            2_000_000L,
            10_000_000L,
            10_000_000L
    );

    public ScipIngestionLimits {
        if (maxArtifactBytes < 1L || maxArtifactBytes > Integer.MAX_VALUE
                || maxDocuments < 1L || maxSymbols < 1L
                || maxOccurrences < 1L || maxRelationshipFacts < 1L) {
            throw new IllegalArgumentException("SCIP ingestion limits must be positive and artifact size must fit protobuf limits");
        }
    }

    public void validate(Index index) throws IOException {
        Objects.requireNonNull(index, "index");
        long documents = index.getDocumentsCount();
        if (documents > maxDocuments) fail("documents", documents, maxDocuments);

        long symbols = index.getExternalSymbolsCount();
        long occurrences = 0L;
        long relationshipFacts = relationshipFacts(index.getExternalSymbolsList());
        for (Document document : index.getDocumentsList()) {
            symbols = add(symbols, document.getSymbolsCount(), "symbols");
            occurrences = add(occurrences, document.getOccurrencesCount(), "occurrences");
            relationshipFacts = add(relationshipFacts, relationshipFacts(document.getSymbolsList()), "relationship facts");
            if (symbols > maxSymbols) fail("symbols", symbols, maxSymbols);
            if (occurrences > maxOccurrences) fail("occurrences", occurrences, maxOccurrences);
            if (relationshipFacts > maxRelationshipFacts) {
                fail("relationship facts", relationshipFacts, maxRelationshipFacts);
            }
        }
    }

    private static long relationshipFacts(Iterable<SymbolInformation> symbols) throws IOException {
        long count = 0L;
        for (SymbolInformation symbol : symbols) {
            count = add(count, symbol.getRelationshipsCount(), "relationship facts");
        }
        return count;
    }

    private static long add(long left, long right, String label) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException("SCIP " + label + " counter overflow", exception);
        }
    }

    private static void fail(String label, long actual, long maximum) throws IOException {
        throw new IOException("SCIP index exceeds " + label + " limit: " + actual + "/" + maximum);
    }
}

package com.minos.adapter.scip;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import com.minos.orchestration.IndexArtifactLimits;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.SymbolInformation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** End-to-end safety limits for local and distributed SCIP ingestion. */
public record ScipIngestionLimits(
        long maxArtifactBytes,
        long maxDocuments,
        long maxSymbols,
        long maxOccurrences,
        long maxRelationshipFacts
) {
    private static final long MAX_NESTED_MESSAGE_BYTES = 768L * 1024L * 1024L;
    private static final long MAX_DECODE_HEAP_BUDGET_BYTES = 1024L * 1024L * 1024L;
    private static final long DECODE_BASE_OVERHEAD_BYTES = 16L * 1024L * 1024L;
    private static final long DOCUMENT_HEAP_BYTES = 256L;
    private static final long SYMBOL_HEAP_BYTES = 384L;
    private static final long OCCURRENCE_HEAP_BYTES = 96L;
    private static final long RELATIONSHIP_HEAP_BYTES = 64L;

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

    /**
     * Streams the protobuf wire format without materializing the full index and returns the
     * cardinalities used by the decode-memory gate.
     */
    public PreflightMetrics preflight(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        CodedInputStream input = CodedInputStream.newInstance(source);
        input.setSizeLimit((int) Math.min(Integer.MAX_VALUE, maxArtifactBytes));
        Counters counters = new Counters();
        scanIndex(input, counters);
        return counters.snapshot();
    }

    /**
     * Refuses a full protobuf decode when its conservative object-graph estimate would consume too
     * much of the JVM heap. The parser remains bounded by the encoded artifact limit as well; this
     * second gate specifically addresses encoded-to-object amplification.
     */
    void enforceDecodeHeapBudget(
            PreflightMetrics metrics,
            long artifactBytes,
            long maxHeapBytes
    ) throws IOException {
        Objects.requireNonNull(metrics, "metrics");
        if (artifactBytes < 1L || maxHeapBytes < 1L) {
            throw new IllegalArgumentException("SCIP decode budget inputs must be positive");
        }
        long budget = decodeHeapBudget(maxHeapBytes);
        long estimate = estimatedDecodedHeapBytes(metrics, artifactBytes);
        if (estimate > budget) {
            throw new IOException("SCIP index exceeds safe decode heap budget: estimated=" + estimate
                    + "/" + budget + " bytes; artifact=" + artifactBytes
                    + ", documents=" + metrics.documents()
                    + ", symbols=" + metrics.symbols()
                    + ", occurrences=" + metrics.occurrences()
                    + ", relationships=" + metrics.relationshipFacts());
        }
    }

    static long decodeHeapBudget(long maxHeapBytes) {
        if (maxHeapBytes < 1L) throw new IllegalArgumentException("maxHeapBytes must be positive");
        return Math.min(MAX_DECODE_HEAP_BUDGET_BYTES, Math.max(1L, maxHeapBytes / 3L));
    }

    static long estimatedDecodedHeapBytes(PreflightMetrics metrics, long artifactBytes) {
        Objects.requireNonNull(metrics, "metrics");
        if (artifactBytes < 0L) throw new IllegalArgumentException("artifactBytes must not be negative");
        long estimate = DECODE_BASE_OVERHEAD_BYTES;
        estimate = saturatingAdd(estimate, saturatingMultiply(artifactBytes, 2L));
        estimate = saturatingAdd(estimate, saturatingMultiply(metrics.documents(), DOCUMENT_HEAP_BYTES));
        estimate = saturatingAdd(estimate, saturatingMultiply(metrics.symbols(), SYMBOL_HEAP_BYTES));
        estimate = saturatingAdd(estimate, saturatingMultiply(metrics.occurrences(), OCCURRENCE_HEAP_BYTES));
        estimate = saturatingAdd(estimate,
                saturatingMultiply(metrics.relationshipFacts(), RELATIONSHIP_HEAP_BYTES));
        return estimate;
    }

    private void scanIndex(CodedInputStream input, Counters counters) throws IOException {
        int tag;
        while ((tag = input.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && field == Index.DOCUMENTS_FIELD_NUMBER) {
                counters.documents = increment(counters.documents, maxDocuments, "documents");
                scanNested(input, counters, nested -> scanDocument(nested, counters));
            } else if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && field == Index.EXTERNAL_SYMBOLS_FIELD_NUMBER) {
                counters.symbols = increment(counters.symbols, maxSymbols, "symbols");
                scanNested(input, counters, nested -> scanSymbolInformation(nested, counters));
            } else if (!input.skipField(tag)) {
                return;
            }
        }
    }

    private void scanDocument(CodedInputStream input, Counters counters) throws IOException {
        int tag;
        while ((tag = input.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            int wire = WireFormat.getTagWireType(tag);
            if (wire == WireFormat.WIRETYPE_LENGTH_DELIMITED && field == Document.SYMBOLS_FIELD_NUMBER) {
                counters.symbols = increment(counters.symbols, maxSymbols, "symbols");
                scanNested(input, counters, nested -> scanSymbolInformation(nested, counters));
            } else if (wire == WireFormat.WIRETYPE_LENGTH_DELIMITED && field == Document.OCCURRENCES_FIELD_NUMBER) {
                counters.occurrences = increment(counters.occurrences, maxOccurrences, "occurrences");
                skipNested(input, counters);
            } else if (!input.skipField(tag)) {
                return;
            }
        }
    }

    private void scanSymbolInformation(CodedInputStream input, Counters counters) throws IOException {
        int tag;
        while ((tag = input.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && field == SymbolInformation.RELATIONSHIPS_FIELD_NUMBER) {
                counters.relationshipFacts = increment(
                        counters.relationshipFacts, maxRelationshipFacts, "relationship facts");
                skipNested(input, counters);
            } else if (!input.skipField(tag)) {
                return;
            }
        }
    }

    private static void scanNested(CodedInputStream input, Counters counters, NestedScanner scanner) throws IOException {
        int length = input.readRawVarint32();
        accountNestedBytes(counters, length);
        int previous = input.pushLimit(length);
        try {
            scanner.scan(input);
            if (!input.isAtEnd()) throw new IOException("SCIP nested protobuf message was not fully consumed");
        } finally {
            input.popLimit(previous);
        }
    }

    private static void skipNested(CodedInputStream input, Counters counters) throws IOException {
        int length = input.readRawVarint32();
        accountNestedBytes(counters, length);
        input.skipRawBytes(length);
    }

    private static void accountNestedBytes(Counters counters, int length) throws IOException {
        if (length < 0) throw new IOException("SCIP protobuf contains a negative message length");
        counters.nestedMessageBytes = add(counters.nestedMessageBytes, length, "nested message bytes");
        if (counters.nestedMessageBytes > MAX_NESTED_MESSAGE_BYTES) {
            fail("nested message bytes", counters.nestedMessageBytes, MAX_NESTED_MESSAGE_BYTES);
        }
    }

    private static long increment(long value, long maximum, String label) throws IOException {
        long next = add(value, 1L, label);
        if (next > maximum) fail(label, next, maximum);
        return next;
    }

    @FunctionalInterface
    private interface NestedScanner {
        void scan(CodedInputStream input) throws IOException;
    }

    private static final class Counters {
        private long documents;
        private long symbols;
        private long occurrences;
        private long relationshipFacts;
        private long nestedMessageBytes;

        private PreflightMetrics snapshot() {
            return new PreflightMetrics(
                    documents,
                    symbols,
                    occurrences,
                    relationshipFacts,
                    nestedMessageBytes);
        }
    }

    public record PreflightMetrics(
            long documents,
            long symbols,
            long occurrences,
            long relationshipFacts,
            long nestedMessageBytes
    ) {
        public PreflightMetrics {
            if (documents < 0L || symbols < 0L || occurrences < 0L
                    || relationshipFacts < 0L || nestedMessageBytes < 0L) {
                throw new IllegalArgumentException("SCIP preflight metrics must not be negative");
            }
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

    private static long saturatingAdd(long left, long right) {
        if (left < 0L || right < 0L) throw new IllegalArgumentException("heap estimate values must not be negative");
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value < 0L || multiplier < 0L) throw new IllegalArgumentException("heap estimate values must not be negative");
        if (value == 0L || multiplier == 0L) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static void fail(String label, long actual, long maximum) throws IOException {
        throw new IOException("SCIP index exceeds " + label + " limit: " + actual + "/" + maximum);
    }
}

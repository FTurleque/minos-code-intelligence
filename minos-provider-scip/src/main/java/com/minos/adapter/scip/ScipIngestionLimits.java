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

    public void preflight(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        CodedInputStream input = CodedInputStream.newInstance(source);
        input.setSizeLimit((int) Math.min(Integer.MAX_VALUE, maxArtifactBytes));
        Counters counters = new Counters();
        scanIndex(input, counters);
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

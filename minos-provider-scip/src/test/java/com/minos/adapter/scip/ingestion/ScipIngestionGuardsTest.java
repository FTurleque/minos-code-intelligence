package com.minos.adapter.scip.ingestion;

import com.minos.domain.SymbolKind;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.Relationship;
import org.scip_code.scip.SymbolInformation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guards of SCIP ingestion at their boundaries: every limit refuses exactly one unit past its maximum, a
 * project-relative root can never leave the project, and every provider kind maps to one MINOS kind.
 */
class ScipIngestionGuardsTest {

    private static SymbolInformation symbolWithRelationships(int relationships) {
        SymbolInformation.Builder symbol = SymbolInformation.newBuilder().setSymbol("s");
        for (int i = 0; i < relationships; i++) {
            symbol.addRelationships(Relationship.newBuilder().setSymbol("r" + i));
        }
        return symbol.build();
    }

    /** Two documents, three document symbols, one external symbol, four occurrences, three relationship facts. */
    private static Index sampleIndex() {
        return Index.newBuilder()
                .addDocuments(Document.newBuilder().setRelativePath("A.java")
                        .addSymbols(symbolWithRelationships(2))
                        .addSymbols(symbolWithRelationships(0))
                        .addOccurrences(Occurrence.newBuilder())
                        .addOccurrences(Occurrence.newBuilder())
                        .addOccurrences(Occurrence.newBuilder()))
                .addDocuments(Document.newBuilder().setRelativePath("B.java")
                        .addSymbols(symbolWithRelationships(0))
                        .addOccurrences(Occurrence.newBuilder()))
                .addExternalSymbols(symbolWithRelationships(1))
                .build();
    }

    private static ScipIngestionLimits limits(long documents, long symbols, long occurrences, long relationships) {
        return new ScipIngestionLimits(1024 * 1024, documents, symbols, occurrences, relationships);
    }

    // ------------------------------------------------------------------ limits: construction

    @Test
    void everyLimitMustBePositiveAndTheArtifactSizeMustFitProtobuf() {
        long tooLarge = Integer.MAX_VALUE + 1L;
        for (long[] invalid : new long[][] {
                {0, 1, 1, 1, 1}, {tooLarge, 1, 1, 1, 1}, {1, 0, 1, 1, 1}, {1, 1, 0, 1, 1}, {1, 1, 1, 0, 1}, {1, 1, 1, 1, 0}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ScipIngestionLimits(invalid[0], invalid[1], invalid[2], invalid[3], invalid[4]),
                    java.util.Arrays.toString(invalid));
        }
        assertDoesNotThrow(() -> new ScipIngestionLimits(Integer.MAX_VALUE, 1, 1, 1, 1));
    }

    @Test
    void preflightMetricsAreNeverNegative() {
        for (long[] invalid : new long[][] {{-1, 0, 0, 0, 0}, {0, -1, 0, 0, 0}, {0, 0, -1, 0, 0}, {0, 0, 0, -1, 0}, {0, 0, 0, 0, -1}}) {
            assertThrows(IllegalArgumentException.class, () -> new ScipIngestionLimits.PreflightMetrics(
                    invalid[0], invalid[1], invalid[2], invalid[3], invalid[4]));
        }
    }

    // ------------------------------------------------------------------ limits: preflight on the wire format

    @Test
    void thePreflightCountsEveryCardinalityWithoutDecodingTheIndex() throws Exception {
        ScipIngestionLimits.PreflightMetrics metrics = limits(10, 10, 10, 10)
                .preflight(new ByteArrayInputStream(sampleIndex().toByteArray()));

        assertEquals(2, metrics.documents());
        assertEquals(4, metrics.symbols(), "document symbols and external symbols are both counted");
        assertEquals(4, metrics.occurrences());
        assertEquals(3, metrics.relationshipFacts());
        assertTrue(metrics.nestedMessageBytes() > 0);
    }

    @Test
    void thePreflightRefusesOneUnitPastEachLimit() {
        byte[] encoded = sampleIndex().toByteArray();
        Map<String, ScipIngestionLimits> pastTheLimit = Map.of(
                "documents", limits(1, 10, 10, 10),
                "symbols", limits(10, 3, 10, 10),
                "occurrences", limits(10, 10, 3, 10),
                "relationship facts", limits(10, 10, 10, 2));

        pastTheLimit.forEach((label, limit) -> {
            IOException refusal = assertThrows(IOException.class,
                    () -> limit.preflight(new ByteArrayInputStream(encoded)), label);
            assertTrue(refusal.getMessage().contains("exceeds " + label + " limit"), refusal.getMessage());
        });
        assertDoesNotThrow(() -> limits(2, 4, 4, 3).preflight(new ByteArrayInputStream(encoded)));
    }

    @Test
    void thePreflightRequiresASource() {
        assertThrows(NullPointerException.class, () -> ScipIngestionLimits.DEFAULT.preflight(null));
    }

    // ------------------------------------------------------------------ limits: validation of a decoded index

    @Test
    void validationRefusesOneUnitPastEachLimitAndAcceptsTheExactBoundary() {
        Index index = sampleIndex();
        Map<String, ScipIngestionLimits> pastTheLimit = Map.of(
                "documents", limits(1, 10, 10, 10),
                "symbols", limits(10, 3, 10, 10),
                "occurrences", limits(10, 10, 3, 10),
                "relationship facts", limits(10, 10, 10, 2));

        pastTheLimit.forEach((label, limit) -> {
            IOException refusal = assertThrows(IOException.class, () -> limit.validate(index), label);
            assertTrue(refusal.getMessage().contains("exceeds " + label + " limit"), refusal.getMessage());
        });
        assertDoesNotThrow(() -> limits(2, 4, 4, 3).validate(index));
        assertThrows(NullPointerException.class, () -> ScipIngestionLimits.DEFAULT.validate(null));
    }

    // ------------------------------------------------------------------ limits: decode heap budget

    @Test
    void theDecodeHeapBudgetIsAThirdOfTheHeapWithinItsBounds() {
        assertEquals(1L, ScipIngestionLimits.decodeHeapBudget(1L), "never below one byte");
        assertEquals(100L, ScipIngestionLimits.decodeHeapBudget(300L));
        assertEquals(1024L * 1024L * 1024L, ScipIngestionLimits.decodeHeapBudget(Long.MAX_VALUE), "capped at one gibibyte");
        assertThrows(IllegalArgumentException.class, () -> ScipIngestionLimits.decodeHeapBudget(0L));
    }

    @Test
    void theHeapEstimateSaturatesInsteadOfOverflowing() {
        ScipIngestionLimits.PreflightMetrics enormous = new ScipIngestionLimits.PreflightMetrics(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0L);
        ScipIngestionLimits.PreflightMetrics empty = new ScipIngestionLimits.PreflightMetrics(0L, 0L, 0L, 0L, 0L);

        assertEquals(Long.MAX_VALUE, ScipIngestionLimits.estimatedDecodedHeapBytes(enormous, Long.MAX_VALUE));
        assertEquals(16L * 1024L * 1024L, ScipIngestionLimits.estimatedDecodedHeapBytes(empty, 0L),
                "an empty index still costs the fixed decode overhead");
        assertThrows(IllegalArgumentException.class, () -> ScipIngestionLimits.estimatedDecodedHeapBytes(empty, -1L));
        assertThrows(NullPointerException.class, () -> ScipIngestionLimits.estimatedDecodedHeapBytes(null, 1L));
    }

    @Test
    void theDecodeBudgetCheckRefusesInvalidInputs() {
        ScipIngestionLimits.PreflightMetrics empty = new ScipIngestionLimits.PreflightMetrics(0L, 0L, 0L, 0L, 0L);

        assertThrows(IllegalArgumentException.class,
                () -> ScipIngestionLimits.DEFAULT.enforceDecodeHeapBudget(empty, 0L, 1024L));
        assertThrows(IllegalArgumentException.class,
                () -> ScipIngestionLimits.DEFAULT.enforceDecodeHeapBudget(empty, 1L, 0L));
        assertThrows(NullPointerException.class,
                () -> ScipIngestionLimits.DEFAULT.enforceDecodeHeapBudget(null, 1L, 1024L));
    }

    // ------------------------------------------------------------------ request: identity and relative root

    private static ScipIngestionRequest request(String root) {
        return new ScipIngestionRequest("project", " ", "scip-java", "", null, null, root);
    }

    @Test
    void blankOptionalFieldsBecomeAbsentAndAMissingFileMapIsEmpty() {
        ScipIngestionRequest request = request(null);

        assertNull(request.moduleId());
        assertNull(request.providerVersion());
        assertNull(request.indexRunId());
        assertEquals(Map.of(), request.fileIdsByRelativePath());
        assertNull(request.explicitFileId("A.java"));
        assertEquals("", request.projectRelativeRoot());
    }

    @Test
    void theRequestRequiresAProjectAndAProvider() {
        assertThrows(RuntimeException.class,
                () -> new ScipIngestionRequest(" ", null, "scip-java", null, null, Map.of()));
        assertThrows(RuntimeException.class,
                () -> new ScipIngestionRequest("project", null, "", null, null, Map.of()));
    }

    @Test
    void theFileMapIsCopiedAndAnswersExplicitIdentifiers() {
        Map<String, String> files = new java.util.HashMap<>(Map.of("A.java", "file-1"));
        ScipIngestionRequest request = new ScipIngestionRequest("project", "module", "scip-java", "1", "run", files);

        files.put("B.java", "file-2");

        assertEquals("file-1", request.explicitFileId("A.java"));
        assertNull(request.explicitFileId("B.java"), "a later change of the caller's map must not reach the request");
        assertEquals("module", request.moduleId());
    }

    @Test
    void theRelativeRootIsNormalisedToAPortableFormAndCannotLeaveTheProject() {
        assertEquals("", request(".").projectRelativeRoot());
        assertEquals("", request("  ").projectRelativeRoot());
        assertEquals("services/api", request("services\\api").projectRelativeRoot());
        assertEquals("services/api", request("services/./api/../api").projectRelativeRoot());
        assertThrows(IllegalArgumentException.class, () -> request("../outside"));
        // A path the platform itself calls absolute (a drive letter is needed on Windows, a leading slash elsewhere).
        String absolute = java.nio.file.Path.of("").toAbsolutePath().toString();
        assertThrows(IllegalArgumentException.class, () -> request(absolute));
    }

    @Test
    void aProviderPathIsPrefixedByTheRootAndAnEscapingPathIsLeftUntouched() {
        ScipIngestionRequest rooted = request("services/api");
        ScipIngestionRequest atProjectRoot = request("");

        assertEquals("services/api/src/A.java", rooted.projectRelativePath("src\\A.java"));
        assertEquals("src/A.java", atProjectRoot.projectRelativePath("src/./A.java"));
        assertEquals("../escape.java", rooted.projectRelativePath("../escape.java"),
                "a path the request cannot place is returned as the provider wrote it");
        assertNull(rooted.projectRelativePath(null));
        assertEquals(" ", rooted.projectRelativePath(" "));
    }

    // ------------------------------------------------------------------ provider kind to MINOS kind

    @Test
    void everyProviderKindMapsToOneMinosKind() {
        Map<SymbolKind, List<SymbolInformation.Kind>> expected = new EnumMap<>(SymbolKind.class);
        expected.put(SymbolKind.CLASS, List.of(SymbolInformation.Kind.Class, SymbolInformation.Kind.SingletonClass));
        expected.put(SymbolKind.INTERFACE, List.of(SymbolInformation.Kind.Interface));
        expected.put(SymbolKind.STRUCT, List.of(SymbolInformation.Kind.Struct));
        expected.put(SymbolKind.ENUM, List.of(SymbolInformation.Kind.Enum));
        expected.put(SymbolKind.TRAIT, List.of(SymbolInformation.Kind.Trait, SymbolInformation.Kind.Protocol,
                SymbolInformation.Kind.TypeClass, SymbolInformation.Kind.Mixin));
        expected.put(SymbolKind.METHOD, List.of(SymbolInformation.Kind.Method, SymbolInformation.Kind.AbstractMethod,
                SymbolInformation.Kind.StaticMethod, SymbolInformation.Kind.SingletonMethod,
                SymbolInformation.Kind.ProtocolMethod, SymbolInformation.Kind.PureVirtualMethod,
                SymbolInformation.Kind.TraitMethod, SymbolInformation.Kind.TypeClassMethod,
                SymbolInformation.Kind.MethodSpecification));
        expected.put(SymbolKind.CONSTRUCTOR, List.of(SymbolInformation.Kind.Constructor));
        expected.put(SymbolKind.FUNCTION, List.of(SymbolInformation.Kind.Function));
        expected.put(SymbolKind.FIELD, List.of(SymbolInformation.Kind.Field, SymbolInformation.Kind.StaticField,
                SymbolInformation.Kind.StaticDataMember));
        expected.put(SymbolKind.PROPERTY, List.of(SymbolInformation.Kind.Property, SymbolInformation.Kind.StaticProperty,
                SymbolInformation.Kind.Getter, SymbolInformation.Kind.Setter, SymbolInformation.Kind.Accessor));
        expected.put(SymbolKind.VARIABLE, List.of(SymbolInformation.Kind.Variable, SymbolInformation.Kind.StaticVariable,
                SymbolInformation.Kind.Value));
        expected.put(SymbolKind.TYPE_ALIAS, List.of(SymbolInformation.Kind.TypeAlias));
        expected.put(SymbolKind.NAMESPACE, List.of(SymbolInformation.Kind.Namespace));
        expected.put(SymbolKind.PACKAGE, List.of(SymbolInformation.Kind.Package, SymbolInformation.Kind.PackageObject));
        ScipSymbolKindMapper mapper = new ScipSymbolKindMapper();

        expected.forEach((minosKind, providerKinds) -> providerKinds.forEach(providerKind ->
                assertEquals(minosKind, mapper.map(providerKind), providerKind.name())));
        assertEquals(SymbolKind.OTHER, mapper.map((SymbolInformation.Kind) null));
        assertEquals(SymbolKind.OTHER, mapper.map(SymbolInformation.Kind.UnspecifiedKind));
        assertEquals(SymbolKind.OTHER, mapper.map(SymbolInformation.Kind.Macro), "a kind MINOS has no equivalent for");
    }
}

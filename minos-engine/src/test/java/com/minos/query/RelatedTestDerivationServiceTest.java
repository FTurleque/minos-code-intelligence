package com.minos.query;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelatedTestDerivationServiceTest {

    private final RelatedTestDerivationService service = new RelatedTestDerivationService();

    @Test
    void combinesDirectReferenceNamingNamespaceAndLocationDeterministically() {
        Symbol test = symbol(
                "test", "UserServiceTest", "com.acme.UserServiceTest",
                SymbolKind.CLASS, "src/test/java/com/acme/UserServiceTest.java");
        Symbol production = symbol(
                "production", "UserService", "com.acme.UserService",
                SymbolKind.CLASS, "src/main/java/com/acme/UserService.java");
        SymbolOccurrence reference = reference("reference", production, test.fileId(), 8);

        Relationship first = service.derive(
                List.of(test, production), List.of(reference), List.of()).getFirst();
        Relationship reversed = service.derive(
                List.of(production, test), List.of(reference), List.of()).getFirst();

        assertEquals(first, reversed);
        assertEquals(ref(test), first.source());
        assertEquals(ref(production), first.target());
        assertEquals(RelationshipKind.RELATED_TEST, first.kind());
        assertEquals(InformationNature.DERIVED, first.nature());
        assertEquals(ResolutionStatus.RESOLVED, first.resolutionStatus());
        assertEquals(0.887, first.confidence());
        assertEquals(Set.of(
                EvidenceType.DIRECT_REFERENCE,
                EvidenceType.NAMING_CONVENTION,
                EvidenceType.PACKAGE_PROXIMITY,
                EvidenceType.TEST_LOCATION
        ), evidenceTypes(first));
        assertEquals(OriginType.DERIVED_BY_MINOS, first.origin().sourceType());
    }

    @Test
    void derivesDirectCallFromATestMethodThroughItsUniqueContainer() {
        String testFile = "src/test/java/com/acme/ServiceTest.java";
        Symbol testClass = symbol(
                "test-class", "ServiceTest", "com.acme.ServiceTest",
                SymbolKind.CLASS, testFile);
        Symbol testMethod = symbol(
                "test-method", "runs", "com.acme.ServiceTest.runs",
                SymbolKind.METHOD, testFile);
        Symbol productionMethod = symbol(
                "production-method", "run", "com.acme.Service.run",
                SymbolKind.METHOD, "src/main/java/com/acme/Service.java");
        Relationship call = factual(
                "call", ref(testMethod), ref(productionMethod), RelationshipKind.CALLS);

        Relationship related = service.derive(
                List.of(testClass, testMethod, productionMethod), List.of(), List.of(call))
                .getFirst();

        assertEquals(ref(testClass), related.source());
        assertEquals(ref(productionMethod), related.target());
        assertEquals(InformationNature.DERIVED, related.nature());
        assertEquals(0.856, related.confidence());
        assertTrue(evidenceTypes(related).contains(EvidenceType.DIRECT_CALL));
    }

    @Test
    void keepsNamingOnlyCandidateHeuristicAndNeverUsesNamespaceAlone() {
        Symbol namedTest = symbol(
                "widget-spec", "WidgetSpec", "com.acme.WidgetSpec",
                SymbolKind.CLASS, "test/com/acme/WidgetSpec.java");
        Symbol widget = symbol(
                "widget", "Widget", "com.acme.Widget",
                SymbolKind.CLASS, "src/main/java/com/acme/Widget.java");
        Symbol unrelatedTest = symbol(
                "helper-spec", "HelperSpec", "com.acme.HelperSpec",
                SymbolKind.CLASS, "test/com/acme/HelperSpec.java");

        List<Relationship> related = service.derive(
                List.of(namedTest, widget, unrelatedTest), List.of(), List.of());

        assertEquals(1, related.size());
        assertEquals(InformationNature.HEURISTIC, related.getFirst().nature());
        assertEquals(ResolutionStatus.HEURISTIC, related.getFirst().resolutionStatus());
        assertEquals(0.676, related.getFirst().confidence());
        assertEquals(Set.of(
                EvidenceType.NAMING_CONVENTION,
                EvidenceType.PACKAGE_PROXIMITY,
                EvidenceType.TEST_LOCATION
        ), evidenceTypes(related.getFirst()));
    }

    @Test
    void refusesToAttributeFileLevelReferenceWhenSeveralTestContainersExist() {
        String file = "test/multiple.spec.ts";
        Symbol firstTest = symbol(
                "first-test", "FirstSpec", "tests.FirstSpec", SymbolKind.CLASS, file);
        Symbol secondTest = symbol(
                "second-test", "SecondSpec", "tests.SecondSpec", SymbolKind.CLASS, file);
        Symbol production = symbol(
                "service", "Service", "main.Service", SymbolKind.CLASS, "src/service.ts");

        List<Relationship> related = service.derive(
                List.of(firstTest, secondTest, production),
                List.of(reference("reference", production, file, 4)),
                List.of()
        );

        assertTrue(related.isEmpty());
    }

    @Test
    void acceptsSingleUnspecifiedTypeScriptFunctionAsPrudentFileAnchor() {
        String file = "test/user-service.spec.ts";
        Symbol testFunction = symbol(
                "test-function", "findsExistingUser", "findsExistingUser",
                SymbolKind.OTHER, file);
        Symbol production = symbol(
                "user-service", "UserService", "UserService",
                SymbolKind.CLASS, "src/user-service.ts");

        Relationship related = service.derive(
                List.of(testFunction, production),
                List.of(reference("reference", production, file, 4)),
                List.of()
        ).getFirst();

        assertEquals(ref(testFunction), related.source());
        assertEquals(ref(production), related.target());
        assertEquals(InformationNature.DERIVED, related.nature());
        assertEquals(0.858, related.confidence());
    }

    /** MINOS-AUD-F02 : un test Go ({@code foo_test.go}) est reconnu comme test, sans répertoire de test ni suffixe {@code .test}. */
    @Test
    void recognisesAGoTestFileByItsSuffixConvention() {
        String file = "pkg/user_service_test.go";
        Symbol testFunction = symbol(
                "go-test", "TestFindsUser", "pkg.TestFindsUser", SymbolKind.FUNCTION, file);
        Symbol production = symbol(
                "go-service", "UserService", "pkg.UserService", SymbolKind.CLASS, "pkg/user_service.go");

        List<Relationship> related = service.derive(
                List.of(testFunction, production),
                List.of(reference("reference", production, file, 4)),
                List.of()
        );

        assertEquals(1, related.size());
        assertEquals(ref(testFunction), related.getFirst().source());
        assertEquals(ref(production), related.getFirst().target());
    }

    @Test
    void doesNotTreatAGoFileThatMerelyEndsWithTheWordTestAsATest() {
        String file = "pkg/contest.go";
        Symbol notATest = symbol(
                "contest", "TestFindsUser", "pkg.TestFindsUser", SymbolKind.FUNCTION, file);
        Symbol production = symbol(
                "go-service", "UserService", "pkg.UserService", SymbolKind.CLASS, "pkg/user_service.go");

        assertTrue(service.derive(
                List.of(notATest, production),
                List.of(reference("reference", production, file, 4)),
                List.of()
        ).isEmpty());
    }

    @Test
    void keepsFirstLocationsUpToTheCapAndDeclaresTheOmittedCount() {
        int cap = RelatedTestDerivationService.MAX_LOCATIONS_PER_EVIDENCE_TYPE;
        Symbol test = symbol("cap-test", "UserServiceTest", "com.acme.UserServiceTest",
                SymbolKind.CLASS, "src/test/java/com/acme/UserServiceTest.java");
        Symbol production = symbol("cap-production", "UserService", "com.acme.UserService",
                SymbolKind.CLASS, "src/main/java/com/acme/UserService.java");

        Relationship related = service.derive(List.of(test, production),
                references(production, test.fileId(), cap + 3, false), List.of()).getFirst();

        List<Evidence> direct = related.evidence().stream()
                .filter(evidence -> evidence.type() == EvidenceType.DIRECT_REFERENCE).toList();
        List<Evidence> located = direct.stream().filter(evidence -> evidence.location() != null).toList();
        assertEquals(cap, located.size());
        assertEquals(java.util.stream.IntStream.range(10, 10 + cap).boxed().toList(),
                located.stream().map(evidence -> evidence.location().startLine()).toList());
        List<Evidence> summaries = direct.stream().filter(evidence -> evidence.location() == null).toList();
        assertEquals(1, summaries.size());
        assertEquals("Resolved reference from test file to UserService: 3 additional locations omitted",
                summaries.getFirst().description());
        assertEquals(ref(test), summaries.getFirst().source());
        assertEquals(ref(production), summaries.getFirst().target());
    }

    @Test
    void keepsExactlyCapLocationsWithoutSummary() {
        int cap = RelatedTestDerivationService.MAX_LOCATIONS_PER_EVIDENCE_TYPE;
        Symbol test = symbol("exact-test", "UserServiceTest", "com.acme.UserServiceTest",
                SymbolKind.CLASS, "src/test/java/com/acme/UserServiceTest.java");
        Symbol production = symbol("exact-production", "UserService", "com.acme.UserService",
                SymbolKind.CLASS, "src/main/java/com/acme/UserService.java");

        Relationship related = service.derive(List.of(test, production),
                references(production, test.fileId(), cap, false), List.of()).getFirst();

        List<Evidence> direct = related.evidence().stream()
                .filter(evidence -> evidence.type() == EvidenceType.DIRECT_REFERENCE).toList();
        assertEquals(cap, direct.size());
        assertTrue(direct.stream().allMatch(evidence -> evidence.location() != null));
    }

    @Test
    void keepsTheSameLocationsWhateverTheOccurrenceOrder() {
        int cap = RelatedTestDerivationService.MAX_LOCATIONS_PER_EVIDENCE_TYPE;
        Symbol test = symbol("order-test", "UserServiceTest", "com.acme.UserServiceTest",
                SymbolKind.CLASS, "src/test/java/com/acme/UserServiceTest.java");
        Symbol production = symbol("order-production", "UserService", "com.acme.UserService",
                SymbolKind.CLASS, "src/main/java/com/acme/UserService.java");

        Relationship sorted = service.derive(List.of(test, production),
                references(production, test.fileId(), cap + 4, false), List.of()).getFirst();
        Relationship reversed = service.derive(List.of(production, test),
                references(production, test.fileId(), cap + 4, true), List.of()).getFirst();

        assertEquals(sorted, reversed);
    }

    @Test
    void capChangesNeitherPairsNorConfidencesNorOrder() {
        int cap = RelatedTestDerivationService.MAX_LOCATIONS_PER_EVIDENCE_TYPE;
        Symbol firstTest = symbol("pair-test-a", "AlphaTest", "com.acme.AlphaTest",
                SymbolKind.CLASS, "src/test/java/com/acme/AlphaTest.java");
        Symbol secondTest = symbol("pair-test-b", "BetaTest", "com.acme.BetaTest",
                SymbolKind.CLASS, "src/test/java/com/acme/BetaTest.java");
        Symbol alpha = symbol("pair-alpha", "Alpha", "com.acme.Alpha",
                SymbolKind.CLASS, "src/main/java/com/acme/Alpha.java");
        Symbol gamma = symbol("pair-gamma", "Gamma", "com.other.Gamma",
                SymbolKind.CLASS, "src/main/java/com/other/Gamma.java");
        List<Symbol> symbols = List.of(firstTest, secondTest, alpha, gamma);

        List<SymbolOccurrence> many = new java.util.ArrayList<>();
        many.addAll(references(alpha, firstTest.fileId(), cap + 7, false));
        many.addAll(references(gamma, firstTest.fileId(), cap + 2, false));
        many.addAll(references(gamma, secondTest.fileId(), cap + 1, false));
        List<SymbolOccurrence> single = List.of(
                reference("single-a", alpha, firstTest.fileId(), 10),
                reference("single-g1", gamma, firstTest.fileId(), 10),
                reference("single-g2", gamma, secondTest.fileId(), 10));

        assertEquals(pairsAndConfidences(service.derive(symbols, single, List.of())),
                pairsAndConfidences(service.derive(symbols, many, List.of())));
    }

    private static List<String> pairsAndConfidences(List<Relationship> relationships) {
        return relationships.stream()
                .map(relationship -> relationship.source().id() + "->" + relationship.target().id()
                        + "@" + relationship.confidence())
                .toList();
    }

    private static List<SymbolOccurrence> references(Symbol target, String fileId, int count, boolean reversed) {
        List<SymbolOccurrence> occurrences = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            occurrences.add(reference(target.id() + "-" + fileId + "-ref-" + index, target, fileId, 10 + index));
        }
        if (reversed) {
            java.util.Collections.reverse(occurrences);
        }
        return occurrences;
    }

    private static Set<EvidenceType> evidenceTypes(Relationship relationship) {
        return relationship.evidence().stream()
                .map(Evidence::type)
                .collect(Collectors.toSet());
    }

    private static Relationship factual(
            String id,
            CodeEntityRef source,
            CodeEntityRef target,
            RelationshipKind kind
    ) {
        return new Relationship(
                id, "project-1", source, target, null, kind, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null,
                origin(), List.of()
        );
    }

    private static SymbolOccurrence reference(
            String id,
            Symbol target,
            String fileId,
            int line
    ) {
        return new SymbolOccurrence(
                id,
                "project-1",
                new ResolvedSymbolReference(target.id()),
                location(fileId, line),
                Set.of(OccurrenceRole.REFERENCE),
                ResolutionStatus.RESOLVED,
                origin(),
                Set.of()
        );
    }

    private static Symbol symbol(
            String id,
            String name,
            String qualifiedName,
            SymbolKind kind,
            String fileId
    ) {
        return new Symbol(
                id,
                "key-" + id,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                "project-1",
                "main",
                fileId,
                null,
                kind,
                name,
                qualifiedName,
                null,
                "java",
                location(fileId, 1),
                ResolutionStatus.RESOLVED,
                origin(),
                false,
                false,
                Set.of()
        );
    }

    private static SymbolLocation location(String fileId, int line) {
        return new SymbolLocation(
                fileId, line, 0, line, 8, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static CodeEntityRef ref(Symbol symbol) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbol.id());
    }

    private static Origin origin() {
        return new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    }
}

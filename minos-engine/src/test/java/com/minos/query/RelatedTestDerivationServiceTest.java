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

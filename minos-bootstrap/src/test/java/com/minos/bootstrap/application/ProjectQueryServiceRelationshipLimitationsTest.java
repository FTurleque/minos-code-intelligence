package com.minos.bootstrap.application;

import com.minos.application.LocalProjectSymbolQuery;
import com.minos.application.ProjectQueryService;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
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
import com.minos.registry.RegisteredProject;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-F01 : les requêtes d'appelants, d'appelés et de dépendances déclarent ce qu'un snapshot ne leur permet pas
 * de voir ; les requêtes d'implémentations et de tests liés ne reçoivent rien. Sur de vrais stockages.
 */
class ProjectQueryServiceRelationshipLimitationsTest {
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    private static final List<String> NO_CALLS = List.of("CALL_RELATIONS_NOT_PRODUCED");
    private static final List<String> NOT_PROJECTED = List.of("OCCURRENCE_REFERENCES_NOT_PROJECTED");

    @TempDir
    Path root;

    @Test
    void callersAndCalleesDeclareTheAbsenceOfCallRelationsOnASnapshotWithoutAny() throws Exception {
        Fixture fixture = fixture(List.of(), List.of(referenceOccurrence()));

        assertEquals(NO_CALLS, fixture.service.relationshipLimitations("f01", Set.of(RelationshipKind.CALLS)));
        assertEquals(NO_CALLS, new LocalProjectSymbolQuery(fixture.registry, fixture.snapshots)
                .relationshipLimitations("f01", Set.of(RelationshipKind.CALLS)));
    }

    @Test
    void callersDeclareNothingOnASnapshotThatContainsACall() throws Exception {
        Fixture fixture = fixture(List.of(relationship(RelationshipKind.CALLS)), List.of(referenceOccurrence()));

        assertEquals(List.of(), fixture.service.relationshipLimitations("f01", Set.of(RelationshipKind.CALLS)));
    }

    @Test
    void dependenciesDeclareThatOccurrenceReferencesAreNotProjected() throws Exception {
        Fixture fixture = fixture(List.of(), List.of(referenceOccurrence()));

        assertEquals(NOT_PROJECTED, fixture.service.relationshipLimitations("f01", Set.of(RelationshipKind.DEPENDS_ON)));
        assertEquals(List.of(), fixture.service.relationshipLimitations("f01", Set.of(RelationshipKind.IMPLEMENTS)));
        assertEquals(List.of(), fixture.service.relationshipLimitations("f01", Set.of(RelationshipKind.RELATED_TEST)));
    }

    @Test
    void aSnapshotWithoutReferenceOccurrencesDeclaresNoDependencyLimitation() throws Exception {
        Fixture fixture = fixture(List.of(), List.of());

        assertEquals(List.of(), fixture.service.relationshipLimitations("f01", Set.of(RelationshipKind.DEPENDS_ON)));
        assertEquals(NO_CALLS, fixture.service.relationshipLimitations("f01", Set.of(RelationshipKind.CALLS)));
    }

    @Test
    void aProjectWithoutAnActiveSnapshotIsAnErrorNotAnEmptyAnswer() throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        registry.registerProject(Files.createDirectories(root.resolve("project")), "f01");
        ProjectQueryService service = new ProjectQueryService(registry, new FileSymbolSnapshotStore(root.resolve("snapshots")));

        assertThrows(IllegalStateException.class,
                () -> service.relationshipLimitations("f01", Set.of(RelationshipKind.CALLS)));
    }

    private Fixture fixture(List<Relationship> relationships, List<SymbolOccurrence> occurrences) throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(Files.createDirectories(root.resolve("project")), "f01");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(project.id(), "snapshot-f01", List.of(symbol(project, "a"), symbol(project, "b")),
                occurrences.stream().map(occurrence -> withProject(occurrence, project)).toList(),
                relationships.stream().map(relationship -> withProject(relationship, project)).toList());
        return new Fixture(registry, snapshots, new ProjectQueryService(registry, snapshots), project);
    }

    private record Fixture(LocalProjectRegistry registry, FileSymbolSnapshotStore snapshots, ProjectQueryService service,
                           RegisteredProject project) {
    }

    private static SymbolOccurrence referenceOccurrence() {
        return new SymbolOccurrence("occurrence-1", "placeholder", new ResolvedSymbolReference("a"),
                new SymbolLocation("src/B.java", 3, 0, 3, 4, PositionEncoding.UTF16_CODE_UNITS),
                Set.of(OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED, ORIGIN, Set.of());
    }

    private static SymbolOccurrence withProject(SymbolOccurrence occurrence, RegisteredProject project) {
        return new SymbolOccurrence(occurrence.id(), project.id().toString(), occurrence.symbolRef(), occurrence.location(),
                occurrence.roles(), occurrence.resolutionStatus(), occurrence.origin(), occurrence.providerReferences());
    }

    private static Relationship relationship(RelationshipKind kind) {
        return new Relationship("relationship-1", "placeholder", ref("b"), ref("a"), null, kind, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, ORIGIN, List.of());
    }

    private static Relationship withProject(Relationship relationship, RegisteredProject project) {
        return new Relationship(relationship.id(), project.id().toString(), relationship.source(), relationship.target(),
                null, relationship.kind(), null, relationship.resolutionStatus(), relationship.nature(), null,
                relationship.origin(), relationship.evidence());
    }

    private static CodeEntityRef ref(String symbolId) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbolId);
    }

    private static Symbol symbol(RegisteredProject project, String id) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.id().toString(), null,
                "src/" + id + ".java", null, SymbolKind.CLASS, id, "com.acme." + id, null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}

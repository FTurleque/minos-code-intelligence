package com.minos.nexus;

import com.minos.application.MinosApplication;
import com.minos.application.export.KnowledgeExportUseCase;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The service translates the application export into the NEXUS contract v1, field by field. */
class NexusExportServiceTest {

    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @TempDir
    Path temp;

    @Test
    void translatesTheExportIntoTheContract() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Greeting.java"), "class Greeting { }");
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            var project = application.projectRegistry().registerProject(root, "fixture");
            String projectId = project.id().toString();
            Symbol greeting = symbol(projectId, "a-greeting", "Greeting", "demo.Greeting", "class Greeting");
            Symbol helperSymbol = symbol(projectId, "b-helper", "helper", null, null);
            Relationship call = new Relationship("r-call", projectId, new CodeEntityRef(CodeEntityType.SYMBOL, greeting.id()),
                    new CodeEntityRef(CodeEntityType.SYMBOL, helperSymbol.id()), null, RelationshipKind.CALLS, null,
                    ResolutionStatus.RESOLVED, InformationNature.DERIVED, 0.5, ORIGIN,
                    List.of(new Evidence(EvidenceType.DIRECT_CALL, "call site", null, null, null, 0.75)));
            application.snapshotStore().publish(project.id(), "snapshot-1", List.of(helperSymbol, greeting), List.of(),
                    List.of(call));

            NexusExportContract.ExportSnapshot export = new NexusExportService(application).export(root);

            assertEquals(NexusExportContract.CONTRACT_VERSION, export.contractVersion());
            assertEquals(NexusExportContract.PRODUCER, export.producer());
            assertEquals(new NexusExportContract.ExportProject(projectId, "fixture", root.toString(), "snapshot-1"),
                    export.project());
            NexusExportContract.ExportOrigin origin = new NexusExportContract.ExportOrigin(
                    "fixture", "TEST", "1", "run-1", "OTHER");
            assertEquals(List.of(
                    new NexusExportContract.ExportSymbol("a-greeting", "key:a-greeting", "src/Greeting.java", "module",
                            "CLASS", "Greeting", "demo.Greeting", "class Greeting", "java", 1, 2, "RESOLVED",
                            "CANONICAL", false, origin),
                    new NexusExportContract.ExportSymbol("b-helper", "key:b-helper", "src/Greeting.java", "module",
                            "CLASS", "helper", "helper", "", "java", 1, 2, "RESOLVED", "CANONICAL", false, origin)),
                    export.symbols());
            assertEquals(List.of(new NexusExportContract.ExportRelation("r-call", "src/Greeting.java", "CALLS",
                    "a-greeting", "demo.Greeting", "b-helper", "helper", "RESOLVED", "DERIVED", 0.5, origin,
                    List.of(new NexusExportContract.ExportEvidence("DIRECT_CALL", "call site", 0.75)))),
                    export.relations());
            assertEquals(List.of(), export.limitations());
        }
    }

    @Test
    void anUnregisteredProjectRootIsRefusedByItsName() throws Exception {
        Path root = Files.createDirectories(temp.resolve("unregistered-project"));
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            NexusExportService service = new NexusExportService(application);

            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> service.export(root));

            assertEquals("project root is not registered in MINOS: unregistered-project", refusal.getMessage());
            assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
        }
    }

    @Test
    void theBoundsAreThoseOfTheUseCase() {
        assertEquals(KnowledgeExportUseCase.MAX_EXPORTED_SYMBOLS, NexusExportService.MAX_EXPORTED_SYMBOLS);
        assertEquals(KnowledgeExportUseCase.MAX_EXPORTED_RELATIONS, NexusExportService.MAX_EXPORTED_RELATIONS);
        assertEquals(KnowledgeExportUseCase.MAX_FILE_PATH_CANDIDATES, NexusExportService.MAX_FILE_PATH_CANDIDATES);
        assertEquals(KnowledgeExportUseCase.MAX_SYMBOL_SELECTION_WEIGHT_BYTES,
                NexusExportService.MAX_SYMBOL_SELECTION_WEIGHT_BYTES);
        assertEquals(KnowledgeExportUseCase.MAX_RELATION_SELECTION_WEIGHT_BYTES,
                NexusExportService.MAX_RELATION_SELECTION_WEIGHT_BYTES);
        assertThrows(NullPointerException.class, () -> new NexusExportService(null));
    }

    private static Symbol symbol(String projectId, String id, String name, String qualifiedName, String signature) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.CANONICAL, projectId, "module", "src/Greeting.java",
                null, SymbolKind.CLASS, name, qualifiedName, signature, "java",
                new SymbolLocation("src/Greeting.java", 1, 0, 2, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}

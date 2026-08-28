package com.minos.integration.nexus;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusExportContractTest {

    @Test
    void contractVersionAndProducerArePinned() {
        assertEquals("1", NexusExportContract.CONTRACT_VERSION);
        assertEquals("MINOS", NexusExportContract.PRODUCER);
    }

    @Test
    void exportedRecordComponentsDoNotDependOnNexusTypes() {
        Arrays.stream(NexusExportContract.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getGenericType)
                .forEach(type -> assertFalse(
                        type.getTypeName().contains("com.nexus"),
                        () -> "M13 transport leaks a NEXUS type through " + type.getTypeName()));
    }

    @Test
    void snapshotDefensivelyCopiesCollectionsAndNormalizesNullLists() {
        NexusExportContract.ExportProject project = project();
        List<NexusExportContract.ExportSymbol> symbols = new ArrayList<>();
        symbols.add(symbol());
        NexusExportContract.ExportSnapshot snapshot = new NexusExportContract.ExportSnapshot(
                NexusExportContract.CONTRACT_VERSION,
                NexusExportContract.PRODUCER,
                project,
                symbols,
                null,
                null
        );
        symbols.clear();

        assertEquals(1, snapshot.symbols().size());
        assertTrue(snapshot.relations().isEmpty());
        assertTrue(snapshot.limitations().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.symbols().clear());
    }

    @Test
    void projectOriginAndEvidenceValidateRequiredFieldsAndProbability() {
        assertThrows(IllegalArgumentException.class,
                () -> new NexusExportContract.ExportProject("", "name", "/root", "snapshot"));
        assertThrows(IllegalArgumentException.class,
                () -> new NexusExportContract.ExportOrigin("", "scip", "1", "run", "STATIC"));
        assertThrows(IllegalArgumentException.class,
                () -> new NexusExportContract.ExportOrigin("provider", "scip", "1", "run", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new NexusExportContract.ExportEvidence("FACT", "evidence", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new NexusExportContract.ExportEvidence("FACT", "evidence", -0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new NexusExportContract.ExportEvidence("FACT", "evidence", 1.01));
        assertEquals(0.5, new NexusExportContract.ExportEvidence("FACT", "evidence", 0.5).weight());
    }

    @Test
    void symbolValidatesLineRangeAndRequiredFields() {
        NexusExportContract.ExportOrigin origin = origin();
        assertThrows(IllegalArgumentException.class, () -> new NexusExportContract.ExportSymbol(
                "id", "key", "file.java", null, "CLASS", "Name", null, null, "java",
                0, 1, "RESOLVED", "CANONICAL", false, origin));
        assertThrows(IllegalArgumentException.class, () -> new NexusExportContract.ExportSymbol(
                "id", "key", "file.java", null, "CLASS", "Name", null, null, "java",
                2, 1, "RESOLVED", "CANONICAL", false, origin));
        assertThrows(IllegalArgumentException.class, () -> new NexusExportContract.ExportSymbol(
                "id", "", "file.java", null, "CLASS", "Name", null, null, "java",
                1, 1, "RESOLVED", "CANONICAL", false, origin));

        NexusExportContract.ExportSymbol symbol = symbol();
        assertEquals(1, symbol.startLine());
        assertEquals(2, symbol.endLine());
    }

    @Test
    void relationCopiesEvidenceAndRejectsInvalidConfidence() {
        NexusExportContract.ExportEvidence evidence = new NexusExportContract.ExportEvidence("FACT", "proof", 1.0);
        ArrayList<NexusExportContract.ExportEvidence> evidenceList = new ArrayList<>(List.of(evidence));
        NexusExportContract.ExportRelation relation = new NexusExportContract.ExportRelation(
                "rel", "file.java", "CALLS", "source", "a.Source", "target", "a.Target",
                "RESOLVED", "FACTUAL", 0.9, origin(), evidenceList
        );
        evidenceList.clear();

        assertEquals(1, relation.evidence().size());
        assertThrows(UnsupportedOperationException.class, () -> relation.evidence().clear());
        assertThrows(IllegalArgumentException.class, () -> new NexusExportContract.ExportRelation(
                "rel", "file.java", "CALLS", "source", "a.Source", "target", "a.Target",
                "RESOLVED", "FACTUAL", Double.POSITIVE_INFINITY, origin(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NexusExportContract.ExportRelation(
                "", "file.java", "CALLS", "source", "a.Source", "target", "a.Target",
                "RESOLVED", "FACTUAL", null, origin(), null));
    }

    private static NexusExportContract.ExportProject project() {
        return new NexusExportContract.ExportProject("project", "Project", "/repo", "snapshot");
    }

    private static NexusExportContract.ExportOrigin origin() {
        return new NexusExportContract.ExportOrigin("provider", "scip", "1", "run", "STATIC");
    }

    private static NexusExportContract.ExportSymbol symbol() {
        return new NexusExportContract.ExportSymbol(
                "symbol", "symbol-key", "src/File.java", "module", "CLASS", "File",
                "example.File", null, "java", 1, 2, "RESOLVED", "CANONICAL", false, origin());
    }
}

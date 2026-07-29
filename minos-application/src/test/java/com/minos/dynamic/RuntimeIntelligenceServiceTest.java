package com.minos.dynamic;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileRuntimeObservationStore;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeIntelligenceServiceTest {

    private static final Instant IMPORTED_AT = Instant.parse("2026-07-29T08:00:00Z");

    @Test
    void importsStrictPartialEvidenceAndReportsResolutionHotPathsAndSymbolFacts(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root);
        Path envelope = writeEnvelope(root.resolve("runtime.tsv"), fixture.project().id(), "snapshot-1", "run-1");
        RuntimeObservationEnvelopeCodec.DecodedSession decoded = new RuntimeObservationEnvelopeCodec().read(envelope);

        RuntimeIntelligenceService.ImportResult imported = fixture.service().importSession("runtime-fixture", decoded);
        RuntimeIntelligenceService.ImportResult idempotent = fixture.service().importSession("runtime-fixture", decoded);
        RuntimeIntelligenceService.RuntimeReport report = fixture.service().report("runtime-fixture", "run-1", 20);
        RuntimeIntelligenceService.SymbolRuntimeReport symbol = fixture.service()
                .symbolReport("runtime-fixture", "service", "run-1", 20);

        assertEquals("OBSERVED_PARTIAL", imported.nature());
        assertFalse(imported.exhaustive());
        assertEquals(4, imported.resolvedReferences());
        assertEquals(1, imported.unresolvedReferences());
        assertEquals(1, imported.ambiguousReferences());
        assertTrue(idempotent.alreadyPresent());
        assertEquals(IMPORTED_AT, idempotent.importedAt());

        assertEquals(4, report.staticSymbolCount());
        assertEquals(2, report.observedSymbolCount());
        assertEquals(0.5, report.observedSymbolRatio());
        assertEquals(1, report.coveredLineCount());
        assertEquals(15, report.totalHits());
        assertEquals(710, report.totalDurationNanos());
        assertFalse(report.exhaustive());
        assertTrue(report.limitations().stream().anyMatch(value -> value.contains("absence")));
        assertEquals("SYMBOL_EXECUTION", report.hotPaths().getFirst().type());

        assertTrue(symbol.observedInSelectedSessions());
        assertEquals(5, symbol.executionHits());
        assertEquals(500, symbol.totalDurationNanos());
        assertEquals(4, symbol.coveredLineHits());
        assertEquals(1, symbol.outgoingCalls().size());
        assertEquals(0, symbol.incomingCalls().size());
    }

    @Test
    void rejectsProjectAndSnapshotMisalignmentAndStaleSessionQueries(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        RuntimeObservationEnvelopeCodec codec = new RuntimeObservationEnvelopeCodec();
        Path wrongProject = writeEnvelope(root.resolve("wrong-project.tsv"), java.util.UUID.randomUUID(),
                "snapshot-1", "wrong-project");
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().importSession("runtime-fixture", codec.read(wrongProject)));

        Path wrongSnapshot = writeEnvelope(root.resolve("wrong-snapshot.tsv"), fixture.project().id(),
                "snapshot-old", "wrong-snapshot");
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().importSession("runtime-fixture", codec.read(wrongSnapshot)));

        Path accepted = writeEnvelope(root.resolve("accepted.tsv"), fixture.project().id(), "snapshot-1", "run-1");
        fixture.service().importSession("runtime-fixture", codec.read(accepted));
        fixture.snapshots().publish(fixture.project().id(), "snapshot-2", symbols(fixture.project()));
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> fixture.service().report("runtime-fixture", "run-1", 20));
        assertTrue(stale.getMessage().contains("non-active snapshot"));
    }

    @Test
    void codecFailsClosedOnBomTraversalUnknownKindsAndNonPartialCompleteness(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root);
        String valid = envelope(fixture.project().id(), "snapshot-1", "run-1");
        RuntimeObservationEnvelopeCodec codec = new RuntimeObservationEnvelopeCodec();

        Path bom = root.resolve("bom.tsv");
        Files.writeString(bom, "\ufeff" + valid, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> codec.read(bom));

        Path traversal = root.resolve("traversal.tsv");
        Files.writeString(traversal, valid.replace("src/Service.java", "../Service.java"), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> codec.read(traversal));

        Path unknown = root.resolve("unknown.tsv");
        Files.writeString(unknown, valid.replace("symbol\tkey:service", "sample\tkey:service"), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> codec.read(unknown));

        Path complete = root.resolve("complete.tsv");
        Files.writeString(complete, valid.replace("completeness\tPARTIAL", "completeness\tCOMPLETE"), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> codec.read(complete));
    }

    private static Fixture fixture(Path root) throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "runtime-fixture");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(project.id(), "snapshot-1", symbols(project));
        RuntimeIntelligenceService service = new RuntimeIntelligenceService(
                registry, snapshots, new FileRuntimeObservationStore(root.resolve("runtime")),
                Clock.fixed(IMPORTED_AT, ZoneOffset.UTC));
        return new Fixture(project, snapshots, service);
    }

    private static List<Symbol> symbols(RegisteredProject project) {
        return List.of(
                symbol(project, "service", "key:service", "com.acme.Service", "src/Service.java", 10, 30),
                symbol(project, "helper", "key:helper", "com.acme.Helper", "src/Helper.java", 20, 40),
                symbol(project, "duplicate-a", "key:duplicate-a", "com.acme.Duplicate", "src/A.java", 1, 5),
                symbol(project, "duplicate-b", "key:duplicate-b", "com.acme.Duplicate", "src/B.java", 1, 5));
    }

    private static Symbol symbol(
            RegisteredProject project, String id, String key, String qualifiedName,
            String file, int startLine, int endLine
    ) {
        return new Symbol(
                id, key, SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.id().toString(), "main", file,
                null, SymbolKind.CLASS, id, qualifiedName, null, "java",
                new SymbolLocation(file, startLine, 0, endLine, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    private static Path writeEnvelope(Path path, java.util.UUID projectId, String snapshotId, String sessionId)
            throws IOException {
        Files.writeString(path, envelope(projectId, snapshotId, sessionId), StandardCharsets.UTF_8);
        return path;
    }

    private static String envelope(java.util.UUID projectId, String snapshotId, String sessionId) {
        return String.join("\n",
                RuntimeObservationSession.FORMAT,
                "session\t" + sessionId,
                "project\t" + projectId,
                "snapshot\t" + snapshotId,
                "started\t2026-07-29T06:00:00Z",
                "ended\t2026-07-29T06:05:00Z",
                "collector\tfixture\t1.0.0",
                "environment\ttest",
                "completeness\tPARTIAL",
                "symbol\tkey:service\tcom.acme.Service\tsrc/Service.java\t10\t5\t500",
                "call\tkey:service\tcom.acme.Service\tsrc/Service.java\t10\tkey:helper\tcom.acme.Helper\tsrc/Helper.java\t20\t3\t200",
                "line\tsrc/Service.java\t12\t4",
                "symbol\t\tcom.acme.Duplicate\t\t\t2\t10",
                "symbol\t\tcom.acme.Missing\t\t\t1\t0",
                "");
    }

    private record Fixture(
            RegisteredProject project,
            FileSymbolSnapshotStore snapshots,
            RuntimeIntelligenceService service
    ) { }
}

package com.minos.adapter.scip.ingestion;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.io.CommitUncertainException;
import com.minos.io.Sha256;
import com.minos.orchestration.ScipSymbolSnapshotReport;
import com.minos.orchestration.ScipSymbolSnapshotRequest;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Metadata;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;
import org.scip_code.scip.ToolInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bridge between a SCIP artifact and the persistent snapshot: what it accepts, the file identifiers it derives,
 * and the artifact and commit failures it refuses to turn into a published snapshot.
 */
class ScipSymbolSnapshotImporterBehaviorTest {

    private static final String RAW_SERVICE = "scip-java maven fixture 1.0 io/example/Service#";

    @TempDir
    Path temp;

    private Path scratch() {
        return temp.resolve("scratch");
    }

    private ScipSymbolSnapshotImporter importer() {
        return new ScipSymbolSnapshotImporter(ScipIngestionLimits.DEFAULT, scratch());
    }

    private static Document document(String relativePath) {
        return Document.newBuilder()
                .setLanguage("java")
                .setRelativePath(relativePath)
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(SymbolInformation.newBuilder()
                        .setSymbol(RAW_SERVICE).setDisplayName("Service").setKind(SymbolInformation.Kind.Class))
                .addOccurrences(Occurrence.newBuilder()
                        .setSymbol(RAW_SERVICE)
                        .setSymbolRoles(SymbolRole.Definition_VALUE)
                        .setSingleLineRange(SingleLineRange.newBuilder().setLine(2).setStartCharacter(0).setEndCharacter(7)))
                .build();
    }

    private Path artifact(Document... documents) throws IOException {
        Index.Builder index = Index.newBuilder().setMetadata(Metadata.newBuilder()
                .setProjectRoot("file:///fixture")
                .setToolInfo(ToolInfo.newBuilder().setName("fixture").setVersion("1")));
        for (Document document : documents) index.addDocuments(document);
        Path file = temp.resolve("index.scip");
        try (OutputStream output = Files.newOutputStream(file)) {
            index.build().writeTo(output);
        }
        return file;
    }

    private static ScipSymbolSnapshotRequest request(UUID projectId, String snapshotId, String root,
                                                     Map<String, String> fileIds) {
        return new ScipSymbolSnapshotRequest(projectId, snapshotId, "main", "scip-java", "1", "run", fileIds, root);
    }

    private static List<String> occurrenceFileIds(CodeKnowledgeSnapshot snapshot) {
        return snapshot.occurrences().stream().map(occurrence -> occurrence.location().fileId()).sorted().toList();
    }

    private void assertScratchIsEmpty() throws IOException {
        try (Stream<Path> remaining = Files.list(scratch())) {
            assertEquals(List.of(), remaining.toList(), "the frozen copy of the artifact must be deleted");
        }
    }

    // ------------------------------------------------------------------ accepted artifacts

    @Test
    void importsTheArtifactUnderTheProjectRelativeRootAndDeletesItsFrozenCopy() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        Path file = artifact(document("src/Service.java"));

        ScipSymbolSnapshotReport report = importer().importSnapshot(
                file, request(projectId, "snapshot-1", "services/api", Map.of()), store);

        assertEquals(ScipSymbolSnapshotReport.CommitStatus.COMMITTED, report.commitStatus());
        assertEquals("snapshot-1", report.snapshotId());
        assertEquals(1, report.catalogSymbolCount());
        assertEquals(1, report.occurrenceCount());
        CodeKnowledgeSnapshot snapshot = store.loadActiveKnowledge(projectId).orElseThrow();
        assertEquals(List.of("services/api/src/Service.java"), occurrenceFileIds(snapshot));
        assertTrue(Files.isRegularFile(file), "the source artifact is never consumed");
        assertScratchIsEmpty();
    }

    @Test
    void importsAtTheProjectRootWithoutPrefix() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));

        importer().importSnapshot(artifact(document("src/Service.java")),
                request(projectId, "snapshot-1", "", Map.of()), store);

        assertEquals(List.of("src/Service.java"), occurrenceFileIds(store.loadActiveKnowledge(projectId).orElseThrow()));
    }

    @Test
    void anExplicitFileIdWinsOverTheDerivedOne() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));

        importer().importSnapshot(artifact(document("src/Service.java")),
                request(projectId, "snapshot-1", "", Map.of("src/Service.java", "file-explicit")), store);

        assertEquals(List.of("file-explicit"), occurrenceFileIds(store.loadActiveKnowledge(projectId).orElseThrow()));
    }

    @Test
    void aDocumentPathThatEscapesTheProjectNeverBecomesAFileIdentifier() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));

        importer().importSnapshot(artifact(document("../outside/Escape.java")),
                request(projectId, "snapshot-1", "", Map.of()), store);

        List<String> fileIds = occurrenceFileIds(store.loadActiveKnowledge(projectId).orElseThrow());
        assertEquals(1, fileIds.size());
        assertTrue(fileIds.get(0).startsWith("file:"), "an unsafe path falls back to an opaque hash: " + fileIds);
        assertFalse(fileIds.get(0).contains("outside"));
    }

    @Test
    void aSnapshotIdDerivedFromTheArtifactHashIsAcceptedWhenItMatches() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        Path file = artifact(document("src/Service.java"));
        String snapshotId = "scip-" + Sha256.hex(Files.readAllBytes(file)).substring(0, 24);

        ScipSymbolSnapshotReport report = importer().importSnapshot(
                file, request(projectId, snapshotId, "", Map.of()), store);

        assertEquals(snapshotId, report.snapshotId());
        assertEquals(snapshotId, store.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
    }

    // ------------------------------------------------------------------ refused artifacts

    @Test
    void anArtifactThatIsNotARegularFileIsRefusedAndNothingIsPublished() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        ScipSymbolSnapshotImporter importer = importer();
        ScipSymbolSnapshotRequest request = request(projectId, "snapshot-1", "", Map.of());
        Path folder = Files.createDirectories(temp.resolve("folder.scip"));
        Path missing = temp.resolve("missing.scip");

        assertThrows(IOException.class, () -> importer.importSnapshot(folder, request, store));
        IOException refusal = assertThrows(IOException.class, () -> importer.importSnapshot(missing, request, store));

        assertTrue(refusal.getMessage().contains("does not exist or is not a regular file"), refusal.getMessage());
        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
    }

    @Test
    void anEmptyArtifactIsRefusedAndNoFrozenCopyRemains() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        Path empty = Files.createFile(temp.resolve("empty.scip"));
        ScipSymbolSnapshotImporter importer = importer();
        ScipSymbolSnapshotRequest request = request(projectId, "snapshot-1", "", Map.of());

        IOException refusal = assertThrows(IOException.class, () -> importer.importSnapshot(empty, request, store));

        assertTrue(refusal.getMessage().contains("outside configured byte limit"), refusal.getMessage());
        assertScratchIsEmpty();
        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
    }

    @Test
    void anArtifactLargerThanTheConfiguredLimitIsRefused() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        Path file = artifact(document("src/Service.java"));
        ScipIngestionLimits tiny = new ScipIngestionLimits(8L, 10L, 10L, 10L, 10L);
        ScipSymbolSnapshotImporter importer = new ScipSymbolSnapshotImporter(tiny, scratch());
        ScipSymbolSnapshotRequest request = request(projectId, "snapshot-1", "", Map.of());

        assertThrows(IOException.class, () -> importer.importSnapshot(file, request, store));

        assertScratchIsEmpty();
        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
    }

    @Test
    void bytesThatAreNotAScipIndexAreRefusedAndNothingIsPublished() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        Path garbage = Files.write(temp.resolve("garbage.scip"), new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01});
        ScipSymbolSnapshotImporter importer = importer();
        ScipSymbolSnapshotRequest request = request(projectId, "snapshot-1", "", Map.of());

        assertThrows(IOException.class, () -> importer.importSnapshot(garbage, request, store));

        assertScratchIsEmpty();
        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
    }

    @Test
    void aScratchPathThatIsAFileIsRefused() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        Path file = artifact(document("src/Service.java"));
        Path blocker = Files.writeString(temp.resolve("scratch-is-a-file"), "x");
        ScipSymbolSnapshotImporter importer = new ScipSymbolSnapshotImporter(ScipIngestionLimits.DEFAULT, blocker);
        ScipSymbolSnapshotRequest request = request(projectId, "snapshot-1", "", Map.of());

        assertThrows(IOException.class, () -> importer.importSnapshot(file, request, store));

        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
    }

    @Test
    void theImporterRequiresItsCollaborators() throws Exception {
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        Path file = artifact(document("src/Service.java"));
        ScipSymbolSnapshotRequest request = request(UUID.randomUUID(), "snapshot-1", "", Map.of());
        ScipSymbolSnapshotImporter importer = importer();

        assertThrows(NullPointerException.class, () -> new ScipSymbolSnapshotImporter(null, scratch()));
        assertThrows(NullPointerException.class, () -> new ScipSymbolSnapshotImporter(ScipIngestionLimits.DEFAULT, null));
        assertThrows(NullPointerException.class, () -> importer.importSnapshot(null, request, store));
        assertThrows(NullPointerException.class, () -> importer.importSnapshot(file, null, store));
        assertThrows(NullPointerException.class, () -> importer.importSnapshot(file, request, null));
    }

    // ------------------------------------------------------------------ commit uncertainty

    @Test
    void anUncertainCommitWhoseTargetIsNotActiveIsNotReportedAsCommitted() throws Exception {
        Path file = artifact(document("src/Service.java"));
        ScriptedStore store = new ScriptedStore(false, false);

        CommitUncertainException failure = assertThrows(CommitUncertainException.class, () -> importer().importSnapshot(
                file, request(UUID.randomUUID(), "snapshot-1", "", Map.of()), store));

        assertEquals("synthetic uncertain publication", failure.getMessage());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void anUncertainCommitThatCannotBeObservedKeepsTheObservationFailureAsSuppressed() throws Exception {
        Path file = artifact(document("src/Service.java"));
        ScriptedStore store = new ScriptedStore(false, true);

        CommitUncertainException failure = assertThrows(CommitUncertainException.class, () -> importer().importSnapshot(
                file, request(UUID.randomUUID(), "snapshot-1", "", Map.of()), store));

        assertEquals(1, failure.getSuppressed().length);
        assertEquals("synthetic observation failure", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void anUncertainCommitWhoseTargetIsActiveIsReportedAsDurabilityPendingWithASanitizedDiagnostic() throws Exception {
        Path file = artifact(document("src/Service.java"));
        ScriptedStore store = new ScriptedStore(true, false);

        ScipSymbolSnapshotReport report = importer().importSnapshot(
                file, request(UUID.randomUUID(), "snapshot-1", "", Map.of()), store);

        assertEquals(ScipSymbolSnapshotReport.CommitStatus.COMMITTED_DURABILITY_PENDING, report.commitStatus());
        assertTrue(report.commitDiagnostic().contains("durable commit acknowledgement was lost"), report.commitDiagnostic());
        assertEquals("snapshot-1", store.active.orElseThrow().snapshotId());
    }

    /** A snapshot store whose publication is always uncertain; the target is active only when asked. */
    private static final class ScriptedStore implements CodeKnowledgeSnapshotStore {
        private final boolean activateOnPublish;
        private final boolean observationFails;
        private Optional<CodeKnowledgeSnapshot> active = Optional.empty();

        ScriptedStore(boolean activateOnPublish, boolean observationFails) {
            this.activateOnPublish = activateOnPublish;
            this.observationFails = observationFails;
        }

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("symbols-only publication is not used by the importer");
        }

        @Override
        public CodeKnowledgeSnapshot publish(
                UUID projectId, String snapshotId, Collection<Symbol> symbols,
                Collection<SymbolOccurrence> occurrences, Collection<Relationship> relationships
        ) throws IOException {
            if (activateOnPublish) {
                active = Optional.of(new CodeKnowledgeSnapshot(projectId, snapshotId, symbols.stream().toList(),
                        occurrences.stream().toList(), relationships.stream().toList()));
            }
            throw new CommitUncertainException("synthetic uncertain publication");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            return Optional.empty();
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
            if (observationFails) throw new IOException("synthetic observation failure");
            return active;
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return Optional.empty();
        }
    }
}

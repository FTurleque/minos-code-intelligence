package com.minos.application.dynamic;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservationSession;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrat de {@link RuntimeIntelligenceService} et de {@link RuntimeObservationEnvelopeCodec}, horloge figée.
 *
 * <p>Le service est construit par son constructeur à {@link Clock} (package-private) sur des doublures en
 * mémoire des ports d'engine, qui reproduisent la sémantique des adaptateurs fichiers : projet résolu par
 * nom, snapshot actif = dernier publié (symboles triés par identifiant), session immuable par identifiant
 * (même empreinte → déjà présente, sinon refus), sessions listées par date d'import décroissante puis
 * identifiant. La même intégration sur les adaptateurs fichiers réels est vérifiée dans minos-bootstrap
 * ({@code RuntimeIntelligenceFileAdaptersTest}).</p>
 */
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
        Path wrongProject = writeEnvelope(root.resolve("wrong-project.tsv"), UUID.randomUUID(),
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
        Files.writeString(bom, "﻿" + valid, StandardCharsets.UTF_8);
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
        InMemoryProjectRegistry registry = new InMemoryProjectRegistry();
        RegisteredProject project = registry.registerProject(projectRoot, "runtime-fixture");
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        snapshots.publish(project.id(), "snapshot-1", symbols(project));
        RuntimeIntelligenceService service = new RuntimeIntelligenceService(
                registry, snapshots, new InMemoryRuntimeObservationStore(),
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

    private static Path writeEnvelope(Path path, UUID projectId, String snapshotId, String sessionId)
            throws IOException {
        Files.writeString(path, envelope(projectId, snapshotId, sessionId), StandardCharsets.UTF_8);
        return path;
    }

    private static String envelope(UUID projectId, String snapshotId, String sessionId) {
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
            InMemorySnapshotStore snapshots,
            RuntimeIntelligenceService service
    ) { }

    /** Registre en mémoire : seuls l'enregistrement et la résolution d'un projet sont utilisés ici. */
    private static final class InMemoryProjectRegistry implements ProjectRegistry {
        private final Map<UUID, RegisteredProject> projects = new HashMap<>();

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            Instant now = Instant.parse("2026-07-29T07:00:00Z");
            RegisteredProject project = new RegisteredProject(
                    UUID.randomUUID(), rootPath, displayName, Optional.empty(), now, now);
            projects.put(project.id(), project);
            return project;
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public List<RegisteredProject> listProjects() {
            return List.copyOf(projects.values());
        }

        @Override
        public RegisteredWorkspace createWorkspace(String name) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public RegisteredProject removeProjectFromWorkspace(UUID projectId) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredWorkspace> listWorkspaces() {
            return List.of();
        }
    }

    /** Snapshots en mémoire : le dernier publié devient actif, symboles triés par identifiant. */
    private static final class InMemorySnapshotStore implements CodeKnowledgeSnapshotStore {
        private final Map<UUID, CodeKnowledgeSnapshot> active = new HashMap<>();

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            List<Symbol> ordered = symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList();
            active.put(projectId, new CodeKnowledgeSnapshot(projectId, snapshotId, ordered, List.of(), List.of()));
            return new SymbolSnapshot(projectId, snapshotId, ordered);
        }

        @Override
        public CodeKnowledgeSnapshot publish(
                UUID projectId, String snapshotId, Collection<Symbol> symbols,
                Collection<SymbolOccurrence> occurrences, Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("occurrences and relationships are not used by this test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            return loadActiveKnowledge(projectId).map(snapshot -> new SymbolSnapshot(
                    snapshot.projectId(), snapshot.snapshotId(), snapshot.symbols()));
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            return Optional.ofNullable(active.get(projectId));
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            throw new UnsupportedOperationException("query views are not used by this test");
        }
    }

    /** Observations en mémoire : session immuable par identifiant, listée par import décroissant. */
    private static final class InMemoryRuntimeObservationStore implements RuntimeObservationStore {
        private final List<CorrelatedRuntimeSession> sessions = new ArrayList<>();

        @Override
        public SaveResult save(CorrelatedRuntimeSession session) throws IOException {
            Optional<CorrelatedRuntimeSession> existing =
                    find(session.session().projectId(), session.session().sessionId());
            if (existing.isPresent()) {
                if (!existing.orElseThrow().sourceSha256().equals(session.sourceSha256())) {
                    throw new IOException("runtime session is immutable and already exists with different content: "
                            + session.session().sessionId());
                }
                return new SaveResult(existing.orElseThrow(), true);
            }
            sessions.add(session);
            return new SaveResult(session, false);
        }

        @Override
        public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) {
            return sessions.stream()
                    .filter(value -> value.session().projectId().equals(projectId)
                            && value.session().sessionId().equals(sessionId))
                    .findFirst();
        }

        @Override
        public List<CorrelatedRuntimeSession> list(UUID projectId) {
            return sessions.stream()
                    .filter(value -> value.session().projectId().equals(projectId))
                    .sorted(Comparator.comparing(CorrelatedRuntimeSession::importedAt).reversed()
                            .thenComparing(value -> value.session().sessionId()))
                    .toList();
        }
    }
}

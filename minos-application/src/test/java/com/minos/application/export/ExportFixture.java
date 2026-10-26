package com.minos.application.export;

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
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Registry and snapshot store doubles serving one registered project and its active snapshot. */
final class ExportFixture {

    static final String SNAPSHOT_ID = "snapshot-1";
    static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    final Path root;
    final RegisteredProject project;

    ExportFixture(Path workDirectory) throws IOException {
        this.root = Files.createDirectories(workDirectory.resolve("project")).toRealPath();
        Instant now = Instant.parse("2026-10-26T08:00:00Z");
        this.project = new RegisteredProject(UUID.randomUUID(), root, "demo", Optional.empty(), now, now);
    }

    String projectId() {
        return project.id().toString();
    }

    Path file(String relativePath) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "x");
    }

    KnowledgeExportUseCase useCase(List<Symbol> symbols, List<Relationship> relationships) {
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project.id(), SNAPSHOT_ID, symbols, List.of(),
                relationships);
        return new KnowledgeExportUseCase(registry(List.of(project)), store(Optional.of(snapshot)));
    }

    static ProjectRegistry registry(List<RegisteredProject> projects) {
        return (ProjectRegistry) Proxy.newProxyInstance(ProjectRegistry.class.getClassLoader(),
                new Class<?>[] {ProjectRegistry.class}, (proxy, method, args) -> {
                    if (method.getName().equals("listProjects")) return projects;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    static CodeKnowledgeSnapshotStore store(Optional<CodeKnowledgeSnapshot> active) {
        return (CodeKnowledgeSnapshotStore) Proxy.newProxyInstance(CodeKnowledgeSnapshotStore.class.getClassLoader(),
                new Class<?>[] {CodeKnowledgeSnapshotStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("loadActiveKnowledge")) return active;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    Symbol symbol(String id, String fileId) {
        return symbol(id, fileId, false);
    }

    Symbol symbol(String id, String fileId, boolean external) {
        SymbolLocation location = fileId == null ? null
                : new SymbolLocation(fileId, 1, 0, 2, 1, PositionEncoding.UTF16_CODE_UNITS);
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.CANONICAL, projectId(), "module", fileId, null,
                SymbolKind.CLASS, id, "demo." + id, null, "java", location, ResolutionStatus.RESOLVED, ORIGIN,
                external, false, Set.of());
    }

    Relationship call(String id, String sourceId, String targetId, String locationFileId) {
        SymbolLocation location = locationFileId == null ? null
                : new SymbolLocation(locationFileId, 3, 0, 3, 4, PositionEncoding.UTF16_CODE_UNITS);
        return new Relationship(id, projectId(), new CodeEntityRef(CodeEntityType.SYMBOL, sourceId),
                new CodeEntityRef(CodeEntityType.SYMBOL, targetId), null, RelationshipKind.CALLS, location,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, ORIGIN,
                List.of(new Evidence(EvidenceType.DIRECT_CALL, "call site", null, null, null, 1.0)));
    }

    Relationship fileImport(String id, String sourceId, String fileId) {
        return new Relationship(id, projectId(), new CodeEntityRef(CodeEntityType.SYMBOL, sourceId),
                new CodeEntityRef(CodeEntityType.SOURCE_FILE, fileId), null, RelationshipKind.IMPORTS, null,
                ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null, ORIGIN, List.of());
    }
}

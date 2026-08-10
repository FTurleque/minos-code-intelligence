package com.minos.integration.nexus;

import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolLocation;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.minos.integration.nexus.NexusExportContract.CONTRACT_VERSION;
import static com.minos.integration.nexus.NexusExportContract.PRODUCER;

/** Read-only M13 projection of an active MINOS knowledge snapshot for NEXUS. */
public final class NexusExportService {
    public static final int MAX_EXPORTED_SYMBOLS = 1_000_000;
    public static final int MAX_EXPORTED_RELATIONS = 1_000_000;
    public static final int MAX_FILE_PATH_CANDIDATES = 1_000_000;

    private final ProjectRegistry registry;
    private final CodeKnowledgeSnapshotStore snapshots;

    public NexusExportService(ProjectRegistry registry, CodeKnowledgeSnapshotStore snapshots) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public NexusExportContract.ExportSnapshot export(Path projectRoot) throws IOException {
        Path root = canonicalProjectRoot(projectRoot);
        RegisteredProject project = registry.listProjects().stream().filter(candidate -> candidate.rootPath().equals(root)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("project root is not registered in MINOS: " + root));
        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active MINOS knowledge snapshot: " + project.displayName()));
        Set<String> limitations = new LinkedHashSet<>();

        BoundedById<Symbol> symbolCandidates = selectSymbolCandidates(snapshot, limitations);
        BoundedById<Relationship> relationCandidates = selectRelationCandidates(
                snapshot, symbolCandidates.orderedMap().keySet(), limitations);
        Set<String> requiredFileIds = candidateFileIds(symbolCandidates.values(), relationCandidates.values());
        Map<String, String> pathByFileId = resolveFilePaths(
                root, project.id().toString(), requiredFileIds, limitations);

        Map<String, NexusExportContract.ExportSymbol> exportedById = exportSymbols(
                symbolCandidates.values(), pathByFileId, limitations);
        List<NexusExportContract.ExportRelation> relations = exportRelations(
                relationCandidates.values(), exportedById, pathByFileId, limitations);
        return new NexusExportContract.ExportSnapshot(CONTRACT_VERSION, PRODUCER,
                new NexusExportContract.ExportProject(project.id().toString(), project.displayName(), root.toString(), snapshot.snapshotId()),
                List.copyOf(exportedById.values()), relations, List.copyOf(limitations));
    }

    private static BoundedById<Symbol> selectSymbolCandidates(
            CodeKnowledgeSnapshot snapshot,
            Set<String> limitations
    ) {
        BoundedById<Symbol> selected = new BoundedById<>(MAX_EXPORTED_SYMBOLS);
        int omittedExternal = 0;
        int omittedLocation = 0;
        for (Symbol symbol : snapshot.symbols()) {
            if (symbol.external()) { omittedExternal++; continue; }
            if (symbol.location() == null) { omittedLocation++; continue; }
            selected.offer(symbol.id(), symbol);
        }
        if (selected.truncated()) limitations.add("SYMBOLS_TRUNCATED");
        if (omittedExternal > 0) limitations.add("EXTERNAL_SYMBOLS_OMITTED");
        if (omittedLocation > 0) limitations.add("SYMBOL_WITHOUT_LOCAL_LOCATION_OMITTED");
        return selected;
    }

    private static BoundedById<Relationship> selectRelationCandidates(
            CodeKnowledgeSnapshot snapshot,
            Set<String> candidateSymbolIds,
            Set<String> limitations
    ) {
        BoundedById<Relationship> selected = new BoundedById<>(MAX_EXPORTED_RELATIONS);
        int omittedNonSymbol = 0;
        int omittedNonLocal = 0;
        for (Relationship relationship : snapshot.relationships()) {
            if (relationship.source().type() != CodeEntityType.SYMBOL || relationship.target() == null
                    || relationship.target().type() != CodeEntityType.SYMBOL) {
                omittedNonSymbol++;
                continue;
            }
            if (!candidateSymbolIds.contains(relationship.source().id())
                    || !candidateSymbolIds.contains(relationship.target().id())) {
                omittedNonLocal++;
                continue;
            }
            selected.offer(relationship.id(), relationship);
        }
        if (selected.truncated()) limitations.add("RELATIONS_TRUNCATED");
        if (omittedNonSymbol > 0) limitations.add("NON_SYMBOL_RELATIONS_OMITTED");
        if (omittedNonLocal > 0) limitations.add("NON_LOCAL_RELATIONS_OMITTED");
        return selected;
    }

    private static Set<String> candidateFileIds(
            Collection<Symbol> symbols,
            Collection<Relationship> relationships
    ) {
        Set<String> required = new LinkedHashSet<>();
        for (Symbol symbol : symbols) {
            SymbolLocation location = symbol.location();
            if (location != null && location.fileId() != null) required.add(location.fileId());
        }
        for (Relationship relationship : relationships) {
            SymbolLocation location = relationship.location();
            if (location != null && location.fileId() != null) required.add(location.fileId());
        }
        return required;
    }

    private static Map<String, NexusExportContract.ExportSymbol> exportSymbols(
            Collection<Symbol> symbols,
            Map<String, String> pathByFileId,
            Set<String> limitations
    ) {
        TreeMap<String, NexusExportContract.ExportSymbol> exported = new TreeMap<>();
        int omittedPath = 0;
        for (Symbol symbol : symbols) {
            SymbolLocation location = symbol.location();
            String relativePath = pathByFileId.get(location.fileId());
            if (relativePath == null) { omittedPath++; continue; }
            exported.put(symbol.id(), new NexusExportContract.ExportSymbol(
                    symbol.id(), symbol.symbolKey(), relativePath,
                    symbol.moduleId(), symbol.kind().name(), symbol.name(), textOr(symbol.qualifiedName(), symbol.name()),
                    textOr(symbol.signature(), ""), symbol.language(), location.startLine(), location.endLine(),
                    symbol.resolutionStatus().name(), symbol.identityQuality().name(), symbol.generated(), origin(symbol.origin())));
        }
        if (omittedPath > 0) limitations.add("UNRESOLVED_SYMBOL_FILE_ID_OMITTED");
        return Collections.unmodifiableMap(exported);
    }

    private static List<NexusExportContract.ExportRelation> exportRelations(
            Collection<Relationship> relationships,
            Map<String, NexusExportContract.ExportSymbol> symbols,
            Map<String, String> pathByFileId,
            Set<String> limitations
    ) {
        TreeMap<String, NexusExportContract.ExportRelation> exported = new TreeMap<>();
        int omittedNonLocal = 0;
        int omittedPath = 0;
        for (Relationship relationship : relationships) {
            NexusExportContract.ExportSymbol source = symbols.get(relationship.source().id());
            NexusExportContract.ExportSymbol target = symbols.get(relationship.target().id());
            if (source == null || target == null) { omittedNonLocal++; continue; }
            String relativePath = relationship.location() == null
                    ? source.filePath()
                    : pathByFileId.get(relationship.location().fileId());
            if (relativePath == null) { omittedPath++; continue; }
            exported.put(relationship.id(), new NexusExportContract.ExportRelation(
                    relationship.id(), relativePath, relationship.kind().name(),
                    source.id(), source.qualifiedName(), target.id(), target.qualifiedName(), relationship.resolutionStatus().name(),
                    relationship.nature().name(), relationship.confidence(), origin(relationship.origin()),
                    relationship.evidence().stream().map(NexusExportService::evidence).toList()));
        }
        if (omittedNonLocal > 0) limitations.add("NON_LOCAL_RELATIONS_OMITTED");
        if (omittedPath > 0) limitations.add("UNRESOLVED_RELATION_FILE_ID_OMITTED");
        return List.copyOf(exported.values());
    }

    private static Map<String, String> resolveFilePaths(
            Path root,
            String projectId,
            Collection<String> requiredFileIds,
            Set<String> limitations
    ) throws IOException {
        Set<String> required = new LinkedHashSet<>(Objects.requireNonNull(requiredFileIds, "requiredFileIds"));
        Map<String, String> resolved = new HashMap<>();
        for (String fileId : required) {
            String direct = directRelativePath(root, fileId);
            if (direct != null) resolved.put(fileId, direct);
        }
        Set<String> unresolvedStableIds = required.stream().filter(fileId -> !resolved.containsKey(fileId))
                .filter(fileId -> fileId.startsWith("file:"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (unresolvedStableIds.isEmpty()) return Map.copyOf(resolved);
        int scanned = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext() && !unresolvedStableIds.isEmpty()) {
                if (scanned >= MAX_FILE_PATH_CANDIDATES) { limitations.add("FILE_PATH_DISCOVERY_TRUNCATED"); break; }
                Path file = iterator.next();
                scanned++;
                Path canonical = file.toRealPath();
                if (!canonical.startsWith(root)) continue;
                String relativePath = root.relativize(file).toString().replace('\\', '/');
                String stableId = stableFileId(projectId, relativePath);
                if (unresolvedStableIds.remove(stableId)) resolved.put(stableId, relativePath);
            }
        }
        if (!unresolvedStableIds.isEmpty()) limitations.add("UNRESOLVED_FILE_IDS");
        return Map.copyOf(resolved);
    }

    private static String directRelativePath(Path root, String fileId) {
        if (fileId == null || fileId.isBlank()) return null;
        try {
            Path raw = Path.of(fileId);
            Path resolved = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root) || !Files.isRegularFile(resolved)) return null;
            Path canonical = resolved.toRealPath();
            if (!canonical.startsWith(root)) return null;
            return root.relativize(resolved).toString().replace('\\', '/');
        } catch (InvalidPathException | IOException exception) {
            return null;
        }
    }

    private static String stableFileId(String projectId, String relativePath) {
        return "file:" + sha256(projectId + "\u001F" + relativePath);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static NexusExportContract.ExportOrigin origin(Origin origin) {
        return new NexusExportContract.ExportOrigin(origin.providerId(), origin.providerType(), origin.providerVersion(), origin.indexRunId(), origin.sourceType().name());
    }

    private static NexusExportContract.ExportEvidence evidence(Evidence evidence) {
        return new NexusExportContract.ExportEvidence(evidence.type().name(), evidence.description(), evidence.weight());
    }

    private static Path canonicalProjectRoot(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        return root;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static final class BoundedById<T> {
        private final int limit;
        private final TreeMap<String, T> retained = new TreeMap<>();
        private boolean truncated;

        BoundedById(int limit) {
            if (limit < 1) throw new IllegalArgumentException("limit must be positive");
            this.limit = limit;
        }

        void offer(String id, T value) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(value, "value");
            retained.put(id, value);
            if (retained.size() > limit) {
                retained.pollLastEntry();
                truncated = true;
            }
        }

        boolean truncated() {
            return truncated;
        }

        Map<String, T> orderedMap() {
            return Collections.unmodifiableMap(retained);
        }

        Collection<T> values() {
            return retained.values();
        }
    }
}

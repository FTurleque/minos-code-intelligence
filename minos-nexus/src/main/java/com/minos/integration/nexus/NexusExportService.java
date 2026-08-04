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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        Map<String, String> pathByFileId = resolveFilePaths(root, project.id().toString(), snapshot, limitations);
        Map<String, NexusExportContract.ExportSymbol> exportedById = exportSymbols(snapshot, pathByFileId, limitations);
        List<NexusExportContract.ExportRelation> relations = exportRelations(snapshot, exportedById, pathByFileId, limitations);
        return new NexusExportContract.ExportSnapshot(CONTRACT_VERSION, PRODUCER,
                new NexusExportContract.ExportProject(project.id().toString(), project.displayName(), root.toString(), snapshot.snapshotId()),
                List.copyOf(exportedById.values()), relations, List.copyOf(limitations));
    }

    private static Map<String, NexusExportContract.ExportSymbol> exportSymbols(CodeKnowledgeSnapshot snapshot,
                                                                                Map<String, String> pathByFileId,
                                                                                Set<String> limitations) {
        Map<String, NexusExportContract.ExportSymbol> exported = new LinkedHashMap<>();
        int omittedExternal = 0, omittedLocation = 0, omittedPath = 0;
        for (Symbol symbol : snapshot.symbols().stream().sorted(Comparator.comparing(Symbol::id)).toList()) {
            if (symbol.external()) { omittedExternal++; continue; }
            SymbolLocation location = symbol.location();
            if (location == null) { omittedLocation++; continue; }
            String relativePath = pathByFileId.get(location.fileId());
            if (relativePath == null) { omittedPath++; continue; }
            if (exported.size() >= MAX_EXPORTED_SYMBOLS) { limitations.add("SYMBOLS_TRUNCATED"); break; }
            exported.put(symbol.id(), new NexusExportContract.ExportSymbol(symbol.id(), symbol.symbolKey(), relativePath,
                    symbol.moduleId(), symbol.kind().name(), symbol.name(), textOr(symbol.qualifiedName(), symbol.name()),
                    textOr(symbol.signature(), ""), symbol.language(), location.startLine(), location.endLine(),
                    symbol.resolutionStatus().name(), symbol.identityQuality().name(), symbol.generated(), origin(symbol.origin())));
        }
        if (omittedExternal > 0) limitations.add("EXTERNAL_SYMBOLS_OMITTED");
        if (omittedLocation > 0) limitations.add("SYMBOL_WITHOUT_LOCAL_LOCATION_OMITTED");
        if (omittedPath > 0) limitations.add("UNRESOLVED_SYMBOL_FILE_ID_OMITTED");
        return exported;
    }

    private static List<NexusExportContract.ExportRelation> exportRelations(CodeKnowledgeSnapshot snapshot,
                                                                              Map<String, NexusExportContract.ExportSymbol> symbols,
                                                                              Map<String, String> pathByFileId,
                                                                              Set<String> limitations) {
        List<NexusExportContract.ExportRelation> exported = new ArrayList<>();
        int omittedNonSymbol = 0, omittedNonLocal = 0, omittedPath = 0;
        for (Relationship relationship : snapshot.relationships().stream().sorted(Comparator.comparing(Relationship::id)).toList()) {
            if (relationship.source().type() != CodeEntityType.SYMBOL || relationship.target() == null
                    || relationship.target().type() != CodeEntityType.SYMBOL) { omittedNonSymbol++; continue; }
            NexusExportContract.ExportSymbol source = symbols.get(relationship.source().id());
            NexusExportContract.ExportSymbol target = symbols.get(relationship.target().id());
            if (source == null || target == null) { omittedNonLocal++; continue; }
            String relativePath = relationship.location() == null ? source.filePath() : pathByFileId.get(relationship.location().fileId());
            if (relativePath == null) { omittedPath++; continue; }
            if (exported.size() >= MAX_EXPORTED_RELATIONS) { limitations.add("RELATIONS_TRUNCATED"); break; }
            exported.add(new NexusExportContract.ExportRelation(relationship.id(), relativePath, relationship.kind().name(),
                    source.id(), source.qualifiedName(), target.id(), target.qualifiedName(), relationship.resolutionStatus().name(),
                    relationship.nature().name(), relationship.confidence(), origin(relationship.origin()),
                    relationship.evidence().stream().map(NexusExportService::evidence).toList()));
        }
        if (omittedNonSymbol > 0) limitations.add("NON_SYMBOL_RELATIONS_OMITTED");
        if (omittedNonLocal > 0) limitations.add("NON_LOCAL_RELATIONS_OMITTED");
        if (omittedPath > 0) limitations.add("UNRESOLVED_RELATION_FILE_ID_OMITTED");
        return List.copyOf(exported);
    }

    private static Map<String, String> resolveFilePaths(Path root, String projectId, CodeKnowledgeSnapshot snapshot,
                                                        Set<String> limitations) throws IOException {
        Set<String> requiredFileIds = new LinkedHashSet<>();
        snapshot.symbols().stream().map(Symbol::location).filter(Objects::nonNull).map(SymbolLocation::fileId)
                .filter(Objects::nonNull).forEach(requiredFileIds::add);
        snapshot.relationships().stream().map(Relationship::location).filter(Objects::nonNull).map(SymbolLocation::fileId)
                .filter(Objects::nonNull).forEach(requiredFileIds::add);
        Map<String, String> resolved = new HashMap<>();
        for (String fileId : requiredFileIds) {
            String direct = directRelativePath(root, fileId);
            if (direct != null) resolved.put(fileId, direct);
        }
        Set<String> unresolvedStableIds = requiredFileIds.stream().filter(fileId -> !resolved.containsKey(fileId))
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
            Path raw = Path.of(fileId); Path resolved = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root) || !Files.isRegularFile(resolved)) return null;
            Path canonical = resolved.toRealPath();
            if (!canonical.startsWith(root)) return null;
            return root.relativize(resolved).toString().replace('\\', '/');
        } catch (InvalidPathException | IOException exception) { return null; }
    }

    private static String stableFileId(String projectId, String relativePath) { return "file:" + sha256(projectId + "\u001F" + relativePath); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    private static NexusExportContract.ExportOrigin origin(Origin origin) {
        return new NexusExportContract.ExportOrigin(origin.providerId(), origin.providerType(), origin.providerVersion(), origin.indexRunId(), origin.sourceType().name());
    }
    private static NexusExportContract.ExportEvidence evidence(Evidence evidence) {
        return new NexusExportContract.ExportEvidence(evidence.type().name(), evidence.description(), evidence.weight());
    }
    private static Path canonicalProjectRoot(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot"); Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        return root;
    }
    private static String textOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}

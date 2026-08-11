package com.minos.program.analysis;

import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolLocation;
import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedLineReader;
import com.minos.io.BoundedProperties;
import com.minos.io.FixedTsv;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Production M21 provider for explicit advanced-program facts stored beside the project. */
public final class FileProgramGraphProvider implements ProgramGraphProvider {

    public static final String PROVIDER_ID = "minos-program-sidecar-v1";
    public static final String RELATIVE_DIRECTORY = ".minos/program-graph-v1";
    public static final String METADATA_FILE = "metadata.properties";
    public static final String NODES_FILE = "nodes.tsv";
    public static final String EDGES_FILE = "edges.tsv";

    private static final String NODE_HEADER = "id\tsymbolId\tkind\tlabel\tfileId\tstartLine\tstartColumn\tendLine\tendColumn\tpositionEncoding";
    private static final String EDGE_HEADER = "id\tsourceNodeId\ttargetNodeId\tkind";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_EDGES = 500_000;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_LINE_CHARS = 1024 * 1024;

    @Override
    public String id() { return PROVIDER_ID; }

    @Override
    public String cacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Path directory = directory(project);
        if (!Files.isDirectory(directory)) return PROVIDER_ID + ":absent";
        Path metadata = requiredFile(directory, METADATA_FILE);
        Path nodes = requiredFile(directory, NODES_FILE);
        Path edges = requiredFile(directory, EDGES_FILE);
        return PROVIDER_ID + ":" + sha256(metadata, nodes, edges);
    }

    @Override
    public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        String projectId = project.id().toString();
        Path directory = directory(project);
        if (!Files.isDirectory(directory)) {
            return empty(projectId, snapshot.snapshotId(), "ADVANCED_PROGRAM_SIDECAR_NOT_PRESENT");
        }

        Path metadataFile = requiredFile(directory, METADATA_FILE);
        Path nodesFile = requiredFile(directory, NODES_FILE);
        Path edgesFile = requiredFile(directory, EDGES_FILE);
        Metadata metadata = readMetadata(metadataFile);
        if (!snapshot.snapshotId().equals(metadata.snapshotId())) {
            return empty(projectId, snapshot.snapshotId(), "ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT");
        }

        Origin origin = new Origin(
                metadata.providerId(), metadata.providerType(), metadata.providerVersion(), metadata.indexRunId(), OriginType.OTHER);
        Map<String, Symbol> symbols = new LinkedHashMap<>();
        snapshot.symbols().forEach(symbol -> symbols.put(symbol.id(), symbol));
        List<ProgramGraphNode> nodes = readNodes(nodesFile, projectId, origin, symbols);
        List<ProgramGraphEdge> edges = readEdges(edgesFile, projectId, origin, nodes);
        validateCapabilities(metadata.capabilities(), nodes, edges);

        return new ProgramGraph(
                projectId,
                snapshot.snapshotId(),
                metadata.capabilities(),
                nodes.stream().sorted(Comparator.comparing(ProgramGraphNode::id)).toList(),
                edges.stream().sorted(Comparator.comparing(ProgramGraphEdge::id)).toList(),
                List.of("ADVANCED_PROGRAM_SIDECAR_V1", "ADVANCED_PROGRAM_FACTS_PROVIDER_ASSERTED"));
    }

    private static Path directory(RegisteredProject project) {
        return project.rootPath().resolve(RELATIVE_DIRECTORY).toAbsolutePath().normalize();
    }

    private static ProgramGraph empty(String projectId, String snapshotId, String limitation) {
        return new ProgramGraph(projectId, snapshotId, Set.of(), List.of(), List.of(), List.of(limitation));
    }

    private static Path requiredFile(Path directory, String name) throws IOException {
        Path file = directory.resolve(name).normalize();
        if (!file.startsWith(directory)
                || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("advanced program sidecar missing or unsafe required file: " + name);
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) throw new IOException("advanced program sidecar file exceeds 64 MiB: " + name);
        return file;
    }

    private static Metadata readMetadata(Path file) throws IOException {
        Properties properties = BoundedProperties.load(
                file, 64L * 1024L, 32, 128, 16_384,
                "advanced program metadata");
        int version = integer(required(properties, "formatVersion"), "formatVersion");
        if (version != FORMAT_VERSION) throw new IOException("unsupported advanced program sidecar formatVersion: " + version);
        Set<ProgramGraphCapability> capabilities = new LinkedHashSet<>();
        String rawCapabilities = required(properties, "capabilities");
        for (String token : rawCapabilities.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) continue;
            try {
                capabilities.add(ProgramGraphCapability.valueOf(value));
            } catch (IllegalArgumentException exception) {
                throw new IOException("unknown advanced program capability: " + value, exception);
            }
        }
        if (capabilities.contains(ProgramGraphCapability.CPG)) {
            throw new IOException("CPG is composed by MINOS and must not be declared by the sidecar provider");
        }
        return new Metadata(
                required(properties, "snapshotId"),
                required(properties, "providerId"),
                properties.getProperty("providerType", "PROGRAM_GRAPH_SIDECAR").trim(),
                required(properties, "providerVersion"),
                required(properties, "indexRunId"),
                Set.copyOf(capabilities));
    }

    private static List<ProgramGraphNode> readNodes(
            Path file, String projectId, Origin origin, Map<String, Symbol> symbols) throws IOException {
        List<ProgramGraphNode> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(file), MAX_FILE_BYTES, "advanced program nodes");
             BoundedLineReader reader = new BoundedLineReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), MAX_LINE_CHARS)) {
            String header = reader.readLine();
            if (!NODE_HEADER.equals(header)) {
                throw new IOException(file.getFileName() + " has an incompatible header; expected: " + NODE_HEADER);
            }
            int line = 1;
            String raw;
            while ((raw = reader.readLine()) != null) {
                line++;
                if (raw.isBlank() || raw.startsWith("#")) continue;
                if (result.size() >= MAX_NODES) throw new IOException("advanced program sidecar exceeds max nodes: " + MAX_NODES);
                String[] values = FixedTsv.splitExact(raw, 10, line);
                String id = field(values[0]);
                String symbolId = nullable(field(values[1]));
                ProgramNodeKind kind = enumValue(ProgramNodeKind.class, field(values[2]), file, line, "node kind");
                String label = field(values[3]);
                if (id.isBlank() || label.isBlank()) throw rowFailure(file, line, "node id and label must not be blank");
                if (!ids.add(id)) throw rowFailure(file, line, "duplicate node id: " + id);
                Symbol symbol = symbolId == null ? null : symbols.get(symbolId);
                if (symbolId != null && symbol == null) throw rowFailure(file, line, "node references unknown active-snapshot symbol: " + symbolId);
                SymbolLocation location = location(values, file, line);
                if (location == null && symbol != null) location = symbol.location();
                result.add(new ProgramGraphNode(
                        id, projectId, symbolId, kind, label, location,
                        InformationNature.FACTUAL, null, origin, List.of()));
            }
        }
        return List.copyOf(result);
    }

    private static List<ProgramGraphEdge> readEdges(
            Path file, String projectId, Origin origin, List<ProgramGraphNode> nodes) throws IOException {
        Set<String> nodeIds = nodes.stream().map(ProgramGraphNode::id).collect(java.util.stream.Collectors.toSet());
        List<ProgramGraphEdge> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(file), MAX_FILE_BYTES, "advanced program edges");
             BoundedLineReader reader = new BoundedLineReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8), MAX_LINE_CHARS)) {
            String header = reader.readLine();
            if (!EDGE_HEADER.equals(header)) {
                throw new IOException(file.getFileName() + " has an incompatible header; expected: " + EDGE_HEADER);
            }
            int line = 1;
            String raw;
            while ((raw = reader.readLine()) != null) {
                line++;
                if (raw.isBlank() || raw.startsWith("#")) continue;
                if (result.size() >= MAX_EDGES) throw new IOException("advanced program sidecar exceeds max edges: " + MAX_EDGES);
                String[] values = FixedTsv.splitExact(raw, 4, line);
                String id = field(values[0]);
                String source = field(values[1]);
                String target = field(values[2]);
                ProgramEdgeKind kind = enumValue(ProgramEdgeKind.class, field(values[3]), file, line, "edge kind");
                if (id.isBlank() || source.isBlank() || target.isBlank()) throw rowFailure(file, line, "edge id/source/target must not be blank");
                if (!ids.add(id)) throw rowFailure(file, line, "duplicate edge id: " + id);
                if (!nodeIds.contains(source) || !nodeIds.contains(target)) throw rowFailure(file, line, "edge references a node not declared by this sidecar: " + id);
                result.add(new ProgramGraphEdge(
                        id, projectId, source, target, kind, InformationNature.FACTUAL, null, origin, List.of()));
            }
        }
        return List.copyOf(result);
    }

    private static SymbolLocation location(String[] values, Path file, int line) throws IOException {
        String fileId = nullable(field(values[4]));
        boolean anyCoordinate = false;
        for (int index = 5; index <= 9; index++) anyCoordinate |= !field(values[index]).isBlank();
        if (fileId == null) {
            if (anyCoordinate) throw rowFailure(file, line, "location coordinates require fileId");
            return null;
        }
        try {
            return new SymbolLocation(
                    fileId,
                    integer(field(values[5]), "startLine"),
                    integer(field(values[6]), "startColumn"),
                    integer(field(values[7]), "endLine"),
                    integer(field(values[8]), "endColumn"),
                    PositionEncoding.valueOf(field(values[9])));
        } catch (RuntimeException exception) {
            throw rowFailure(file, line, "invalid source location: " + exception.getMessage());
        }
    }

    private static void validateCapabilities(Set<ProgramGraphCapability> capabilities,
                                             List<ProgramGraphNode> nodes,
                                             List<ProgramGraphEdge> edges) throws IOException {
        Set<ProgramEdgeKind> kinds = EnumSet.noneOf(ProgramEdgeKind.class);
        edges.forEach(edge -> kinds.add(edge.kind()));
        requireCapabilityForEdge(capabilities, kinds, ProgramEdgeKind.CALL, ProgramGraphCapability.CALL_GRAPH);
        requireCapabilityForEdge(capabilities, kinds, ProgramEdgeKind.CONTROL_FLOW, ProgramGraphCapability.CONTROL_FLOW);
        requireCapabilityForAnyEdge(capabilities, kinds, Set.of(ProgramEdgeKind.DEF_USE, ProgramEdgeKind.DATA_FLOW), ProgramGraphCapability.LOCAL_DATA_FLOW);
        requireCapabilityForAnyEdge(capabilities, kinds, Set.of(ProgramEdgeKind.ARGUMENT_FLOW, ProgramEdgeKind.RETURN_FLOW), ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW);
        requireCapabilityForEdge(capabilities, kinds, ProgramEdgeKind.TAINT_FLOW, ProgramGraphCapability.SECURITY_TAINT);

        requireFactsForCapability(capabilities, ProgramGraphCapability.CALL_GRAPH, kinds.contains(ProgramEdgeKind.CALL), "CALL edge");
        requireFactsForCapability(capabilities, ProgramGraphCapability.CONTROL_FLOW, kinds.contains(ProgramEdgeKind.CONTROL_FLOW), "CONTROL_FLOW edge");
        requireFactsForCapability(capabilities, ProgramGraphCapability.LOCAL_DATA_FLOW,
                kinds.contains(ProgramEdgeKind.DEF_USE) || kinds.contains(ProgramEdgeKind.DATA_FLOW), "DEF_USE or DATA_FLOW edge");
        requireFactsForCapability(capabilities, ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW,
                kinds.contains(ProgramEdgeKind.ARGUMENT_FLOW) || kinds.contains(ProgramEdgeKind.RETURN_FLOW), "ARGUMENT_FLOW or RETURN_FLOW edge");
        if (capabilities.contains(ProgramGraphCapability.SECURITY_TAINT)) {
            boolean hasSource = nodes.stream().anyMatch(node -> node.kind() == ProgramNodeKind.SOURCE);
            boolean hasSink = nodes.stream().anyMatch(node -> node.kind() == ProgramNodeKind.SINK);
            requireFactsForCapability(capabilities, ProgramGraphCapability.SECURITY_TAINT,
                    kinds.contains(ProgramEdgeKind.TAINT_FLOW) && hasSource && hasSink,
                    "TAINT_FLOW edge plus SOURCE and SINK nodes");
        }
    }

    private static void requireCapabilityForEdge(Set<ProgramGraphCapability> capabilities,
                                                 Set<ProgramEdgeKind> kinds,
                                                 ProgramEdgeKind edge,
                                                 ProgramGraphCapability capability) throws IOException {
        if (kinds.contains(edge) && !capabilities.contains(capability)) throw new IOException("sidecar contains " + edge + " facts without declaring capability " + capability);
    }

    private static void requireCapabilityForAnyEdge(Set<ProgramGraphCapability> capabilities,
                                                    Set<ProgramEdgeKind> kinds,
                                                    Set<ProgramEdgeKind> edges,
                                                    ProgramGraphCapability capability) throws IOException {
        if (edges.stream().anyMatch(kinds::contains) && !capabilities.contains(capability)) throw new IOException("sidecar contains " + edges + " facts without declaring capability " + capability);
    }

    private static void requireFactsForCapability(Set<ProgramGraphCapability> capabilities,
                                                  ProgramGraphCapability capability,
                                                  boolean present,
                                                  String evidence) throws IOException {
        if (capabilities.contains(capability) && !present) throw new IOException("sidecar declares capability " + capability + " without required " + evidence);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, Path file, int line, String label) throws IOException {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException exception) { throw rowFailure(file, line, "unknown " + label + ": " + value); }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("advanced program sidecar metadata missing: " + key);
        return value.trim();
    }

    private static int integer(String value, String label) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(label + " must be an integer", exception); }
    }

    private static String field(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                result.append(switch (current) {
                    case 't' -> '\t'; case 'n' -> '\n'; case 'r' -> '\r'; case '\\' -> '\\'; default -> current;
                });
                escaped = false;
            } else if (current == '\\') escaped = true;
            else result.append(current);
        }
        if (escaped) result.append('\\');
        return result.toString().trim();
    }

    private static String nullable(String value) { return value.isBlank() ? null : value; }
    private static IOException rowFailure(Path file, int line, String message) { return new IOException(file.getFileName() + ":" + line + ": " + message); }

    private static String sha256(Path... files) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                try (BoundedInputStream input = new BoundedInputStream(
                        Files.newInputStream(file), MAX_FILE_BYTES, "advanced program sidecar hash")) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Metadata(String snapshotId, String providerId, String providerType,
                            String providerVersion, String indexRunId,
                            Set<ProgramGraphCapability> capabilities) {
        private Metadata {
            if (providerType == null || providerType.isBlank()) providerType = "PROGRAM_GRAPH_SIDECAR";
            capabilities = Set.copyOf(capabilities);
        }
    }
}

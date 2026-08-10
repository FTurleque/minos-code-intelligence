from pathlib import Path
import re


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8", newline="\n")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def regex_once(path: str, pattern: str, replacement: str) -> None:
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: regex anchor mismatch: {pattern[:120]!r}")
    write(path, updated)


# MNC-06/MNC-07 — group chunk construction by file, reuse one bounded source load, and bound FILE aggregation while appending.
semantic = "minos-application/src/main/java/com/minos/semantic/SemanticDocumentFactory.java"
text = read(semantic)
old_chunk = '''            SymbolLocation location = symbol.location();
            if (location != null) {
                symbolsByFile.computeIfAbsent(location.fileId(), ignored -> new ArrayList<>()).add(symbol);
                String chunkText = sourceReader.readExcerpt(location, 2, CHUNK_MAX_TOKENS)
                        .map(SourceExcerpt::content)
                        .filter(value -> !value.isBlank())
                        .orElse(symbolContent);
                String chunkStableKey = "chunk:" + symbol.symbolKey() + ":" + location.startLine() + ":" + location.endLine();
                add(documents, tracker, document(projectId, snapshot.snapshotId(), SemanticDocumentKind.CHUNK,
                        chunkStableKey, symbol.id(), location.fileId(), location.startLine(), location.endLine(),
                        bounded(chunkText, CHUNK_MAX_TOKENS)));
            }
'''
new_chunk = '''            SymbolLocation location = symbol.location();
            if (location != null) {
                symbolsByFile.computeIfAbsent(location.fileId(), ignored -> new ArrayList<>()).add(symbol);
            }
'''
if text.count(old_chunk) != 1:
    raise SystemExit("SemanticDocumentFactory chunk anchor mismatch")
text = text.replace(old_chunk, new_chunk, 1)
old_files = '''        for (Map.Entry<String, List<Symbol>> entry : symbolsByFile.entrySet()) {
            String fileId = entry.getKey();
            StringBuilder aggregate = new StringBuilder("file ").append(fileId).append('\n');
            entry.getValue().stream().sorted(Comparator.comparing(Symbol::symbolKey)).forEach(symbol ->
                    aggregate.append(symbol.kind().name()).append(' ')
                            .append(textOr(symbol.qualifiedName(), symbol.name())).append(' ')
                            .append(textOr(symbol.signature(), "")).append('\n'));
            String stableFileKey = "file:" + fileId;
            add(documents, tracker, document(projectId, snapshot.snapshotId(), SemanticDocumentKind.FILE,
                    stableFileKey, fileId, fileId, 0, 0, bounded(aggregate.toString(), FILE_MAX_TOKENS)));
        }
'''
new_files = '''        for (Map.Entry<String, List<Symbol>> entry : symbolsByFile.entrySet()) {
            String fileId = entry.getKey();
            List<Symbol> ordered = entry.getValue().stream().sorted(Comparator.comparing(Symbol::symbolKey)).toList();
            // Symbols from the same file are processed consecutively. LocalSourceReader retains only
            // one bounded decoded source, so a file is not reread once per symbol.
            for (Symbol symbol : ordered) {
                SymbolLocation location = symbol.location();
                String symbolContent = bounded(symbolText(symbol), SYMBOL_MAX_TOKENS);
                String chunkText = sourceReader.readExcerpt(location, 2, CHUNK_MAX_TOKENS)
                        .map(SourceExcerpt::content)
                        .filter(value -> !value.isBlank())
                        .orElse(symbolContent);
                String chunkStableKey = "chunk:" + symbol.symbolKey() + ":" + location.startLine() + ":" + location.endLine();
                add(documents, tracker, document(projectId, snapshot.snapshotId(), SemanticDocumentKind.CHUNK,
                        chunkStableKey, symbol.id(), location.fileId(), location.startLine(), location.endLine(),
                        bounded(chunkText, CHUNK_MAX_TOKENS)));
            }
            String stableFileKey = "file:" + fileId;
            add(documents, tracker, document(projectId, snapshot.snapshotId(), SemanticDocumentKind.FILE,
                    stableFileKey, fileId, fileId, 0, 0, fileSummary(fileId, ordered)));
        }
'''
if text.count(old_files) != 1:
    raise SystemExit("SemanticDocumentFactory file aggregation anchor mismatch")
text = text.replace(old_files, new_files, 1)
anchor = "    private static String symbolText(Symbol symbol) {\n"
helper = '''    private static String fileSummary(String fileId, List<Symbol> symbols) {
        StringBuilder aggregate = new StringBuilder();
        int usedTokens = appendWithinTokenBudget(aggregate, "file " + fileId + "\n", 0, FILE_MAX_TOKENS);
        for (Symbol symbol : symbols) {
            if (usedTokens >= FILE_MAX_TOKENS) break;
            String line = symbol.kind().name() + " "
                    + textOr(symbol.qualifiedName(), symbol.name()) + " "
                    + textOr(symbol.signature(), "") + "\n";
            usedTokens = appendWithinTokenBudget(aggregate, line, usedTokens, FILE_MAX_TOKENS);
        }
        return aggregate.toString();
    }

    private static int appendWithinTokenBudget(
            StringBuilder target,
            String value,
            int usedTokens,
            int maximumTokens
    ) {
        int remaining = maximumTokens - usedTokens;
        if (remaining <= 0) return usedTokens;
        String accepted = bounded(value, remaining);
        if (accepted.isEmpty()) return usedTokens;
        target.append(accepted);
        return Math.min(maximumTokens, usedTokens + TokenEstimator.estimate(accepted));
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("SemanticDocumentFactory helper anchor mismatch")
text = text.replace(anchor, helper + anchor, 1)
write(semantic, text)

source_reader = "minos-application/src/main/java/com/minos/context/LocalSourceReader.java"
text = read(source_reader)
text = text.replace("    private final Path projectRoot;\n", "    private final Path projectRoot;\n    private CachedSource cachedSource;\n", 1)
text = text.replace(
    "        List<String> lines = readLines(source.orElseThrow());\n",
    "        CachedSource cached = readSource(source.orElseThrow());\n        List<String> lines = cached.lines();\n",
    1,
)
text = text.replace(
    "        int totalFileTokens = TokenEstimator.estimate(String.join(\"\\n\", lines));\n",
    "        int totalFileTokens = cached.totalTokens();\n",
    1,
)
old_read_lines = '''    private List<String> readLines(Path source) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(source), MAX_SOURCE_BYTES, "source file");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }
'''
new_read_lines = '''    private CachedSource readSource(Path source) throws IOException {
        if (cachedSource != null && cachedSource.path().equals(source)) return cachedSource;
        List<String> lines = new ArrayList<>();
        int utf8Bytes = 0;
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(source), MAX_SOURCE_BYTES, "source file");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) utf8Bytes = Math.addExact(utf8Bytes, 1);
                utf8Bytes = Math.addExact(utf8Bytes, line.getBytes(StandardCharsets.UTF_8).length);
                lines.add(line);
                first = false;
            }
        } catch (ArithmeticException exception) {
            throw new IOException("source token byte counter overflow", exception);
        }
        int totalTokens = utf8Bytes == 0 ? 0 : Math.max(1, (utf8Bytes + 3) / 4);
        cachedSource = new CachedSource(source, List.copyOf(lines), totalTokens);
        return cachedSource;
    }
'''
if text.count(old_read_lines) != 1:
    raise SystemExit("LocalSourceReader readLines anchor mismatch")
text = text.replace(old_read_lines, new_read_lines, 1)
text = text[:-2] + '''

    private record CachedSource(Path path, List<String> lines, int totalTokens) { }
}
'''
write(source_reader, text)

# MNC-08 — NEXUS path discovery counts every traversed entry, not only regular files selected after filtering.
nexus = "minos-nexus/src/main/java/com/minos/integration/nexus/NexusExportService.java"
text = read(nexus)
text = text.replace("import java.nio.file.Files;\n", "import java.nio.file.FileVisitResult;\nimport java.nio.file.Files;\n", 1)
text = text.replace("import java.nio.file.Path;\n", "import java.nio.file.Path;\nimport java.nio.file.SimpleFileVisitor;\nimport java.nio.file.attribute.BasicFileAttributes;\n", 1)
old_walk = '''        int scanned = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext() && !unresolvedStableIds.isEmpty()) {
                if (scanned >= MAX_FILE_PATH_CANDIDATES) { limitations.add("FILE_PATH_DISCOVERY_TRUNCATED"); break; }
                Path file = iterator.next();
                scanned++;
                Path canonical = file.toRealPath();
                if (!canonical.startsWith(root)) continue;
                String relativePath = root.relativize(file).toString().replace('\\\\', '/');
                String stableId = stableFileId(projectId, relativePath);
                if (unresolvedStableIds.remove(stableId)) resolved.put(stableId, relativePath);
            }
        }
'''
new_walk = '''        long[] traversed = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private FileVisitResult account() {
                traversed[0]++;
                if (traversed[0] > MAX_FILE_PATH_CANDIDATES) {
                    limitations.add("FILE_PATH_DISCOVERY_TRUNCATED");
                    return FileVisitResult.TERMINATE;
                }
                return unresolvedStableIds.isEmpty() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return account();
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                FileVisitResult decision = account();
                if (decision == FileVisitResult.TERMINATE) return decision;
                if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                Path canonical = file.toRealPath();
                if (!canonical.startsWith(root)) return FileVisitResult.CONTINUE;
                String relativePath = root.relativize(file).toString().replace('\\\\', '/');
                String stableId = stableFileId(projectId, relativePath);
                if (unresolvedStableIds.remove(stableId)) resolved.put(stableId, relativePath);
                return unresolvedStableIds.isEmpty() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
'''
if text.count(old_walk) != 1:
    raise SystemExit("Nexus traversal anchor mismatch")
text = text.replace(old_walk, new_walk, 1)
write(nexus, text)

# MNC-09 — runtime observations parse line-by-line under the byte limit.
runtime_codec = "minos-application/src/main/java/com/minos/dynamic/RuntimeObservationEnvelopeCodec.java"
text = read(runtime_codec)
for old, new in [
    ("import java.io.IOException;\n", "import java.io.BufferedReader;\nimport java.io.IOException;\nimport java.io.InputStreamReader;\n"),
    ("import java.security.MessageDigest;\n", "import java.security.DigestInputStream;\nimport java.security.MessageDigest;\n"),
]:
    text = text.replace(old, new, 1)
pattern = r"    public DecodedSession read\(Path source\) throws IOException \{.*?\n    \}\n\n    private static RuntimeObservation parseObservation"
replacement = '''    public DecodedSession read(Path source) throws IOException {
        Path path = source == null ? null : source.toAbsolutePath().normalize();
        if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("runtime observation input must be a regular non-symlink file");
        }
        MessageDigest digest = digest();
        String[] metadata = new String[METADATA_LINES];
        List<RuntimeObservation> observations = new ArrayList<>();
        long sourceBytes;
        try (DigestInputStream digestInput = new DigestInputStream(Files.newInputStream(path), digest);
             BoundedInputStream bounded = new BoundedInputStream(
                     digestInput, MAX_INPUT_BYTES, "runtime observation input");
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     bounded,
                     StandardCharsets.UTF_8.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\\ufeff")) {
                    throw new IOException("runtime observation input must not contain a BOM");
                }
                if (line.isEmpty()) throw new IOException("blank runtime observation line at " + lineNumber);
                if (lineNumber <= METADATA_LINES) {
                    metadata[lineNumber - 1] = line;
                    continue;
                }
                if (observations.size() >= RuntimeObservationSession.MAX_OBSERVATIONS) {
                    throw new IOException("runtime observation count exceeds limit");
                }
                observations.add(parseObservation(line, lineNumber));
            }
            sourceBytes = bounded.consumedBytes();
        }
        if (sourceBytes < 1L) throw new IOException("runtime observation input must not be empty");
        for (int index = 0; index < METADATA_LINES; index++) {
            if (metadata[index] == null) throw new IOException("runtime observation input has incomplete metadata");
        }
        if (observations.isEmpty()) throw new IOException("runtime observation input has no observations");

        expectExact(metadata[0], RuntimeObservationSession.FORMAT, 1);
        String sessionId = singleValue(metadata[1], "session", 2);
        UUID projectId = uuid(singleValue(metadata[2], "project", 3), 3);
        String snapshotId = singleValue(metadata[3], "snapshot", 4);
        Instant startedAt = instant(singleValue(metadata[4], "started", 5), 5);
        Instant endedAt = instant(singleValue(metadata[5], "ended", 6), 6);
        String[] collector = fields(metadata[6], 3, 7);
        expectToken(collector[0], "collector", 7);
        String environment = singleValue(metadata[7], "environment", 8);
        RuntimeObservationCompleteness completeness;
        try {
            completeness = RuntimeObservationCompleteness.valueOf(singleValue(metadata[8], "completeness", 9));
        } catch (IllegalArgumentException exception) {
            throw new IOException("line 9: completeness must be PARTIAL", exception);
        }
        RuntimeObservationSession session = new RuntimeObservationSession(
                RuntimeObservationSession.FORMAT, sessionId, projectId, snapshotId,
                startedAt, endedAt, collector[1], collector[2], environment, completeness, observations);
        return new DecodedSession(session, HexFormat.of().formatHex(digest.digest()), sourceBytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static RuntimeObservation parseObservation'''
text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("RuntimeObservationEnvelopeCodec read method mismatch")
write(runtime_codec, text)

# MNC-09 — Program Graph sidecar rows are streamed instead of Files.readAllLines materialization.
program_graph = "minos-application/src/main/java/com/minos/program/analysis/FileProgramGraphProvider.java"
text = read(program_graph)
text = text.replace("import java.io.IOException;\n", "import java.io.BufferedReader;\nimport java.io.IOException;\n", 1)
node_pattern = r"    private static List<ProgramGraphNode> readNodes\(.*?\n    \}\n\n    private static List<ProgramGraphEdge> readEdges"
node_replacement = '''    private static List<ProgramGraphNode> readNodes(
            Path file,
            String projectId,
            Origin origin,
            Map<String, Symbol> symbols
    ) throws IOException {
        List<ProgramGraphNode> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
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
                String[] values = raw.split("\\t", -1);
                if (values.length != 10) throw rowFailure(file, line, "expected 10 tab-separated node columns");
                String id = field(values[0]);
                String symbolId = nullable(field(values[1]));
                ProgramNodeKind kind = enumValue(ProgramNodeKind.class, field(values[2]), file, line, "node kind");
                String label = field(values[3]);
                if (id.isBlank() || label.isBlank()) throw rowFailure(file, line, "node id and label must not be blank");
                if (!ids.add(id)) throw rowFailure(file, line, "duplicate node id: " + id);
                Symbol symbol = symbolId == null ? null : symbols.get(symbolId);
                if (symbolId != null && symbol == null) {
                    throw rowFailure(file, line, "node references unknown active-snapshot symbol: " + symbolId);
                }
                SymbolLocation location = location(values, file, line);
                if (location == null && symbol != null) location = symbol.location();
                result.add(new ProgramGraphNode(
                        id, projectId, symbolId, kind, label, location,
                        InformationNature.FACTUAL, null, origin, List.of()));
            }
        }
        return List.copyOf(result);
    }

    private static List<ProgramGraphEdge> readEdges'''
text, count = re.subn(node_pattern, node_replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("FileProgramGraphProvider readNodes mismatch")
edge_pattern = r"    private static List<ProgramGraphEdge> readEdges\(.*?\n    \}\n\n    private static SymbolLocation location"
edge_replacement = '''    private static List<ProgramGraphEdge> readEdges(
            Path file,
            String projectId,
            Origin origin,
            List<ProgramGraphNode> nodes
    ) throws IOException {
        Set<String> nodeIds = nodes.stream().map(ProgramGraphNode::id).collect(java.util.stream.Collectors.toSet());
        List<ProgramGraphEdge> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
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
                String[] values = raw.split("\\t", -1);
                if (values.length != 4) throw rowFailure(file, line, "expected 4 tab-separated edge columns");
                String id = field(values[0]);
                String source = field(values[1]);
                String target = field(values[2]);
                ProgramEdgeKind kind = enumValue(ProgramEdgeKind.class, field(values[3]), file, line, "edge kind");
                if (id.isBlank() || source.isBlank() || target.isBlank()) {
                    throw rowFailure(file, line, "edge id/source/target must not be blank");
                }
                if (!ids.add(id)) throw rowFailure(file, line, "duplicate edge id: " + id);
                if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
                    throw rowFailure(file, line, "edge references a node not declared by this sidecar: " + id);
                }
                result.add(new ProgramGraphEdge(
                        id, projectId, source, target, kind, InformationNature.FACTUAL, null, origin, List.of()));
            }
        }
        return List.copyOf(result);
    }

    private static SymbolLocation location'''
text, count = re.subn(edge_pattern, edge_replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("FileProgramGraphProvider readEdges mismatch")
write(program_graph, text)

# MNC-10 — wire-format preflight rejects excessive SCIP structure before full protobuf materialization.
limits = "minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIngestionLimits.java"
text = read(limits)
text = text.replace("import com.minos.orchestration.IndexArtifactLimits;\n", "import com.google.protobuf.CodedInputStream;\nimport com.google.protobuf.WireFormat;\nimport com.minos.orchestration.IndexArtifactLimits;\n", 1)
text = text.replace("import java.io.IOException;\n", "import java.io.IOException;\nimport java.io.InputStream;\n", 1)
anchor = "    public void validate(Index index) throws IOException {\n"
preflight = '''    public void preflight(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        CodedInputStream input = CodedInputStream.newInstance(source);
        input.setSizeLimit((int) Math.min(Integer.MAX_VALUE, maxArtifactBytes));
        Counters counters = new Counters();
        scanIndex(input, counters);
    }

    private void scanIndex(CodedInputStream input, Counters counters) throws IOException {
        int tag;
        while ((tag = input.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && field == Index.DOCUMENTS_FIELD_NUMBER) {
                counters.documents = increment(counters.documents, maxDocuments, "documents");
                scanNested(input, nested -> scanDocument(nested, counters));
            } else if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && field == Index.EXTERNAL_SYMBOLS_FIELD_NUMBER) {
                counters.symbols = increment(counters.symbols, maxSymbols, "symbols");
                scanNested(input, nested -> scanSymbolInformation(nested, counters));
            } else if (!input.skipField(tag)) {
                return;
            }
        }
    }

    private void scanDocument(CodedInputStream input, Counters counters) throws IOException {
        int tag;
        while ((tag = input.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            int wire = WireFormat.getTagWireType(tag);
            if (wire == WireFormat.WIRETYPE_LENGTH_DELIMITED && field == Document.SYMBOLS_FIELD_NUMBER) {
                counters.symbols = increment(counters.symbols, maxSymbols, "symbols");
                scanNested(input, nested -> scanSymbolInformation(nested, counters));
            } else if (wire == WireFormat.WIRETYPE_LENGTH_DELIMITED && field == Document.OCCURRENCES_FIELD_NUMBER) {
                counters.occurrences = increment(counters.occurrences, maxOccurrences, "occurrences");
                skipNested(input);
            } else if (!input.skipField(tag)) {
                return;
            }
        }
    }

    private void scanSymbolInformation(CodedInputStream input, Counters counters) throws IOException {
        int tag;
        while ((tag = input.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && field == SymbolInformation.RELATIONSHIPS_FIELD_NUMBER) {
                counters.relationshipFacts = increment(
                        counters.relationshipFacts, maxRelationshipFacts, "relationship facts");
                skipNested(input);
            } else if (!input.skipField(tag)) {
                return;
            }
        }
    }

    private static void scanNested(CodedInputStream input, NestedScanner scanner) throws IOException {
        int length = input.readRawVarint32();
        int previous = input.pushLimit(length);
        try {
            scanner.scan(input);
            if (!input.isAtEnd()) throw new IOException("SCIP nested protobuf message was not fully consumed");
        } finally {
            input.popLimit(previous);
        }
    }

    private static void skipNested(CodedInputStream input) throws IOException {
        int length = input.readRawVarint32();
        if (length < 0) throw new IOException("SCIP protobuf contains a negative message length");
        input.skipRawBytes(length);
    }

    private static long increment(long value, long maximum, String label) throws IOException {
        long next = add(value, 1L, label);
        if (next > maximum) fail(label, next, maximum);
        return next;
    }

    @FunctionalInterface
    private interface NestedScanner {
        void scan(CodedInputStream input) throws IOException;
    }

    private static final class Counters {
        private long documents;
        private long symbols;
        private long occurrences;
        private long relationshipFacts;
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("ScipIngestionLimits validate anchor mismatch")
text = text.replace(anchor, preflight + anchor, 1)
write(limits, text)

reader = "minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIndexReader.java"
text = read(reader)
text = text.replace("import java.io.InputStream;\n", "import java.io.InputStream;\nimport java.nio.channels.Channels;\nimport java.nio.channels.SeekableByteChannel;\n", 1)
text = text.replace("import java.nio.file.Path;\n", "import java.nio.file.Path;\nimport java.nio.file.StandardOpenOption;\nimport java.util.Set;\n", 1)
old_parse = '''        try (InputStream raw = Files.newInputStream(indexFile);
             BoundedInputStream input = new BoundedInputStream(
                     raw, limits.maxArtifactBytes(), "SCIP artifact")) {
            return Index.parseFrom(input);
        }
'''
new_parse = '''        try (SeekableByteChannel channel = Files.newByteChannel(
                indexFile, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            BoundedInputStream preflight = new BoundedInputStream(
                    Channels.newInputStream(channel), limits.maxArtifactBytes(), "SCIP artifact preflight");
            limits.preflight(preflight);
            channel.position(0L);
            BoundedInputStream input = new BoundedInputStream(
                    Channels.newInputStream(channel), limits.maxArtifactBytes(), "SCIP artifact");
            return Index.parseFrom(input);
        }
'''
if text.count(old_parse) != 1:
    raise SystemExit("ScipIndexReader parse anchor mismatch")
text = text.replace(old_parse, new_parse, 1)
write(reader, text)

# MNC-11/MNC-12 — budget actual discovery traversals and bound ignore files before regex compilation.
ignore = "minos-application/src/main/java/com/minos/discovery/ProjectIgnorePolicy.java"
text = read(ignore)
text = text.replace("package com.minos.discovery;\n\n", "package com.minos.discovery;\n\nimport com.minos.io.BoundedInputStream;\nimport com.minos.source.SourceBudgetPolicy;\n\n", 1)
text = text.replace("import java.io.IOException;\n", "import java.io.BufferedReader;\nimport java.io.IOException;\nimport java.io.InputStreamReader;\nimport java.io.UncheckedIOException;\n", 1)
text = text.replace("import java.util.ArrayList;\n", "import java.util.ArrayList;\nimport java.util.HashSet;\n", 1)
text = text.replace("    private static final Set<String> HARD_IGNORED_DIRECTORY_NAMES = Set.of(\n",
                    "    private static final long MAX_IGNORE_BYTES = 1024L * 1024L;\n    private static final int MAX_IGNORE_LINES = 20_000;\n    private static final int MAX_IGNORE_RULES = 10_000;\n    private static final int MAX_IGNORE_LINE_CHARS = 8_192;\n\n    private static final Set<String> HARD_IGNORED_DIRECTORY_NAMES = Set.of(\n", 1)
text = text.replace(
    "    private final List<IgnoreRule> gitRules;\n    private final List<IgnoreRule> minosRules;\n\n"
    "    private ProjectIgnorePolicy(List<IgnoreRule> gitRules, List<IgnoreRule> minosRules) {\n"
    "        this.gitRules = List.copyOf(gitRules);\n"
    "        this.minosRules = List.copyOf(minosRules);\n"
    "    }\n",
    "    private final Path root;\n"
    "    private final SourceBudgetPolicy.Tracker budget;\n"
    "    private final Set<Path> accountedRegularFiles = new HashSet<>();\n"
    "    private final List<IgnoreRule> gitRules;\n"
    "    private final List<IgnoreRule> minosRules;\n\n"
    "    private ProjectIgnorePolicy(\n"
    "            Path root,\n"
    "            SourceBudgetPolicy.Tracker budget,\n"
    "            List<IgnoreRule> gitRules,\n"
    "            List<IgnoreRule> minosRules\n"
    "    ) {\n"
    "        this.root = root;\n"
    "        this.budget = budget;\n"
    "        this.gitRules = List.copyOf(gitRules);\n"
    "        this.minosRules = List.copyOf(minosRules);\n"
    "    }\n",
    1,
)
old_load = '''    public static ProjectIgnorePolicy load(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        return new ProjectIgnorePolicy(
                readRules(root.resolve(".gitignore")),
                readRules(root.resolve(".minosignore"))
        );
    }
'''
new_load = '''    public static ProjectIgnorePolicy load(Path projectRoot) throws IOException {
        return load(projectRoot, null);
    }

    static ProjectIgnorePolicy load(Path projectRoot, SourceBudgetPolicy.Tracker budget) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        return new ProjectIgnorePolicy(
                root,
                budget,
                readRules(root.resolve(".gitignore")),
                readRules(root.resolve(".minosignore"))
        );
    }
'''
if text.count(old_load) != 1:
    raise SystemExit("ProjectIgnorePolicy load anchor mismatch")
text = text.replace(old_load, new_load, 1)
old_ignore_methods = '''    public boolean isIgnored(Path relativePath, boolean directory) {
        Path normalized = normalizeRelative(relativePath);
        if (isHardIgnored(normalized)) {
            return true;
        }
        String portablePath = portable(normalized);
        return evaluate(gitRules, portablePath, directory)
                || evaluate(minosRules, portablePath, directory);
    }

    public boolean isHardIgnored(Path relativePath) {
        Path normalized = normalizeRelative(relativePath);
        for (Path segment : normalized) {
            if (HARD_IGNORED_DIRECTORY_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
'''
new_ignore_methods = '''    public boolean isIgnored(Path relativePath, boolean directory) {
        Path normalized = normalizeRelative(relativePath);
        accountTraversal();
        if (hardIgnored(normalized)) return true;
        String portablePath = portable(normalized);
        boolean ignored = evaluate(gitRules, portablePath, directory)
                || evaluate(minosRules, portablePath, directory);
        if (!directory && !ignored) accountRegularFile(normalized);
        return ignored;
    }

    public boolean isHardIgnored(Path relativePath) {
        Path normalized = normalizeRelative(relativePath);
        accountTraversal();
        return hardIgnored(normalized);
    }

    private static boolean hardIgnored(Path normalized) {
        for (Path segment : normalized) {
            if (HARD_IGNORED_DIRECTORY_NAMES.contains(segment.toString())) return true;
        }
        return false;
    }

    private void accountTraversal() {
        if (budget == null) return;
        try {
            budget.accountTraversalEntry();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void accountRegularFile(Path relative) {
        if (budget == null || !accountedRegularFiles.add(relative)) return;
        try {
            Path file = root.resolve(relative).normalize();
            if (file.startsWith(root) && Files.isRegularFile(file)) budget.accountRegularFile(Files.size(file));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
'''
if text.count(old_ignore_methods) != 1:
    raise SystemExit("ProjectIgnorePolicy ignore method anchor mismatch")
text = text.replace(old_ignore_methods, new_ignore_methods, 1)
old_rules = '''    private static List<IgnoreRule> readRules(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }

        List<IgnoreRule> rules = new ArrayList<>();
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            IgnoreRule rule = parseRule(rawLine);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }
'''
new_rules = '''    private static List<IgnoreRule> readRules(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<IgnoreRule> rules = new ArrayList<>();
        int lines = 0;
        try (BoundedInputStream input = new BoundedInputStream(
                     Files.newInputStream(file), MAX_IGNORE_BYTES, "project ignore file");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                lines++;
                if (lines > MAX_IGNORE_LINES) throw new IOException("project ignore file exceeds line limit");
                if (rawLine.length() > MAX_IGNORE_LINE_CHARS) {
                    throw new IOException("project ignore rule exceeds character limit");
                }
                IgnoreRule rule = parseRule(rawLine);
                if (rule != null) {
                    if (rules.size() >= MAX_IGNORE_RULES) throw new IOException("project ignore file exceeds rule limit");
                    rules.add(rule);
                }
            }
        }
        return List.copyOf(rules);
    }
'''
if text.count(old_rules) != 1:
    raise SystemExit("ProjectIgnorePolicy readRules anchor mismatch")
text = text.replace(old_rules, new_rules, 1)
write(ignore, text)

discovery = "minos-application/src/main/java/com/minos/discovery/ProjectDiscoveryService.java"
text = read(discovery)
text = text.replace("import java.io.IOException;\n", "import java.io.IOException;\nimport java.io.UncheckedIOException;\n", 1)
text = text.replace(
    "        ProjectIgnorePolicy ignorePolicy = ProjectIgnorePolicy.load(root);\n"
    "        validateSourceBudget(root, ignorePolicy);\n",
    "        SourceBudgetPolicy.Tracker budget = sourceBudgetPolicy.tracker(\"project discovery\");\n"
    "        ProjectIgnorePolicy ignorePolicy = ProjectIgnorePolicy.load(root, budget);\n",
    1,
)
# Remove the obsolete pre-validation traversal: actual detector traversals now consume the shared budget.
text, count = re.subn(r"\n    private void validateSourceBudget\(Path root, ProjectIgnorePolicy ignorePolicy\) throws IOException \{.*?\n    \}\n\n    private Map<Path, EnumSet<BuildSystem>>", "\n    private Map<Path, EnumSet<BuildSystem>>", text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("ProjectDiscoveryService validateSourceBudget block mismatch")
# Translate internal unchecked budget failures back to the API's checked IOException at the outer boundary.
method_start = "    public ProjectDiscovery discover(Path projectRoot) throws IOException {\n"
if text.count(method_start) != 1:
    raise SystemExit("ProjectDiscoveryService discover anchor mismatch")
# Wrap body by splitting at next public languageDetectors method.
start = text.index(method_start) + len(method_start)
end_marker = "\n    /** Exposes registered language classifiers"
end = text.index(end_marker, start)
body = text[start:end]
wrapped = "        try {\n" + "".join("    " + line if line.strip() else line for line in body.splitlines(True)) + "        } catch (UncheckedIOException exception) {\n            throw exception.getCause();\n        }\n"
text = text[:start] + wrapped + text[end:]
write(discovery, text)

# MNC-13 — state fingerprints consume source/config bytes through hard bounds.
java_ws = "minos-application/src/main/java/com/minos/program/analysis/JavaSourceWorkspace.java"
text = read(java_ws)
text = text.replace("package com.minos.program.analysis;\n\n", "package com.minos.program.analysis;\n\nimport com.minos.io.BoundedInputStream;\n", 1)
text = text.replace("import java.io.IOException;\n", "import java.io.IOException;\nimport java.io.InputStream;\n", 1)
text = text.replace("                digest.update(Files.readAllBytes(source.path()));\n", "                updateBounded(digest, source.path(), MAX_SOURCE_BYTES, \"Java source fingerprint\");\n", 1)
text = text.replace("                digest.update(Files.readAllBytes(config.orElseThrow()));\n", "                updateBounded(digest, config.orElseThrow(), MAX_SECURITY_CONFIG_BYTES, \"Java security config fingerprint\");\n", 1)
anchor = "    private static void update(MessageDigest digest, String value) {\n"
helper = '''    private static void updateBounded(MessageDigest digest, Path file, long maximum, String boundary)
            throws IOException {
        try (InputStream input = new BoundedInputStream(Files.newInputStream(file), maximum, boundary)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("JavaSourceWorkspace update anchor mismatch")
text = text.replace(anchor, helper + anchor, 1)
write(java_ws, text)

# MNC-14 — PostgreSQL knowledge snapshots use temp-file/JDBC streaming instead of whole byte[] payload copies.
codec = "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresSnapshotPayloadCodec.java"
write(codec, '''package com.minos.storage.postgresql;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotCodec;
import com.minos.store.SnapshotCodecV2;

import java.io.IOException;
import java.nio.file.Path;

final class PostgresSnapshotPayloadCodec {
    private final SnapshotCodecV2 codec = new SnapshotCodecV2();

    SnapshotCodec.SnapshotEncoding encode(Path target, CodeKnowledgeSnapshot snapshot) throws IOException {
        return codec.write(target, snapshot);
    }

    CodeKnowledgeSnapshot decode(Path payload) throws IOException {
        return codec.read(payload);
    }
}
''')

pg = "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresCodeKnowledgeSnapshotStore.java"
text = read(pg)
text = text.replace("import java.io.IOException;\n", "import java.io.IOException;\nimport java.io.InputStream;\nimport java.io.OutputStream;\n", 1)
text = text.replace("import java.security.MessageDigest;\nimport java.security.NoSuchAlgorithmException;\n", "", 1)
text = text.replace("import java.util.Arrays;\n", "", 1)
text = text.replace("import java.util.HexFormat;\n", "", 1)
text = text.replace("import java.util.UUID;\n", "import java.util.UUID;\nimport java.nio.file.Files;\nimport java.nio.file.Path;\n", 1)
text = text.replace("final class PostgresCodeKnowledgeSnapshotStore implements CodeKnowledgeSnapshotStore {\n", "final class PostgresCodeKnowledgeSnapshotStore implements CodeKnowledgeSnapshotStore {\n    private static final long MAX_PERSISTED_SNAPSHOT_BYTES = 3L * 1024L * 1024L * 1024L;\n", 1)
publish_pattern = r"    private void publishSnapshot\(CodeKnowledgeSnapshot snapshot\) throws IOException \{.*?\n    \}\n\n    private static String existingSnapshotSha"
publish_replacement = '''    private void publishSnapshot(CodeKnowledgeSnapshot snapshot) throws IOException {
        Path payload = Files.createTempFile("minos-postgresql-snapshot-", ".knowledge");
        try {
            String sha = codec.encode(payload, snapshot).sha256();
            long payloadBytes = Files.size(payload);
            if (payloadBytes < 1L || payloadBytes > MAX_PERSISTED_SNAPSHOT_BYTES) {
                throw new IOException("PostgreSQL knowledge snapshot payload exceeds streaming limit");
            }
            try {
                connections.inTransaction(connection -> {
                    String existingSha = existingSnapshotSha(connection, snapshot);
                    validateExistingSnapshot(snapshot, sha, existingSha);
                    if (existingSha == null) insertSnapshot(connection, snapshot, payload, payloadBytes, sha);
                    activateSnapshot(connection, snapshot);
                    return null;
                });
            } catch (SQLException exception) {
                throw new IOException("unable to publish PostgreSQL knowledge snapshot", exception);
            }
        } finally {
            Files.deleteIfExists(payload);
        }
    }

    private static String existingSnapshotSha'''
text, count = re.subn(publish_pattern, publish_replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("Postgres publishSnapshot mismatch")
insert_pattern = r"    private static void insertSnapshot\(.*?\n    \}\n\n    private static void activateSnapshot"
insert_replacement = '''    private static void insertSnapshot(
            Connection connection,
            CodeKnowledgeSnapshot snapshot,
            Path payload,
            long payloadBytes,
            String sha
    ) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO knowledge_snapshots(project_id,snapshot_id,payload,sha256,symbol_count,"
                        + "occurrence_count,relationship_count) VALUES (?,?,?,?,?,?,?)");
             InputStream input = Files.newInputStream(payload)) {
            statement.setObject(1, snapshot.projectId());
            statement.setString(2, snapshot.snapshotId());
            statement.setBinaryStream(3, input, payloadBytes);
            statement.setString(4, sha);
            statement.setInt(5, snapshot.symbols().size());
            statement.setInt(6, snapshot.occurrences().size());
            statement.setInt(7, snapshot.relationships().size());
            statement.executeUpdate();
        }
    }

    private static void activateSnapshot'''
text, count = re.subn(insert_pattern, insert_replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("Postgres insertSnapshot mismatch")
active_pattern = r"    private Optional<Row> activeRow\(UUID projectId\) throws IOException \{.*?\n    \}\n\n    private CodeKnowledgeSnapshot decodeVerified"
active_replacement = '''    private Optional<Row> activeRow(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.snapshot_id,s.payload,s.sha256 FROM knowledge_active a "
                                + "JOIN knowledge_snapshots s "
                                + "ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id "
                                + "WHERE a.project_id=?")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return Optional.empty();
                        Path payload = Files.createTempFile("minos-postgresql-snapshot-read-", ".knowledge");
                        try (InputStream input = result.getBinaryStream(2);
                             OutputStream output = Files.newOutputStream(payload)) {
                            copyBounded(input, output, MAX_PERSISTED_SNAPSHOT_BYTES);
                        } catch (Exception exception) {
                            Files.deleteIfExists(payload);
                            throw exception;
                        }
                        return Optional.of(new Row(result.getString(1), payload, result.getString(3)));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to load PostgreSQL knowledge snapshot", exception);
        }
    }

    private CodeKnowledgeSnapshot decodeVerified'''
text, count = re.subn(active_pattern, active_replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("Postgres activeRow mismatch")
tail_pattern = r"    private CodeKnowledgeSnapshot decodeVerified\(UUID projectId, Row row\) throws IOException \{.*?\n    \}\n\n    private static String sha256\(byte\[] bytes\) \{.*?\n    \}\n\n    private static String requireText"
tail_replacement = '''    private CodeKnowledgeSnapshot decodeVerified(UUID projectId, Row row) throws IOException {
        try {
            String actualSha = com.minos.store.SnapshotIntegrityService.sha256(row.payload());
            if (!row.sha256().equals(actualSha)) {
                throw new IOException("PostgreSQL knowledge snapshot checksum mismatch");
            }
            CodeKnowledgeSnapshot snapshot = codec.decode(row.payload());
            if (!projectId.equals(snapshot.projectId()) || !row.snapshotId().equals(snapshot.snapshotId())) {
                throw new IOException("PostgreSQL knowledge snapshot identity mismatch");
            }
            return snapshot;
        } finally {
            Files.deleteIfExists(row.payload());
        }
    }

    private static void copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total = Math.addExact(total, read);
            if (total > maximum) throw new IOException("PostgreSQL knowledge snapshot payload exceeds streaming limit");
            output.write(buffer, 0, read);
        }
    }

    private static String requireText'''
text, count = re.subn(tail_pattern, tail_replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("Postgres decode/hash tail mismatch")
row_pattern = r"    private record Row\(String snapshotId, byte\[] payload, String sha256\) \{.*?\n    \}\n\}"
row_replacement = '''    private record Row(String snapshotId, Path payload, String sha256) {
        private Row {
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(sha256, "sha256");
        }
    }
}'''
text, count = re.subn(row_pattern, row_replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit("Postgres Row record mismatch")
write(pg, text)

# MNC-15 — hosted control-plane byte limit is enforced during consumption.
hosted = "minos-storage-local/src/main/java/com/minos/store/FileHostedControlPlaneStore.java"
text = read(hosted)
text = text.replace("package com.minos.store;\n\n", "package com.minos.store;\n\nimport com.minos.io.BoundedInputStream;\n", 1)
old = '''        long size = Files.size(file);
        if (size < 1 || size > maxTenantBytes) throw new IOException("hosted tenant file size is invalid");
        byte[] bytes = Files.readAllBytes(file);
'''
new = '''        byte[] bytes;
        try (BoundedInputStream input = new BoundedInputStream(
                Files.newInputStream(file), maxTenantBytes, "hosted tenant file")) {
            bytes = input.readAllBytes();
        }
        if (bytes.length < 1) throw new IOException("hosted tenant file size is invalid");
'''
if text.count(old) != 1:
    raise SystemExit("FileHostedControlPlaneStore read bound anchor mismatch")
text = text.replace(old, new, 1)
write(hosted, text)

# MNC-16 — cached distributed manifest uses the same 64 KiB bounded reader as transported manifests.
distributed = "minos-runtime-local/src/main/java/com/minos/runtime/DistributedArtifactBundleStore.java"
replace_once(
    distributed,
    "            DistributedArtifactManifest actual = decodeManifest(Files.readAllBytes(manifestFile));\n",
    "            DistributedArtifactManifest actual;\n"
    "            try (InputStream input = Files.newInputStream(manifestFile)) {\n"
    "                actual = decodeManifest(readBounded(input, MAX_MANIFEST_BYTES));\n"
    "            }\n"
)

# MNC-17 — reject an oversized worker artifact before hashing it for the manifest.
worker = "minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java"
text = read(worker)
text = text.replace("import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;\n", "import com.minos.orchestration.IndexArtifactLimits;\nimport com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;\n", 1)
old = '''            Path artifactPath = artifact.finalArtifact().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(artifactPath)
                    || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(artifactPath) < 1L) {
                throw new IOException("worker sandbox did not produce a non-empty regular artifact");
            }
            Instant completedAt = clock.instant();
'''
new = '''            Path artifactPath = artifact.finalArtifact().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(artifactPath)
                    || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("worker sandbox did not produce a non-empty regular artifact");
            }
            long artifactBytes = Files.size(artifactPath);
            if (artifactBytes < 1L) throw new IOException("worker sandbox did not produce a non-empty regular artifact");
            if (artifactBytes > IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES) {
                throw new IOException("worker SCIP artifact exceeds configured byte limit before hashing");
            }
            Instant completedAt = clock.instant();
'''
if text.count(old) != 1:
    raise SystemExit("LocalIsolatedIndexWorker artifact prehash anchor mismatch")
text = text.replace(old, new, 1)
text = text.replace("                    Files.size(artifactPath),\n                    DistributedArtifactBundleStore.sha256(artifactPath));\n", "                    artifactBytes,\n                    DistributedArtifactBundleStore.sha256(artifactPath));\n", 1)
write(worker, text)

# MNC-20 — corrupted huge counts never become huge ArrayList preallocations before EOF validation.
binary = "minos-storage-local/src/main/java/com/minos/store/SnapshotBinaryCodecSupport.java"
text = read(binary)
text = re.sub(r"new ArrayList<>\((symbolCount|occurrenceCount|relationshipCount|count)\)", r"new ArrayList<>(initialCapacity(\1))", text)
anchor = "    private SnapshotBinaryCodecSupport() {\n    }\n"
helper = '''    private static int initialCapacity(int declaredCount) {
        // Counts describe protocol limits, not a trusted heap-allocation request. Grow incrementally.
        return Math.min(Math.max(0, declaredCount), 16_384);
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("SnapshotBinaryCodecSupport constructor anchor mismatch")
text = text.replace(anchor, anchor + "\n" + helper, 1)
write(binary, text)

print("MNC bounded-ingestion remediation staged")

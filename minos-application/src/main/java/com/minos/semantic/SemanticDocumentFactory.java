package com.minos.semantic;

import com.minos.context.LocalSourceReader;
import com.minos.context.SourceExcerpt;
import com.minos.context.TokenEstimator;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds deterministic SYMBOL/FILE/CHUNK semantic units from one active MINOS snapshot. */
public final class SemanticDocumentFactory {

    private static final int SYMBOL_MAX_TOKENS = 768;
    private static final int CHUNK_MAX_TOKENS = 768;
    private static final int FILE_MAX_TOKENS = 2_048;

    public List<SemanticDocument> build(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        return build(project, snapshot, SemanticIndexBudget.DEFAULT, 1);
    }

    public List<SemanticDocument> build(
            RegisteredProject project,
            CodeKnowledgeSnapshot snapshot,
            SemanticIndexBudget budget,
            int dimensions
    ) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(budget, "budget");
        String projectId = project.id().toString();
        if (!project.id().equals(snapshot.projectId())) {
            throw new IllegalArgumentException("snapshot belongs to another project");
        }

        SemanticIndexBudget.Tracker tracker = budget.tracker(dimensions);
        LocalSourceReader sourceReader = new LocalSourceReader(project.rootPath());
        List<SemanticDocument> documents = new ArrayList<>();
        Map<String, List<Symbol>> symbolsByFile = new LinkedHashMap<>();

        for (Symbol symbol : snapshot.symbols()) {
            if (symbol.external()) continue;
            String stableSymbolKey = "symbol:" + symbol.symbolKey();
            String symbolContent = bounded(symbolText(symbol), SYMBOL_MAX_TOKENS);
            add(documents, tracker, document(projectId, snapshot.snapshotId(), SemanticDocumentKind.SYMBOL,
                    stableSymbolKey, symbol.id(), fileId(symbol), startLine(symbol), endLine(symbol), symbolContent));

            SymbolLocation location = symbol.location();
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
        }

        for (Map.Entry<String, List<Symbol>> entry : symbolsByFile.entrySet()) {
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

        documents.sort(Comparator.comparing(SemanticDocument::stableKey));
        String previous = null;
        for (SemanticDocument document : documents) {
            if (document.stableKey().equals(previous)) {
                throw new IllegalStateException("duplicate semantic stable key: " + previous);
            }
            previous = document.stableKey();
        }
        return List.copyOf(documents);
    }

    private static void add(
            List<SemanticDocument> documents,
            SemanticIndexBudget.Tracker tracker,
            SemanticDocument document
    ) throws IOException {
        tracker.account(document);
        documents.add(document);
    }

    private static SemanticDocument document(
            String projectId,
            String snapshotId,
            SemanticDocumentKind kind,
            String stableKey,
            String sourceId,
            String fileId,
            int startLine,
            int endLine,
            String content
    ) {
        String checksum = sha256(kind.name() + "\u001f" + stableKey + "\u001f" + content);
        String id = "semantic:" + sha256(snapshotId + "\u001f" + stableKey + "\u001f" + checksum);
        return new SemanticDocument(id, stableKey, projectId, snapshotId, kind, sourceId,
                fileId, startLine, endLine, content, checksum);
    }

    private static String symbolText(Symbol symbol) {
        return String.join("\n",
                "kind " + symbol.kind().name(),
                "name " + symbol.name(),
                "qualified " + textOr(symbol.qualifiedName(), symbol.name()),
                "signature " + textOr(symbol.signature(), ""),
                "language " + symbol.language(),
                "module " + textOr(symbol.moduleId(), ""));
    }

    private static String fileId(Symbol symbol) {
        return symbol.location() == null ? symbol.fileId() : symbol.location().fileId();
    }

    private static int startLine(Symbol symbol) {
        return symbol.location() == null ? 0 : symbol.location().startLine();
    }

    private static int endLine(Symbol symbol) {
        return symbol.location() == null ? 0 : symbol.location().endLine();
    }

    private static String bounded(String value, int maxTokens) {
        String text = Objects.requireNonNull(value, "value");
        return TokenEstimator.estimate(text) <= maxTokens ? text : TokenEstimator.truncate(text, maxTokens);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

package com.minos.context;

import com.minos.domain.SymbolLocation;
import com.minos.io.BoundedInputStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lecteur UTF-8 confiné à la racine réelle d'un projet enregistré.
 */
public final class LocalSourceReader implements SourceReader {

    private static final long MAX_SOURCE_BYTES = 16L * 1024L * 1024L;

    private final Path projectRoot;

    public LocalSourceReader(Path projectRoot) throws IOException {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        if (!Files.isDirectory(this.projectRoot)) {
            throw new IllegalArgumentException("projectRoot must be a directory");
        }
    }

    @Override
    public Optional<SourceExcerpt> readExcerpt(
            SymbolLocation location,
            int contextLines,
            int maxTokens
    ) throws IOException {
        Objects.requireNonNull(location, "location");
        if (contextLines < 0) {
            throw new IllegalArgumentException("contextLines must not be negative");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be greater than zero");
        }

        Optional<Path> source = resolveReadableSource(location.fileId());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines = readLines(source.orElseThrow());
        if (lines.isEmpty() || location.startLine() > lines.size()) {
            return Optional.empty();
        }

        int declarationStart = location.startLine() - 1;
        int declarationEnd = Math.min(lines.size() - 1, location.endLine() - 1);
        int start = Math.max(0, declarationStart - contextLines);
        int end = Math.min(lines.size() - 1, declarationEnd + contextLines);
        int requestedStart = start;
        int requestedEnd = end;
        String content = join(lines, start, end);

        while (TokenEstimator.estimate(content) > maxTokens
                && (start < declarationStart || end > declarationEnd)) {
            int leftContext = declarationStart - start;
            int rightContext = end - declarationEnd;
            if (rightContext >= leftContext && rightContext > 0) {
                end--;
            } else if (leftContext > 0) {
                start++;
            }
            content = join(lines, start, end);
        }

        boolean contentTruncated = TokenEstimator.estimate(content) > maxTokens;
        if (contentTruncated) {
            content = TokenEstimator.truncate(content, maxTokens);
        }
        int totalFileTokens = TokenEstimator.estimate(String.join("\n", lines));
        boolean truncated = start != requestedStart || end != requestedEnd || contentTruncated;
        int actualEnd = content.isEmpty()
                ? start + 1
                : start + 1 + (int) content.chars().filter(character -> character == '\n').count();

        return Optional.of(new SourceExcerpt(
                location.fileId(),
                start + 1,
                Math.max(start + 1, actualEnd),
                content,
                false,
                truncated,
                TokenEstimator.estimate(content),
                lines.size(),
                totalFileTokens
        ));
    }

    @Override
    public SourceExcerpt readFull(String fileId) throws IOException {
        Path source = resolveReadableSource(requireFileId(fileId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "source file is not resolvable inside the project: " + fileId));
        String content = readText(source);
        int totalLines = content.isEmpty()
                ? 0
                : 1 + (int) content.chars().filter(character -> character == '\n').count();
        int tokens = TokenEstimator.estimate(content);
        return new SourceExcerpt(
                fileId,
                1,
                Math.max(1, totalLines),
                content,
                true,
                false,
                tokens,
                totalLines,
                tokens
        );
    }

    private List<String> readLines(Path source) throws IOException {
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

    private String readText(Path source) throws IOException {
        try (BoundedInputStream input = new BoundedInputStream(
                Files.newInputStream(source), MAX_SOURCE_BYTES, "source file")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Optional<Path> resolveReadableSource(String fileId) throws IOException {
        String required = requireFileId(fileId);
        if (required.startsWith("file:")) {
            return Optional.empty();
        }

        Path relative;
        try {
            relative = Path.of(required.replace('/', java.io.File.separatorChar)).normalize();
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || relative.startsWith("..")) {
            throw new IllegalArgumentException("fileId must be a project-relative path");
        }
        Path candidate = projectRoot.resolve(relative).normalize();
        if (!candidate.startsWith(projectRoot)) {
            throw new IllegalArgumentException("fileId escapes the project root");
        }
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(projectRoot)) {
            throw new IllegalArgumentException("source symlink escapes the project root");
        }
        return Optional.of(real);
    }

    private static String requireFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        return fileId;
    }

    private static String join(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start, end + 1));
    }
}

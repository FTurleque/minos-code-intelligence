package com.minos.context;

import com.minos.domain.SymbolLocation;
import com.minos.io.BoundedInputStream;
import com.minos.io.ConfinedFileOpener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
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
        LoadedSource loaded = readSource(source.orElseThrow());
        List<String> lines = loaded.lines();
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
        int totalFileTokens = loaded.totalTokens();
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

    /**
     * Reads the current file contents for every excerpt request.
     *
     * <p>The previous single-path cache could return stale source indefinitely when a file changed
     * between requests. Timestamp/size based invalidation would still be heuristic on file systems
     * with coarse timestamp resolution, so correctness takes precedence over this tiny cache.</p>
     */
    private LoadedSource readSource(Path source) throws IOException {
        List<String> lines = new ArrayList<>();
        int utf8Bytes = 0;
        try (SeekableByteChannel channel = openConfined(source);
             BoundedInputStream input = new BoundedInputStream(
                     Channels.newInputStream(channel), MAX_SOURCE_BYTES, "source file");
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
        return new LoadedSource(List.copyOf(lines), totalTokens);
    }

    private String readText(Path source) throws IOException {
        try (SeekableByteChannel channel = openConfined(source);
             BoundedInputStream input = new BoundedInputStream(
                     Channels.newInputStream(channel), MAX_SOURCE_BYTES, "source file")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Opens a validated project-relative source under the confinement guarantee.
     *
     * <p>The byte ceiling stays on the stream rather than on a pre-read {@code Files.size()} for the
     * same reason the open itself is confined: a size observed before the read says nothing about
     * the bytes that follow it.</p>
     */
    private SeekableByteChannel openConfined(Path relativeSource) throws IOException {
        try {
            return ConfinedFileOpener.openConfinedRegularFile(projectRoot, relativeSource);
        } catch (ConfinedFileOpener.ConfinementException exception) {
            // Kept as an invalid-argument failure, exactly as the previous escaping-symlink check
            // was: the request named something the project is not allowed to serve. The message is
            // deliberately path-free.
            throw new IllegalArgumentException("source file is not a confined project file", exception);
        }
    }

    /**
     * Validates the requested identifier and answers the project-relative path to open.
     *
     * <p>It deliberately stops at the relative path and no longer hands back a resolved real path.
     * A real path is a <em>pathname</em>, and re-walking a pathname at open time is precisely the
     * gap this reader had: whatever was proven about the object it named could stop being true
     * before the bytes were read. Containment is therefore established once here on the request
     * shape, and then again -- against the actual object -- by {@link ConfinedFileOpener} at the
     * moment of the open.</p>
     */
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
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(relative);
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

    private record LoadedSource(List<String> lines, int totalTokens) { }
}

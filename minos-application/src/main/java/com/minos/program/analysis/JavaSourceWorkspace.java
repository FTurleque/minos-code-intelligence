package com.minos.program.analysis;

import com.minos.io.BoundedInputStream;
import com.minos.domain.Symbol;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers and confines the exact Java source set represented by a structured snapshot.
 *
 * <p>This component owns source-count/size limits, path confinement, source-state fingerprinting
 * and security-configuration confinement. It deliberately performs no AST or graph analysis.</p>
 */
final class JavaSourceWorkspace {

    private static final int MAX_SOURCE_FILES = 2_000;
    private static final long MAX_SOURCE_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_TOTAL_SOURCE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SECURITY_CONFIG_BYTES = 1024L * 1024L;

    private JavaSourceWorkspace() {
    }

    static Discovery discover(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Path root = project.rootPath().toRealPath();
        Set<String> requested = new LinkedHashSet<>();
        for (Symbol symbol : snapshot.symbols()) {
            if (!"java".equalsIgnoreCase(symbol.language())) {
                continue;
            }
            String fileId = symbol.fileId();
            if ((fileId == null || fileId.isBlank()) && symbol.location() != null) {
                fileId = symbol.location().fileId();
            }
            if (fileId != null && fileId.toLowerCase(Locale.ROOT).endsWith(".java")) {
                requested.add(fileId.replace('\\', '/'));
            }
        }
        List<String> requestedFileIds = requested.stream().sorted().toList();
        if (requestedFileIds.isEmpty()) {
            return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_NOT_APPLICABLE");
        }
        if (requestedFileIds.size() > MAX_SOURCE_FILES) {
            return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_FILE_LIMIT_EXCEEDED");
        }

        List<SourceFile> sources = new ArrayList<>();
        long total = 0L;
        for (String fileId : requestedFileIds) {
            Path relative;
            try {
                relative = Path.of(fileId.replace('/', java.io.File.separatorChar)).normalize();
            } catch (InvalidPathException exception) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_INVALID_FILE_ID");
            }
            if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_INVALID_FILE_ID");
            }
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_MISSING");
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_ESCAPE");
            }
            long bytes = Files.size(real);
            if (bytes > MAX_SOURCE_BYTES) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_TOO_LARGE");
            }
            total += bytes;
            if (total > MAX_TOTAL_SOURCE_BYTES) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_TOTAL_SOURCE_LIMIT_EXCEEDED");
            }
            sources.add(new SourceFile(fileId, real));
        }
        return Discovery.usable(requestedFileIds, List.copyOf(sources));
    }

    static String stateFingerprint(
            RegisteredProject project,
            CodeKnowledgeSnapshot snapshot,
            Discovery discovery
    ) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, snapshot.snapshotId());
            update(digest, discovery.limitation() == null ? "USABLE" : discovery.limitation());
            for (String fileId : discovery.requestedFileIds()) {
                update(digest, fileId);
            }
            for (SourceFile source : discovery.sources()) {
                update(digest, source.fileId());
                updateBounded(digest, source.path(), MAX_SOURCE_BYTES, "Java source fingerprint");
            }
            Optional<Path> config = securityConfig(project.rootPath());
            if (config.isPresent()) {
                update(digest, JavaSourceProgramGraphProvider.SECURITY_CONFIG);
                updateBounded(digest, config.orElseThrow(), MAX_SECURITY_CONFIG_BYTES, "Java security config fingerprint");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static Optional<Path> securityConfig(Path projectRoot) throws IOException {
        Path root = projectRoot.toRealPath();
        Path candidate = root.resolve(JavaSourceProgramGraphProvider.SECURITY_CONFIG).normalize();
        if (!candidate.startsWith(root) || !Files.exists(candidate)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("Java advanced provider security config is not a regular file");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Java advanced provider security config escapes project root");
        }
        if (Files.size(real) > MAX_SECURITY_CONFIG_BYTES) {
            throw new IOException("Java advanced provider security config exceeds 1 MiB");
        }
        return Optional.of(real);
    }

    private static void updateBounded(MessageDigest digest, Path file, long maximum, String boundary)
            throws IOException {
        try (InputStream input = new BoundedInputStream(Files.newInputStream(file), maximum, boundary)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((byte) 0);
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    record Discovery(
            boolean usable,
            List<String> requestedFileIds,
            List<SourceFile> sources,
            String limitation
    ) {
        static Discovery usable(List<String> requestedFileIds, List<SourceFile> sources) {
            return new Discovery(true, requestedFileIds, sources, null);
        }

        static Discovery failed(List<String> requestedFileIds, String limitation) {
            return new Discovery(false, requestedFileIds, List.of(), limitation);
        }
    }

    record SourceFile(String fileId, Path path) {
    }
}

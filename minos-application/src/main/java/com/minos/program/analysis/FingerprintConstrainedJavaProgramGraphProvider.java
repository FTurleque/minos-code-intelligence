package com.minos.program.analysis;

import com.minos.domain.Symbol;
import com.minos.incremental.FileFingerprint;
import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.program.ProgramGraph;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Production wrapper that binds Java advanced analysis to the immutable M7 fingerprint snapshot.
 *
 * <p>A warm ProgramGraph cache hit no longer needs to re-read every Java source merely to derive
 * the provider cache key. On a cache miss, all Java sources represented by the structured snapshot
 * are checked against their exact persisted SHA-256 before the AST provider is allowed to run. If
 * the working tree has moved away from the active snapshot, analysis fails closed instead of mixing
 * two source states.</p>
 */
public final class FingerprintConstrainedJavaProgramGraphProvider implements ProgramGraphProvider {

    public static final String SOURCE_MISMATCH_LIMITATION =
            "JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT";

    private final FileProjectFingerprintSnapshotStore fingerprints;
    private final JavaSourceProgramGraphProvider delegate;
    private final Map<FingerprintKey, ProjectFingerprintSnapshot> immutableFingerprintCache =
            new ConcurrentHashMap<>();

    public FingerprintConstrainedJavaProgramGraphProvider(
            FileProjectFingerprintSnapshotStore fingerprints
    ) {
        this(fingerprints, new JavaSourceProgramGraphProvider());
    }

    FingerprintConstrainedJavaProgramGraphProvider(
            FileProjectFingerprintSnapshotStore fingerprints,
            JavaSourceProgramGraphProvider delegate
    ) {
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String id() {
        return JavaSourceProgramGraphProvider.PROVIDER_ID;
    }

    @Override
    public String cacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Optional<ProjectFingerprintSnapshot> exact = exactFingerprint(project.id(), snapshot.snapshotId());
        if (exact.isEmpty()) {
            return delegate.cacheKey(project, snapshot);
        }
        return id() + ":snapshot-fingerprint:"
                + exact.orElseThrow().fingerprint().projectSha256()
                + ":security-config:" + securityConfigSha256(project.rootPath());
    }

    @Override
    public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Optional<ProjectFingerprintSnapshot> exact = exactFingerprint(project.id(), snapshot.snapshotId());
        if (exact.isPresent() && !matchesExactSnapshot(project, snapshot, exact.orElseThrow())) {
            return new ProgramGraph(
                    project.id().toString(),
                    snapshot.snapshotId(),
                    Set.of(),
                    List.of(),
                    List.of(),
                    List.of(SOURCE_MISMATCH_LIMITATION));
        }
        return delegate.analyze(project, snapshot);
    }

    private Optional<ProjectFingerprintSnapshot> exactFingerprint(UUID projectId, String snapshotId) throws IOException {
        FingerprintKey key = new FingerprintKey(projectId, snapshotId);
        ProjectFingerprintSnapshot cached = immutableFingerprintCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ProjectFingerprintSnapshot> loaded = fingerprints.load(projectId, snapshotId);
        loaded.ifPresent(value -> immutableFingerprintCache.putIfAbsent(key, value));
        return loaded;
    }

    private static boolean matchesExactSnapshot(
            RegisteredProject project,
            CodeKnowledgeSnapshot snapshot,
            ProjectFingerprintSnapshot fingerprintSnapshot
    ) throws IOException {
        Map<String, FileFingerprint> expected = fingerprintSnapshot.fingerprint().files().stream()
                .collect(Collectors.toUnmodifiableMap(FileFingerprint::relativePath, Function.identity()));
        Path root = project.rootPath().toRealPath();
        Set<String> javaFiles = new LinkedHashSet<>();
        for (Symbol symbol : snapshot.symbols()) {
            if (!"java".equalsIgnoreCase(symbol.language())) {
                continue;
            }
            String fileId = symbol.fileId();
            if ((fileId == null || fileId.isBlank()) && symbol.location() != null) {
                fileId = symbol.location().fileId();
            }
            if (fileId != null && fileId.toLowerCase(Locale.ROOT).endsWith(".java")) {
                javaFiles.add(fileId.replace('\\', '/'));
            }
        }

        for (String fileId : javaFiles) {
            FileFingerprint expectedFile = expected.get(fileId);
            if (expectedFile == null) {
                return false;
            }
            Path candidate = root.resolve(Path.of(fileId.replace('/', java.io.File.separatorChar))).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return false;
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)
                    || Files.size(real) != expectedFile.sizeBytes()
                    || !sha256(real).equals(expectedFile.sha256())) {
                return false;
            }
        }
        return true;
    }

    private static String securityConfigSha256(Path projectRoot) throws IOException {
        Path root = projectRoot.toRealPath();
        Path candidate = root.resolve(JavaSourceProgramGraphProvider.SECURITY_CONFIG).normalize();
        if (!candidate.startsWith(root) || !Files.exists(candidate)) {
            return "absent";
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("Java advanced provider security config is not a regular file");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Java advanced provider security config escapes project root");
        }
        return sha256(real);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record FingerprintKey(UUID projectId, String snapshotId) {
        private FingerprintKey {
            Objects.requireNonNull(projectId, "projectId");
            if (snapshotId == null || snapshotId.isBlank()) {
                throw new IllegalArgumentException("snapshotId must not be blank");
            }
        }
    }
}

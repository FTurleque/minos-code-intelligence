package com.minos.program.analysis;

import com.minos.domain.Symbol;
import com.minos.incremental.FileFingerprint;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.program.ProgramGraph;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Production wrapper binding Java advanced analysis to the immutable fingerprint snapshot. */
public final class FingerprintConstrainedJavaProgramGraphProvider implements ProgramGraphProvider {

    public static final String SOURCE_MISMATCH_LIMITATION =
            "JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT";
    public static final int DEFAULT_MAX_FINGERPRINT_CACHE_ENTRIES = 64;
    public static final long DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_SECURITY_CONFIG_BYTES = 1024L * 1024L;

    private final ProjectFingerprintSnapshotStore fingerprints;
    private final JavaSourceProgramGraphProvider delegate;
    private final LinkedHashMap<UUID, CachedFingerprint> immutableFingerprintCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private long cacheWeightBytes;
    private long cacheEvictions;

    public FingerprintConstrainedJavaProgramGraphProvider(ProjectFingerprintSnapshotStore fingerprints) {
        this(fingerprints, new JavaSourceProgramGraphProvider());
    }

    FingerprintConstrainedJavaProgramGraphProvider(ProjectFingerprintSnapshotStore fingerprints,
                                                   JavaSourceProgramGraphProvider delegate) {
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public String id() { return JavaSourceProgramGraphProvider.PROVIDER_ID; }

    @Override
    public String cacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Optional<ProjectFingerprintSnapshot> exact = exactFingerprint(project.id(), snapshot.snapshotId());
        if (exact.isEmpty()) return delegate.cacheKey(project, snapshot);
        return id() + ":snapshot-fingerprint:" + exact.orElseThrow().fingerprint().projectSha256()
                + ":security-config:" + securityConfigSha256(project.rootPath());
    }

    @Override
    public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Optional<ProjectFingerprintSnapshot> exact = exactFingerprint(project.id(), snapshot.snapshotId());
        if (exact.isPresent() && !matchesExactSnapshot(project, snapshot, exact.orElseThrow())) {
            return new ProgramGraph(project.id().toString(), snapshot.snapshotId(), Set.of(), List.of(), List.of(),
                    List.of(SOURCE_MISMATCH_LIMITATION));
        }
        return delegate.analyze(project, snapshot);
    }

    public FingerprintCacheStats fingerprintCacheStats() {
        synchronized (immutableFingerprintCache) {
            return new FingerprintCacheStats(
                    immutableFingerprintCache.size(), cacheWeightBytes,
                    DEFAULT_MAX_FINGERPRINT_CACHE_ENTRIES, DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES,
                    cacheEvictions);
        }
    }

    private Optional<ProjectFingerprintSnapshot> exactFingerprint(UUID projectId, String snapshotId) throws IOException {
        synchronized (immutableFingerprintCache) {
            CachedFingerprint cached = immutableFingerprintCache.get(projectId);
            if (cached != null && cached.snapshotId().equals(snapshotId)) return Optional.of(cached.snapshot());
        }

        Optional<ProjectFingerprintSnapshot> loaded = fingerprints.load(projectId, snapshotId);
        if (loaded.isEmpty()) return Optional.empty();
        ProjectFingerprintSnapshot value = loaded.orElseThrow();
        long weight = estimateWeight(value);
        synchronized (immutableFingerprintCache) {
            CachedFingerprint current = immutableFingerprintCache.get(projectId);
            if (current != null && current.snapshotId().equals(snapshotId)) return Optional.of(current.snapshot());
            if (current != null) cacheWeightBytes -= current.weightBytes();
            if (weight <= DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES) {
                immutableFingerprintCache.put(projectId, new CachedFingerprint(snapshotId, value, weight));
                cacheWeightBytes = safeAdd(cacheWeightBytes, weight);
                trimCache();
            } else {
                immutableFingerprintCache.remove(projectId);
            }
        }
        return Optional.of(value);
    }

    private void trimCache() {
        Iterator<Map.Entry<UUID, CachedFingerprint>> iterator = immutableFingerprintCache.entrySet().iterator();
        while ((immutableFingerprintCache.size() > DEFAULT_MAX_FINGERPRINT_CACHE_ENTRIES
                || cacheWeightBytes > DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES) && iterator.hasNext()) {
            Map.Entry<UUID, CachedFingerprint> eldest = iterator.next();
            cacheWeightBytes -= eldest.getValue().weightBytes();
            iterator.remove();
            cacheEvictions++;
        }
        if (cacheWeightBytes < 0L) cacheWeightBytes = 0L;
    }

    private static long estimateWeight(ProjectFingerprintSnapshot snapshot) {
        long weight = 4_096L;
        for (FileFingerprint file : snapshot.fingerprint().files()) {
            weight = safeAdd(weight, 256L);
            weight = safeAdd(weight, stringWeight(file.relativePath()));
            weight = safeAdd(weight, stringWeight(file.sha256()));
        }
        return weight;
    }

    private static long stringWeight(String value) {
        return value == null ? 0L : safeAdd(40L, (long) value.length() * Character.BYTES);
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static boolean matchesExactSnapshot(RegisteredProject project, CodeKnowledgeSnapshot snapshot,
                                                ProjectFingerprintSnapshot fingerprintSnapshot) throws IOException {
        Map<String, FileFingerprint> expected = fingerprintSnapshot.fingerprint().files().stream()
                .collect(Collectors.toUnmodifiableMap(FileFingerprint::relativePath, Function.identity()));
        Path root = project.rootPath().toRealPath();
        Set<String> javaFiles = new LinkedHashSet<>();
        for (Symbol symbol : snapshot.symbols()) {
            if (!"java".equalsIgnoreCase(symbol.language())) continue;
            String fileId = symbol.fileId();
            if ((fileId == null || fileId.isBlank()) && symbol.location() != null) fileId = symbol.location().fileId();
            if (fileId != null && fileId.toLowerCase(Locale.ROOT).endsWith(".java")) javaFiles.add(fileId.replace('\\', '/'));
        }
        for (String fileId : javaFiles) {
            FileFingerprint expectedFile = expected.get(fileId);
            if (expectedFile == null) return false;
            Path candidate = root.resolve(Path.of(fileId.replace('/', java.io.File.separatorChar))).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) return false;
            Path real = candidate.toRealPath();
            if (!real.startsWith(root) || Files.size(real) != expectedFile.sizeBytes()
                    || !sha256Exact(real, expectedFile.sizeBytes(), "Java snapshot source").equals(expectedFile.sha256())) return false;
        }
        return true;
    }

    private static String securityConfigSha256(Path projectRoot) throws IOException {
        Path root = projectRoot.toRealPath();
        Path candidate = root.resolve(JavaSourceProgramGraphProvider.SECURITY_CONFIG).normalize();
        if (!candidate.startsWith(root) || !Files.exists(candidate)) return "absent";
        // The loader (BoundedProperties) already refuses a linked security config; a fingerprint that
        // followed one would cover a different file than the analysis reads.
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Java advanced provider security config is not a regular file");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) throw new IOException("Java advanced provider security config escapes project root");
        long size = Files.size(real);
        if (size > MAX_SECURITY_CONFIG_BYTES) {
            throw new IOException("Java advanced provider security config exceeds byte limit: " + size);
        }
        return sha256Exact(real, size, "Java advanced provider security config", LinkOption.NOFOLLOW_LINKS);
    }

    private static String sha256Exact(Path file, long expectedBytes, String label, OpenOption... options)
            throws IOException {
        if (expectedBytes < 0L) throw new IOException(label + " has a negative expected size");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            try (InputStream input = Files.newInputStream(file, options)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    try { total = Math.addExact(total, read); }
                    catch (ArithmeticException exception) { throw new IOException(label + " byte counter overflow", exception); }
                    if (total > expectedBytes) throw new IOException(label + " grew while being fingerprinted");
                    digest.update(buffer, 0, read);
                }
            }
            if (total != expectedBytes) throw new IOException(label + " changed size while being fingerprinted");
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record CachedFingerprint(String snapshotId, ProjectFingerprintSnapshot snapshot, long weightBytes) { }
    public record FingerprintCacheStats(int entries, long weightBytes, int maximumEntries,
                                        long maximumWeightBytes, long evictions) { }
}

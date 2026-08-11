package com.minos.runtime;

import com.minos.io.BoundedProperties;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Creates, verifies and bounds the local cache of M25 distributed artifact bundles. */
public final class DistributedArtifactBundleStore {

    static final String MANIFEST_ENTRY = "manifest.properties";
    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;

    private final Path cacheRoot;
    private final Path leasesRoot;
    private final DistributedArtifactCachePolicy policy;
    private static final int LEASE_STRIPE_COUNT = 64;
    private final Object leaseMonitor = new Object();
    private final ReentrantLock[] leaseStripes = createLeaseStripes();
    private final Map<String, LeaseState> activeLeases = new HashMap<>();

    public DistributedArtifactBundleStore(Path minosHome) throws IOException {
        this(minosHome, DistributedArtifactCachePolicy.DEFAULT);
    }

    public DistributedArtifactBundleStore(
            Path minosHome,
            DistributedArtifactCachePolicy policy
    ) throws IOException {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.cacheRoot = home.resolve("distributed-artifacts");
        this.leasesRoot = cacheRoot.resolve(".leases");
        this.policy = Objects.requireNonNull(policy, "policy");
        Files.createDirectories(cacheRoot);
        Files.createDirectories(leasesRoot);
    }

    public Path createBundle(
            Path output,
            DistributedArtifactManifest manifest,
            Path artifact
    ) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Path source = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("distributed worker artifact is missing");
        }
        long size = Files.size(source);
        if (size != manifest.artifactSize() || size > policy.maxArtifactBytes()) {
            throw new IOException("distributed worker artifact size does not match its manifest or limit");
        }
        if (!sha256(source).equals(manifest.artifactSha256())) {
            throw new IOException("distributed worker artifact checksum does not match its manifest");
        }

        Path target = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".bundle-", ".tmp");
        try {
            byte[] manifestBytes = encodeManifest(manifest);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ))) {
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                zip.write(manifestBytes);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(DistributedArtifactManifest.ARTIFACT_PATH));
                try (InputStream input = Files.newInputStream(source)) {
                    transferBounded(input, zip, policy.maxArtifactBytes());
                }
                zip.closeEntry();
            }
            move(temporary, target);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Accepts and leases a verified cache entry. The caller must invoke {@link #release(VerifiedArtifact)}
     * after the artifact has been staged/consumed so cross-process eviction can reclaim it safely.
     *
     * <p>Accept is deliberately not synchronized on the whole store. Cache publication is serialized
     * only by the key stripe, so a cross-process lease wait for one artifact cannot block unrelated
     * cache keys in the JVM.</p>
     */
    public VerifiedArtifact accept(Path bundle) throws IOException {
        Path source = Objects.requireNonNull(bundle, "bundle").toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("distributed artifact bundle is missing");
        }
        long maxBundleBytes = Math.addExact(policy.maxArtifactBytes(), 1024L * 1024L);
        if (Files.size(source) > maxBundleBytes) {
            throw new IOException("distributed artifact bundle exceeds its byte limit");
        }

        Path extraction = Files.createTempDirectory(cacheRoot, ".accept-");
        String leasedKey = null;
        try {
            Extracted extracted = extract(source, extraction);
            DistributedArtifactManifest manifest = decodeManifest(extracted.manifestBytes());
            Path artifact = extracted.artifact();
            long size = Files.size(artifact);
            if (size != manifest.artifactSize()) {
                throw new IOException("transported artifact size does not match its manifest");
            }
            String checksum = sha256(artifact);
            if (!checksum.equals(manifest.artifactSha256())) {
                throw new IOException("transported artifact checksum does not match its manifest");
            }

            String cacheKey = cacheKey(manifest);
            ReentrantLock operationLock = leaseStripe(cacheKey);
            operationLock.lock();
            try {
                acquireLease(cacheKey);
                leasedKey = cacheKey;
                Path entry = cacheRoot.resolve(cacheKey);
                Path cachedArtifact = entry.resolve(DistributedArtifactManifest.ARTIFACT_PATH);
                Path cachedManifest = entry.resolve(MANIFEST_ENTRY);
                String bundleSha = sha256(source);
                if (validCached(entry, manifest)) {
                    Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.from(Instant.now()));
                    leasedKey = null;
                    return new VerifiedArtifact(manifest, cachedArtifact, cacheKey, true, bundleSha);
                }
                if (Files.exists(entry)) {
                    deleteCacheTree(entry);
                }
                Files.createDirectory(entry);
                try {
                    move(artifact, cachedArtifact);
                    Files.write(cachedManifest, extracted.manifestBytes());
                    evict(cacheKey);
                } catch (IOException | RuntimeException exception) {
                    try {
                        if (Files.exists(entry)) deleteCacheTree(entry);
                    } catch (IOException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                    throw exception;
                }
                leasedKey = null;
                return new VerifiedArtifact(manifest, cachedArtifact, cacheKey, false, bundleSha);
            } finally {
                operationLock.unlock();
            }
        } finally {
            if (leasedKey != null) {
                releaseLease(leasedKey);
            }
            if (Files.exists(extraction)) {
                deleteCacheTree(extraction);
            }
        }
    }

    public void release(VerifiedArtifact artifact) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        releaseLease(artifact.cacheKey());
    }

    private Extracted extract(Path bundle, Path extraction) throws IOException {
        byte[] manifest = null;
        Path artifact = extraction.resolve(DistributedArtifactManifest.ARTIFACT_PATH);
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!Set.of(MANIFEST_ENTRY, DistributedArtifactManifest.ARTIFACT_PATH).contains(name)
                        || entry.isDirectory()
                        || !names.add(name)) {
                    throw new IOException("distributed artifact bundle contains an unsafe or duplicate entry");
                }
                if (MANIFEST_ENTRY.equals(name)) {
                    manifest = readBounded(zip, MAX_MANIFEST_BYTES);
                } else {
                    copyBounded(zip, artifact, policy.maxArtifactBytes());
                }
                zip.closeEntry();
            }
        }
        if (!names.equals(Set.of(MANIFEST_ENTRY, DistributedArtifactManifest.ARTIFACT_PATH))
                || manifest == null
                || !Files.isRegularFile(artifact)) {
            throw new IOException("distributed artifact bundle must contain exactly manifest.properties and index.scip");
        }
        return new Extracted(manifest, artifact);
    }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transferBounded(input, output, maximum);
        return output.toByteArray();
    }

    private static void copyBounded(InputStream input, Path target, long maximum) throws IOException {
        try (OutputStream output = Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            transferBounded(input, output, maximum);
        } catch (Exception exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
    }

    private static void transferBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            try {
                total = Math.addExact(total, read);
            } catch (ArithmeticException exception) {
                throw new IOException("distributed artifact bundle byte counter overflow", exception);
            }
            if (total > maximum) {
                throw new IOException("distributed artifact bundle entry exceeds its byte limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private static byte[] encodeManifest(DistributedArtifactManifest manifest) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("format", manifest.format());
        properties.setProperty("runId", manifest.runId().toString());
        properties.setProperty("projectId", manifest.projectId().toString());
        properties.setProperty("sourceRepository", manifest.sourceRepository());
        properties.setProperty("sourceCommit", manifest.sourceCommit());
        properties.setProperty("language", manifest.language().name());
        properties.setProperty("providerId", manifest.providerId());
        properties.setProperty("providerVersion", manifest.providerVersion());
        properties.setProperty("workerId", manifest.workerId());
        properties.setProperty("isolation", manifest.isolation().name());
        properties.setProperty("networkPolicy", manifest.networkPolicy().name());
        properties.setProperty("networkDenyEnforced", Boolean.toString(manifest.networkDenyEnforced()));
        properties.setProperty("startedAt", manifest.startedAt().toString());
        properties.setProperty("completedAt", manifest.completedAt().toString());
        properties.setProperty("artifactPath", manifest.artifactPath());
        properties.setProperty("artifactSize", Long.toString(manifest.artifactSize()));
        properties.setProperty("artifactSha256", manifest.artifactSha256());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Writer writer = new java.io.OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, "MINOS M25 distributed artifact manifest - no secrets");
        }
        return output.toByteArray();
    }

    private static DistributedArtifactManifest decodeManifest(byte[] bytes) throws IOException {
        Properties properties = BoundedProperties.loadUtf8(
                bytes, MAX_MANIFEST_BYTES, 32, 128, 16_384,
                "distributed artifact manifest");
        Set<String> expected = Set.of(
                "format", "runId", "projectId", "sourceRepository", "sourceCommit", "language",
                "providerId", "providerVersion", "workerId", "isolation", "networkPolicy",
                "networkDenyEnforced", "startedAt", "completedAt", "artifactPath",
                "artifactSize", "artifactSha256"
        );
        if (!properties.stringPropertyNames().equals(expected)) {
            throw new IOException("distributed artifact manifest fields do not match format v1");
        }
        try {
            return new DistributedArtifactManifest(
                    required(properties, "format"),
                    UUID.fromString(required(properties, "runId")),
                    UUID.fromString(required(properties, "projectId")),
                    required(properties, "sourceRepository"),
                    required(properties, "sourceCommit"),
                    Language.valueOf(required(properties, "language")),
                    required(properties, "providerId"),
                    required(properties, "providerVersion"),
                    required(properties, "workerId"),
                    WorkerIsolation.valueOf(required(properties, "isolation")),
                    WorkerNetworkPolicy.valueOf(required(properties, "networkPolicy")),
                    parseBooleanStrict(required(properties, "networkDenyEnforced")),
                    Instant.parse(required(properties, "startedAt")),
                    Instant.parse(required(properties, "completedAt")),
                    required(properties, "artifactPath"),
                    Long.parseLong(required(properties, "artifactSize")),
                    required(properties, "artifactSha256")
            );
        } catch (RuntimeException exception) {
            throw new IOException("distributed artifact manifest is invalid", exception);
        }
    }

    private boolean validCached(Path entry, DistributedArtifactManifest expected) {
        try {
            Path manifestFile = entry.resolve(MANIFEST_ENTRY);
            Path artifact = entry.resolve(DistributedArtifactManifest.ARTIFACT_PATH);
            if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(artifact)) {
                return false;
            }
            DistributedArtifactManifest actual;
            try (InputStream input = Files.newInputStream(manifestFile)) {
                actual = decodeManifest(readBounded(input, MAX_MANIFEST_BYTES));
            }
            return expected.equals(actual)
                    && Files.size(artifact) == actual.artifactSize()
                    && sha256(artifact).equals(actual.artifactSha256());
        } catch (Exception exception) {
            return false;
        }
    }

    private void evict(String protectedKey) throws IOException {
        List<CacheEntry> entries = new ArrayList<>();
        try (var paths = Files.list(cacheRoot)) {
            for (Path path : paths.filter(Files::isDirectory)
                    .filter(value -> !value.getFileName().toString().startsWith("."))
                    .toList()) {
                entries.add(new CacheEntry(
                        path,
                        path.getFileName().toString(),
                        Files.getLastModifiedTime(path).toInstant(),
                        sizeOf(path)
                ));
            }
        }
        entries.sort(Comparator.comparing(CacheEntry::lastAccessAt).thenComparing(CacheEntry::key));
        long bytes = 0L;
        for (CacheEntry entry : entries) {
            try {
                bytes = Math.addExact(bytes, entry.size());
            } catch (ArithmeticException exception) {
                throw new IOException("distributed artifact cache byte counter overflow", exception);
            }
        }
        int count = entries.size();
        for (CacheEntry entry : entries) {
            if (count <= policy.maxEntries() && bytes <= policy.maxCacheBytes()) {
                break;
            }
            if (protectedKey.equals(entry.key())) {
                continue;
            }
            try (EvictionLease lease = tryAcquireEvictionLease(entry.key())) {
                if (lease == null) continue;
                deleteCacheTree(entry.path());
                count--;
                bytes -= entry.size();
            }
        }
        if (count > policy.maxEntries() || bytes > policy.maxCacheBytes()) {
            throw new IOException("distributed artifact cache limits cannot retain the accepted artifact without evicting an active entry");
        }
    }

    private void acquireLease(String cacheKey) throws IOException {
        ReentrantLock stripe = leaseStripe(cacheKey);
        stripe.lock();
        try {
            synchronized (leaseMonitor) {
                LeaseState existing = activeLeases.get(cacheKey);
                if (existing != null) {
                    existing.references++;
                    return;
                }
            }
            Path leaseFile = leasesRoot.resolve(cacheKey + ".lease");
            FileChannel channel = FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                // Deliberately outside leaseMonitor: another process may hold this lock indefinitely.
                FileLock lock = channel.lock();
                synchronized (leaseMonitor) {
                    activeLeases.put(cacheKey, new LeaseState(channel, lock));
                }
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        } finally {
            stripe.unlock();
        }
    }

    private ReentrantLock leaseStripe(String cacheKey) {
        return leaseStripes[Math.floorMod(cacheKey.hashCode(), leaseStripes.length)];
    }

    private static ReentrantLock[] createLeaseStripes() {
        ReentrantLock[] stripes = new ReentrantLock[LEASE_STRIPE_COUNT];
        for (int index = 0; index < stripes.length; index++) stripes[index] = new ReentrantLock();
        return stripes;
    }

    private void releaseLease(String cacheKey) throws IOException {
        synchronized (leaseMonitor) {
            LeaseState state = activeLeases.get(cacheKey);
            if (state == null) return;
            state.references--;
            if (state.references > 0) return;
            activeLeases.remove(cacheKey);
            IOException failure = null;
            try {
                state.lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                state.channel.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) throw failure;
        }
    }

    private EvictionLease tryAcquireEvictionLease(String cacheKey) throws IOException {
        synchronized (leaseMonitor) {
            if (activeLeases.containsKey(cacheKey)) return null;
        }
        Path leaseFile = leasesRoot.resolve(cacheKey + ".lease");
        FileChannel channel = FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                channel.close();
                return null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            return new EvictionLease(channel, lock);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    private static String cacheKey(DistributedArtifactManifest manifest) {
        return sha256(String.join("\n",
                manifest.format(),
                manifest.sourceRepository(),
                manifest.sourceCommit(),
                manifest.language().name(),
                manifest.providerId(),
                manifest.providerVersion(),
                manifest.artifactSha256()
        ));
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        MessageDigest digest = digest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteCacheTree(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.equals(cacheRoot) || !normalized.startsWith(cacheRoot)) {
            throw new IOException("refusing to delete outside the distributed artifact cache");
        }
        if (!Files.exists(normalized)) return;
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static long sizeOf(Path root) throws IOException {
        long total = 0L;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total = Math.addExact(total, Files.size(path));
            }
        }
        return total;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing distributed artifact manifest field: " + key);
        }
        return value;
    }

    private static boolean parseBooleanStrict(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("boolean manifest values must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record VerifiedArtifact(
            DistributedArtifactManifest manifest,
            Path artifact,
            String cacheKey,
            boolean cacheHit,
            String bundleSha256
    ) {
        public VerifiedArtifact {
            Objects.requireNonNull(manifest, "manifest");
            artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
            if (cacheKey == null || !cacheKey.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("cacheKey must be SHA-256");
            }
            if (bundleSha256 == null || !bundleSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("bundleSha256 must be SHA-256");
            }
        }
    }

    private static final class LeaseState {
        private final FileChannel channel;
        private final FileLock lock;
        private int references = 1;

        private LeaseState(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
    }

    private static final class EvictionLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private EvictionLease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) throw failure;
        }
    }

    private record Extracted(byte[] manifestBytes, Path artifact) {
    }

    private record CacheEntry(Path path, String key, Instant lastAccessAt, long size) {
    }
}

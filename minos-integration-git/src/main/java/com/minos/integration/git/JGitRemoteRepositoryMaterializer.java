package com.minos.integration.git;

import com.minos.io.BoundedFileLease;
import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;
import com.minos.io.FileTreeOperations;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.Sha256;
import com.minos.io.SharedCacheLeaseRegistry;
import com.minos.io.StaleScratchReclamation;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JGit HTTPS materializer with immutable revision checks, active-use leases and a bounded local cache.
 *
 * <p>Cache metadata is stored outside the checkout. Authentication material is resolved only for
 * the clone call and is never written to the repository config, cache metadata or diagnostics.</p>
 */
public final class JGitRemoteRepositoryMaterializer implements RemoteRepositoryMaterializer {

    private static final String FORMAT_VERSION = "1";
    private static final String METADATA_FILE = "entry.properties";
    private static final String PIN_FILE = "registered.pin";
    private static final String REPOSITORY_DIRECTORY = "repository";
    private static final String ENTRY_TEMPORARY_PREFIX = ".entry-";
    private static final String ENTRY_TEMPORARY_SUFFIX = ".tmp";
    private static final int MAX_CACHE_ROOT_SCAN_ENTRIES = 4_096;
    static final Duration LOCK_ACQUIRE_TIMEOUT = Duration.ofMinutes(2);
    private static final int LOCK_STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final Path cacheRoot;
    private final Path locksRoot;
    private final Path leasesRoot;
    private final RemoteRepositoryCachePolicy cachePolicy;
    private final RemoteGitClient gitClient;
    private final SecretResolver secretResolver;
    private final Clock clock;
    private final SharedCacheLeaseRegistry leases;
    private final Duration lockTimeout;

    public JGitRemoteRepositoryMaterializer(Path minosHome) throws IOException {
        this(minosHome, RemoteRepositoryCachePolicy.DEFAULT);
    }

    public JGitRemoteRepositoryMaterializer(Path minosHome, RemoteRepositoryCachePolicy cachePolicy) throws IOException {
        this(minosHome, cachePolicy, new JGitRemoteGitClient(),
                name -> Optional.ofNullable(System.getenv(name)).map(String::toCharArray), Clock.systemUTC());
    }

    JGitRemoteRepositoryMaterializer(
            Path minosHome,
            RemoteRepositoryCachePolicy cachePolicy,
            RemoteGitClient gitClient,
            SecretResolver secretResolver,
            Clock clock
    ) throws IOException {
        this(minosHome, cachePolicy, gitClient, secretResolver, clock, LOCK_ACQUIRE_TIMEOUT);
    }

    JGitRemoteRepositoryMaterializer(
            Path minosHome,
            RemoteRepositoryCachePolicy cachePolicy,
            RemoteGitClient gitClient,
            SecretResolver secretResolver,
            Clock clock,
            Duration lockTimeout
    ) throws IOException {
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        Path remoteRoot = home.resolve("remote-cache");
        this.cacheRoot = remoteRoot.resolve("repositories");
        this.locksRoot = remoteRoot.resolve("locks");
        this.leasesRoot = remoteRoot.resolve("leases");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.gitClient = Objects.requireNonNull(gitClient, "gitClient");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        PrivateLocalStorage.ensurePrivateDirectory(remoteRoot);
        PrivateLocalStorage.ensurePrivateDirectory(cacheRoot);
        PrivateLocalStorage.ensurePrivateDirectory(locksRoot);
        PrivateLocalStorage.ensurePrivateDirectory(leasesRoot);
        this.leases = new SharedCacheLeaseRegistry(leasesRoot, LOCK_ACQUIRE_TIMEOUT, "remote cache");
    }

    @Override
    public RemoteMaterialization materialize(RemoteRepositoryRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        String cacheKey = cacheKey(request);
        leases.acquire(cacheKey);
        boolean success = false;
        try {
            Path lockFile = locksRoot.resolve(cacheKey + ".lock");
            ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(cacheKey.hashCode(), JVM_LOCKS.length)];
            try (BoundedFileLease ignored = BoundedFileLease.acquire(
                    lockFile, jvmLock, lockTimeout, "remote materialization lock " + cacheKey)) {
                RemoteMaterialization result = materializeLocked(request, cacheKey);
                success = true;
                return result;
            }
        } finally {
            if (!success) leases.release(cacheKey);
        }
    }

    @Override
    public void pin(RemoteMaterialization materialization) throws IOException {
        Path entry = validatedEntry(materialization);
        PrivateLocalStorage.writePrivateFile(
                entry.resolve(PIN_FILE),
                ("registeredAt=" + clock.instant() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void unpin(RemoteMaterialization materialization) throws IOException {
        Path entry = validatedEntry(materialization);
        Files.deleteIfExists(entry.resolve(PIN_FILE));
    }

    @Override
    public void release(RemoteMaterialization materialization) throws IOException {
        Objects.requireNonNull(materialization, "materialization");
        leases.release(materialization.cacheKey());
    }

    private Path validatedEntry(RemoteMaterialization materialization) throws IOException {
        Objects.requireNonNull(materialization, "materialization");
        Path entry = cacheRoot.resolve(materialization.cacheKey()).toAbsolutePath().normalize();
        if (!entry.startsWith(cacheRoot) || !Files.isDirectory(entry)) {
            throw new IOException("remote materialization is outside the active cache");
        }
        Path repositoryRoot = entry.resolve(REPOSITORY_DIRECTORY).toRealPath();
        if (!repositoryRoot.equals(materialization.repositoryRoot().toRealPath())) {
            throw new IOException("remote materialization does not match its cache entry");
        }
        return entry;
    }

    private RemoteMaterialization materializeLocked(RemoteRepositoryRequest request, String cacheKey) throws Exception {
        Path entry = cacheRoot.resolve(cacheKey);
        Optional<CacheEntry> cached = readValidEntry(entry, request, cacheKey);
        if (cached.isPresent()) {
            CacheEntry value = cached.orElseThrow();
            touch(entry, value.metadata(), clock.instant());
            return materialization(request, value.repositoryRoot(), cacheKey, true, value.materializedAt());
        }
        if (Files.exists(entry)) deleteCacheTree(entry);

        // MINOS-AUD-A02: a clone killed before its finally leaves its temporary for ever. Reclaimed here, where the next
        // one is created: bounded, never fatal, and only the temporaries, never a valid cache entry (evict() ignores
        // the entries that start with a dot).
        StaleScratchReclamation.reclaim(cacheRoot, JGitRemoteRepositoryMaterializer::isEntryTemporary,
                java.util.Set.of(), clock.instant());
        Path temporary = cacheRoot.resolve(ENTRY_TEMPORARY_PREFIX + UUID.randomUUID() + ENTRY_TEMPORARY_SUFFIX);
        PrivateLocalStorage.ensurePrivateDirectory(temporary);
        try {
            Path repositoryRoot = temporary.resolve(REPOSITORY_DIRECTORY);
            char[] secret = resolveSecret(request);
            try {
                gitClient.cloneRepository(request, repositoryRoot, secret,
                        new CloneBudget(repositoryRoot, cachePolicy));
            } finally {
                if (secret != null) java.util.Arrays.fill(secret, '\0');
            }
            validateCheckout(repositoryRoot, request);
            ensureProjectRoot(repositoryRoot, request.projectSubdirectory());

            Instant now = clock.instant();
            Properties metadata = metadata(request, now, now);
            writeProperties(temporary.resolve(METADATA_FILE), metadata);
            long entrySize = new CloneBudget(temporary, cachePolicy).checkpoint().bytes();
            if (entrySize > cachePolicy.maxBytes()) {
                throw new IOException("remote repository exceeds the configured cache byte limit");
            }
            moveDirectory(temporary, entry);
            try {
                evict(cacheKey);
            } catch (IOException exception) {
                deleteCacheTree(entry);
                throw exception;
            }
            return materialization(request, entry.resolve(REPOSITORY_DIRECTORY), cacheKey, false, now);
        } finally {
            if (Files.exists(temporary)) deleteCacheTree(temporary);
        }
    }

    private static boolean isEntryTemporary(String name) {
        return name.startsWith(ENTRY_TEMPORARY_PREFIX) && name.endsWith(ENTRY_TEMPORARY_SUFFIX);
    }

    private char[] resolveSecret(RemoteRepositoryRequest request) {
        if (request.credentialEnvironmentVariable().isEmpty()) return null;
        String name = request.credentialEnvironmentVariable().orElseThrow();
        char[] secret = secretResolver.resolve(name)
                .orElseThrow(() -> new IllegalStateException("configured credential environment variable is unavailable"));
        if (secret.length == 0) throw new IllegalStateException("configured credential environment variable is empty");
        return secret;
    }

    private Optional<CacheEntry> readValidEntry(Path entry, RemoteRepositoryRequest request, String cacheKey) {
        try {
            Path metadataFile = entry.resolve(METADATA_FILE);
            Path repositoryRoot = entry.resolve(REPOSITORY_DIRECTORY);
            if (!Files.isRegularFile(metadataFile) || !Files.isDirectory(repositoryRoot)) return Optional.empty();
            Properties metadata = readProperties(metadataFile);
            if (!FORMAT_VERSION.equals(metadata.getProperty("formatVersion"))
                    || !cacheKey.equals(metadata.getProperty("cacheKey"))
                    || !request.canonicalRepositoryUri().equals(metadata.getProperty("repositoryUri"))
                    || !request.reference().equals(metadata.getProperty("reference"))
                    || !request.expectedCommit().equals(metadata.getProperty("commit"))
                    || !portableSubdirectory(request.projectSubdirectory()).equals(metadata.getProperty("projectSubdirectory"))) {
                return Optional.empty();
            }
            new CloneBudget(repositoryRoot, cachePolicy).checkpoint();
            validateCheckout(repositoryRoot, request);
            ensureProjectRoot(repositoryRoot, request.projectSubdirectory());
            return Optional.of(new CacheEntry(repositoryRoot, metadata, Instant.parse(required(metadata, "materializedAt"))));
        } catch (Exception unusableEntry) {
            return Optional.empty();
        }
    }

    private RemoteMaterialization materialization(
            RemoteRepositoryRequest request,
            Path repositoryRoot,
            String cacheKey,
            boolean cacheHit,
            Instant materializedAt
    ) throws IOException {
        Path realRepository = repositoryRoot.toRealPath();
        Path projectRoot = ensureProjectRoot(realRepository, request.projectSubdirectory());
        return new RemoteMaterialization(request, realRepository, projectRoot, cacheKey, cacheHit, materializedAt);
    }

    private static Path ensureProjectRoot(Path repositoryRoot, Path subdirectory) throws IOException {
        Path realRepository = repositoryRoot.toRealPath();
        Path projectRoot = subdirectory.toString().isEmpty() ? realRepository : realRepository.resolve(subdirectory).toRealPath();
        if (!projectRoot.startsWith(realRepository) || !Files.isDirectory(projectRoot)) {
            throw new IOException("remote project subdirectory escapes the materialized repository");
        }
        return projectRoot;
    }

    private static void validateCheckout(Path repositoryRoot, RemoteRepositoryRequest request) throws Exception {
        try (Git git = Git.open(repositoryRoot.toFile())) {
            String head = Objects.requireNonNull(
                    git.getRepository().resolve(Constants.HEAD), "materialized repository has no HEAD").getName();
            if (!request.expectedCommit().equals(head)) throw new IOException("remote ref resolved to an unexpected commit");
            if (!git.status().call().isClean()) throw new IOException("materialized remote checkout is not clean");
            String origin = git.getRepository().getConfig().getString("remote", "origin", "url");
            if (!request.canonicalRepositoryUri().equals(origin)) {
                throw new IOException("materialized remote origin does not match the canonical repository URI");
            }
        }
    }

    private void evict(String protectedKey) throws IOException {
        List<EvictionCandidate> entries = new ArrayList<>();
        try (var paths = Files.list(cacheRoot)) {
            var iterator = paths.iterator();
            int scanned = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++scanned > MAX_CACHE_ROOT_SCAN_ENTRIES) {
                    throw new IOException("remote repository cache root exceeds entry scan limit");
                }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        || path.getFileName().toString().startsWith(".")) {
                    continue;
                }
                try {
                    Properties metadata = readProperties(path.resolve(METADATA_FILE));
                    long size = new CloneBudget(path, cachePolicy).checkpoint().bytes();
                    entries.add(new EvictionCandidate(path, path.getFileName().toString(),
                            Instant.parse(required(metadata, "lastAccessAt")), size, false));
                } catch (Exception exception) {
                    entries.add(new EvictionCandidate(
                            path, path.getFileName().toString(), Instant.EPOCH, 0L, true));
                }
            }
        }
        entries.sort(Comparator.comparing(EvictionCandidate::lastAccessAt).thenComparing(EvictionCandidate::cacheKey));
        long bytes = 0L;
        int invalid = 0;
        for (EvictionCandidate entry : entries) {
            bytes = saturatingAdd(bytes, entry.size());
            if (entry.invalid()) invalid++;
        }
        int count = entries.size();
        for (EvictionCandidate candidate : entries) {
            if (count <= cachePolicy.maxEntries() && bytes <= cachePolicy.maxBytes() && invalid == 0) break;
            if (protectedKey.equals(candidate.cacheKey()) || Files.isRegularFile(candidate.path().resolve(PIN_FILE))) {
                continue;
            }
            try (SharedCacheLeaseRegistry.EvictionLease lease = leases.tryAcquireEviction(candidate.cacheKey())) {
                if (lease == null) continue;
                deleteCacheTree(candidate.path());
                count--;
                bytes -= candidate.size();
                if (candidate.invalid()) invalid--;
            }
        }
        if (count > cachePolicy.maxEntries() || bytes > cachePolicy.maxBytes() || invalid > 0) {
            throw new IOException("remote cache limits cannot be satisfied without evicting an active or registered entry");
        }
    }

    private static Properties metadata(RemoteRepositoryRequest request, Instant materializedAt, Instant lastAccessAt) {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", FORMAT_VERSION);
        properties.setProperty("cacheKey", cacheKey(request));
        properties.setProperty("host", request.host().name());
        properties.setProperty("repositoryUri", request.canonicalRepositoryUri());
        properties.setProperty("reference", request.reference());
        properties.setProperty("commit", request.expectedCommit());
        properties.setProperty("projectSubdirectory", portableSubdirectory(request.projectSubdirectory()));
        properties.setProperty("fetchNetworkPolicy", request.fetchNetworkPolicy().name());
        properties.setProperty("materializedAt", materializedAt.toString());
        properties.setProperty("lastAccessAt", lastAccessAt.toString());
        return properties;
    }

    private static void touch(Path entry, Properties metadata, Instant instant) throws IOException {
        metadata.setProperty("lastAccessAt", instant.toString());
        Path target = entry.resolve(METADATA_FILE);
        Path temporary = PrivateLocalStorage.createPrivateTempFile(entry, ".metadata-", ".tmp");
        try {
            writeProperties(temporary, metadata);
            moveFile(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String cacheKey(RemoteRepositoryRequest request) {
        return Sha256.hex(String.join("\n",
                request.host().name(), request.canonicalRepositoryUri(), request.reference(), request.expectedCommit(),
                portableSubdirectory(request.projectSubdirectory()), request.fetchNetworkPolicy().name()));
    }

    private static String portableSubdirectory(Path path) {
        return path.toString().isEmpty() ? "." : path.toString().replace('\\', '/');
    }

    private void deleteCacheTree(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.equals(cacheRoot) || !normalized.startsWith(cacheRoot)) {
            throw new IOException("refusing to delete outside the remote repository cache");
        }
        FileTreeOperations.deleteRecursively(normalized);
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static Properties readProperties(Path file) throws IOException {
        return BoundedProperties.load(
                file, 64L * 1024L, 32, 128, 16_384,
                "remote repository cache metadata");
    }

    private static void writeProperties(Path file, Properties properties) throws IOException {
        StringWriter writer = new StringWriter();
        properties.store(writer, "MINOS remote cache metadata - no secrets");
        PrivateLocalStorage.writePrivateFile(file, writer.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing remote cache metadata: " + key);
        return value;
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("remote repository cache requires atomic directory publication", exception);
        }
    }

    private static void moveFile(Path source, Path target) throws IOException {
        DurableAtomicFile.replace(source, target, "remote repository cache metadata replacement");
    }

    interface RemoteGitClient {
        void cloneRepository(RemoteRepositoryRequest request, Path destination, char[] secret, CloneBudget budget) throws Exception;
    }

    interface SecretResolver {
        Optional<char[]> resolve(String environmentVariable);
    }

    /** Compatibility type retained for existing package-level transport and tests. */
    static final class CloneBudget extends RemoteCloneBudget {
        CloneBudget(Path destination, RemoteRepositoryCachePolicy policy) {
            super(destination, policy);
        }
    }

    private record CacheEntry(Path repositoryRoot, Properties metadata, Instant materializedAt) { }
    private record EvictionCandidate(
            Path path, String cacheKey, Instant lastAccessAt, long size, boolean invalid) { }

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }
}

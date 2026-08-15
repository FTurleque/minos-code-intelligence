package com.minos.git;

import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.remote.RemoteRepositoryRequest.RemoteHost;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    private static final int MAX_CACHE_ROOT_SCAN_ENTRIES = 4_096;
    static final Duration LOCK_ACQUIRE_TIMEOUT = Duration.ofMinutes(2);
    private static final long LOCK_POLL_MILLIS = 50L;

    private final Path cacheRoot;
    private final Path locksRoot;
    private final Path leasesRoot;
    private final RemoteRepositoryCachePolicy cachePolicy;
    private final RemoteGitClient gitClient;
    private final SecretResolver secretResolver;
    private final Clock clock;
    private static final int LEASE_STRIPE_COUNT = 64;
    private final Object leaseMonitor = new Object();
    private final ReentrantLock[] leaseStripes = createLeaseStripes();
    private final Map<String, LeaseState> activeLeases = new HashMap<>();

    public JGitRemoteRepositoryMaterializer(Path minosHome) throws IOException {
        this(minosHome, RemoteRepositoryCachePolicy.DEFAULT);
    }

    public JGitRemoteRepositoryMaterializer(Path minosHome, RemoteRepositoryCachePolicy cachePolicy) throws IOException {
        this(minosHome, cachePolicy, new JGitClient(),
                name -> Optional.ofNullable(System.getenv(name)).map(String::toCharArray), Clock.systemUTC());
    }

    JGitRemoteRepositoryMaterializer(
            Path minosHome,
            RemoteRepositoryCachePolicy cachePolicy,
            RemoteGitClient gitClient,
            SecretResolver secretResolver,
            Clock clock
    ) throws IOException {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        Path remoteRoot = home.resolve("remote-cache");
        this.cacheRoot = remoteRoot.resolve("repositories");
        this.locksRoot = remoteRoot.resolve("locks");
        this.leasesRoot = remoteRoot.resolve("leases");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.gitClient = Objects.requireNonNull(gitClient, "gitClient");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(cacheRoot);
        Files.createDirectories(locksRoot);
        Files.createDirectories(leasesRoot);
    }

    @Override
    public RemoteMaterialization materialize(RemoteRepositoryRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        String cacheKey = cacheKey(request);
        acquireLease(cacheKey);
        boolean success = false;
        try {
            Path lockFile = locksRoot.resolve(cacheKey + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = acquireFileLock(
                         channel, LOCK_ACQUIRE_TIMEOUT, "remote materialization lock " + cacheKey)) {
                RemoteMaterialization result = materializeLocked(request, cacheKey);
                success = true;
                return result;
            }
        } finally {
            if (!success) releaseLease(cacheKey);
        }
    }

    @Override
    public void pin(RemoteMaterialization materialization) throws IOException {
        Path entry = validatedEntry(materialization);
        Files.writeString(
                entry.resolve(PIN_FILE),
                "registeredAt=" + clock.instant() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    @Override
    public void unpin(RemoteMaterialization materialization) throws IOException {
        Path entry = validatedEntry(materialization);
        Files.deleteIfExists(entry.resolve(PIN_FILE));
    }

    @Override
    public void release(RemoteMaterialization materialization) throws IOException {
        Objects.requireNonNull(materialization, "materialization");
        releaseLease(materialization.cacheKey());
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

        Path temporary = cacheRoot.resolve(".entry-" + UUID.randomUUID() + ".tmp");
        Files.createDirectory(temporary);
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

    private void acquireLease(String cacheKey) throws IOException {
        ReentrantLock stripe = leaseStripe(cacheKey);
        boolean stripeAcquired = false;
        try {
            stripeAcquired = stripe.tryLock(LOCK_ACQUIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!stripeAcquired) {
                throw new IOException("timed out waiting for remote cache JVM lease after "
                        + LOCK_ACQUIRE_TIMEOUT + ": " + cacheKey);
            }
            synchronized (leaseMonitor) {
                LeaseState existing = activeLeases.get(cacheKey);
                if (existing != null) {
                    existing.references++;
                    return;
                }
            }
            LeaseState created = openActiveLease(cacheKey);
            synchronized (leaseMonitor) {
                activeLeases.put(cacheKey, created);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for remote cache JVM lease: " + cacheKey, interrupted);
        } finally {
            if (stripeAcquired) stripe.unlock();
        }
    }

    private LeaseState openActiveLease(String cacheKey) throws IOException {
        Path leaseFile = leasesRoot.resolve(cacheKey + ".lease");
        FileChannel channel = FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = acquireFileLock(
                    channel, LOCK_ACQUIRE_TIMEOUT, "remote cache active-use lease " + cacheKey);
            return new LeaseState(channel, lock);
        } catch (IOException | RuntimeException failure) {
            closeLeaseChannelAfterFailure(channel, failure);
            throw failure;
        }
    }

    private static void closeLeaseChannelAfterFailure(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    static FileLock acquireFileLock(FileChannel channel, Duration timeout, String description) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Duration wait = Objects.requireNonNull(timeout, "timeout");
        if (wait.isZero() || wait.isNegative()) throw new IllegalArgumentException("lock timeout must be positive");
        String label = Objects.requireNonNull(description, "description");
        long deadline = deadline(wait);
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException unavailableInThisJvm) {
                // A lock held through another channel in this JVM is still unavailable to this caller.
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("timed out waiting for " + label + " after " + wait);
            }
            sleepUntilRetry(deadline, label);
        }
    }

    private static long deadline(Duration timeout) {
        long now = System.nanoTime();
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        return nanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
    }

    private static void sleepUntilRetry(long deadline, String description) throws IOException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) return;
        long convertedMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        long sleepMillis = boundedPollMillis(convertedMillis);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + description, interrupted);
        }
    }

    private static long boundedPollMillis(long convertedMillis) {
        if (convertedMillis <= 0L) return 1L;
        if (convertedMillis > LOCK_POLL_MILLIS) return LOCK_POLL_MILLIS;
        return convertedMillis;
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
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
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
        } catch (Exception ignored) {
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
            try (EvictionLease lease = tryAcquireEvictionLease(candidate.cacheKey())) {
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
        Path temporary = Files.createTempFile(entry, ".metadata-", ".tmp");
        try {
            writeProperties(temporary, metadata);
            moveFile(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String cacheKey(RemoteRepositoryRequest request) {
        return sha256(String.join("\n",
                request.host().name(), request.canonicalRepositoryUri(), request.reference(), request.expectedCommit(),
                portableSubdirectory(request.projectSubdirectory()), request.fetchNetworkPolicy().name()));
    }

    private static String portableSubdirectory(Path path) {
        return path.toString().isEmpty() ? "." : path.toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteCacheTree(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.equals(cacheRoot) || !normalized.startsWith(cacheRoot)) {
            throw new IOException("refusing to delete outside the remote repository cache");
        }
        if (!Files.exists(normalized)) return;
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                clearReadOnly(path);
                Files.deleteIfExists(path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                clearReadOnly(directory);
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path, DosFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (attributes != null && attributes.readAttributes().isReadOnly()) attributes.setReadOnly(false);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS file systems do not need this Windows-specific cleanup.
        }
    }

    private static long checkedAdd(long left, long right) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException("remote cache byte counter overflow", exception);
        }
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
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(writer, "MINOS remote cache metadata - no secrets");
        }
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

    static final class CloneBudget {
        private final Path destination;
        private final long maxBytes;
        private final long maxFiles;
        private final long maxDirectories;
        private final long maxTraversalEntries;
        private final long timeoutNanos;
        private final long startedNanos = System.nanoTime();

        CloneBudget(Path destination, RemoteRepositoryCachePolicy policy) {
            this.destination = Objects.requireNonNull(destination, "destination");
            RemoteRepositoryCachePolicy limits = Objects.requireNonNull(policy, "policy");
            this.maxBytes = limits.maxBytes();
            this.maxFiles = limits.maxFiles();
            this.maxDirectories = limits.maxDirectories();
            this.maxTraversalEntries = limits.maxTraversalEntries();
            this.timeoutNanos = limits.cloneTimeout().toNanos();
        }

        TreeMetrics checkpoint() throws IOException {
            enforceTimeout();
            if (!Files.exists(destination)) return new TreeMetrics(0L, 0L, 0L, 0L);
            final long[] bytes = {0L};
            final long[] files = {0L};
            final long[] directories = {0L};
            final long[] entries = {0L};
            Files.walkFileTree(destination, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    enforceTimeout();
                    entries[0] = increment(entries[0], "traversal entry");
                    directories[0] = increment(directories[0], "directory");
                    enforceCardinality(files[0], directories[0], entries[0]);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    enforceTimeout();
                    entries[0] = increment(entries[0], "traversal entry");
                    files[0] = increment(files[0], "file");
                    enforceCardinality(files[0], directories[0], entries[0]);
                    if (attributes.isRegularFile()) {
                        bytes[0] = checkedAdd(bytes[0], attributes.size());
                        if (bytes[0] > maxBytes) {
                            throw new IOException("remote repository exceeds the configured clone byte limit");
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                    enforceTimeout();
                    entries[0] = increment(entries[0], "traversal entry");
                    enforceCardinality(files[0], directories[0], entries[0]);
                    throw failure;
                }
            });
            enforceTimeout();
            return new TreeMetrics(bytes[0], files[0], directories[0], entries[0]);
        }

        private long increment(long value, String counter) throws IOException {
            try {
                return Math.addExact(value, 1L);
            } catch (ArithmeticException exception) {
                throw new IOException("remote repository " + counter + " counter overflow", exception);
            }
        }

        private void enforceCardinality(long files, long directories, long traversalEntries) throws IOException {
            if (files > maxFiles) {
                throw new IOException("remote repository exceeds the configured clone file limit");
            }
            if (directories > maxDirectories) {
                throw new IOException("remote repository exceeds the configured clone directory limit");
            }
            if (traversalEntries > maxTraversalEntries) {
                throw new IOException("remote repository exceeds the configured clone traversal entry limit");
            }
        }

        private void enforceTimeout() throws IOException {
            if (System.nanoTime() - startedNanos > timeoutNanos) {
                throw new IOException("remote repository clone exceeds the configured time limit");
            }
        }
    }

    private static final class JGitClient implements RemoteGitClient {
        @Override
        public void cloneRepository(RemoteRepositoryRequest request, Path destination, char[] secret, CloneBudget budget)
                throws Exception {
            CloneProgressMonitor monitor = new CloneProgressMonitor(budget);
            var command = Git.cloneRepository()
                    .setURI(request.canonicalRepositoryUri())
                    .setDirectory(destination.toFile())
                    .setBranch(request.reference())
                    .setBranchesToClone(List.of(request.reference()))
                    .setCloneAllBranches(false)
                    .setCloneSubmodules(false)
                    .setDepth(1)
                    .setProgressMonitor(monitor);
            UsernamePasswordCredentialsProvider credentials = null;
            if (secret != null) {
                String username = request.host() == RemoteHost.GITHUB ? "x-access-token" : "oauth2";
                credentials = new UsernamePasswordCredentialsProvider(username, secret);
                command.setCredentialsProvider(credentials);
            }
            try {
                try (Git ignored = command.call()) {
                    // CloneCommand has completed and resources are closed through Git.close().
                }
                budget.checkpoint();
            } catch (Exception exception) {
                IOException budgetFailure = monitor.failure();
                if (budgetFailure != null) throw budgetFailure;
                throw exception;
            } finally {
                if (credentials != null) credentials.clear();
            }
        }
    }

    private static final class CloneProgressMonitor implements ProgressMonitor {
        private static final long MIN_CHECKPOINT_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
        private final CloneBudget budget;
        private volatile IOException failure;
        private int updates;
        private long lastCheckpointNanos;

        private CloneProgressMonitor(CloneBudget budget) { this.budget = budget; }
        @Override public void start(int totalTasks) { checkpoint(true); }
        @Override public void beginTask(String title, int totalWork) { checkpoint(false); }
        @Override public void update(int completed) {
            updates += Math.max(1, completed);
            if (updates >= 1024) { updates = 0; checkpoint(false); }
        }
        @Override public void endTask() { checkpoint(true); }
        @Override public boolean isCancelled() { checkpoint(false); return failure != null; }
        public void showDuration(boolean enabled) { }
        private void checkpoint(boolean force) {
            if (failure != null) return;
            long now = System.nanoTime();
            if (!force && now - lastCheckpointNanos < MIN_CHECKPOINT_INTERVAL_NANOS) return;
            try { budget.checkpoint(); }
            catch (IOException exception) { failure = exception; }
            finally { lastCheckpointNanos = now; }
        }
        private IOException failure() { return failure; }
    }

    private static final class LeaseState {
        private final FileChannel channel;
        private final FileLock lock;
        private int references = 1;
        private LeaseState(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    }

    private static final class EvictionLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private EvictionLease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException {
            IOException failure = null;
            try { lock.release(); } catch (IOException exception) { failure = exception; }
            try { channel.close(); } catch (IOException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
            if (failure != null) throw failure;
        }
    }

    private record CacheEntry(Path repositoryRoot, Properties metadata, Instant materializedAt) { }
    private record TreeMetrics(long bytes, long files, long directories, long traversalEntries) { }
    private record EvictionCandidate(
            Path path, String cacheKey, Instant lastAccessAt, long size, boolean invalid) { }
}

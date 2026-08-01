package com.minos.git;

import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.remote.RemoteRepositoryRequest.RemoteHost;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * JGit HTTPS materializer with immutable revision checks and a bounded local cache.
 *
 * <p>Cache metadata is stored outside the checkout. Authentication material is resolved only for
 * the clone call and is never written to the repository config, cache metadata or diagnostics.</p>
 */
public final class JGitRemoteRepositoryMaterializer implements RemoteRepositoryMaterializer {

    private static final String FORMAT_VERSION = "1";
    private static final String METADATA_FILE = "entry.properties";
    private static final String REPOSITORY_DIRECTORY = "repository";

    private final Path cacheRoot;
    private final Path locksRoot;
    private final RemoteRepositoryCachePolicy cachePolicy;
    private final RemoteGitClient gitClient;
    private final SecretResolver secretResolver;
    private final Clock clock;

    public JGitRemoteRepositoryMaterializer(Path minosHome) throws IOException {
        this(minosHome, RemoteRepositoryCachePolicy.DEFAULT);
    }

    public JGitRemoteRepositoryMaterializer(
            Path minosHome,
            RemoteRepositoryCachePolicy cachePolicy
    ) throws IOException {
        this(
                minosHome,
                cachePolicy,
                new JGitClient(),
                name -> Optional.ofNullable(System.getenv(name)).map(String::toCharArray),
                Clock.systemUTC()
        );
    }

    JGitRemoteRepositoryMaterializer(
            Path minosHome,
            RemoteRepositoryCachePolicy cachePolicy,
            RemoteGitClient gitClient,
            SecretResolver secretResolver,
            Clock clock
    ) throws IOException {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.cacheRoot = home.resolve("remote-cache").resolve("repositories");
        this.locksRoot = home.resolve("remote-cache").resolve("locks");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.gitClient = Objects.requireNonNull(gitClient, "gitClient");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(cacheRoot);
        Files.createDirectories(locksRoot);
    }

    @Override
    public RemoteMaterialization materialize(RemoteRepositoryRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        String cacheKey = cacheKey(request);
        Path lockFile = locksRoot.resolve(cacheKey + ".lock");
        try (FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            return materializeLocked(request, cacheKey);
        }
    }

    private RemoteMaterialization materializeLocked(RemoteRepositoryRequest request, String cacheKey) throws Exception {
        Path entry = cacheRoot.resolve(cacheKey);
        Optional<CacheEntry> cached = readValidEntry(entry, request, cacheKey);
        if (cached.isPresent()) {
            CacheEntry value = cached.orElseThrow();
            touch(entry, value.metadata(), clock.instant());
            return materialization(request, value.repositoryRoot(), cacheKey, true, value.materializedAt());
        }
        if (Files.exists(entry)) {
            deleteCacheTree(entry);
        }

        Path temporary = cacheRoot.resolve(".entry-" + UUID.randomUUID() + ".tmp");
        Files.createDirectory(temporary);
        try {
            Path repositoryRoot = temporary.resolve(REPOSITORY_DIRECTORY);
            char[] secret = resolveSecret(request);
            try {
                gitClient.cloneRepository(request, repositoryRoot, secret);
            } finally {
                if (secret != null) {
                    java.util.Arrays.fill(secret, '\0');
                }
            }
            validateCheckout(repositoryRoot, request);
            ensureProjectRoot(repositoryRoot, request.projectSubdirectory());

            Instant now = clock.instant();
            Properties metadata = metadata(request, now, now);
            writeProperties(temporary.resolve(METADATA_FILE), metadata);
            long entrySize = sizeOf(temporary);
            if (entrySize > cachePolicy.maxBytes()) {
                throw new IOException("remote repository exceeds the configured cache byte limit");
            }
            moveDirectory(temporary, entry);
            evict(cacheKey);
            return materialization(request, entry.resolve(REPOSITORY_DIRECTORY), cacheKey, false, now);
        } finally {
            if (Files.exists(temporary)) {
                deleteCacheTree(temporary);
            }
        }
    }

    private char[] resolveSecret(RemoteRepositoryRequest request) {
        if (request.credentialEnvironmentVariable().isEmpty()) {
            return null;
        }
        String name = request.credentialEnvironmentVariable().orElseThrow();
        char[] secret = secretResolver.resolve(name)
                .orElseThrow(() -> new IllegalStateException(
                        "configured credential environment variable is unavailable"));
        if (secret.length == 0) {
            throw new IllegalStateException("configured credential environment variable is empty");
        }
        return secret;
    }

    private Optional<CacheEntry> readValidEntry(
            Path entry,
            RemoteRepositoryRequest request,
            String cacheKey
    ) {
        try {
            Path metadataFile = entry.resolve(METADATA_FILE);
            Path repositoryRoot = entry.resolve(REPOSITORY_DIRECTORY);
            if (!Files.isRegularFile(metadataFile) || !Files.isDirectory(repositoryRoot)) {
                return Optional.empty();
            }
            Properties metadata = readProperties(metadataFile);
            if (!FORMAT_VERSION.equals(metadata.getProperty("formatVersion"))
                    || !cacheKey.equals(metadata.getProperty("cacheKey"))
                    || !request.canonicalRepositoryUri().equals(metadata.getProperty("repositoryUri"))
                    || !request.reference().equals(metadata.getProperty("reference"))
                    || !request.expectedCommit().equals(metadata.getProperty("commit"))
                    || !portableSubdirectory(request.projectSubdirectory())
                    .equals(metadata.getProperty("projectSubdirectory"))) {
                return Optional.empty();
            }
            validateCheckout(repositoryRoot, request);
            ensureProjectRoot(repositoryRoot, request.projectSubdirectory());
            return Optional.of(new CacheEntry(
                    repositoryRoot,
                    metadata,
                    Instant.parse(required(metadata, "materializedAt"))
            ));
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
        return new RemoteMaterialization(
                request,
                realRepository,
                projectRoot,
                cacheKey,
                cacheHit,
                materializedAt
        );
    }

    private static Path ensureProjectRoot(Path repositoryRoot, Path subdirectory) throws IOException {
        Path realRepository = repositoryRoot.toRealPath();
        Path projectRoot = subdirectory.toString().isEmpty()
                ? realRepository : realRepository.resolve(subdirectory).toRealPath();
        if (!projectRoot.startsWith(realRepository) || !Files.isDirectory(projectRoot)) {
            throw new IOException("remote project subdirectory escapes the materialized repository");
        }
        return projectRoot;
    }

    private static void validateCheckout(Path repositoryRoot, RemoteRepositoryRequest request) throws Exception {
        try (Git git = Git.open(repositoryRoot.toFile())) {
            String head = Objects.requireNonNull(
                    git.getRepository().resolve(Constants.HEAD), "materialized repository has no HEAD").getName();
            if (!request.expectedCommit().equals(head)) {
                throw new IOException("remote ref resolved to an unexpected commit");
            }
            if (!git.status().call().isClean()) {
                throw new IOException("materialized remote checkout is not clean");
            }
            String origin = git.getRepository().getConfig().getString("remote", "origin", "url");
            if (!request.canonicalRepositoryUri().equals(origin)) {
                throw new IOException("materialized remote origin does not match the canonical repository URI");
            }
        }
    }

    private void evict(String protectedKey) throws IOException {
        List<EvictionCandidate> entries = new ArrayList<>();
        try (var paths = Files.list(cacheRoot)) {
            for (Path path : paths.filter(Files::isDirectory)
                    .filter(value -> !value.getFileName().toString().startsWith("."))
                    .toList()) {
                try {
                    Properties metadata = readProperties(path.resolve(METADATA_FILE));
                    entries.add(new EvictionCandidate(
                            path,
                            path.getFileName().toString(),
                            Instant.parse(required(metadata, "lastAccessAt")),
                            sizeOf(path)
                    ));
                } catch (Exception exception) {
                    entries.add(new EvictionCandidate(path, path.getFileName().toString(), Instant.EPOCH, sizeOf(path)));
                }
            }
        }
        entries.sort(Comparator.comparing(EvictionCandidate::lastAccessAt)
                .thenComparing(EvictionCandidate::cacheKey));
        long bytes = entries.stream().mapToLong(EvictionCandidate::size).sum();
        int count = entries.size();
        for (EvictionCandidate candidate : entries) {
            if (count <= cachePolicy.maxEntries() && bytes <= cachePolicy.maxBytes()) {
                break;
            }
            if (protectedKey.equals(candidate.cacheKey())) {
                continue;
            }
            deleteCacheTree(candidate.path());
            count--;
            bytes -= candidate.size();
        }
        if (count > cachePolicy.maxEntries() || bytes > cachePolicy.maxBytes()) {
            throw new IOException("remote cache limits cannot be satisfied without evicting the active entry");
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
                request.host().name(),
                request.canonicalRepositoryUri(),
                request.reference(),
                request.expectedCommit(),
                portableSubdirectory(request.projectSubdirectory()),
                request.fetchNetworkPolicy().name()
        ));
    }

    private static String portableSubdirectory(Path path) {
        if (path.toString().isEmpty()) {
            return ".";
        }
        return path.toString().replace('\\', '/');
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
        if (!Files.exists(normalized)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                clearReadOnly(path);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path, DosFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (attributes != null && attributes.readAttributes().isReadOnly()) {
                attributes.setReadOnly(false);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS file systems do not need this Windows-specific cleanup.
        }
        path.toFile().setWritable(true);
    }

    private static long sizeOf(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        long total = 0L;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total = Math.addExact(total, Files.size(path));
            }
        }
        return total;
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void writeProperties(Path file, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            properties.store(writer, "MINOS M25 remote cache metadata - no secrets");
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing remote cache metadata: " + key);
        }
        return value;
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    interface RemoteGitClient {
        void cloneRepository(RemoteRepositoryRequest request, Path destination, char[] secret) throws Exception;
    }

    interface SecretResolver {
        Optional<char[]> resolve(String environmentVariable);
    }

    private static final class JGitClient implements RemoteGitClient {
        @Override
        public void cloneRepository(RemoteRepositoryRequest request, Path destination, char[] secret) throws Exception {
            var command = Git.cloneRepository()
                    .setURI(request.canonicalRepositoryUri())
                    .setDirectory(destination.toFile())
                    .setBranch(request.reference())
                    .setBranchesToClone(List.of(request.reference()))
                    .setCloneAllBranches(false)
                    .setCloneSubmodules(false)
                    .setDepth(1);
            UsernamePasswordCredentialsProvider credentials = null;
            if (secret != null) {
                String username = request.host() == RemoteHost.GITHUB ? "x-access-token" : "oauth2";
                credentials = new UsernamePasswordCredentialsProvider(username, secret);
                command.setCredentialsProvider(credentials);
            }
            try (Git ignored = command.call()) {
                // CloneCommand has completed and closed resources through Git.close().
            } finally {
                if (credentials != null) {
                    credentials.clear();
                }
            }
        }
    }

    private record CacheEntry(
            Path repositoryRoot,
            Properties metadata,
            Instant materializedAt
    ) {
    }

    private record EvictionCandidate(Path path, String cacheKey, Instant lastAccessAt, long size) {
    }
}

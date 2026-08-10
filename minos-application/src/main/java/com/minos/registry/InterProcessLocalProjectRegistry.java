package com.minos.registry;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-process serialization wrapper for the file-backed project registry.
 *
 * <p>The historical registry uses atomic file replacement and in-instance synchronization. This
 * wrapper adds one MINOS-home lock shared by CLI, MCP, IDE and other JVMs so root uniqueness and
 * workspace mutations are evaluated against one serialized view of the registry.</p>
 */
public final class InterProcessLocalProjectRegistry implements ProjectRegistry {

    private static final String LOCK_FILE = ".registry.lock";
    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path storageRoot;
    private final Path projectsDirectory;
    private final Path lockFile;
    private final ReentrantLock jvmLock;
    private final LocalProjectRegistry delegate;

    public InterProcessLocalProjectRegistry(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        Files.createDirectories(this.storageRoot);
        this.projectsDirectory = this.storageRoot.resolve("projects");
        this.lockFile = this.storageRoot.resolve(LOCK_FILE);
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.lockFile, ignored -> new ReentrantLock());
        this.delegate = new LocalProjectRegistry(this.storageRoot);
    }

    @Override
    public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {
        return withLock(() -> delegate.registerProject(rootPath, displayName));
    }

    @Override
    public RegisteredWorkspace createWorkspace(String name) throws IOException {
        return withLock(() -> delegate.createWorkspace(name));
    }

    @Override
    public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException {
        return withLock(() -> delegate.assignProjectToWorkspace(projectId, workspaceId));
    }

    @Override
    public RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException {
        return withLock(() -> delegate.removeProjectFromWorkspace(projectId));
    }

    @Override
    public Optional<RegisteredProject> findProject(UUID projectId) throws IOException {
        return withLock(() -> delegate.findProject(projectId));
    }

    @Override
    public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException {
        return withLock(() -> delegate.findWorkspace(workspaceId));
    }

    @Override
    public List<RegisteredProject> listProjects() throws IOException {
        return withLock(delegate::listProjects);
    }

    @Override
    public List<RegisteredWorkspace> listWorkspaces() throws IOException {
        return withLock(delegate::listWorkspaces);
    }

    @Override
    public boolean deleteProject(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        return withLock(() -> {
            Path target = projectsDirectory.resolve(projectId + ".properties").normalize();
            if (!target.startsWith(projectsDirectory)) {
                throw new IOException("project registry deletion escapes projects directory");
            }
            if (delegate.findProject(projectId).isEmpty()) {
                return false;
            }
            return Files.deleteIfExists(target);
        });
    }

    private <T> T withLock(IoOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        jvmLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                         lockFile,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}

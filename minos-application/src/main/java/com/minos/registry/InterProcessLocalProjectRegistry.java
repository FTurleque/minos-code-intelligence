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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-process serialization wrapper for the file-backed project registry.
 *
 * <p>A bounded striped JVM lock layer prevents {@link java.nio.channels.OverlappingFileLockException}
 * before taking the OS file lock. Stripes are fixed-size, so opening many independent MINOS homes
 * cannot grow a static path-to-lock map for the lifetime of the JVM.</p>
 */
public final class InterProcessLocalProjectRegistry implements ProjectRegistry {

    private static final String LOCK_FILE = ".registry.lock";
    private static final int JVM_LOCK_STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();

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
        this.jvmLock = JVM_LOCKS[Math.floorMod(this.lockFile.hashCode(), JVM_LOCKS.length)];
        this.delegate = new LocalProjectRegistry(this.storageRoot);
    }

    @Override
    public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {
        return registerProjectWithResult(rootPath, displayName).project();
    }

    @Override
    public RegistrationResult registerProjectWithResult(Path rootPath, String displayName) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath");
        return withLock(() -> {
            Path canonical = rootPath.toRealPath();
            Optional<RegisteredProject> existing = delegate.listProjects().stream()
                    .filter(project -> project.rootPath().equals(canonical))
                    .findFirst();
            RegisteredProject project = delegate.registerProject(canonical, displayName);
            return new RegistrationResult(project, existing.isEmpty());
        });
    }

    @Override
    public RegisteredWorkspace createWorkspace(String name) throws IOException {
        return createWorkspaceWithResult(name).workspace();
    }

    @Override
    public WorkspaceRegistrationResult createWorkspaceWithResult(String name) throws IOException {
        return withLock(() -> delegate.createWorkspaceWithResult(name));
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

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[JVM_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}

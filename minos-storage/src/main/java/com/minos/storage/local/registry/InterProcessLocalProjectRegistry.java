package com.minos.storage.local.registry;

import com.minos.io.BoundedFileLease;
import com.minos.io.DurableAtomicFile;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;
import com.minos.registry.ProjectRegistry;
import com.minos.storage.home.ProjectPathMappingStore;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * Cross-process serialization wrapper for the file-backed project registry.
 *
 * <p>A bounded striped JVM lock layer prevents overlapping file locks before taking the OS file
 * lock. Stripes are fixed-size, so opening many independent MINOS homes cannot grow a static
 * path-to-lock map for the lifetime of the JVM. The registry root, metadata leaves and lock file
 * are all part of the same local trust boundary and therefore fail closed on symbolic links.</p>
 */
public final class InterProcessLocalProjectRegistry implements ProjectRegistry {

    /** Folder of the local registry under a MINOS home. */
    public static final String NAMESPACE = "registry";
    private static final String LOCK_FILE = ".registry.lock";
    private static final int JVM_LOCK_STRIPES = 64;
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final Path storageRoot;
    private final Path projectsDirectory;
    private final Path workspacesDirectory;
    private final Path lockFile;
    private final ReentrantLock jvmLock;
    private final LocalProjectRegistry delegate;
    /** The same registry for reads made under a reader's lease: it never migrates a legacy entry. */
    private final LocalProjectRegistry reader;

    public InterProcessLocalProjectRegistry(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        DurableAtomicFile.ensureDirectory(this.storageRoot, "local project registry root");
        this.projectsDirectory = this.storageRoot.resolve("projects");
        this.workspacesDirectory = this.storageRoot.resolve("workspaces");
        this.lockFile = this.storageRoot.resolve(LOCK_FILE);
        this.jvmLock = JVM_LOCKS[Math.floorMod(this.lockFile.hashCode(), JVM_LOCKS.length)];
        this.delegate = new LocalProjectRegistry(this.storageRoot);
        this.reader = LocalProjectRegistry.readingWithoutMigration(this.storageRoot);
        validateRegistryStorage();
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
        return withSharedLock(() -> reader.findProject(projectId));
    }

    @Override
    public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException {
        return withSharedLock(() -> reader.findWorkspace(workspaceId));
    }

    @Override
    public List<RegisteredProject> listProjects() throws IOException {
        return withSharedLock(reader::listProjects);
    }

    @Override
    public Inventory inventory() throws IOException {
        return withSharedLock(reader::inventory);
    }

    @Override
    public List<RegisteredWorkspace> listWorkspaces() throws IOException {
        return withSharedLock(reader::listWorkspaces);
    }

    @Override
    public WorkspaceInventory workspaceInventory() throws IOException {
        return withSharedLock(reader::workspaceInventory);
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

    /**
     * Replaces the project path mapping saved in {@code store} by {@code desired} and re-expresses every project entry
     * against it, under the exclusive registry lease ({@link ProjectRootReplacement}).
     */
    public ProjectPathMappings.Replacement replaceRoot(ProjectPathMappingStore store, ProjectPathMapping desired)
            throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(desired, "desired");
        return withLock(() -> new ProjectRootReplacement(projectsDirectory, store).replace(desired));
    }

    private <T> T withLock(IoOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        try (BoundedFileLease ignored = BoundedFileLease.acquire(
                lockFile, jvmLock, LOCK_TIMEOUT, "local project registry lease")) {
            validateRegistryStorage();
            return operation.run();
        }
    }

    /**
     * A read: shared with the readers of other processes, excluded by a writer, and nothing is written
     * (ADR 0017) -- no lock file created or hardened, no legacy entry migrated ({@link #reader}). The query
     * plane reads the registry through this on a data root it cannot write. The JVM lock both paths take
     * keeps a read and a mutation of this process apart, as the file lease does across processes.
     */
    private <T> T withSharedLock(IoOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        return BoundedFileLease.readShared(lockFile, jvmLock, LOCK_TIMEOUT, "local project registry lease", () -> {
            validateRegistryStorage();
            return operation.run();
        });
    }

    private void validateRegistryStorage() throws IOException {
        requireDirectory(projectsDirectory, "project registry directory");
        requireDirectory(workspacesDirectory, "workspace registry directory");
        validateMetadataDirectory(projectsDirectory);
        validateMetadataDirectory(workspacesDirectory);
    }

    private static void requireDirectory(Path directory, String label) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a non-symlink directory: " + leafName(directory));
        }
    }

    private static void validateMetadataDirectory(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.properties")) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("registry metadata must be a regular non-symlink file: "
                            + entry.getFileName());
                }
            }
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

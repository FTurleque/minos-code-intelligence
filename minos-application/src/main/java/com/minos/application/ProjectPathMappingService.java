package com.minos.application;

import com.minos.domain.registry.ProjectMount;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;
import com.minos.registry.ProjectPathMappings.RegisteredRoot;
import com.minos.registry.RegisteredProject;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Host/container project path mapping of one MINOS_HOME, for the Docker administration plane. A single root mapping
 * is created once; the same mapping is accepted again, and a different one is replaced only by {@link #replace},
 * never implicitly. A mount table holds one mount per exposed project: mounts are added and removed by name, and a
 * single root mapping migrates to a table once ({@link #migrate}). The persisted port comes from the composition root
 * (ADR 0042), so its caller names no engine port (ADR 0057, point 6).
 */
public final class ProjectPathMappingService {

    /** The container project root of the Docker runtime. */
    public static final String CONTAINER_ROOT = "/workspace/projects";

    /** What a configuration, an initialisation or a migration did. */
    public enum Outcome {
        /** No mapping existed; the requested one is saved. */
        CREATED,
        /** The requested mapping is already the saved one; nothing is written. */
        UNCHANGED,
        /** Another mapping is saved; it is kept and nothing is written. */
        CONFLICT,
        /** Another mapping was saved; {@link #replace} saved the requested one and re-expressed the projects. */
        REPLACED,
        /** A single root mapping was replaced by a mount table. */
        MIGRATED,
        /** The migration was computed and nothing was written. */
        PLANNED
    }

    /** Why a registered project gets no mount from the migration. */
    public enum SkipReason {
        /** The project is the old root itself: mounting it would expose the whole root again. */
        ROOT,
        /** The project is detached from every root. */
        DETACHED,
        /** The host side refused its folder. */
        REFUSED
    }

    /** What {@link #replace(ProjectPathMapping)} did, and to how many registered projects. */
    public record Result(Outcome outcome, int rebased, int reattached, int detached) {

        public Result {
            Objects.requireNonNull(outcome, "outcome");
        }

        /** The project counts, as the port reports them. */
        public ProjectPathMappings.Replacement replacement() {
            return new ProjectPathMappings.Replacement(rebased, reattached, detached);
        }
    }

    /** A registered project that the migration does not mount. */
    public record Skipped(String displayName, SkipReason reason) {

        public Skipped {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The mount table a migration writes or would write, and the projects it leaves unmounted. */
    public record Migration(Outcome outcome, ProjectPathMapping table, List<Skipped> skipped) {

        public Migration {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(table, "table");
            skipped = List.copyOf(skipped);
        }
    }

    /** The registered projects, by their root relative to the container root. */
    @FunctionalInterface
    interface RegisteredRoots {
        List<RegisteredRoot> list() throws IOException;
    }

    private final ProjectPathMappings store;
    private final RegisteredRoots registeredRoots;

    ProjectPathMappingService(ProjectPathMappings store, RegisteredRoots registeredRoots) {
        this.store = Objects.requireNonNull(store, "store");
        this.registeredRoots = Objects.requireNonNull(registeredRoots, "registeredRoots");
    }

    /**
     * The service of {@code home}, its store supplied by the composition root. The registered projects are read from
     * the local registry, or from the storage backend of {@code home} when it is PostgreSQL.
     */
    public static ProjectPathMappingService forHome(Path home) {
        Objects.requireNonNull(home, "home");
        ProjectPathMappings store = MinosApplicationComposers.resolve().projectPathMappings(home);
        return new ProjectPathMappingService(store, () -> postgresqlBackend(home)
                ? storageBackendRoots(home, store)
                : store.registeredRoots());
    }

    /** Saves {@code desired} when no mapping exists, and never replaces a different one. */
    public Outcome configure(ProjectPathMapping desired) throws IOException {
        Objects.requireNonNull(desired, "desired");
        Optional<ProjectPathMapping> existing = store.loadOptional();
        if (existing.isPresent()) {
            return existing.orElseThrow().equals(desired) ? Outcome.UNCHANGED : Outcome.CONFLICT;
        }
        store.save(desired);
        return Outcome.CREATED;
    }

    /**
     * Saves {@code desired}, replacing a different saved mapping: projects under the new host root are rebased, the
     * others are detached and keep their index. The same mapping still reattaches what an interrupted replacement left
     * detached. Refused for a mount table, which has no root to replace.
     */
    public Result replace(ProjectPathMapping desired) throws IOException {
        Objects.requireNonNull(desired, "desired");
        Optional<ProjectPathMapping> existing = store.loadOptional();
        if (existing.isPresent() && existing.orElseThrow().mountTable()) {
            throw new IOException("project root replacement refused: the Docker runtime exposes one mount per project; "
                    + "expose a project with mcp-lifecycle.ps1 -Action AddProject");
        }
        if (existing.isEmpty()) {
            store.save(desired);
            return new Result(Outcome.CREATED, 0, 0, 0);
        }
        Outcome outcome = existing.orElseThrow().equals(desired) ? Outcome.UNCHANGED : Outcome.REPLACED;
        ProjectPathMappings.Replacement replacement = store.replace(desired);
        return new Result(outcome, replacement.rebased(), replacement.reattached(), replacement.detached());
    }

    /** The saved mapping, single root or mount table; empty when none is saved. */
    public Optional<ProjectPathMapping> load() throws IOException {
        return store.loadOptional();
    }

    /**
     * Saves an empty mount table of {@code containerRoot} when no mapping exists. A table of the same root is kept,
     * one of another root is a conflict, and a single root mapping is refused: it migrates by {@link #migrate}.
     */
    public Outcome initialize(String containerRoot) throws IOException {
        ProjectPathMapping empty = ProjectPathMapping.mounted(containerRoot, List.of());
        Optional<ProjectPathMapping> existing = store.loadOptional();
        if (existing.isEmpty()) {
            store.save(empty);
            return Outcome.CREATED;
        }
        ProjectPathMapping saved = existing.orElseThrow();
        if (!saved.mountTable()) throw singleRootSaved();
        return saved.containerRoot().equals(empty.containerRoot()) ? Outcome.UNCHANGED : Outcome.CONFLICT;
    }

    /** Adds the mount {@code name} of {@code hostFolder} to the saved table and returns the saved table. */
    public ProjectPathMapping addMount(String name, String hostFolder) throws IOException {
        ProjectPathMapping next = requireTable().withMount(name, hostFolder);
        store.save(next);
        return next;
    }

    /** Removes the mount {@code name} from the saved table and returns the saved table. */
    public ProjectPathMapping removeMount(String name) throws IOException {
        ProjectPathMapping next = requireTable().withoutMount(name);
        store.save(next);
        return next;
    }

    /** The registered projects whose root lies under the mount path {@code mountPath}, by display name. */
    public List<String> projectsUnder(String mountPath) throws IOException {
        List<String> names = new ArrayList<>();
        String comparable = mountPath.toLowerCase(Locale.ROOT);
        for (RegisteredRoot root : registeredRoots.list()) {
            if (root.relativeRoot().isEmpty()) continue;
            String relative = root.relativeRoot().orElseThrow().toLowerCase(Locale.ROOT);
            if (relative.equals(comparable) || relative.startsWith(comparable + "/")) names.add(root.displayName());
        }
        return names;
    }

    /**
     * Migrates a single root mapping to a mount table: one mount per registered project on its current container
     * path, nested projects sharing the mount of the shortest one. A project registered at the root itself, a
     * detached project and a project under a path of {@code refusedPaths} get no mount. An unreadable registry entry
     * refuses the whole migration before anything is written. With {@code dryRun}, nothing is written. Without a
     * mapping, an empty table is created; a table is left as it is.
     */
    public Migration migrate(Set<String> refusedPaths, boolean dryRun) throws IOException {
        Objects.requireNonNull(refusedPaths, "refusedPaths");
        Optional<ProjectPathMapping> existing = store.loadOptional();
        if (existing.isEmpty()) {
            ProjectPathMapping empty = ProjectPathMapping.mounted(CONTAINER_ROOT, List.of());
            if (!dryRun) store.save(empty);
            return new Migration(dryRun ? Outcome.PLANNED : Outcome.CREATED, empty, List.of());
        }
        ProjectPathMapping saved = existing.orElseThrow();
        if (saved.mountTable()) return new Migration(Outcome.UNCHANGED, saved, List.of());

        List<RegisteredRoot> roots = new ArrayList<>(registeredRoots.list());
        roots.sort(Comparator.comparing(root -> root.relativeRoot().orElse("")));
        List<String> refused = refusedPaths.stream().map(path -> path.toLowerCase(Locale.ROOT)).toList();
        List<ProjectMount> mounts = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (RegisteredRoot root : roots) {
            if (root.relativeRoot().isEmpty()) {
                skipped.add(new Skipped(root.displayName(), SkipReason.DETACHED));
                continue;
            }
            String relative = root.relativeRoot().orElseThrow();
            if (".".equals(relative)) {
                skipped.add(new Skipped(root.displayName(), SkipReason.ROOT));
            } else if (under(relative, refused)) {
                skipped.add(new Skipped(root.displayName(), SkipReason.REFUSED));
            } else if (!under(relative, mounts.stream().map(m -> m.path().toLowerCase(Locale.ROOT)).toList())) {
                mounts.add(new ProjectMount(relative, saved.hostPathOf(relative)));
            }
        }
        ProjectPathMapping table = ProjectPathMapping.mounted(saved.containerRoot(), mounts);
        if (dryRun) return new Migration(Outcome.PLANNED, table, skipped);
        store.migrateToTable(table);
        return new Migration(Outcome.MIGRATED, table, skipped);
    }

    private static boolean under(String relative, List<String> lowerCasePrefixes) {
        String comparable = relative.toLowerCase(Locale.ROOT);
        for (String prefix : lowerCasePrefixes) {
            if (comparable.equals(prefix) || comparable.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private ProjectPathMapping requireTable() throws IOException {
        Optional<ProjectPathMapping> existing = store.loadOptional();
        if (existing.isEmpty()) {
            throw new IOException("no project mount table is saved; start the Docker runtime first");
        }
        if (!existing.orElseThrow().mountTable()) throw singleRootSaved();
        return existing.orElseThrow();
    }

    private static IOException singleRootSaved() {
        return new IOException("a single project root is still saved; run migrate-project-mounts first");
    }

    private static boolean postgresqlBackend(Path home) throws IOException {
        String backend = MinosRuntimeSettings.load(home)
                .value(StorageBackendConfiguration.BACKEND_PROPERTY, StorageBackendConfiguration.BACKEND_ENV);
        return backend != null && "postgresql".equals(backend.toLowerCase(Locale.ROOT));
    }

    /** The projects of the storage backend of {@code home}, relative to the container root of the saved mapping. */
    private static List<RegisteredRoot> storageBackendRoots(Path home, ProjectPathMappings store) throws IOException {
        ProjectPathMapping saved = store.loadOptional().orElseThrow(
                () -> new IOException("no project path mapping is saved"));
        ProjectPathMapping.RuntimeLocation location =
                ProjectPathMapping.resolveRuntimeLocation(System.getenv(), System.getProperties());
        List<RegisteredRoot> roots = new ArrayList<>();
        try (StorageBackend backend = StorageBackends.open(StorageBackendConfiguration.resolve(home))) {
            for (RegisteredProject project : backend.projectRegistry().listProjects()) {
                Optional<String> relative;
                try {
                    relative = Optional.of(saved.relativeForPhysical(project.rootPath(), location));
                } catch (IllegalArgumentException _) {
                    relative = Optional.empty();
                }
                roots.add(new RegisteredRoot(project.displayName(), relative));
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("storage backend could not be closed", exception);
        }
        return roots;
    }
}

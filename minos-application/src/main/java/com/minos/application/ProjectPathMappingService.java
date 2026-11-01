package com.minos.application;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Host/container project path mapping of one MINOS_HOME, for the Docker administration plane. The mapping is created
 * once; the same mapping is accepted again, and a different one is replaced only by {@link #replace}, never implicitly.
 * The persisted port comes from the composition root (ADR 0042), so its caller names no engine port (ADR 0057, point 6).
 */
public final class ProjectPathMappingService {

    /** What {@link #configure(ProjectPathMapping)} did. */
    public enum Outcome {
        /** No mapping existed; the requested one is saved. */
        CREATED,
        /** The requested mapping is already the saved one; nothing is written. */
        UNCHANGED,
        /** Another mapping is saved; it is kept and nothing is written. */
        CONFLICT,
        /** Another mapping was saved; {@link #replace} saved the requested one and re-expressed the projects. */
        REPLACED
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

    private final ProjectPathMappings store;

    ProjectPathMappingService(ProjectPathMappings store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The service of {@code home}, its store supplied by the composition root. */
    public static ProjectPathMappingService forHome(Path home) {
        Objects.requireNonNull(home, "home");
        return new ProjectPathMappingService(MinosApplicationComposers.resolve().projectPathMappings(home));
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
     * detached.
     */
    public Result replace(ProjectPathMapping desired) throws IOException {
        Objects.requireNonNull(desired, "desired");
        Optional<ProjectPathMapping> existing = store.loadOptional();
        if (existing.isEmpty()) {
            store.save(desired);
            return new Result(Outcome.CREATED, 0, 0, 0);
        }
        Outcome outcome = existing.orElseThrow().equals(desired) ? Outcome.UNCHANGED : Outcome.REPLACED;
        ProjectPathMappings.Replacement replacement = store.replace(desired);
        return new Result(outcome, replacement.rebased(), replacement.reattached(), replacement.detached());
    }
}

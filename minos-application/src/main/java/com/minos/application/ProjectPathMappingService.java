package com.minos.application;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Host/container project path mapping of one MINOS_HOME, for the Docker administration plane. The mapping is created
 * once; the same mapping is accepted again, and a different one is never replaced implicitly. The persisted port comes
 * from the composition root (ADR 0042), so its caller names no engine port (ADR 0057, point 6).
 */
public final class ProjectPathMappingService {

    /** What {@link #configure(ProjectPathMapping)} did. */
    public enum Outcome {
        /** No mapping existed; the requested one is saved. */
        CREATED,
        /** The requested mapping is already the saved one; nothing is written. */
        UNCHANGED,
        /** Another mapping is saved; it is kept and nothing is written. */
        CONFLICT
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
}

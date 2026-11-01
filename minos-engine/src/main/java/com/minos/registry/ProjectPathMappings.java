package com.minos.registry;

import com.minos.domain.registry.ProjectPathMapping;

import java.io.IOException;
import java.util.Optional;

/**
 * Port: the persisted mapping between the host and container project roots (Docker administration plane). The
 * {@code minos-app} assembly only knows this contract; the file implementation (minos-storage) is wired by
 * minos-bootstrap.
 */
public interface ProjectPathMappings {

    Optional<ProjectPathMapping> loadOptional() throws IOException;

    void save(ProjectPathMapping mapping) throws IOException;

    /**
     * Saves {@code desired} in place of the saved mapping. Every registered project is rebased when the new host root
     * contains it, and detached otherwise: it stays registered with its identity and index, and is never resolved to a
     * folder of the new root. A detached project that the new root contains is reattached. Refused, with nothing
     * written, when a registry entry is unreadable or when the projects are not held by the local registry.
     */
    Replacement replace(ProjectPathMapping desired) throws IOException;

    /** What {@link #replace(ProjectPathMapping)} did to the registered projects. */
    record Replacement(int rebased, int reattached, int detached) {

        public Replacement {
            if (rebased < 0 || reattached < 0 || detached < 0) {
                throw new IllegalArgumentException("replacement counts must not be negative");
            }
        }
    }
}

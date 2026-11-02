package com.minos.registry;

import com.minos.domain.registry.ProjectPathMapping;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
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

    /**
     * Every project of the local registry, by its root relative to the container root of the saved single root
     * mapping, read without writing anything. Refused as a whole when an entry is unreadable, or when no single root
     * mapping is saved.
     */
    List<RegisteredRoot> registeredRoots() throws IOException;

    /**
     * Replaces the saved single root mapping by the mount table {@code table}, after saving the single root file
     * beside itself. Refused, with nothing written, when the saved mapping is not a single root mapping.
     */
    void migrateToTable(ProjectPathMapping table) throws IOException;

    /** What {@link #replace(ProjectPathMapping)} did to the registered projects. */
    record Replacement(int rebased, int reattached, int detached) {

        public Replacement {
            if (rebased < 0 || reattached < 0 || detached < 0) {
                throw new IllegalArgumentException("replacement counts must not be negative");
            }
        }
    }

    /**
     * One registered project: its display name and its root relative to the container root, empty when the project
     * is detached from every root.
     */
    record RegisteredRoot(String displayName, Optional<String> relativeRoot) {

        public RegisteredRoot {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(relativeRoot, "relativeRoot");
        }
    }
}

package com.minos.orchestration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Exclusive project-index lease authority used by the indexing lifecycle.
 *
 * <p>Production callers must inject a cross-process implementation. The file-backed
 * implementation delegates to {@link ProjectIndexLease} and converts checked I/O failures
 * into fail-closed unchecked failures so lifecycle entry points cannot silently continue
 * without ownership.</p>
 */
@FunctionalInterface
public interface ProjectIndexLeaseProvider {

    Lease acquire(UUID projectId);

    static ProjectIndexLeaseProvider file(Path minosHome) {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        return projectId -> {
            try {
                ProjectIndexLease lease = ProjectIndexLease.acquire(home, Objects.requireNonNull(projectId, "projectId"));
                return () -> {
                    try {
                        lease.close();
                    } catch (IOException exception) {
                        throw new UncheckedIOException("cannot release project indexing lease", exception);
                    }
                };
            } catch (IOException exception) {
                throw new UncheckedIOException("cannot acquire project indexing lease", exception);
            }
        };
    }

    @FunctionalInterface
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}

package com.minos.remote;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Materializes immutable remote repository requests into a local cache. */
public interface RemoteRepositoryMaterializer {

    RemoteMaterialization materialize(RemoteRepositoryRequest request) throws Exception;

    /**
     * Releases the active-use lease associated with a materialization. Implementations that do not
     * evict materializations may keep the default no-op behavior.
     */
    default void release(RemoteMaterialization materialization) throws Exception {
        Objects.requireNonNull(materialization, "materialization");
    }

    record RemoteMaterialization(
            RemoteRepositoryRequest request,
            Path repositoryRoot,
            Path projectRoot,
            String cacheKey,
            boolean cacheHit,
            Instant materializedAt
    ) {
        public RemoteMaterialization {
            Objects.requireNonNull(request, "request");
            repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
            if (!projectRoot.startsWith(repositoryRoot)) {
                throw new IllegalArgumentException("projectRoot must be contained in repositoryRoot");
            }
            if (cacheKey == null || cacheKey.isBlank()) {
                throw new IllegalArgumentException("cacheKey must not be blank");
            }
            Objects.requireNonNull(materializedAt, "materializedAt");
        }
    }
}

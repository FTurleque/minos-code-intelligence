package com.minos.remote;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Materializes one immutable remote revision into a controlled local cache. */
public interface RemoteRepositoryMaterializer {

    RemoteMaterialization materialize(RemoteRepositoryRequest request) throws Exception;

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
            repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                    .toAbsolutePath().normalize();
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot")
                    .toAbsolutePath().normalize();
            if (!projectRoot.startsWith(repositoryRoot)) {
                throw new IllegalArgumentException("projectRoot must stay inside repositoryRoot");
            }
            if (cacheKey == null || !cacheKey.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("cacheKey must be a SHA-256 value");
            }
            Objects.requireNonNull(materializedAt, "materializedAt");
        }
    }
}

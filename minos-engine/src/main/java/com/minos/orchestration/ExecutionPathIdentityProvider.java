package com.minos.orchestration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Trusted platform SPI for strong physical identity of an execution path pair.
 *
 * <p>Implementations are application runtime components, not provider plugins. They are consulted
 * only when the standard Java filesystem provider cannot expose a stable identity. Returning empty
 * means the implementation does not support the current platform/filesystem; a local process launch
 * then remains fail-closed unless another trusted implementation can establish the identity.</p>
 */
public interface ExecutionPathIdentityProvider {

    Optional<IdentityPair> capture(Path registeredProjectRoot, Path projectRoot) throws IOException;

    record IdentityPair(String registeredProjectIdentity, String projectIdentity) {
        public IdentityPair {
            registeredProjectIdentity = requireIdentity(registeredProjectIdentity, "registeredProjectIdentity");
            projectIdentity = requireIdentity(projectIdentity, "projectIdentity");
        }

        private static String requireIdentity(String value, String label) {
            String identity = Objects.requireNonNull(value, label).trim();
            if (identity.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return identity;
        }
    }
}

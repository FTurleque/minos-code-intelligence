package io.github.fturleque.minos.domain;

import java.util.Objects;

/**
 * Référence stable vers une entité connue de MINOS.
 */
public record CodeEntityRef(CodeEntityType type, String id) {

    public CodeEntityRef {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}

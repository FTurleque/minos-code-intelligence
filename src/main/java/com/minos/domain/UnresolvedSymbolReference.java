package com.minos.domain;

import java.util.Set;

/**
 * Cible symbolique connue seulement partiellement.
 */
public record UnresolvedSymbolReference(
        String displayName,
        String qualifiedNameCandidate,
        String language,
        String reason,
        Set<ProviderReference> providerReferences) implements SymbolReference {

    public UnresolvedSymbolReference {
        displayName = blankToNull(displayName);
        qualifiedNameCandidate = blankToNull(qualifiedNameCandidate);
        language = blankToNull(language);
        reason = blankToNull(reason);
        providerReferences = providerReferences == null ? Set.of() : Set.copyOf(providerReferences);

        if (displayName == null && qualifiedNameCandidate == null && providerReferences.isEmpty()) {
            throw new IllegalArgumentException(
                    "an unresolved symbol reference needs a display name, qualified-name candidate or provider reference"
            );
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

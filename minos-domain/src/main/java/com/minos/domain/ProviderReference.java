package com.minos.domain;

import static com.minos.domain.Preconditions.requireText;

/**
 * Identité opaque conservée pour retrouver un objet dans un fournisseur externe.
 *
 * <p>Cette référence n'est jamais utilisée comme identité métier MINOS. Elle permet
 * uniquement de maintenir la traçabilité vers SCIP, Glean ou un autre fournisseur.</p>
 */
public record ProviderReference(String providerId, String externalId) {

    public ProviderReference {
        requireText(providerId, "providerId");
        requireText(externalId, "externalId");
    }
}

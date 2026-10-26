package com.minos.application.team;

import java.util.Objects;

/** Result of a key rotation: the tenant under its new key and a replacement bearer token, never shown as text. */
public record TeamKeyRotation(TeamTenant tenant, String replacementBearerToken) {
    public TeamKeyRotation {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(replacementBearerToken, "replacementBearerToken");
    }

    @Override
    public String toString() {
        return "TeamKeyRotation[tenant=" + tenant + ", replacementBearerToken=***]";
    }
}

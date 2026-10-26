package com.minos.application.team;

import java.util.Objects;

/** Result of a tenant bootstrap: the new tenant and the owner's bearer token, which its text form never shows. */
public record TeamBootstrap(TeamTenant tenant, String bearerToken) {
    public TeamBootstrap {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(bearerToken, "bearerToken");
    }

    @Override
    public String toString() {
        return "TeamBootstrap[tenant=" + tenant + ", bearerToken=***]";
    }
}

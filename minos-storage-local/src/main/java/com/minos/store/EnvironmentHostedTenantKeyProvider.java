package com.minos.store;

import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedTenantKeyProvider;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Resolves operator-managed M27 master keys from environment variables and derives purpose keys. */
public final class EnvironmentHostedTenantKeyProvider implements HostedTenantKeyProvider {
    public static final String ENV_PREFIX = "MINOS_TEAM_KEY_";

    private final Function<String, String> environment;

    public EnvironmentHostedTenantKeyProvider() {
        this(System::getenv);
    }

    public EnvironmentHostedTenantKeyProvider(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public SecretKey resolve(UUID tenantId, String keyId, Purpose purpose) {
        Objects.requireNonNull(tenantId, "tenantId");
        String safeKeyId = HostedPrincipal.safeId(keyId, "keyId");
        Objects.requireNonNull(purpose, "purpose");
        String variable = ENV_PREFIX + safeKeyId.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_");
        String encoded = environment.apply(variable);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("missing hosted tenant key environment variable: " + variable);
        }
        byte[] master;
        try {
            master = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("hosted tenant key is not valid base64: " + variable, exception);
        }
        try {
            if (master.length != 32) {
                throw new IllegalStateException("hosted tenant key must decode to exactly 32 bytes: " + variable);
            }
            byte[] derived = derive(master, tenantId, safeKeyId, purpose);
            return new SecretKeySpec(derived, purpose == Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        } finally {
            Arrays.fill(master, (byte) 0);
        }
    }

    private static byte[] derive(byte[] master, UUID tenantId, String keyId, Purpose purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(("minos-hosted-v1\0" + tenantId + "\0" + keyId + "\0" + purpose.name())
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}

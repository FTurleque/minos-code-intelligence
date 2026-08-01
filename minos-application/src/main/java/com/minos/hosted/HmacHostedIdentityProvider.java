package com.minos.hosted;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Short-lived HMAC bearer-token reference implementation for opt-in local hosted mode. */
public final class HmacHostedIdentityProvider implements HostedIdentityProvider {
    public static final String TOKEN_PREFIX = "mht1";
    public static final Duration MAX_TOKEN_LIFETIME = Duration.ofHours(24);
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(1);
    private static final int PAYLOAD_VERSION = 1;
    private static final int MAX_TOKEN_BYTES = 8 * 1024;

    private final HostedTenantKeyProvider keys;

    public HmacHostedIdentityProvider(HostedTenantKeyProvider keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public String issue(
            UUID tenantId,
            String principalId,
            String keyId,
            Instant issuedAt,
            Duration lifetime,
            String tokenId
    ) {
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_TOKEN_LIFETIME) > 0) {
            throw new IllegalArgumentException("hosted token lifetime must be positive and at most 24 hours");
        }
        HostedAccessClaims claims = new HostedAccessClaims(
                tenantId, principalId, keyId, issuedAt, issuedAt.plus(lifetime), tokenId);
        byte[] payload = encode(claims);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        byte[] signature = sign(claims.tenantId(), claims.keyId(), encodedPayload.getBytes(StandardCharsets.US_ASCII));
        return TOKEN_PREFIX + "." + encodedPayload + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    @Override
    public HostedAccessClaims authenticate(String bearerToken, Instant now) {
        try {
            Objects.requireNonNull(now, "now");
            if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > MAX_TOKEN_BYTES) {
                throw new SecurityException("invalid hosted bearer token");
            }
            String[] parts = bearerToken.trim().split("\\.", -1);
            if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0]) || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new SecurityException("invalid hosted bearer token");
            }
            byte[] payload = decodeCanonical(parts[1]);
            HostedAccessClaims claims = decode(payload);
            byte[] actual = decodeCanonical(parts[2]);
            byte[] expected = sign(claims.tenantId(), claims.keyId(), parts[1].getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new SecurityException("invalid hosted bearer token");
            }
            if (claims.issuedAt().isAfter(now.plus(MAX_FUTURE_SKEW)) || !claims.expiresAt().isAfter(now)
                    || Duration.between(claims.issuedAt(), claims.expiresAt()).compareTo(MAX_TOKEN_LIFETIME) > 0) {
                throw new SecurityException("invalid hosted bearer token");
            }
            return claims;
        } catch (SecurityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SecurityException("invalid hosted bearer token", exception);
        }
    }

    private byte[] sign(UUID tenantId, String keyId, byte[] payload) {
        try {
            SecretKey key = keys.resolve(tenantId, keyId, HostedTenantKeyProvider.Purpose.TOKEN_SIGNING);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException | IllegalStateException exception) {
            throw new SecurityException("hosted token signing operation failed", exception);
        }
    }

    private static byte[] encode(HostedAccessClaims claims) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(PAYLOAD_VERSION);
            output.writeLong(claims.tenantId().getMostSignificantBits());
            output.writeLong(claims.tenantId().getLeastSignificantBits());
            writeString(output, claims.principalId());
            writeString(output, claims.keyId());
            output.writeLong(claims.issuedAt().getEpochSecond());
            output.writeInt(claims.issuedAt().getNano());
            output.writeLong(claims.expiresAt().getEpochSecond());
            output.writeInt(claims.expiresAt().getNano());
            writeString(output, claims.tokenId());
            output.flush();
            return buffer.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("in-memory token encoding failed", exception);
        }
    }

    private static HostedAccessClaims decode(byte[] payload) {
        if (payload.length > MAX_TOKEN_BYTES) {
            throw new SecurityException("invalid hosted bearer token");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != PAYLOAD_VERSION) {
                throw new SecurityException("invalid hosted bearer token");
            }
            UUID tenantId = new UUID(input.readLong(), input.readLong());
            String principal = readString(input);
            String keyId = readString(input);
            Instant issuedAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
            Instant expiresAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
            String tokenId = readString(input);
            if (input.available() != 0) {
                throw new SecurityException("invalid hosted bearer token");
            }
            return new HostedAccessClaims(tenantId, principal, keyId, issuedAt, expiresAt, tokenId);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new SecurityException("invalid hosted bearer token", exception);
        }
    }

    private static byte[] decodeCanonical(String value) {
        if (value.indexOf('=') >= 0) {
            throw new SecurityException("invalid hosted bearer token");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!value.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded))) {
            throw new SecurityException("invalid hosted bearer token");
        }
        return decoded;
    }

    private static void writeString(DataOutputStream output, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) {
            throw new IllegalArgumentException("hosted token field exceeds limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws java.io.IOException {
        int length = input.readInt();
        if (length < 1 || length > 4096 || length > input.available()) {
            throw new SecurityException("invalid hosted bearer token");
        }
        byte[] bytes = input.readNBytes(length);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("invalid hosted bearer token");
        }
        return value;
    }
}

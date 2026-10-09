package com.minos.hosted;

import com.minos.testsupport.DerivedTenantKeys;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MINOS-AUD-H07 : bornes des jetons du plan de contrôle. Les charges utiles malformées sont signées avec la clé de
 * test, pour que la garde de décodage visée refuse le jeton, et non la vérification de la signature.
 */
class HmacHostedIdentityProviderBoundaryTest {
    private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
    private static final HostedTenantKeyProvider KEYS = DerivedTenantKeys.provider();
    private final HmacHostedIdentityProvider provider = new HmacHostedIdentityProvider(KEYS);

    @Test
    void theLifetimeMustBePositiveAndAtMostTwentyFourHours() {
        for (Duration refused : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1),
                HmacHostedIdentityProvider.MAX_TOKEN_LIFETIME.plusNanos(1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> provider.issue(TENANT, "owner", "primary", NOW, refused, "t-1"), refused.toString());
        }
        String longest = provider.issue(TENANT, "owner", "primary", NOW,
                HmacHostedIdentityProvider.MAX_TOKEN_LIFETIME, "t-1");
        assertDoesNotThrow(() -> provider.authenticate(longest, NOW));
    }

    @Test
    void aTokenIsRefusedExactlyAtItsExpiry() {
        String token = provider.issue(TENANT, "owner", "primary", NOW, Duration.ofHours(1), "t-1");
        Instant expiry = NOW.plus(Duration.ofHours(1));

        assertDoesNotThrow(() -> provider.authenticate(token, expiry.minusNanos(1)));
        assertRefused(() -> provider.authenticate(token, expiry));
    }

    @Test
    void aTokenIssuedSlightlyInTheFutureIsAcceptedWithinTheSkewOnly() {
        Instant issued = NOW.plus(HmacHostedIdentityProvider.MAX_FUTURE_SKEW);
        String token = provider.issue(TENANT, "owner", "primary", issued, Duration.ofHours(1), "t-1");

        assertDoesNotThrow(() -> provider.authenticate(token, NOW));
        assertRefused(() -> provider.authenticate(token, NOW.minusNanos(1)));
    }

    @Test
    void aSignedPayloadClaimingMoreThanTheMaximumLifetimeIsRefused() {
        Instant expires = NOW.plus(HmacHostedIdentityProvider.MAX_TOKEN_LIFETIME).plusSeconds(1);
        assertRefused(() -> provider.authenticate(signed(payload(1, "owner", expires, output -> { })), NOW));
        assertDoesNotThrow(() -> provider.authenticate(
                signed(payload(1, "owner", NOW.plus(HmacHostedIdentityProvider.MAX_TOKEN_LIFETIME), output -> { })),
                NOW));
    }

    @Test
    void malformedSignedPayloadsAreRefused() {
        Instant expires = NOW.plusSeconds(3600);
        byte[] valid = payload(1, "owner", expires, output -> { });

        assertRefused(() -> provider.authenticate(signed(payload(2, "owner", expires, output -> { })), NOW));
        assertRefused(() -> provider.authenticate(signed(Arrays.copyOf(valid, valid.length - 1)), NOW));
        assertRefused(() -> provider.authenticate(signed(payload(1, "owner", expires, output -> write(output, 0))), NOW));
        assertRefused(() -> provider.authenticate(signed(payload(1, "", expires, output -> { })), NOW));
        assertRefused(() -> provider.authenticate(signed(withPrincipalLengthBeyondPayload(valid)), NOW));
        assertDoesNotThrow(() -> provider.authenticate(signed(valid), NOW));
    }

    @Test
    void nonCanonicalOrOversizedTokensAreRefused() {
        String token = provider.issue(TENANT, "owner", "primary", NOW, Duration.ofHours(1), "t-1");
        String[] parts = token.split("\\.");

        assertRefused(() -> provider.authenticate(parts[0] + "." + parts[1] + "=." + parts[2], NOW));
        assertRefused(() -> provider.authenticate(parts[0] + "." + parts[1] + "." + parts[2] + "=", NOW));
        assertRefused(() -> provider.authenticate("mht1." + "A".repeat(8 * 1024) + "." + parts[2], NOW));
        assertRefused(() -> provider.authenticate(token + ".extra", NOW));
        assertRefused(() -> provider.authenticate("   ", NOW));
    }

    private static void assertRefused(org.junit.jupiter.api.function.Executable call) {
        SecurityException failure = assertThrows(SecurityException.class, call);
        assertEquals("invalid hosted bearer token", failure.getMessage());
    }

    /** Same layout as {@code HmacHostedIdentityProvider.encode}, with an optional suffix of extra bytes. */
    private static byte[] payload(int version, String principal, Instant expires, Consumer<DataOutputStream> extra) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(version);
            output.writeLong(TENANT.getMostSignificantBits());
            output.writeLong(TENANT.getLeastSignificantBits());
            writeString(output, principal);
            writeString(output, "primary");
            output.writeLong(NOW.getEpochSecond());
            output.writeInt(NOW.getNano());
            output.writeLong(expires.getEpochSecond());
            output.writeInt(expires.getNano());
            writeString(output, "t-1");
            extra.accept(output);
            output.flush();
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The valid payload with the principal length raised past the end of the payload. */
    private static byte[] withPrincipalLengthBeyondPayload(byte[] valid) {
        byte[] tampered = valid.clone();
        int lengthOffset = 4 + 16;
        tampered[lengthOffset] = 0;
        tampered[lengthOffset + 1] = 0;
        tampered[lengthOffset + 2] = 0x0F;
        tampered[lengthOffset + 3] = (byte) 0xFF;
        return tampered;
    }

    private static void write(DataOutputStream output, int value) {
        try {
            output.writeByte(value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String signed(byte[] payload) {
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(KEYS.resolve(TENANT, "primary", HostedTenantKeyProvider.Purpose.TOKEN_SIGNING));
            byte[] signature = mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII));
            return HmacHostedIdentityProvider.TOKEN_PREFIX + "." + encoded + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

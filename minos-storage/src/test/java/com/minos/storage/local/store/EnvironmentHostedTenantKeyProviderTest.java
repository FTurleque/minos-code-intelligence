package com.minos.storage.local.store;

import com.minos.hosted.HostedTenantKeyProvider.Purpose;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentHostedTenantKeyProviderTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000027");
    private static final String VARIABLE = "MINOS_TEAM_KEY_PRIMARY";

    @Test
    void refusesAMissingEnvironmentFunction() {
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> new EnvironmentHostedTenantKeyProvider(null));
        assertEquals("environment", failure.getMessage());
    }

    @Test
    void mapsTheKeyIdToOneUpperCaseVariableName() {
        List<String> requested = new ArrayList<>();
        String master = encoded(32, (byte) 7);
        EnvironmentHostedTenantKeyProvider provider = new EnvironmentHostedTenantKeyProvider(name -> {
            requested.add(name);
            return master;
        });

        provider.resolve(TENANT, "team-a.v2@eu+1", Purpose.ENCRYPTION);

        assertEquals(List.of("MINOS_TEAM_KEY_TEAM_A_V2_EU_1"), requested);
    }

    @Test
    void refusesAnUnsafeKeyIdBeforeConsultingTheEnvironment() {
        List<String> requested = new ArrayList<>();
        EnvironmentHostedTenantKeyProvider provider = new EnvironmentHostedTenantKeyProvider(name -> {
            requested.add(name);
            return encoded(32, (byte) 1);
        });

        assertThrows(IllegalArgumentException.class, () -> provider.resolve(TENANT, "../primary", Purpose.ENCRYPTION));
        NullPointerException tenant = assertThrows(NullPointerException.class,
                () -> provider.resolve(null, "primary", Purpose.ENCRYPTION));
        assertEquals("tenantId", tenant.getMessage());
        NullPointerException purpose = assertThrows(NullPointerException.class,
                () -> provider.resolve(TENANT, "primary", null));
        assertEquals("purpose", purpose.getMessage());
        assertTrue(requested.isEmpty(), "no environment variable may be read for a refused request: " + requested);
    }

    @Test
    void refusesAnAbsentOrBlankKeyNamingOnlyTheVariable() {
        assertRefused(name -> null, "missing hosted tenant key environment variable: " + VARIABLE);
        assertRefused(name -> " \t", "missing hosted tenant key environment variable: " + VARIABLE);
    }

    @Test
    void refusesAKeyThatIsNotBase64WithoutEchoingIt() {
        String malformed = "not-base64-material!";
        IllegalStateException failure = assertRefused(name -> malformed,
                "hosted tenant key is not valid base64: " + VARIABLE);
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(failure.getMessage().contains(malformed));
    }

    @Test
    void refusesAKeyThatDoesNotDecodeToExactly32Bytes() {
        String expected = "hosted tenant key must decode to exactly 32 bytes: " + VARIABLE;
        String shortKey = encoded(31, (byte) 3);
        IllegalStateException tooShort = assertRefused(name -> shortKey, expected);
        assertFalse(tooShort.getMessage().contains(shortKey));
        assertRefused(name -> encoded(33, (byte) 3), expected);
        assertRefused(name -> "AA==", expected);
    }

    @Test
    void derivesStableKeysWhoseAlgorithmFollowsThePurpose() {
        String master = encoded(32, (byte) 9);
        EnvironmentHostedTenantKeyProvider provider = new EnvironmentHostedTenantKeyProvider(
                Map.of(VARIABLE, "  " + master + "\n")::get);

        SecretKey encryption = provider.resolve(TENANT, "primary", Purpose.ENCRYPTION);
        SecretKey again = provider.resolve(TENANT, "primary", Purpose.ENCRYPTION);
        SecretKey audit = provider.resolve(TENANT, "primary", Purpose.AUDIT_CHAIN);

        assertEquals("AES", encryption.getAlgorithm());
        assertEquals("HmacSHA256", audit.getAlgorithm());
        assertEquals(32, encryption.getEncoded().length);
        assertArrayEquals(encryption.getEncoded(), again.getEncoded(), "the derivation is deterministic");
        assertFalse(java.util.Arrays.equals(Base64.getDecoder().decode(master), encryption.getEncoded()),
                "the master key itself is never handed out");
    }

    @Test
    void defaultConstructorReadsTheProcessEnvironmentAndFailsClosedOnAnAbsentKey() {
        // A key id unique to this run names a variable no host defines; the process environment is only read.
        String keyId = "absent-" + UUID.randomUUID();
        EnvironmentHostedTenantKeyProvider provider = new EnvironmentHostedTenantKeyProvider();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> provider.resolve(TENANT, keyId, Purpose.ENCRYPTION));

        assertTrue(failure.getMessage().startsWith("missing hosted tenant key environment variable: MINOS_TEAM_KEY_ABSENT_"),
                failure.getMessage());
    }

    private static IllegalStateException assertRefused(Function<String, String> environment, String message) {
        EnvironmentHostedTenantKeyProvider provider = new EnvironmentHostedTenantKeyProvider(environment);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> provider.resolve(TENANT, "primary", Purpose.ENCRYPTION));
        assertEquals(message, failure.getMessage());
        return failure;
    }

    private static String encoded(int length, byte fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}

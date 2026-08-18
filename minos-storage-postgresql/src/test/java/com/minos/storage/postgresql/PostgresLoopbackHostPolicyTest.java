package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresLoopbackHostPolicyTest {

    @Test
    void dnsNamesStartingWith127RemainExternal(@TempDir Path home) {
        for (String host : new String[]{"127.attacker.example", "127.0.0.1.attacker.example", "127.example.com"}) {
            assertThrows(IOException.class, () -> factory(home, host, false, ""), host);
            assertDoesNotThrow(() -> factory(home, host, false, "?sslmode=verify-full").close(), host);
            assertThrows(IOException.class, () -> factory(home, host, true, "?sslmode=verify-full"), host);
        }
    }

    @Test
    void onlyCanonicalIpv4LoopbackLiteralsBypassExternalTlsRequirement(@TempDir Path home) {
        assertDoesNotThrow(() -> factory(home, "127.0.0.1", false, "?sslmode=disable").close());
        assertDoesNotThrow(() -> factory(home, "127.255.255.254", false, "").close());

        assertThrows(IOException.class,
                () -> factory(home, "127.00.0.1", false, ""),
                "ambiguous leading-zero forms must not receive loopback trust");
    }

    private static PostgresConnectionFactory factory(
            Path home,
            String host,
            boolean managed,
            String query
    ) throws IOException {
        return new PostgresConnectionFactory(new StorageBackendConfiguration(
                "postgresql",
                home,
                "jdbc:postgresql://" + host + ":5432/minos" + query,
                "minos",
                "secret",
                "minos",
                managed));
    }
}

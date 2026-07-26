package com.minos.application;

import com.minos.api.LocalProviderPlatformApi;
import com.minos.cli.MinosCliRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M17ProviderSurfaceIntegrationTest {

    @Test
    void exposesSameProviderLimitationsThroughCliAndJavaApi(@TempDir Path home) throws Exception {
        MinosApplication application = MinosApplication.open(home);

        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exit = MinosCliRunner.run(application,
                new String[]{"providers", "scip-python", "--format", "json"}, output, error);
        assertEquals(0, exit, error.toString());
        assertTrue(output.toString().contains("\"limitations\""));
        assertTrue(output.toString().contains("Python 3.10+"));

        var provider = new LocalProviderPlatformApi(application).getProvider("scip-python");
        assertEquals("scip-python", provider.id());
        assertTrue(provider.limitations().stream().anyMatch(value -> value.contains("Python")));
        assertEquals(com.minos.orchestration.IndexerCapability.values().length, provider.capabilities().size());
    }
}

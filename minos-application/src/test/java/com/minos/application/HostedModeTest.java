package com.minos.application;

import com.minos.storage.MinosRuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One rule reads the team mode of a home: absent, empty or {@code disabled} is disabled, {@code enabled} is enabled,
 * whatever the case; any other value is refused. Opening the application classifies every value the same way.
 */
class HostedModeTest {

    @TempDir
    Path temp;

    @Test
    void everyValueIsClassifiedByTheSingleRule() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(null, "DISABLED");
        expected.put("", "DISABLED");
        expected.put("disabled", "DISABLED");
        expected.put("DISABLED", "DISABLED");
        expected.put("enabled", "ENABLED");
        expected.put("Enabled", "ENABLED");
        expected.put("maybe", "unsupported hosted mode: maybe");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), classify(settings(entry.getKey())), String.valueOf(entry.getKey()));
        }
    }

    @Test
    void openingTheApplicationClassifiesEveryValueTheSameWay() throws Exception {
        for (String value : new String[] {null, "", "disabled", "DISABLED", "maybe"}) {
            Path home = home(value);
            String rule = classify(MinosRuntimeSettings.load(home));
            if (rule.startsWith("unsupported")) {
                IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                        () -> MinosApplicationRuntimeConfiguration.apply(MinosRuntimeSettings.load(home),
                                MinosApplication.builder(home)));
                assertEquals(rule, refusal.getMessage());
            } else {
                MinosApplication.Builder builder = MinosApplication.builder(home);
                MinosApplicationRuntimeConfiguration.apply(MinosRuntimeSettings.load(home), builder);
                assertEquals(null, builder.hostedTenantKeyProvider, String.valueOf(value));
            }
        }
    }

    @Test
    void theUnsupportedValueIsNamedInTheRefusal() throws Exception {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> HostedMode.from(settings("sometimes")));

        assertTrue(refusal.getMessage().contains("sometimes"), refusal.getMessage());
    }

    private static String classify(MinosRuntimeSettings settings) {
        try {
            return HostedMode.from(settings).name();
        } catch (IllegalArgumentException refusal) {
            return refusal.getMessage();
        }
    }

    private MinosRuntimeSettings settings(String value) throws Exception {
        return MinosRuntimeSettings.load(home(value));
    }

    private Path home(String value) throws Exception {
        Path home = Files.createTempDirectory(temp, "home");
        if (value != null) {
            Path configuration = Files.createDirectories(home.resolve(MinosRuntimeSettings.CONFIG_DIRECTORY))
                    .resolve(MinosRuntimeSettings.CONFIG_FILE);
            Files.writeString(configuration, MinosApplication.HOSTED_MODE_PROPERTY + "=" + value + "\n",
                    StandardCharsets.UTF_8);
        }
        return home;
    }
}

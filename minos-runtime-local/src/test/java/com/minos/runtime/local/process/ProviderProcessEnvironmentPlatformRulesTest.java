package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inherited-key rules of both systems, the per-drive current directories of Windows and the deny pattern, proved
 * on every system (AUD-TST-03): each refusal is asserted, not only each acceptance.
 */
class ProviderProcessEnvironmentPlatformRulesTest {

    @Test
    void windowsAcceptsItsOwnKeysTheCommonKeysInAnyCaseAndTheDriveDirectories() {
        assertTrue(ProviderProcessEnvironment.isSafeInheritedKey("=C:", true));
        assertTrue(ProviderProcessEnvironment.isSafeInheritedKey("path", true));
        assertTrue(ProviderProcessEnvironment.isSafeInheritedKey("Path", true));
        assertTrue(ProviderProcessEnvironment.isSafeInheritedKey("systemroot", true));
        assertTrue(ProviderProcessEnvironment.isSafeInheritedKey("USERPROFILE", true));
    }

    @Test
    void windowsRefusesAnUnlistedKey() {
        assertFalse(ProviderProcessEnvironment.isSafeInheritedKey("CUSTOM_TOOL_HOME", true));
        assertFalse(ProviderProcessEnvironment.isSafeInheritedKey("=CD", true));
    }

    @Test
    void otherSystemsMatchTheCommonKeysExactlyAndRefuseTheWindowsOnes() {
        assertTrue(ProviderProcessEnvironment.isSafeInheritedKey("PATH", false));
        assertFalse(ProviderProcessEnvironment.isSafeInheritedKey("path", false), "exact case outside Windows");
        assertFalse(ProviderProcessEnvironment.isSafeInheritedKey("SystemRoot", false));
        assertFalse(ProviderProcessEnvironment.isSafeInheritedKey("=C:", false));
        assertFalse(ProviderProcessEnvironment.isSafeInheritedKey("CUSTOM_TOOL_HOME", false));
    }

    @Test
    void aDriveCurrentDirectoryIsAnEqualsSignALetterAndAColonOnly() {
        assertTrue(ProviderProcessEnvironment.isWindowsDriveCurrentDirectory("=C:"));
        assertTrue(ProviderProcessEnvironment.isWindowsDriveCurrentDirectory("=z:"));
        for (String refused : List.of("=C", "=C:\\", "XC:", "=1:", "=CD", "")) {
            assertFalse(ProviderProcessEnvironment.isWindowsDriveCurrentDirectory(refused), refused);
        }
    }

    @Test
    void theTrustedLauncherDropsAnEntryWithoutKeyOrValue() {
        Map<String, String> inherited = new HashMap<>();
        inherited.put(null, "orphan value");
        inherited.put("TEMP", null);
        inherited.put("PATH", "/usr/bin");

        Map<String, String> kept = ProviderProcessEnvironment.trustedLauncherEnvironment(
                inherited, Map.of(), key -> true);

        assertEquals(Map.of("PATH", "/usr/bin"), kept);
    }

    @Test
    void theDenyPatternRefusesPrefixesAndFragmentsInAnyCaseExceptTheNamedException() {
        for (String denied : List.of("MINOS_TEAM_TOKEN", "minos_home", "GITHUB_ACTOR", "npm_token", "NPM_AUTH",
                "ApiKey", "DB_PASSWORD", "aws_secret_access", "GIT_CREDENTIAL_HELPER", "SONAR_TOKEN")) {
            assertTrue(ProviderProcessEnvironment.isDeniedName(denied), denied);
        }
        assertFalse(ProviderProcessEnvironment.isDeniedName("npm_config_cache"));
        assertFalse(ProviderProcessEnvironment.isDeniedName("NPM_CONFIG_CACHE"), "the exception ignores case");
        assertTrue(ProviderProcessEnvironment.isDeniedName("npm_config_cache_token"), "a fragment still wins");
        assertFalse(ProviderProcessEnvironment.isDeniedName("JAVA_HOME"));
    }
}

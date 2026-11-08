package com.minos.runtime.local.wsl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The probe lines the jar writes in the distribution and the Windows side reads back. */
class WslJarProbeProtocolTest {

    @Test
    void theJarChecksTravelAsOneLinePerCheckAndComeBackIdentical() {
        List<WslCheck> checks = List.of(
                WslJarProbeProtocol.check(WslCheck.MAVEN, Optional.empty()),
                WslJarProbeProtocol.check(WslCheck.PROJECT_JDK,
                        Optional.of("JAVA_HOME is not set to the project JDK and minos.scip.java.project-jdk is not set")),
                WslJarProbeProtocol.check(WslCheck.MAVEN_REPOSITORY, Optional.empty()));

        String encoded = WslJarProbeProtocol.encode(checks);

        assertEquals(3, encoded.lines().count(), encoded);
        assertTrue(encoded.lines().allMatch(line -> line.startsWith(WslJarProbeProtocol.PROTOCOL + " ")), encoded);
        assertEquals(checks, WslJarProbeProtocol.decode(encoded.getBytes(StandardCharsets.UTF_8)));
        assertEquals(WslCheckState.MISSING, checks.get(1).state());
    }

    @Test
    void anActionThatNamesAPathIsReplacedByTheFixedActionOfItsCheck() {
        WslCheck jdk = WslJarProbeProtocol.check(WslCheck.PROJECT_JDK,
                Optional.of("JAVA_HOME does not contain javac: /usr/lib/jvm/broken"));
        WslCheck maven = WslJarProbeProtocol.check(WslCheck.MAVEN, Optional.of("the mvn of PATH is installed outside "
                + "the system directories the Linux sandbox mounts (/usr, /bin, /lib, /lib64, /sbin)"));

        assertEquals(WslCheckState.MISSING, jdk.state());
        assertFalse(jdk.action().contains("/usr"), jdk.action());
        assertTrue(jdk.action().contains("minos.scip.java.project-jdk"), jdk.action());
        assertFalse(maven.action().contains("/usr"), maven.action());
        assertTrue(maven.action().contains("3.9.x"), maven.action());

        List<WslCheck> forged = WslJarProbeProtocol.decode((WslJarProbeProtocol.PROTOCOL
                + " maven-repository MISSING the repository C:\\Users\\someone\\.m2 is refused\n")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(1, forged.size());
        assertFalse(forged.getFirst().action().contains("Users"), forged.getFirst().action());
        assertTrue(forged.getFirst().action().contains("minos.scip.java.maven-repository"),
                forged.getFirst().action());
    }

    @Test
    void foreignMalformedAndDuplicateLinesAreIgnored() {
        String stdout = String.join("\n",
                "Picked up JAVA_TOOL_OPTIONS: something",
                WslJarProbeProtocol.PROTOCOL + " maven OK",
                WslJarProbeProtocol.PROTOCOL + " maven MISSING a second answer is ignored",
                WslJarProbeProtocol.PROTOCOL + " bubblewrap OK",
                WslJarProbeProtocol.PROTOCOL + " project-jdk BROKEN state",
                WslJarProbeProtocol.PROTOCOL + " maven-repository OK but an action is not allowed",
                WslJarProbeProtocol.PROTOCOL + " project-jdk MISSING",
                "minos-wsl-worker-v1 OK",
                "");

        List<WslCheck> checks = WslJarProbeProtocol.decode(stdout.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, checks.size(), checks.toString());
        assertEquals(new WslCheck(WslCheck.MAVEN, WslCheckState.OK, ""), checks.get(0));
        assertEquals(WslCheck.PROJECT_JDK, checks.get(1).id());
        assertEquals(WslCheckState.MISSING, checks.get(1).state());
        assertFalse(checks.get(1).action().isBlank(), "a missing check always names an action");
    }

    @Test
    void anUnreadableConfigurationReportsEveryJarCheckUnknown() {
        List<WslCheck> checks = WslJarProbeProtocol.unknownChecks("the MINOS configuration cannot be read");

        assertEquals(List.of(WslCheck.MAVEN, WslCheck.PROJECT_JDK, WslCheck.MAVEN_REPOSITORY),
                checks.stream().map(WslCheck::id).toList());
        assertTrue(checks.stream().allMatch(check -> check.state() == WslCheckState.UNKNOWN), checks.toString());
    }
}

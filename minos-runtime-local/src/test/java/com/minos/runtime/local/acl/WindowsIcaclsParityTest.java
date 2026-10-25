package com.minos.runtime.local.acl;

import com.minos.io.PlatformAccessControl;
import com.minos.testsupport.IcaclsTestAccessControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test double of the port, used by the modules that cannot see this one, must keep doing what the production
 * implementation does: the same DACL after protection, the same refusal of a conditional deny. A double that
 * drifts would let those modules test a mechanism that no longer exists.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsIcaclsParityTest {

    private static final String CONDITIONAL_ALLOW = "(XA;OICI;0x1200a9;;;WD;(Member_of {SID(BA)}))";
    private static final String CONDITIONAL_DENY = "(XD;OICI;0x1301bf;;;WD;(Member_of {SID(BG)}))";

    private final PlatformAccessControl production = new WindowsIcaclsAccessControl();
    private final PlatformAccessControl testDouble = new IcaclsTestAccessControl();

    @TempDir
    Path temporary;

    @Test
    void bothProtectTheSameInheritedDaclTheSameWay() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("parent"));
        setSddl(parent, "D:PAI(A;OICI;FA;;;" + currentUserSid() + ")(A;OICI;0x1200a9;;;WD)" + CONDITIONAL_ALLOW);
        Path byProduction = Files.createDirectory(parent.resolve("production"));
        Path byDouble = Files.createDirectory(parent.resolve("double"));

        production.requireNoConditionalDeny(byProduction);
        testDouble.requireNoConditionalDeny(byDouble);
        production.protectFromInheritance(byProduction);
        testDouble.protectFromInheritance(byDouble);

        String protectedByProduction = rawDacl(byProduction);
        assertTrue(protectedByProduction.startsWith("D:P"), "the DACL is protected: " + protectedByProduction);
        assertEquals(protectedByProduction, rawDacl(byDouble));
    }

    @Test
    void bothRefuseAConditionalDenyWithTheSameMessage() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("guarded"));
        // The condition is false for any normal account (BUILTIN\Guests), so the fixture never locks the test out.
        setSddl(parent, "D:PAI(A;OICI;FA;;;" + currentUserSid() + ")" + CONDITIONAL_DENY);
        Path byProduction = Files.createDirectory(parent.resolve("production"));
        Path byDouble = Files.createDirectory(parent.resolve("double"));

        IOException productionRefusal = assertThrows(IOException.class,
                () -> production.requireNoConditionalDeny(byProduction));
        IOException doubleRefusal = assertThrows(IOException.class, () -> testDouble.requireNoConditionalDeny(byDouble));

        assertEquals(productionRefusal.getMessage(), doubleRefusal.getMessage());
        assertFalse(productionRefusal.getMessage().contains(temporary.toString()));
    }

    // ------------------------------------------------------------------------------ helpers

    private static String currentUserSid() throws Exception {
        Process process = new ProcessBuilder("whoami", "/user", "/fo", "csv", "/nh").redirectErrorStream(true).start();
        String line = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
        assertEquals(0, process.waitFor());
        return line.substring(line.lastIndexOf(',') + 1).replace("\"", "").trim();
    }

    private static void setSddl(Path path, String sddl) throws Exception {
        String script = "$a = New-Object System.Security.AccessControl.DirectorySecurity;"
                + "$a.SetSecurityDescriptorSddlForm('" + sddl + "');"
                + "Set-Acl -LiteralPath '" + path + "' -AclObject $a";
        Process process = new ProcessBuilder(
                Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertEquals(0, process.waitFor(), "Set-Acl failed: " + output);
    }

    /** The DACL line of {@code icacls /save}, which names no object. */
    private static String rawDacl(Path path) throws Exception {
        Path saved = Files.createTempFile("minos-dacl-parity-", ".sddl");
        try {
            Process process = new ProcessBuilder(
                    Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString(),
                    path.toString(), "/save", saved.toString(), "/q")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
            String text = new String(Files.readAllBytes(saved), StandardCharsets.UTF_16LE);
            return text.lines().map(line -> line.replace(String.valueOf((char) 0xFEFF), ""))
                    .filter(line -> line.startsWith("D:")).findFirst().orElseThrow();
        } finally {
            Files.deleteIfExists(saved);
        }
    }
}

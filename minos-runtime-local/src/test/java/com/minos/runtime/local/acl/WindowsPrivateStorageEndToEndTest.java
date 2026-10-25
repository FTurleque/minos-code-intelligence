package com.minos.runtime.local.acl;

import com.minos.io.PrivateLocalStorage;
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
 * Private storage on Windows through the production platform access control, discovered from this module's
 * {@code META-INF/services} as in the distributed JAR: the cases of the engine's Windows test that depend on the
 * mechanism (conditional entries, protection from inheritance) replayed against the real implementation, which the
 * engine's own tests only reach through their test double.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsPrivateStorageEndToEndTest {

    /** The condition is false for any normal account (BUILTIN\Guests), so the fixture never locks the test out. */
    private static final String CONDITIONAL_DENY = "(XD;OICI;0x1301bf;;;WD;(Member_of {SID(BG)}))";

    @TempDir
    Path temporary;

    @Test
    void anOrdinaryDirectoryIsHardenedAndProtectedFromInheritance() throws Exception {
        Path directory = temporary.resolve("ordinary");

        PrivateLocalStorage.ensurePrivateDirectory(directory);

        assertTrue(rawSddl(directory).startsWith("D:P"), "protected: " + rawSddl(directory));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(directory));
    }

    @Test
    void anInheritableGrantAddedLaterToTheParentDoesNotReachAHardenedChild() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("parent"));
        Path child = parent.resolve("child");
        PrivateLocalStorage.ensurePrivateDirectory(child);

        run(icacls(), parent.toString(), "/grant", "*S-1-1-0:(OI)(CI)R", "/q");

        assertFalse(rawSddl(child).contains(";WD)"), "nothing reaches the child: " + rawSddl(child));
    }

    @Test
    void anExplicitConditionalDenyIsRefusedWithoutAPathAndStillThereAfterwards() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("conditional-deny"));
        setSddl(directory, "D:PAI(A;OICI;FA;;;" + currentUserSid() + ")" + CONDITIONAL_DENY);

        IOException refusal = assertThrows(IOException.class, () -> PrivateLocalStorage.ensurePrivateDirectory(directory));

        assertFalse(String.valueOf(refusal.getMessage()).contains(temporary.toString()));
        assertTrue(rawSddl(directory).contains("XD;"), "the conditional deny survives: " + rawSddl(directory));
    }

    @Test
    void aConditionalDenyInheritedByAFileIsNotDroppedByHardeningTheFile() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("conditional-deny-parent"));
        setSddl(parent, "D:PAI(A;OICI;FA;;;" + currentUserSid() + ")" + CONDITIONAL_DENY);
        Path file = Files.writeString(parent.resolve("file.txt"), "x");

        assertThrows(IOException.class, () -> PrivateLocalStorage.hardenExistingFile(file));

        assertTrue(rawSddl(file).contains("XD;"), "the inherited conditional deny survives: " + rawSddl(file));
    }

    @Test
    void aConditionalAllowInheritedFromTheParentIsNotKept() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("conditional-parent"));
        setSddl(parent, "D:PAI(A;OICI;FA;;;" + currentUserSid() + ")(XA;OICI;0x1301bf;;;WD;(Member_of {SID(WD)}))");
        Path child = Files.createDirectory(parent.resolve("child"));
        assertTrue(rawSddl(child).contains("XA;"), "fixture: the child inherits the conditional entry");

        PrivateLocalStorage.ensurePrivateDirectory(child);

        assertFalse(rawSddl(child).contains("XA;"), "the conditional allow is gone: " + rawSddl(child));
        assertTrue(rawSddl(child).startsWith("D:P"), "protected: " + rawSddl(child));
    }

    // ------------------------------------------------------------------------------ helpers

    private static String icacls() {
        return Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString();
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + output);
        return output;
    }

    private static String currentUserSid() throws Exception {
        String line = run("whoami", "/user", "/fo", "csv", "/nh").trim();
        return line.substring(line.lastIndexOf(',') + 1).replace("\"", "").trim();
    }

    private static void setSddl(Path path, String sddl) throws Exception {
        run(Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                "$a = New-Object System.Security.AccessControl.DirectorySecurity;"
                        + "$a.SetSecurityDescriptorSddlForm('" + sddl + "');"
                        + "Set-Acl -LiteralPath '" + path + "' -AclObject $a");
    }

    /** The DACL line {@code icacls /save} writes for {@code path}. */
    private static String rawSddl(Path path) throws Exception {
        Path saved = Files.createTempFile("minos-dacl-e2e-", ".sddl");
        try {
            run(icacls(), path.toString(), "/save", saved.toString(), "/q");
            String text = new String(Files.readAllBytes(saved), StandardCharsets.UTF_16LE);
            return text.lines().map(line -> line.replace(String.valueOf((char) 0xFEFF), ""))
                    .filter(line -> line.startsWith("D:")).findFirst().orElseThrow();
        } finally {
            Files.deleteIfExists(saved);
        }
    }
}

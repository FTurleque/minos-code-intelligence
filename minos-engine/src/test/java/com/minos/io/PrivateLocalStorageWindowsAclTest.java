package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real NTFS ACLs, driven with {@code icacls}: what an administrator does to a location MINOS then
 * hardens. Every test here is Windows-only and is skipped, not neutralised, elsewhere.
 */
@EnabledOnOs(OS.WINDOWS)
class PrivateLocalStorageWindowsAclTest {

    @TempDir
    Path temporary;

    @Test
    void anExplicitDenyEntryPlacedByAnAdministratorSurvivesHardening() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        String sid = currentUserSid();
        icacls(home, "/deny", "*" + sid + ":(WD,AD)");

        try {
            PrivateLocalStorage.ensurePrivateDirectory(home);

            assertTrue(hasWriteDenyForOwner(home), "MINOS must not remove a restriction an administrator placed");
            assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(home));
            assertTrue(denyPrecedesEveryAllow(home), "deny entries stay ahead of the owner's allow entry");
        } finally {
            icacls(home, "/remove:d", "*" + sid);
        }
    }

    @Test
    void aDenyEntryInheritedFromTheParentSurvivesHardeningAsAnExplicitEntry() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("parent"));
        Path child = Files.createDirectory(parent.resolve("child"));
        String sid = currentUserSid();
        icacls(parent, "/deny", "*" + sid + ":(OI)(CI)(IO)(WA)");

        try {
            PrivateLocalStorage.ensurePrivateDirectory(child);

            assertTrue(hasDenyOf(child, AclEntryPermission.WRITE_ATTRIBUTES),
                    "an inherited restriction is converted, never dropped, when the DACL stops inheriting");
        } finally {
            icacls(parent, "/remove:d", "*" + sid);
        }
    }

    @Test
    void aWriteProtectedLocationFailsClosedAndSaysSoWithoutNamingThePath() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("write-protected-home"));
        String sid = currentUserSid();
        icacls(home, "/deny", "*" + sid + ":(WD,AD)");

        try {
            Path hardened = PrivateLocalStorage.ensurePrivateDirectory(home);

            IOException file = assertThrows(IOException.class,
                    () -> PrivateLocalStorage.createPrivateFile(hardened.resolve("created.bin")));
            IOException directory = assertThrows(IOException.class,
                    () -> PrivateLocalStorage.ensurePrivateDirectory(hardened.resolve("created")));

            for (IOException failure : List.of(file, directory)) {
                assertNotNull(failure.getMessage());
                assertTrue(failure.getMessage().contains("explicit deny entry"), failure.getMessage());
                assertTrue(failure.getMessage().contains("does not remove it"), failure.getMessage());
                assertFalse(failure.getMessage().contains(temporary.toString()),
                        "no absolute path in the message: " + failure.getMessage());
            }
            assertTrue(hasWriteDenyForOwner(home), "failing is the only answer: the deny is still there");
            assertFalse(Files.exists(hardened.resolve("created.bin")));
        } finally {
            icacls(home, "/remove:d", "*" + sid);
        }
    }

    @Test
    void aWriteProtectedLocationStaysReadableAndVerifiableWithoutAnyWrite() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("read-only-home"));
        Files.writeString(home.resolve("present.txt"), "existing");
        String sid = currentUserSid();
        icacls(home, "/deny", "*" + sid + ":(WD,AD)");

        try {
            PrivateLocalStorage.ensurePrivateDirectory(home);

            assertEquals("existing", Files.readString(home.resolve("present.txt")));
            PrivateLocalStorage.verifyPrivateDirectory(home);
            assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(home));
        } finally {
            icacls(home, "/remove:d", "*" + sid);
        }
    }

    @Test
    void anInheritableGrantAddedLaterToTheParentDoesNotReachHardenedChildren() throws Exception {
        Path home = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("home"));
        Path nested = PrivateLocalStorage.ensurePrivateDirectory(home.resolve("tools").resolve("x"));
        Path nestedFile = PrivateLocalStorage.createPrivateFile(nested.resolve("artifact.bin"));
        Path direct = PrivateLocalStorage.writePrivateFile(home.resolve("direct.bin"), new byte[]{1});

        icacls(home, "/grant", "*S-1-1-0:(OI)(CI)R");

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(home.resolve("tools")));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(nested));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(nestedFile));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(direct),
                "a hardened file directly under the modified directory keeps its own DACL");
    }

    @Test
    void aHardenedDaclIsProtectedAgainstInheritanceForDirectoriesAndFiles() throws Exception {
        Path home = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("home"));
        Path file = PrivateLocalStorage.createPrivateFile(home.resolve("created.bin"));
        Path written = PrivateLocalStorage.writePrivateFile(home.resolve("written.bin"), new byte[]{1});
        Path legacy = Files.writeString(home.resolve("legacy.txt"), "legacy");
        PrivateLocalStorage.hardenExistingFile(legacy);

        for (Path path : List.of(home, file, written, legacy)) {
            assertTrue(isDaclProtected(path), "the DACL must not inherit from its parent");
        }
    }

    @Test
    void hardeningAnAlreadyRightLocationDoesNotRewriteItsDacl() throws Exception {
        Path home = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("home"));
        CountingAclProbe probe = new CountingAclProbe();
        PrivateLocalStorage.useForTesting(probe);
        try {
            PrivateLocalStorage.forgetProtectedLocationsForTesting();
            PrivateLocalStorage.ensurePrivateDirectory(home);
            PrivateLocalStorage.ensurePrivateDirectory(home);
        } finally {
            PrivateLocalStorage.resetCapabilityProbeForTesting();
        }

        assertEquals(0, probe.setAclCalls, "a location that already has the right DACL is read, not written");
        assertTrue(isDaclProtected(home));
    }

    @Test
    void anObjectWeCreatedIsOwnedByTheCurrentUserAndASystemOneIsNot() throws Exception {
        Path ours = Files.createDirectory(temporary.resolve("ours"));

        PrivateLocalStorage.verifyOwnedByCurrentUser(ours);

        IOException refusal = assertThrows(IOException.class,
                () -> PrivateLocalStorage.verifyOwnedByCurrentUser(Path.of(System.getenv("SystemRoot"), "System32")));
        assertFalse(refusal.getMessage().contains("System32"), "no path in the message");
    }

    @Test
    void anObjectDeletedAndCreatedAgainAtTheSamePathIsProtectedAgain() throws Exception {
        Path home = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("home"));
        Path file = PrivateLocalStorage.createPrivateFile(home.resolve("recreated.bin"));
        Path directory = PrivateLocalStorage.ensurePrivateDirectory(home.resolve("recreated"));
        Files.delete(file);
        Files.delete(directory);

        Path againFile = PrivateLocalStorage.createPrivateFile(home.resolve("recreated.bin"));
        Path againDirectory = PrivateLocalStorage.ensurePrivateDirectory(home.resolve("recreated"));
        Path againWritten = PrivateLocalStorage.writePrivateFile(home.resolve("written.bin"), new byte[]{1});
        Files.delete(againWritten);
        againWritten = PrivateLocalStorage.writePrivateFile(home.resolve("written.bin"), new byte[]{2});

        for (Path path : List.of(againFile, againDirectory, againWritten)) {
            assertTrue(isDaclProtected(path), "a new object at a known path is a new object");
        }
        icacls(home, "/grant", "*S-1-1-0:(OI)(CI)R");
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(againFile));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(againDirectory));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(againWritten));
    }

    // ------------------------------------------------------------------------------ helpers

    private static boolean hasWriteDenyForOwner(Path path) throws IOException {
        return hasDenyOf(path, AclEntryPermission.WRITE_DATA) && hasDenyOf(path, AclEntryPermission.APPEND_DATA);
    }

    private static boolean hasDenyOf(Path path, AclEntryPermission permission) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        UserPrincipal owner = view.getOwner();
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() == AclEntryType.DENY
                    && entry.principal().equals(owner)
                    && entry.permissions().contains(permission)) {
                return true;
            }
        }
        return false;
    }

    private static boolean denyPrecedesEveryAllow(Path path) throws IOException {
        boolean allowSeen = false;
        for (AclEntry entry : Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl()) {
            if (entry.type() == AclEntryType.ALLOW) allowSeen = true;
            else if (allowSeen && entry.type() == AclEntryType.DENY) return false;
        }
        return true;
    }

    private static void icacls(Path path, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString());
        command.add(path.toString());
        command.addAll(List.of(arguments));
        command.add("/q");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertEquals(0, process.waitFor(), "icacls failed: " + output);
    }

    /** Reads the protected-DACL bit the way an administrator would: from the SDDL {@code icacls /save} writes. */
    private static boolean isDaclProtected(Path path) throws Exception {
        Path saved = Files.createTempFile("minos-dacl-", ".txt");
        try {
            Process process = new ProcessBuilder(
                    Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString(),
                    path.toString(), "/save", saved.toString(), "/q")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
            String sddl = new String(Files.readAllBytes(saved), java.nio.charset.StandardCharsets.UTF_16LE);
            String dacl = sddl.lines().filter(line -> line.startsWith("D:")).findFirst().orElseThrow();
            return dacl.substring(2, dacl.indexOf('(')).contains("P");
        } finally {
            Files.deleteIfExists(saved);
        }
    }

    /** Real ACL view, counting how many times the DACL is written. */
    private static final class CountingAclProbe implements PrivateLocalStorage.CapabilityProbe {
        int setAclCalls;

        @Override
        public boolean supportsPosix(Path target) {
            return false;
        }

        @Override
        public AclFileAttributeView aclView(Path target) {
            AclFileAttributeView real = Files.getFileAttributeView(
                    target, AclFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return new AclFileAttributeView() {
                @Override public String name() { return real.name(); }
                @Override public List<AclEntry> getAcl() throws IOException { return real.getAcl(); }
                @Override public void setAcl(List<AclEntry> acl) throws IOException {
                    setAclCalls++;
                    real.setAcl(acl);
                }
                @Override public UserPrincipal getOwner() throws IOException { return real.getOwner(); }
                @Override public void setOwner(UserPrincipal owner) throws IOException { real.setOwner(owner); }
            };
        }
    }

    private static String currentUserSid() throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getenv("SystemRoot"), "System32", "whoami.exe").toString(),
                "/user", "/fo", "csv", "/nh")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
        assertEquals(0, process.waitFor(), output);
        String sid = output.substring(output.lastIndexOf(",\"") + 2, output.length() - 1);
        assertTrue(sid.startsWith("S-1-"), "unexpected whoami output");
        return sid;
    }
}

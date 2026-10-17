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
                for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                    assertFalse(String.valueOf(cause.getMessage()).contains(temporary.toString()),
                            "no absolute path in the chained cause");
                }
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
    void hardeningRewritesTheDaclOnTheFirstTouchOfAJvmAndNeverWhenNothingChanged() throws Exception {
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

        assertEquals(1, probe.setAclCalls,
                "the first touch of a JVM rewrites the DACL; the second call, with nothing changed, writes nothing");
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

    @Test
    void theProtectionIsAssertedOncePerObjectAndPerJvmNotOnEveryCall() throws Exception {
        Path home = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("home"));
        PrivateLocalStorage.CapabilityProbe real = PrivateLocalStorage.CapabilityProbe.real();
        int[] calls = {0};
        PrivateLocalStorage.useForTesting(new PrivateLocalStorage.CapabilityProbe() {
            @Override public boolean supportsPosix(Path target) { return real.supportsPosix(target); }
            @Override public AclFileAttributeView aclView(Path target) { return real.aclView(target); }
            @Override public void protectFromInheritance(Path target) throws IOException {
                calls[0]++;
                real.protectFromInheritance(target);
            }
        });
        try {
            PrivateLocalStorage.forgetProtectedLocationsForTesting();
            PrivateLocalStorage.ensurePrivateDirectory(home);
            PrivateLocalStorage.ensurePrivateDirectory(home);
            assertEquals(1, calls[0], "once per object in this JVM");

            PrivateLocalStorage.forgetProtectedLocationsForTesting();
            PrivateLocalStorage.ensurePrivateDirectory(home);
            assertEquals(2, calls[0], "a fresh JVM asserts it again, even on an object that is already protected");
        } finally {
            PrivateLocalStorage.resetCapabilityProbeForTesting();
        }
        assertTrue(isDaclProtected(home));
    }

    @Test
    void aConditionalEntryInheritedFromTheParentIsNotKeptByHardening() throws Exception {
        // Java's getAcl() does not see conditional (XA) entries: a child that inherits one looks "already
        // right" to it, and turning inheritance off would make the hidden entry explicit and permanent.
        Path parent = Files.createDirectory(temporary.resolve("conditional-parent"));
        String sid = currentUserSid();
        setSddl(parent, "D:PAI(A;OICI;FA;;;" + sid + ")(XA;OICI;0x1301bf;;;WD;(Member_of {SID(WD)}))");
        Path child = Files.createDirectory(parent.resolve("child"));
        Path file = Files.writeString(parent.resolve("file.txt"), "x");
        assertTrue(rawSddl(child).contains("XA;"), "fixture: the child inherits the conditional entry");

        PrivateLocalStorage.ensurePrivateDirectory(child);
        PrivateLocalStorage.hardenExistingFile(file);

        assertFalse(rawSddl(child).contains("XA;"), "the conditional entry must not survive: " + rawSddl(child));
        assertFalse(rawSddl(file).contains("XA;"), "the conditional entry must not survive: " + rawSddl(file));
        assertTrue(isDaclProtected(child));
    }

    /** The condition is false for any normal account (BUILTIN\Guests), so the fixture never locks the test out. */
    private static final String CONDITIONAL_DENY = "(XD;OICI;0x1301bf;;;WD;(Member_of {SID(BG)}))";

    @Test
    void anExplicitConditionalDenyIsRefusedAndStillThereAfterwards() throws Exception {
        // Java's getAcl() does not show conditional entries and setAcl cannot write one back: rewriting the DACL
        // would silently lift an administrator's restriction. MINOS refuses instead and leaves the DACL alone.
        Path directory = Files.createDirectory(temporary.resolve("conditional-deny"));
        setSddl(directory, "D:PAI(A;OICI;FA;;;" + currentUserSid() + ")" + CONDITIONAL_DENY);
        assertTrue(rawSddl(directory).contains("XD;"), "fixture: the directory holds the conditional deny");

        IOException refusal = assertThrows(IOException.class, () -> PrivateLocalStorage.ensurePrivateDirectory(directory));

        assertFalse(String.valueOf(refusal.getMessage()).contains(temporary.toString()),
                "the refusal must not name the path");
        assertTrue(rawSddl(directory).contains("XD;"), "the conditional deny must survive: " + rawSddl(directory));
    }

    @Test
    void aConditionalDenyInheritedByAFileIsNotDroppedByHardeningTheFile() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("conditional-deny-parent"));
        setSddl(parent, "D:PAI(A;OICI;FA;;;" + currentUserSid() + ")" + CONDITIONAL_DENY);
        Path file = Files.writeString(parent.resolve("file.txt"), "x");
        assertTrue(rawSddl(file).contains("XD;"), "fixture: the file inherits the conditional deny");

        assertThrows(IOException.class, () -> PrivateLocalStorage.hardenExistingFile(file));

        assertTrue(rawSddl(file).contains("XD;"), "the inherited conditional deny must survive: " + rawSddl(file));
    }

    @Test
    void aDaclThatCannotBeReadFailsTheHardeningBeforeAnyWrite() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("unreadable-dacl"));
        CountingAclProbe probe = new CountingAclProbe();
        probe.daclFailure = new IOException("cannot read the DACL of private storage");
        PrivateLocalStorage.useForTesting(probe);
        try {
            IOException failure = assertThrows(IOException.class, () -> PrivateLocalStorage.ensurePrivateDirectory(directory));
            assertEquals("cannot read the DACL of private storage", failure.getMessage());
        } finally {
            PrivateLocalStorage.resetCapabilityProbeForTesting();
        }
        assertEquals(0, probe.setAclCalls, "a DACL that cannot be read is never overwritten");
    }

    @Test
    void anOrdinaryDirectoryIsStillHardenedWhenNoConditionalDenyIsPresent() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("ordinary"));

        PrivateLocalStorage.ensurePrivateDirectory(directory);

        assertTrue(isDaclProtected(directory));
        assertFalse(rawSddl(directory).contains("XD;"));
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
                    && (entry.principal().equals(owner) || namesTheCurrentUser(entry.principal()))
                    && entry.permissions().contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /** An elevated token (CI runners) owns what it creates as the Administrators group, not as the user. */
    private static boolean namesTheCurrentUser(UserPrincipal principal) {
        String name = principal.getName();
        String account = name.substring(name.lastIndexOf('\\') + 1);
        return account.equalsIgnoreCase(System.getProperty("user.name"));
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

    private static String rawSddl(Path path) throws Exception {
        Path saved = Files.createTempFile("minos-sddl-", ".txt");
        try {
            Process process = new ProcessBuilder(
                    Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString(),
                    path.toString(), "/save", saved.toString(), "/q")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
            return new String(Files.readAllBytes(saved), java.nio.charset.StandardCharsets.UTF_16LE);
        } finally {
            Files.deleteIfExists(saved);
        }
    }

    /** Reads the protected-DACL bit the way an administrator would: from the SDDL {@code icacls /save} writes. */
    private static boolean isDaclProtected(Path path) throws Exception {
        String dacl = rawSddl(path).lines().filter(line -> line.startsWith("D:")).findFirst().orElseThrow();
        return dacl.substring(2, dacl.indexOf('(')).contains("P");
    }

    /** Real ACL view, counting how many times the DACL is written. */
    private static final class CountingAclProbe implements PrivateLocalStorage.CapabilityProbe {
        int setAclCalls;
        IOException daclFailure;

        @Override
        public boolean supportsPosix(Path target) {
            return false;
        }

        @Override
        public void requireNoConditionalDeny(Path target) throws IOException {
            if (daclFailure != null) throw daclFailure;
            PrivateLocalStorage.CapabilityProbe.real().requireNoConditionalDeny(target);
        }

        @Override
        public void protectFromInheritance(Path target) throws IOException {
            PrivateLocalStorage.CapabilityProbe.real().protectFromInheritance(target);
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

    // MINOS-AUD-H23: a pre-existing directory is never handed to another account. The profile is a temporary directory
    // passed as user.home, and "another account" is the owner of the Windows directory passed as the current user:
    // the real profile is never touched.

    @Test
    void aMinosHomeOnTheProfileOrOneOfItsParentsIsRefusedAndItsAclIsLeftAlone() throws Exception {
        Path profile = Files.createDirectories(temporary.resolve("Users").resolve("someone"));
        UserPrincipal currentUser = Files.getOwner(profile);
        for (Path refused : List.of(profile, profile.getParent())) {
            List<AclEntry> before = acl(refused);

            IOException refusal = assertThrows(IOException.class,
                    () -> PrivateLocalStorage.ensurePrivateDirectory(refused, currentUser, profile));

            assertTrue(refusal.getMessage().contains("dedicated to MINOS"), refusal.getMessage());
            assertFalse(refusal.getMessage().contains(temporary.toString()), "no path in the message");
            assertEquals(before, acl(refused), "a refused directory keeps its DACL");
        }
        PrivateLocalStorage.ensurePrivateDirectory(profile.resolve(".minos"), currentUser, profile);
    }

    @Test
    void aPreExistingDirectoryOfAnotherAccountIsRefusedAndItsAclIsLeftAlone() throws Exception {
        Path shared = Files.createDirectories(temporary.resolve("shared"));
        List<AclEntry> before = acl(shared);
        UserPrincipal someoneElse = Files.getOwner(Path.of(System.getenv("SystemRoot")));

        IOException refusal = assertThrows(IOException.class,
                () -> PrivateLocalStorage.ensurePrivateDirectory(shared, someoneElse, temporary.resolve("profile")));

        assertTrue(refusal.getMessage().contains("belongs to another account"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), "no path in the message");
        assertEquals(before, acl(shared), "a refused directory keeps its DACL");
    }

    @Test
    void aPreExistingDirectoryOfTheUserIsStillMadePrivate() throws Exception {
        Path mine = Files.createDirectories(temporary.resolve("mine"));
        UserPrincipal currentUser = Files.getOwner(mine);

        PrivateLocalStorage.ensurePrivateDirectory(mine, currentUser, temporary.resolve("profile"));

        assertTrue(acl(mine).stream().allMatch(entry -> entry.type() != AclEntryType.ALLOW
                || entry.principal().equals(currentUser)), "only the owner is allowed");
        assertTrue(isDaclProtected(mine));
    }

    @Test
    void theIdentityProbeFallsBackOnTheCheckedDirectoryWhenItsParentCannotBeWritten() throws Exception {
        Path parent = Files.createDirectories(temporary.resolve("locked-parent"));
        Path home = Files.createDirectories(parent.resolve("home"));
        UserPrincipal currentUser = Files.getOwner(home);
        denyWriting(parent, currentUser);
        try {
            assertEquals(currentUser, PrivateLocalStorage.probeOwner(home));
            try (var leftovers = Files.list(home)) {
                assertEquals(0L, leftovers.count(), "the probe is deleted");
            }
        } finally {
            allowWritingAgain(parent);
        }
    }

    @Test
    void anIdentityThatCannotBeProbedFailsWithoutAPathInTheMessage() throws Exception {
        Path parent = Files.createDirectories(temporary.resolve("locked-parent"));
        Path home = Files.createDirectories(parent.resolve("locked-home"));
        UserPrincipal currentUser = Files.getOwner(home);
        denyWriting(home, currentUser);
        denyWriting(parent, currentUser);
        try {
            IOException refusal = assertThrows(IOException.class, () -> PrivateLocalStorage.probeOwner(home));

            assertTrue(refusal.getMessage().contains("cannot verify who owns"), refusal.getMessage());
            for (Throwable cause = refusal; cause != null; cause = cause.getCause()) {
                assertFalse(String.valueOf(cause.getMessage()).contains(temporary.toString()),
                        "no path in the message or its causes: " + cause);
            }
        } finally {
            allowWritingAgain(home);
            allowWritingAgain(parent);
        }
    }

    /** Puts a DENY entry for creating files and folders in front of the DACL; the owner can still remove it. */
    private static void denyWriting(Path directory, UserPrincipal principal) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                directory, AclFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> entries = new java.util.ArrayList<>(view.getAcl());
        entries.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(principal)
                .setPermissions(AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY).build());
        view.setAcl(entries);
    }

    private static void allowWritingAgain(Path directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                directory, AclFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        view.setAcl(view.getAcl().stream().filter(entry -> entry.type() != AclEntryType.DENY).toList());
    }

    private static List<AclEntry> acl(Path path) throws IOException {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .getAcl();
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

package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inheritable grant on a sandbox write root goes to the real identity of the process, never to a
 * name read from the {@code user.name} system property, which the command line can set to anything,
 * including a SID of a broad group (icacls accepts {@code *S-1-1-0}).
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxWriteRootIdentityTest {

    @TempDir
    Path temporary;

    @Test
    void aHostileUserNamePropertyCannotGrantAccessToAnotherPrincipal() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("write-root"));
        String owner = Files.getOwner(root).getName();
        Set<String> before = allowedPrincipals(root);

        withUserName("*S-1-1-0", () -> WindowsAppContainerWorkerSandboxBackend.requireInheritableOwnerAccess(root));

        Set<String> added = allowedPrincipals(root);
        added.removeAll(before);
        added.remove(owner);
        assertTrue(added.isEmpty(), "the grant went to a principal named by user.name: " + added);
    }

    @Test
    void aUserNameThatDivergesFromTheProcessIdentityDoesNotBreakTheGrant() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("write-root-diverging"));

        assertDoesNotThrow(() -> withUserName("minos-no-such-user",
                () -> WindowsAppContainerWorkerSandboxBackend.requireInheritableOwnerAccess(root)));
    }

    private static Set<String> allowedPrincipals(Path path) throws IOException {
        Set<String> principals = new HashSet<>();
        for (AclEntry entry : Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl()) {
            if (entry.type() == AclEntryType.ALLOW) principals.add(entry.principal().getName());
        }
        return principals;
    }

    private static void withUserName(String value, Runnable action) {
        String previous = System.getProperty("user.name");
        System.setProperty("user.name", value);
        try {
            action.run();
        } finally {
            System.setProperty("user.name", previous);
        }
    }
}

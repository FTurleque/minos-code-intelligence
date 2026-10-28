package com.minos.runtime.local.sandbox;

import com.minos.orchestration.ExecutionPathIdentityProvider.IdentityPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows identity provider fails closed: a path whose identity cannot be read, or a capture that
 * is interrupted, gives no identity at all, never a partial or invented one. The provider is called
 * directly, not through the service loader.
 */
class WindowsExecutionPathIdentityProviderFailureTest {

    private static final String IDENTITY = "windows:[0-9a-f]{8}:[0-9a-f]{16}";

    @TempDir
    Path temporary;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void twoDirectoriesGetTwoLowerCaseIdentitiesAndTheSameDirectoryGetsTheSameOne() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Path module = Files.createDirectory(project.resolve("module"));
        WindowsExecutionPathIdentityProvider provider = new WindowsExecutionPathIdentityProvider();

        IdentityPair distinct = provider.capture(project, module).orElseThrow();
        IdentityPair same = provider.capture(project, project).orElseThrow();

        assertTrue(distinct.registeredProjectIdentity().matches(IDENTITY), distinct.registeredProjectIdentity());
        assertTrue(distinct.projectIdentity().matches(IDENTITY), distinct.projectIdentity());
        assertNotEquals(distinct.registeredProjectIdentity(), distinct.projectIdentity());
        assertEquals(same.registeredProjectIdentity(), same.projectIdentity());
        assertEquals(distinct.registeredProjectIdentity(), same.registeredProjectIdentity(),
                "the identity of a directory does not change between two captures");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRegisteredRootThatDoesNotExistGivesNoIdentityAndTheFailureDoesNotNameIt() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Path missing = temporary.resolve("missing-registered-root");
        WindowsExecutionPathIdentityProvider provider = new WindowsExecutionPathIdentityProvider();

        IOException failure = assertThrows(IOException.class, () -> provider.capture(missing, project));

        assertTrue(failure.getMessage().startsWith("Windows filesystem identity query failed (exit "), failure.getMessage());
        assertFalse(failure.getMessage().contains(temporary.toString()), "no absolute path: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("missing-registered-root"), failure.getMessage());
        assertFalse(failure.getMessage().contains("registered="), "no partial identity leaks into the failure");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aProjectRootThatDoesNotExistGivesNoIdentityEvenWhenTheRegisteredRootHasOne() throws Exception {
        Path registered = Files.createDirectory(temporary.resolve("registered"));
        Path missing = registered.resolve("missing-module");
        WindowsExecutionPathIdentityProvider provider = new WindowsExecutionPathIdentityProvider();

        IOException failure = assertThrows(IOException.class, () -> provider.capture(registered, missing));

        assertTrue(failure.getMessage().startsWith("Windows filesystem identity query failed (exit "), failure.getMessage());
        assertFalse(failure.getMessage().contains(temporary.toString()), "no absolute path: " + failure.getMessage());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void anInterruptedCaptureFailsAndKeepsTheInterruptionForTheCaller() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        WindowsExecutionPathIdentityProvider provider = new WindowsExecutionPathIdentityProvider();
        boolean stillInterrupted;

        Thread.currentThread().interrupt();
        IOException failure;
        try {
            failure = assertThrows(IOException.class, () -> provider.capture(project, project));
        } finally {
            // Reads and clears the flag, so that an interruption never leaks into the next test.
            stillInterrupted = Thread.interrupted();
        }

        assertEquals("interrupted while capturing Windows filesystem identity", failure.getMessage());
        assertTrue(stillInterrupted, "the interruption must remain visible to the caller");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void anotherOperatingSystemGetsNoIdentityFromTheWindowsProvider() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));

        Optional<IdentityPair> identity = new WindowsExecutionPathIdentityProvider().capture(project, project);

        assertEquals(Optional.empty(), identity, "the provider does not apply here and must say so, not guess");
    }
}

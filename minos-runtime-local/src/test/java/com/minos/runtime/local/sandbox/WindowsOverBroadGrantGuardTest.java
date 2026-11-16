package com.minos.runtime.local.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two predicates that decide whether a toolchain root may be granted on Windows answer from an injected
 * environment reader, so the decision runs on every platform without touching the JVM's environment. An
 * unreadable broad location refuses the grant (fail closed) and its value never reaches a message.
 */
class WindowsOverBroadGrantGuardTest {

    /** A character no platform accepts in a path, so {@code Path.of} refuses the value everywhere. */
    private static final String NOT_A_PATH = "bad\0value-with-a-secret-looking-spelling";

    private static final Path TOOLCHAIN = Path.of("opt", "jdk-test", "home").toAbsolutePath().normalize();

    private static UnaryOperator<String> environment(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values);
        return copy::get;
    }

    private static Path outsideToolchain(String leaf) {
        return Path.of("elsewhere", leaf).toAbsolutePath().normalize();
    }

    @Test
    void anUnparsableProfileLocationMakesTheGrantTooBroad() {
        assertThrows(InvalidPathException.class, () -> Path.of(NOT_A_PATH), "the fixture must not be a path");

        boolean tooBroad = WindowsAppContainerWorkerSandboxBackend.isOverBroadGrant(
                TOOLCHAIN, environment(Map.of("USERPROFILE", NOT_A_PATH)));

        assertTrue(tooBroad);
    }

    @Test
    void anUnparsableLocationRefusesEvenWhenOtherLocationsAreFine() {
        boolean tooBroad = WindowsAppContainerWorkerSandboxBackend.isOverBroadGrant(
                TOOLCHAIN,
                environment(Map.of("USERPROFILE", outsideToolchain("profile").toString(), "ProgramData", NOT_A_PATH)));

        assertTrue(tooBroad);
    }

    @Test
    void anUnparsableSystemRootMakesTheRootNotGrantableWithoutThrowing() {
        boolean systemRoot = assertDoesNotThrow(() -> WindowsAppContainerWorkerSandboxBackend.isWindowsSystemRoot(
                TOOLCHAIN, environment(Map.of("SystemRoot", NOT_A_PATH))));

        assertTrue(systemRoot);
    }

    @Test
    void parsableLocationsThatDoNotContainTheRootDoNotMakeItTooBroad() {
        UnaryOperator<String> environment = environment(Map.of(
                "USERPROFILE", outsideToolchain("profile").toString(),
                "APPDATA", outsideToolchain("appdata").toString(),
                "SystemDrive", outsideToolchain("drive").toString(),
                "SystemRoot", outsideToolchain("windows").toString()));

        assertFalse(WindowsAppContainerWorkerSandboxBackend.isOverBroadGrant(TOOLCHAIN, environment));
        assertFalse(WindowsAppContainerWorkerSandboxBackend.isWindowsSystemRoot(TOOLCHAIN, environment));
    }

    @Test
    void aParsableLocationInsideTheRootStillMakesItTooBroad() {
        UnaryOperator<String> environment = environment(Map.of(
                "USERPROFILE", TOOLCHAIN.resolve("profile").toString()));

        assertTrue(WindowsAppContainerWorkerSandboxBackend.isOverBroadGrant(TOOLCHAIN, environment));
    }

    @Test
    void aSystemRootContainingTheRootIsRefused() {
        assertTrue(WindowsAppContainerWorkerSandboxBackend.isWindowsSystemRoot(
                TOOLCHAIN, environment(Map.of("SystemRoot", TOOLCHAIN.getParent().toString()))));
    }

    @Test
    void anAbsentOrBlankLocationConstrainsNothingAndTheOthersStayChecked() {
        Map<String, String> values = new HashMap<>();
        values.put("USERPROFILE", "   ");
        values.put("HOME", null);
        values.put("PUBLIC", TOOLCHAIN.resolve("public").toString());

        assertTrue(WindowsAppContainerWorkerSandboxBackend.isOverBroadGrant(TOOLCHAIN, environment(values)));

        values.remove("PUBLIC");
        assertFalse(WindowsAppContainerWorkerSandboxBackend.isOverBroadGrant(TOOLCHAIN, environment(values)));
        assertFalse(WindowsAppContainerWorkerSandboxBackend.isWindowsSystemRoot(TOOLCHAIN, environment(Map.of())));
    }

    @Test
    void aShallowRootIsAlwaysTooBroad() {
        Path shallow = Path.of("opt").toAbsolutePath().getRoot();
        assertTrue(WindowsAppContainerWorkerSandboxBackend.isOverBroadGrant(shallow, environment(Map.of())));
    }
}

package com.minos.domain.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host/container project-root mapping: which runtime is current, how a relative project root resolves in it,
 * and which paths are refused. Pure string and path rules: they hold the same on Windows and Linux.
 */
class ProjectPathMappingTest {

    private static final ProjectPathMapping WINDOWS_HOST = new ProjectPathMapping("C:\\Users\\me\\code", "/workspace");

    @TempDir
    Path temporary;

    @Test
    void theRuntimeLocationIsTheSystemPropertyThenTheEnvironmentThenNative() {
        Properties docker = new Properties();
        docker.setProperty(ProjectPathMapping.RUNTIME_LOCATION_PROPERTY, "Docker");

        assertEquals(ProjectPathMapping.RuntimeLocation.DOCKER,
                ProjectPathMapping.resolveRuntimeLocation(Map.of(ProjectPathMapping.RUNTIME_LOCATION_ENV, "native"), docker));
        assertEquals(ProjectPathMapping.RuntimeLocation.DOCKER,
                ProjectPathMapping.resolveRuntimeLocation(Map.of(ProjectPathMapping.RUNTIME_LOCATION_ENV, " docker "),
                        new Properties()));
        assertEquals(ProjectPathMapping.RuntimeLocation.NATIVE,
                ProjectPathMapping.resolveRuntimeLocation(Map.of(), new Properties()));
    }

    @Test
    void anUnknownRuntimeLocationIsRefused() {
        Map<String, String> environment = Map.of(ProjectPathMapping.RUNTIME_LOCATION_ENV, "cloud");
        Properties properties = new Properties();
        assertThrows(IllegalArgumentException.class,
                () -> ProjectPathMapping.resolveRuntimeLocation(environment, properties));
    }

    @Test
    void rootsMustBeAbsoluteWindowsOrPosixPaths() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectPathMapping(" ", "/workspace"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectPathMapping("code", "/workspace"));
        assertEquals("C:/Users/me/code", WINDOWS_HOST.hostRoot());
        assertEquals("/workspace", new ProjectPathMapping("/srv/", "/workspace//").containerRoot());
    }

    @Test
    void aWindowsHostPathTranslatesToTheContainerWithoutRegardToCase() {
        assertEquals("/workspace/app/src", WINDOWS_HOST.hostToContainer("c:\\users\\ME\\code\\app\\src"));
        assertEquals("C:/Users/me/code/app", WINDOWS_HOST.containerToHost("/workspace/app"));
        assertEquals("/workspace", WINDOWS_HOST.hostToContainer("C:\\Users\\me\\code\\"));
    }

    @Test
    void aPathOutsideTheRootIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> WINDOWS_HOST.hostToContainer("D:\\other"));
        assertThrows(IllegalArgumentException.class, () -> WINDOWS_HOST.containerToHost("/workspaces/app"));
    }

    @Test
    void aLegacyPathResolvesAgainstEitherRootOrIsRefusedWithBothFailures() {
        assertEquals("app", WINDOWS_HOST.relativeForLegacyPath("C:\\Users\\me\\code\\app"));
        assertEquals("app/lib", WINDOWS_HOST.relativeForLegacyPath("/workspace/app/lib"));

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> WINDOWS_HOST.relativeForLegacyPath("/elsewhere/app"));
        assertTrue(refusal.getMessage().startsWith("legacy project path is outside both configured roots"));
        assertEquals(1, refusal.getCause().getSuppressed().length, "the host failure is kept");
    }

    @Test
    void aPhysicalPathIsMadeRelativeToTheRootOfTheCurrentRuntime() {
        ProjectPathMapping mapping = new ProjectPathMapping(temporary.toAbsolutePath().toString(), "/workspace");

        assertEquals("project/module",
                mapping.relativeForPhysical(temporary.resolve("project").resolve("module"),
                        ProjectPathMapping.RuntimeLocation.NATIVE));
        assertEquals(".", mapping.relativeForPhysical(temporary, ProjectPathMapping.RuntimeLocation.NATIVE));
    }

    @Test
    void aRelativeProjectRootResolvesUnderTheRootOfTheRuntime() {
        ProjectPathMapping mapping = new ProjectPathMapping(temporary.toAbsolutePath().toString(), "/workspace");

        assertEquals(temporary.resolve("a").resolve("b").toAbsolutePath().normalize(),
                mapping.resolve("a/b", ProjectPathMapping.RuntimeLocation.NATIVE));
        assertEquals(Path.of("/workspace").toAbsolutePath().normalize(),
                mapping.resolve(".", ProjectPathMapping.RuntimeLocation.DOCKER));
    }

    @Test
    void anUnsafeOrAbsoluteRelativeRootIsRefused() {
        for (String unsafe : new String[]{"../escape", "a/../b", "a/./b", "/absolute", "C:\\absolute", " "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WINDOWS_HOST.resolve(unsafe, ProjectPathMapping.RuntimeLocation.DOCKER), unsafe);
        }
    }
}

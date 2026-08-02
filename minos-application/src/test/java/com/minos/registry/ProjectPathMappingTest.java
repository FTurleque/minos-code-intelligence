package com.minos.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPathMappingTest {

    @Test
    void translatesWindowsHostAndLinuxContainerPathsWithoutStringReplaceAtCallSites() {
        ProjectPathMapping mapping = new ProjectPathMapping(
                "N:\\workspace-dev",
                "/workspace/projects");

        assertEquals(
                "/workspace/projects/minos-code-intelligence",
                mapping.hostToContainer("N:\\workspace-dev\\minos-code-intelligence"));
        assertEquals(
                "N:/workspace-dev/minos-code-intelligence",
                mapping.containerToHost("/workspace/projects/minos-code-intelligence"));
        assertThrows(
                IllegalArgumentException.class,
                () -> mapping.hostToContainer("C:\\outside\\project"));
    }

    @Test
    void samePortableRegistryResolvesSameProjectIdInNativeAndDocker(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path hostRoot = Files.createDirectories(temp.resolve("host-projects"));
        Path dockerRoot = Files.createDirectories(temp.resolve("docker-projects"));
        Path hostProject = Files.createDirectories(hostRoot.resolve("demo"));
        Path dockerProject = Files.createDirectories(dockerRoot.resolve("demo"));
        ProjectPathMapping mapping = new ProjectPathMapping(hostRoot.toString(), dockerRoot.toString());
        new ProjectPathMappingStore(home).save(mapping);

        LocalProjectRegistry nativeRegistry = new LocalProjectRegistry(
                home.resolve("registry"), mapping, ProjectPathMapping.RuntimeLocation.NATIVE);
        RegisteredProject nativeProject = nativeRegistry.registerProject(hostProject, "Demo");

        Path record = home.resolve("registry/projects/" + nativeProject.id() + ".properties");
        String persisted = Files.readString(record);
        assertTrue(persisted.contains("rootRelativePath=demo"));
        assertFalse(persisted.contains(hostRoot.toString()));

        LocalProjectRegistry dockerRegistry = new LocalProjectRegistry(
                home.resolve("registry"), mapping, ProjectPathMapping.RuntimeLocation.DOCKER);
        RegisteredProject resolvedInDocker = dockerRegistry.findProject(nativeProject.id()).orElseThrow();
        RegisteredProject registeredAgain = dockerRegistry.registerProject(dockerProject, "Different name");

        assertEquals(nativeProject.id(), resolvedInDocker.id());
        assertEquals(nativeProject.id(), registeredAgain.id());
        assertEquals(dockerProject.toRealPath(), resolvedInDocker.rootPath());
        assertEquals("Demo", registeredAgain.displayName());
    }

    @Test
    void legacyAbsoluteRegistryMigratesIdempotentlyWithRollbackBackup(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path hostRoot = Files.createDirectories(temp.resolve("host-projects"));
        Path dockerRoot = Files.createDirectories(temp.resolve("docker-projects"));
        Path projectRoot = Files.createDirectories(hostRoot.resolve("legacy"));

        LocalProjectRegistry legacy = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject original = legacy.registerProject(projectRoot, "Legacy");
        Path record = home.resolve("registry/projects/" + original.id() + ".properties");
        assertTrue(Files.readString(record).contains("rootPath="));

        ProjectPathMapping mapping = new ProjectPathMapping(hostRoot.toString(), dockerRoot.toString());
        new ProjectPathMappingStore(home).save(mapping);
        LocalProjectRegistry migrated = new LocalProjectRegistry(
                home.resolve("registry"), mapping, ProjectPathMapping.RuntimeLocation.NATIVE);

        RegisteredProject firstRead = migrated.findProject(original.id()).orElseThrow();
        String migratedContent = Files.readString(record);
        Path backup = record.resolveSibling(record.getFileName() + ".m29-v1.bak");
        assertEquals(original.id(), firstRead.id());
        assertTrue(migratedContent.contains("rootRelativePath=legacy"));
        assertFalse(migratedContent.contains("rootPath="));
        assertTrue(Files.isRegularFile(backup));
        assertTrue(Files.readString(backup).contains("rootPath="));

        RegisteredProject secondRead = migrated.findProject(original.id()).orElseThrow();
        assertEquals(firstRead, secondRead);
        assertEquals(migratedContent, Files.readString(record));
    }

    @Test
    void projectOutsideConfiguredRootFailsClosed(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path hostRoot = Files.createDirectories(temp.resolve("host-projects"));
        Path dockerRoot = Files.createDirectories(temp.resolve("docker-projects"));
        Path outside = Files.createDirectories(temp.resolve("outside/project"));
        ProjectPathMapping mapping = new ProjectPathMapping(hostRoot.toString(), dockerRoot.toString());
        LocalProjectRegistry registry = new LocalProjectRegistry(
                home.resolve("registry"), mapping, ProjectPathMapping.RuntimeLocation.NATIVE);

        IOException failure = assertThrows(IOException.class, () -> registry.registerProject(outside, "Outside"));
        assertTrue(failure.getMessage().contains("cannot be represented by configured runtime mapping"));
    }
}

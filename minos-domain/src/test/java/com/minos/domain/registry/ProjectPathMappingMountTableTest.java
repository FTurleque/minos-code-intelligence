package com.minos.domain.registry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mount table form of {@link ProjectPathMapping}: one read-only mount per exposed project under the container
 * root, no host root. Host resolution goes through the table; names, overlaps and the mount cap are refused.
 */
class ProjectPathMappingMountTableTest {

    private static final String CONTAINER = "/workspace/projects";

    private static ProjectPathMapping table(ProjectMount... mounts) {
        return ProjectPathMapping.mounted(CONTAINER, List.of(mounts));
    }

    @Test
    void anEmptyTableIsValidAndExposesNothing() {
        ProjectPathMapping empty = table();

        assertTrue(empty.mountTable());
        assertTrue(empty.mounts().isEmpty());
        assertEquals(Optional.empty(), empty.hostPathFor("app"));
        assertEquals(Optional.empty(), empty.relativeForHostPath("D:/src/app"));
    }

    @Test
    void aSingleRootMappingIsNotATable() {
        ProjectPathMapping single = ProjectPathMapping.singleRoot("N:/workspace-dev", CONTAINER);

        assertFalse(single.mountTable());
        assertEquals(Optional.of("N:/workspace-dev/a/b"), single.hostPathFor("a/b"));
    }

    @Test
    void theMountThatPrefixesARelativeRootGivesItsHostFolder() {
        ProjectPathMapping mapping = table(
                new ProjectMount("app", "D:\\src\\app"),
                new ProjectMount("team/b", "N:/workspace-dev/team/b"));

        assertEquals(Optional.of("D:/src/app"), mapping.hostPathFor("app"));
        assertEquals(Optional.of("D:/src/app/module/core"), mapping.hostPathFor("app/module/core"));
        assertEquals(Optional.of("N:/workspace-dev/team/b"), mapping.hostPathFor("team/b"));
        assertEquals(Optional.empty(), mapping.hostPathFor("team"));
        assertEquals(Optional.empty(), mapping.hostPathFor("application"));
        assertEquals(Optional.empty(), mapping.hostPathFor("."));
        assertEquals("D:/src/app/x", mapping.hostPathOf("app/x"));
        assertThrows(IllegalArgumentException.class, () -> mapping.hostPathOf("other"));
    }

    @Test
    void aHostFolderMapsBackToItsMountPathIgnoringWindowsCase() {
        ProjectPathMapping mapping = table(new ProjectMount("app", "D:/Src/App"));

        assertEquals(Optional.of("app"), mapping.relativeForHostPath("d:\\src\\app"));
        assertEquals(Optional.of("app/Sub"), mapping.relativeForHostPath("D:/SRC/APP/Sub"));
        assertEquals(Optional.empty(), mapping.relativeForHostPath("D:/Src/Apple"));
        assertEquals("app/Sub", mapping.relativeForLegacyPath("D:/Src/App/Sub"));
        assertEquals("app/x", mapping.relativeForLegacyPath("/workspace/projects/app/x"));
    }

    @Test
    void aContainerPathResolvesUnderTheContainerRootAsBefore() {
        ProjectPathMapping mapping = table(new ProjectMount("app", "D:/src/app"));

        assertEquals("/workspace/projects/app", mapping.resolve("app", ProjectPathMapping.RuntimeLocation.DOCKER)
                .toString().replace('\\', '/').replaceFirst("^[A-Za-z]:", ""));
    }

    @Test
    void mountNamesFollowTheSafeAlphabet() {
        assertTrue(ProjectPathMapping.isValidMountName("app"));
        assertTrue(ProjectPathMapping.isValidMountName("my-app_2.x"));
        assertTrue(ProjectPathMapping.isValidMountName("a".repeat(63)));
        assertFalse(ProjectPathMapping.isValidMountName("a".repeat(64)));
        assertFalse(ProjectPathMapping.isValidMountName("App"));
        assertFalse(ProjectPathMapping.isValidMountName("-app"));
        assertFalse(ProjectPathMapping.isValidMountName("."));
        assertFalse(ProjectPathMapping.isValidMountName(".."));
        assertFalse(ProjectPathMapping.isValidMountName("a/b"));
        assertFalse(ProjectPathMapping.isValidMountName("a b"));
        assertFalse(ProjectPathMapping.isValidMountName(""));
        assertFalse(ProjectPathMapping.isValidMountName(null));
    }

    @Test
    void theDefaultNameIsDerivedFromTheFolderName() {
        assertEquals(Optional.of("my-app"), ProjectPathMapping.defaultMountName("D:/src/My App"));
        assertEquals(Optional.of("projet-l--t--"), ProjectPathMapping.defaultMountName("D:\\src\\Projet l'été!"));
        assertEquals(Optional.empty(), ProjectPathMapping.defaultMountName("D:/src/..."));
    }

    @Test
    void withMountAddsANewNameAndRefusesInvalidOnes() {
        ProjectPathMapping mapping = table().withMount("app", "D:/src/app");

        assertEquals(List.of(new ProjectMount("app", "D:/src/app")), mapping.mounts());
        assertThrows(IllegalArgumentException.class, () -> mapping.withMount("App", "D:/other"));
        assertThrows(IllegalArgumentException.class, () -> mapping.withMount("Other", "D:/other"));
        assertThrows(IllegalArgumentException.class, () -> mapping.withMount("other", "relative/folder"));
        assertThrows(IllegalStateException.class,
                () -> ProjectPathMapping.singleRoot("N:/root", CONTAINER).withMount("app", "D:/src/app"));
    }

    @Test
    void overlappingMountPathsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> table(
                new ProjectMount("team", "D:/a"),
                new ProjectMount("team/b", "D:/b")));
        assertThrows(IllegalArgumentException.class, () -> table(
                new ProjectMount("app", "D:/a"),
                new ProjectMount("APP", "D:/b")));
    }

    @Test
    void aHostFolderEqualToOrContainingAnotherIsRefused() {
        ProjectPathMapping mapping = table(new ProjectMount("app", "D:/src/app"));

        assertThrows(IllegalArgumentException.class, () -> mapping.withMount("sub", "D:/src/app/sub"));
        assertThrows(IllegalArgumentException.class, () -> mapping.withMount("src", "D:/src"));
        assertThrows(IllegalArgumentException.class, () -> mapping.withMount("again", "d:\\SRC\\APP"));
        assertEquals(2, mapping.withMount("apple", "D:/src/apple").mounts().size());
    }

    @Test
    void theTableIsCappedAtSixtyFourMounts() {
        List<ProjectMount> mounts = new ArrayList<>();
        for (int index = 0; index < ProjectPathMapping.MAX_MOUNTS; index++) {
            mounts.add(new ProjectMount("p" + index, "D:/src/p" + index));
        }
        ProjectPathMapping full = ProjectPathMapping.mounted(CONTAINER, mounts);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> full.withMount("one-more", "D:/src/one-more"));
        assertTrue(refusal.getMessage().contains("64"), refusal.getMessage());
    }

    @Test
    void withoutMountRemovesAKnownNameAndRefusesAnUnknownOne() {
        ProjectPathMapping mapping = table(new ProjectMount("app", "D:/src/app"), new ProjectMount("lib", "D:/src/lib"));

        assertEquals(List.of(new ProjectMount("lib", "D:/src/lib")), mapping.withoutMount("app").mounts());
        assertThrows(IllegalArgumentException.class, () -> mapping.withoutMount("absent"));
    }

    @Test
    void aMountPathIsASafeRelativePath() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount("../escape", "D:/a"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount(".", "D:/a"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount("/abs", "D:/a"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount("app", "relative"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount("app", "D:/src/${A:-..}/x"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount("a$b", "D:/src/a"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount("app", "D:/src/a/../b"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectMount("app", "D:/src/a\u0007b"));
        assertEquals("team/b", new ProjectMount("team\\b", "D:/b").path());
    }
}

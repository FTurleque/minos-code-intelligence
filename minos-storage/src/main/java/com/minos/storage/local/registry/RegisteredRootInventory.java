package com.minos.storage.local.registry;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings.RegisteredRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Lists the local registry entries by their root relative to the container root of a single root mapping, reading
 * each entry as it is on disk: nothing is migrated or written. Run under the registry lease
 * ({@link InterProcessLocalProjectRegistry#registeredRoots}).
 */
final class RegisteredRootInventory {

    private final Path projectsDirectory;

    RegisteredRootInventory(Path projectsDirectory) {
        this.projectsDirectory = Objects.requireNonNull(projectsDirectory, "projectsDirectory");
    }

    /** Every entry, in file name order; refused as a whole when one entry cannot be read or placed. */
    List<RegisteredRoot> list(ProjectPathMapping existing) throws IOException {
        Objects.requireNonNull(existing, "existing");
        List<RegisteredRoot> roots = new ArrayList<>();
        int unreadable = 0;
        for (Path file : LocalProjectRegistry.propertyEntries(projectsDirectory)) {
            Optional<RegisteredRoot> root = read(file, existing);
            if (root.isPresent()) roots.add(root.orElseThrow());
            else unreadable++;
        }
        if (unreadable > 0) {
            throw new IOException("project mount migration refused: " + unreadable
                    + (unreadable == 1 ? " registry entry is" : " registry entries are")
                    + " unreadable; list them with `project list`, repair or remove them, then run the migration again");
        }
        return roots;
    }

    private static Optional<RegisteredRoot> read(Path file, ProjectPathMapping existing) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            Properties properties = LocalProjectRegistry.readProperties(file);
            String detached = trimmed(properties, LocalProjectRegistry.DETACHED_HOST_ROOT);
            String relative = trimmed(properties, LocalProjectRegistry.PORTABLE_ROOT_PATH);
            String legacy = trimmed(properties, LocalProjectRegistry.LEGACY_ROOT_PATH);
            String displayName = trimmed(properties, "displayName");
            int roots = (detached.isEmpty() ? 0 : 1) + (relative.isEmpty() ? 0 : 1) + (legacy.isEmpty() ? 0 : 1);
            if (trimmed(properties, "id").isEmpty() || displayName.isEmpty() || roots != 1) return Optional.empty();
            if (!detached.isEmpty()) return Optional.of(new RegisteredRoot(displayName, Optional.empty()));
            String portable = relative.isEmpty() ? existing.relativeForLegacyPath(legacy) : relative;
            // Validates the relative root the same way a read of the registry resolves it.
            existing.hostPathOf(portable);
            return Optional.of(new RegisteredRoot(displayName, Optional.of(portable)));
        } catch (IOException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static String trimmed(Properties properties, String key) {
        return properties.getProperty(key, "").trim();
    }
}

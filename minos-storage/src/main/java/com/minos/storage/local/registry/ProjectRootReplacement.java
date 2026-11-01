package com.minos.storage.local.registry;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings.Replacement;
import com.minos.storage.home.ProjectPathMappingStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Replaces the project path mapping of a MINOS home and re-expresses every local registry entry against it. Run
 * under the exclusive registry lease ({@link InterProcessLocalProjectRegistry#replaceRoot}).
 *
 * <p>Order, so that an interruption never re-points a project to a folder of the new root: every entry is read and
 * planned first (an unreadable entry refuses the whole replacement, nothing written); each entry that changes is saved
 * beside itself, then rewritten in the detached form, which does not depend on any mapping; the new mapping is saved;
 * last, each detached entry that the new host root contains is reattached. Running the replacement again with the same
 * mapping resumes the last step.</p>
 */
final class ProjectRootReplacement {

    private static final String BACKUP_SUFFIX = ".root-replacement.bak";

    private final Path projectsDirectory;
    private final ProjectPathMappingStore store;

    ProjectRootReplacement(Path projectsDirectory, ProjectPathMappingStore store) {
        this.projectsDirectory = Objects.requireNonNull(projectsDirectory, "projectsDirectory");
        this.store = Objects.requireNonNull(store, "store");
    }

    Replacement replace(ProjectPathMapping desired) throws IOException {
        Objects.requireNonNull(desired, "desired");
        Optional<ProjectPathMapping> saved = store.loadOptional();
        if (saved.isEmpty()) {
            // Without a mapping, a portable entry cannot exist: there is nothing to re-express.
            store.save(desired);
            return new Replacement(0, 0, 0);
        }
        ProjectPathMapping existing = saved.orElseThrow();
        List<Planned> plan = plan(existing);
        String backupStamp = "." + Instant.now().toEpochMilli() + BACKUP_SUFFIX;

        boolean unchanged = existing.equals(desired);
        if (!unchanged) {
            for (Planned entry : plan) {
                if (entry.detached()) continue;
                LocalProjectRegistry.backupOnce(entry.file(), backupOf(entry.file(), backupStamp));
                LocalProjectRegistry.writePropertiesAtomically(entry.file(), detachedForm(entry));
            }
            store.save(desired);
        }

        int rebased = 0;
        int reattached = 0;
        int detached = 0;
        for (Planned entry : plan) {
            // An unchanged mapping leaves every attached entry as it is: only detached entries are reattached.
            if (unchanged && !entry.detached()) continue;
            Optional<String> relative = desired.relativeForHostPath(entry.hostRoot());
            if (relative.isEmpty()) {
                detached++;
                continue;
            }
            if (entry.detached()) {
                LocalProjectRegistry.backupOnce(entry.file(), backupOf(entry.file(), backupStamp));
                reattached++;
            } else {
                rebased++;
            }
            LocalProjectRegistry.writePropertiesAtomically(entry.file(), portableForm(entry, relative.orElseThrow()));
        }
        return new Replacement(rebased, reattached, detached);
    }

    private List<Planned> plan(ProjectPathMapping existing) throws IOException {
        List<Planned> plan = new ArrayList<>();
        int unreadable = 0;
        for (Path file : LocalProjectRegistry.propertyEntries(projectsDirectory)) {
            Optional<Planned> planned = planned(file, existing);
            if (planned.isPresent()) plan.add(planned.orElseThrow());
            else unreadable++;
        }
        if (unreadable > 0) {
            throw new IOException("project root replacement refused: " + unreadable
                    + (unreadable == 1 ? " registry entry is" : " registry entries are")
                    + " unreadable; list them with `project list`, repair or remove them, then run the replacement again");
        }
        return plan;
    }

    /** The entry and its absolute host root, or empty when the entry cannot be read or placed. */
    private static Optional<Planned> planned(Path file, ProjectPathMapping existing) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            Properties properties = LocalProjectRegistry.readProperties(file);
            String detached = trimmed(properties, LocalProjectRegistry.DETACHED_HOST_ROOT);
            String relative = trimmed(properties, LocalProjectRegistry.PORTABLE_ROOT_PATH);
            String legacy = trimmed(properties, LocalProjectRegistry.LEGACY_ROOT_PATH);
            if (trimmed(properties, "id").isEmpty() || roots(detached, relative, legacy) != 1) return Optional.empty();
            if (!detached.isEmpty()) return Optional.of(new Planned(file, properties, detached, true));
            String hostRoot = relative.isEmpty()
                    ? existing.hostPathOf(existing.relativeForLegacyPath(legacy))
                    : existing.hostPathOf(relative);
            return Optional.of(new Planned(file, properties, hostRoot, false));
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    private static int roots(String... values) {
        int count = 0;
        for (String value : values) if (!value.isEmpty()) count++;
        return count;
    }

    private static Properties detachedForm(Planned entry) {
        Properties properties = withoutRoots(entry.properties());
        properties.setProperty(LocalProjectRegistry.DETACHED_HOST_ROOT, entry.hostRoot());
        return properties;
    }

    private static Properties portableForm(Planned entry, String relative) {
        Properties properties = withoutRoots(entry.properties());
        properties.setProperty(LocalProjectRegistry.PORTABLE_ROOT_PATH, relative);
        return properties;
    }

    private static Properties withoutRoots(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        copy.remove(LocalProjectRegistry.DETACHED_HOST_ROOT);
        copy.remove(LocalProjectRegistry.PORTABLE_ROOT_PATH);
        copy.remove(LocalProjectRegistry.LEGACY_ROOT_PATH);
        return copy;
    }

    private static Path backupOf(Path file, String stamp) {
        return file.resolveSibling(file.getFileName() + stamp);
    }

    private static String trimmed(Properties properties, String key) {
        return properties.getProperty(key, "").trim();
    }

    private record Planned(Path file, Properties properties, String hostRoot, boolean detached) {
    }
}

package com.minos.storage.home;

import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;
import com.minos.domain.registry.ProjectMount;
import com.minos.domain.registry.ProjectPathMapping;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * Versioned runtime-only persistence for host/container physical project roots. Format 1 holds one host root mapped
 * onto the container root; format 2 holds the container root and a bounded table of project mounts
 * ({@code mount.<i>.path}, {@code mount.<i>.hostRoot}, indexes contiguous from 0). Both are read; each mapping is
 * written in its own format.
 */
public final class ProjectPathMappingStore {

    public static final int SINGLE_ROOT_FORMAT_VERSION = 1;
    public static final int MOUNT_TABLE_FORMAT_VERSION = 2;
    /** The format a new Docker runtime writes. */
    public static final int CURRENT_FORMAT_VERSION = MOUNT_TABLE_FORMAT_VERSION;
    public static final String RUNTIME_DIRECTORY = "runtime";
    public static final String FILE_NAME = "project-paths.properties";

    private static final long MAX_MAPPING_BYTES = 64L * 1024L;
    private static final Set<String> SINGLE_ROOT_KEYS = Set.of("formatVersion", "hostRoot", "containerRoot");
    private static final Set<String> TABLE_KEYS = Set.of("formatVersion", "containerRoot");
    private static final Pattern MOUNT_KEY = Pattern.compile("mount\\.(0|[1-9][0-9]?)\\.(path|hostRoot)");
    private static final int MAX_ENTRIES = TABLE_KEYS.size() + 2 * ProjectPathMapping.MAX_MOUNTS;

    private final Path home;
    private final Path runtimeDirectory;
    private final Path file;

    public ProjectPathMappingStore(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.runtimeDirectory = home.resolve(RUNTIME_DIRECTORY);
        this.file = runtimeDirectory.resolve(FILE_NAME);
    }

    public Optional<ProjectPathMapping> loadOptional() throws IOException {
        Optional<Properties> loaded = loadProperties();
        if (loaded.isEmpty()) return Optional.empty();
        Properties properties = loaded.orElseThrow();
        try {
            int version = formatVersion(properties);
            return Optional.of(version == SINGLE_ROOT_FORMAT_VERSION ? singleRoot(properties) : table(properties));
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid project path mapping: " + exception.getMessage(), exception);
        }
    }

    /** The format version of the saved mapping, read without interpreting the rest; empty when none is saved. */
    public Optional<Integer> loadFormatVersion() throws IOException {
        Optional<Properties> loaded = loadProperties();
        if (loaded.isEmpty()) return Optional.empty();
        try {
            return Optional.of(formatVersion(loaded.orElseThrow()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid project path mapping: " + exception.getMessage(), exception);
        }
    }

    public void save(ProjectPathMapping mapping) throws IOException {
        Objects.requireNonNull(mapping, "mapping");
        validateExistingContainer(home, "MINOS home");
        validateExistingContainer(runtimeDirectory, "project path mapping directory");
        DurableAtomicFile.ensureDirectory(runtimeDirectory, "project path mapping directory");
        Properties properties = new Properties();
        int version;
        if (mapping.mountTable()) {
            version = MOUNT_TABLE_FORMAT_VERSION;
            properties.setProperty("containerRoot", mapping.containerRoot());
            List<ProjectMount> mounts = mapping.mounts();
            for (int index = 0; index < mounts.size(); index++) {
                properties.setProperty("mount." + index + ".path", mounts.get(index).path());
                properties.setProperty("mount." + index + ".hostRoot", mounts.get(index).hostRoot());
            }
        } else {
            version = SINGLE_ROOT_FORMAT_VERSION;
            properties.setProperty("hostRoot", mapping.hostRoot());
            properties.setProperty("containerRoot", mapping.containerRoot());
        }
        properties.setProperty("formatVersion", Integer.toString(version));
        Path temporary = PrivateLocalStorage.createPrivateTempFile(runtimeDirectory, FILE_NAME + ".", ".tmp");
        try {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            try (Writer writer = new OutputStreamWriter(content, StandardCharsets.UTF_8)) {
                properties.store(writer, "MINOS project path mapping v" + version);
            }
            PrivateLocalStorage.writePrivateFile(temporary, content.toByteArray());
            DurableAtomicFile.replace(temporary, file, "project path mapping replacement");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path file() {
        return file;
    }

    private Optional<Properties> loadProperties() throws IOException {
        validateExistingContainer(home, "MINOS home");
        validateExistingContainer(runtimeDirectory, "project path mapping directory");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("project path mapping is not a regular non-symlink file: " + leafName(file));
        }
        Properties properties = BoundedProperties.load(
                file,
                MAX_MAPPING_BYTES,
                MAX_ENTRIES,
                128,
                16 * 1024,
                "project path mapping"
        );
        return Optional.of(properties);
    }

    private static int formatVersion(Properties properties) {
        String raw = required(properties, "formatVersion");
        final int version;
        try {
            version = Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("unsupported project path mapping format: " + raw, exception);
        }
        if (version != SINGLE_ROOT_FORMAT_VERSION && version != MOUNT_TABLE_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported project path mapping format: " + version);
        }
        return version;
    }

    private static ProjectPathMapping singleRoot(Properties properties) throws IOException {
        for (Object rawKey : properties.keySet()) {
            String key = String.valueOf(rawKey);
            if (!SINGLE_ROOT_KEYS.contains(key)) throw unknownKey(key);
        }
        return ProjectPathMapping.singleRoot(required(properties, "hostRoot"), required(properties, "containerRoot"));
    }

    private static ProjectPathMapping table(Properties properties) throws IOException {
        int highest = -1;
        int mountKeys = 0;
        for (Object rawKey : properties.keySet()) {
            String key = String.valueOf(rawKey);
            if (TABLE_KEYS.contains(key)) continue;
            Matcher matcher = MOUNT_KEY.matcher(key);
            if (!matcher.matches()) throw unknownKey(key);
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
            mountKeys++;
        }
        int count = highest + 1;
        if (count > ProjectPathMapping.MAX_MOUNTS || mountKeys != 2 * count) {
            throw new IllegalArgumentException("project mount indexes are not contiguous from 0, or a mount is incomplete");
        }
        List<ProjectMount> mounts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            mounts.add(new ProjectMount(
                    required(properties, "mount." + index + ".path"),
                    required(properties, "mount." + index + ".hostRoot")));
        }
        return ProjectPathMapping.mounted(required(properties, "containerRoot"), mounts);
    }

    private static IOException unknownKey(String key) {
        return new IOException("unknown project path mapping property: " + key);
    }

    private static void validateExistingContainer(Path directory, String label) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a non-symlink directory: " + leafName(directory));
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing property " + key);
        }
        return value.trim();
    }
}

package com.minos.storage.home;

import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;
import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IndexImportHistory;
import com.minos.orchestration.IndexImportHistoryStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * The import history kept as one {@code .properties} file per project under {@code MINOS_HOME/cli-index-history},
 * the same for every storage backend. The directory keeps its historical name so that a history written by an
 * earlier version is read unchanged.
 */
public final class FileIndexImportHistoryStore implements IndexImportHistoryStore {

    public static final String DIRECTORY = "cli-index-history";

    static final long MAX_HISTORY_BYTES = 64L * 1024L;
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 8192;
    private static final String SNAPSHOT_ID = "snapshotId";
    private static final String PROVIDER_ID = "providerId";
    private static final String PROVIDER_VERSION = "providerVersion";
    private static final String COMPLETED_AT = "completedAt";

    private final Path historyDirectory;

    public FileIndexImportHistoryStore(Path minosHome) {
        this.historyDirectory = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize()
                .resolve(DIRECTORY);
    }

    @Override
    public Optional<IndexImportHistory> read(UUID projectId) throws IOException {
        Path file = file(projectId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = BoundedProperties.load(file, MAX_HISTORY_BYTES, MAX_ENTRIES, MAX_KEY_LENGTH,
                MAX_VALUE_LENGTH, "CLI index history metadata");
        return Optional.of(new IndexImportHistory(required(properties, SNAPSHOT_ID, file),
                required(properties, PROVIDER_ID, file), properties.getProperty(PROVIDER_VERSION),
                Instant.parse(required(properties, COMPLETED_AT, file))));
    }

    @Override
    public void write(UUID projectId, IndexImportHistory history) throws IOException {
        Objects.requireNonNull(history, "history");
        Properties properties = new Properties();
        properties.setProperty(SNAPSHOT_ID, history.snapshotId());
        properties.setProperty(PROVIDER_ID, history.providerId());
        properties.setProperty(PROVIDER_VERSION, history.providerVersion() == null ? "" : history.providerVersion());
        properties.setProperty(COMPLETED_AT, history.completedAt().toString());
        byte[] content;
        try (ByteArrayOutputStream rendered = new ByteArrayOutputStream()) {
            properties.store(rendered, "MINOS project index history");
            content = rendered.toByteArray();
        }
        DurableAtomicFile.ensureDirectory(historyDirectory, "CLI import history directory");
        Path target = file(projectId);
        Path temporary = PrivateLocalStorage.createPrivateTempFile(historyDirectory, projectId + ".", ".tmp");
        try {
            PrivateLocalStorage.writePrivateFile(temporary, content);
            DurableAtomicFile.replace(temporary, target, "CLI import history replacement");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(UUID projectId) {
        return historyDirectory.resolve(Objects.requireNonNull(projectId, "projectId") + ".properties");
    }

    private static String required(Properties properties, String key, Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing index property '" + key + "' in " + leafName(file));
        }
        return value;
    }
}

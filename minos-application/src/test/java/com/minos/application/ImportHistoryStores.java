package com.minos.application;

import com.minos.orchestration.IndexImportHistory;
import com.minos.orchestration.IndexImportHistoryStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Import history ports for the tests of this module, which do not see the file implementation. */
final class ImportHistoryStores {

    private ImportHistoryStores() {
    }

    /** A port that knows no history and refuses every write. */
    static IndexImportHistoryStore none() {
        return reading(Optional::empty);
    }

    /** A port that keeps the histories it is given, in memory. */
    static IndexImportHistoryStore inMemory() {
        Map<UUID, IndexImportHistory> histories = new ConcurrentHashMap<>();
        return new IndexImportHistoryStore() {
            @Override
            public Optional<IndexImportHistory> read(UUID projectId) {
                return Optional.ofNullable(histories.get(projectId));
            }

            @Override
            public void write(UUID projectId, IndexImportHistory history) {
                histories.put(projectId, history);
            }
        };
    }

    /** A port whose reading fails with the given refusal, as the file implementation does for a damaged file. */
    static IndexImportHistoryStore failing(RuntimeException refusal) {
        return reading(() -> {
            throw refusal;
        });
    }

    private static IndexImportHistoryStore reading(Supplier<Optional<IndexImportHistory>> read) {
        return new IndexImportHistoryStore() {
            @Override
            public Optional<IndexImportHistory> read(UUID projectId) {
                return read.get();
            }

            @Override
            public void write(UUID projectId, IndexImportHistory history) {
                throw new AssertionError("no history write expected");
            }
        };
    }
}

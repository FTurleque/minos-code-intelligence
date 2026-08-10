package com.minos.dynamic;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Provider-neutral persistence port for accepted runtime observation sessions. */
public interface RuntimeObservationStore {

    SaveResult save(CorrelatedRuntimeSession session) throws IOException;

    Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) throws IOException;

    List<CorrelatedRuntimeSession> list(UUID projectId) throws IOException;

    default List<CorrelatedRuntimeSession> list(UUID projectId, String snapshotId, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("runtime session limit must be positive");
        return list(projectId).stream()
                .filter(value -> snapshotId == null || snapshotId.equals(value.session().snapshotId()))
                .limit(limit)
                .toList();
    }

    record SaveResult(CorrelatedRuntimeSession session, boolean alreadyPresent) {
        public SaveResult {
            java.util.Objects.requireNonNull(session, "session");
        }
    }
}

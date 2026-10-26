package com.minos.domain.git;

import java.time.Instant;
import java.util.List;

/** One scanned commit; {@code authorEmail} holds a masked value unless the query asked for the real addresses. */
public record CommitActivity(
        String commitId,
        Instant committedAt,
        String authorName,
        String authorEmail,
        String message,
        List<String> changedPaths
) {
    public CommitActivity {
        changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
    }
}

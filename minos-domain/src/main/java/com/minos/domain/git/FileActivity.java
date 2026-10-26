package com.minos.domain.git;

import java.time.Instant;

/** Factual activity of one file over the scanned history. */
public record FileActivity(
        String path,
        int commitCount,
        int uniqueAuthorCount,
        Instant lastChangedAt,
        String lastCommitId
) {
}

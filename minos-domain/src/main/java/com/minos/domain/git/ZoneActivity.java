package com.minos.domain.git;

import java.time.Instant;

/** Factual activity of one directory zone over the scanned history. */
public record ZoneActivity(
        String zone,
        int commitTouches,
        int distinctFileCount,
        Instant lastChangedAt
) {
}

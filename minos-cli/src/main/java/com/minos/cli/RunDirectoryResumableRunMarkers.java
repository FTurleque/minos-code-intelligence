package com.minos.cli;

import com.minos.orchestration.ResumableRunMarkers;
import com.minos.runtime.FileResumableRunMarkers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapte le marqueur de répertoire de run du runtime local au port de l'orchestration (ADR 0039 §5).
 * Le runtime local ne dépend pas de l'application : ce câblage vit dans la racine de composition.
 */
final class RunDirectoryResumableRunMarkers implements ResumableRunMarkers {

    private final FileResumableRunMarkers markers;

    RunDirectoryResumableRunMarkers(Path minosHome) {
        this.markers = new FileResumableRunMarkers(Objects.requireNonNull(minosHome, "minosHome"));
    }

    @Override
    public void mark(UUID runId) throws IOException {
        markers.mark(runId);
    }

    @Override
    public void unmark(UUID runId) throws IOException {
        markers.unmark(runId);
    }

    @Override
    public Optional<Path> runDirectory(UUID runId) {
        return Optional.of(markers.runDirectory(runId));
    }
}

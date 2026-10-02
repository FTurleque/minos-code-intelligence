package com.minos.runtime.local;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.DurableAtomicFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Marqueur durable {@code MINOS_HOME/runs/<runId>/.resumable} (ADR 0039 §5).
 *
 * <p>Le marqueur est publié par la même primitive durable que les artefacts de run. Il est lu par la
 * rétention des répertoires de run, qui protège le run qu'il désigne (un run en cours d'une autre
 * indexation, ou un run interrompu offert à la reprise) et en borne la durée de vie ; il ne porte
 * aucune donnée nécessaire à la correction de la reprise.</p>
 *
 * <p>Ce module ne dépend pas de l'application : le port {@code ResumableRunMarkers} de
 * l'orchestration est adapté à cette classe par la racine de composition.</p>
 */
public final class FileResumableRunMarkers {

    public static final String MARKER_FILE_NAME = ".resumable";

    private final Path runsRoot;

    public FileResumableRunMarkers(Path minosHome) {
        this.runsRoot = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize().resolve("runs");
    }

    public void mark(UUID runId) throws IOException {
        Path marker = markerPath(runId);
        DurableAtomicFile.ensureDirectory(marker.getParent(), "resumable run directory");
        Path temporary = PrivateLocalStorage.createPrivateTempFile(marker.getParent(), ".resumable-", ".tmp");
        try {
            PrivateLocalStorage.writePrivateFile(temporary,
                    ("runId=" + runId + "\nmarkedAt=" + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8));
            DurableAtomicFile.replace(temporary, marker, "resumable run marker replacement");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void unmark(UUID runId) throws IOException {
        Path marker = markerPath(runId);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("resumable run marker is not a regular file");
        }
        DurableAtomicFile.deleteIfExists(marker, "resumable run marker deletion");
        removeRunDirectoryIfEmpty(marker.getParent());
    }

    /**
     * Le répertoire est créé par {@link #mark} avant le premier provider : un run qui s'achève sans
     * avoir écrit le moindre artefact ne doit pas laisser un répertoire vide, compté dans le budget de
     * la rétention. Une tentative sans effet sur un répertoire non vide ; jamais une erreur.
     */
    private static void removeRunDirectoryIfEmpty(Path runDirectory) {
        try {
            Files.deleteIfExists(runDirectory);
        } catch (IOException notEmptyOrBusy) {
            // Artefacts présents, ou répertoire tenu par un autre processus : la rétention s'en charge.
        }
    }

    /** Emplacement du marqueur d'un run ; le runId est un UUID, le chemin reste confiné sous runs/. */
    public Path markerPath(UUID runId) {
        Path marker = runDirectory(runId).resolve(MARKER_FILE_NAME).normalize();
        if (!marker.startsWith(runsRoot)) {
            throw new IllegalStateException("resumable run marker escapes the MINOS runs root");
        }
        return marker;
    }

    /** Répertoire {@code runs/<runId>} dont les artefacts réutilisables doivent rester confinés (ADR 0039 §7). */
    public Path runDirectory(UUID runId) {
        Path directory = runsRoot.resolve(Objects.requireNonNull(runId, "runId").toString()).normalize();
        if (!directory.startsWith(runsRoot) || directory.equals(runsRoot)) {
            throw new IllegalStateException("run directory escapes the MINOS runs root");
        }
        return directory;
    }
}

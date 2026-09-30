package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.Sha256;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.minos.domain.Preconditions.requireText;

/**
 * Trace immuable d'un run d'indexation projet.
 *
 * <p>{@code runFormatVersion} identifie le format persistant du run (ADR 0039). Un run écrit par une
 * version antérieure ({@link #LEGACY_FORMAT_VERSION}) reste lisible mais n'est jamais reprenable :
 * ses exécutions ne portent aucun point de contrôle exploitable.</p>
 */
public record IndexingRun(
        UUID id,
        UUID projectId,
        Status status,
        Phase phase,
        Instant createdAt,
        Optional<Instant> completedAt,
        List<IndexerExecution> executions,
        Optional<String> stagedSnapshotId,
        Optional<String> activeSnapshotBefore,
        Optional<String> activeSnapshotAfter,
        Optional<String> message,
        int runFormatVersion,
        Optional<ResumeTrace> resume
) {

    /** Format écrit avant l'ADR 0039 : aucun point de contrôle, aucune reprise possible. */
    public static final int LEGACY_FORMAT_VERSION = 1;

    /** Format courant : points de contrôle par cible (ADR 0039, lot 1). */
    public static final int CURRENT_FORMAT_VERSION = 2;

    public IndexingRun {
        resume = Objects.requireNonNull(resume, "resume");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(createdAt, "createdAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
        stagedSnapshotId = normalizeText(stagedSnapshotId, "stagedSnapshotId");
        activeSnapshotBefore = normalizeText(activeSnapshotBefore, "activeSnapshotBefore");
        activeSnapshotAfter = normalizeText(activeSnapshotAfter, "activeSnapshotAfter");
        message = normalizeText(message, "message");
        if (runFormatVersion < LEGACY_FORMAT_VERSION) {
            throw new IllegalArgumentException("runFormatVersion must be positive");
        }

        if (status == Status.RUNNING && completedAt.isPresent()) {
            throw new IllegalArgumentException("a running run must not have completedAt");
        }
        if (status != Status.RUNNING && completedAt.isEmpty()) {
            throw new IllegalArgumentException("a terminal run requires completedAt");
        }
        if (status == Status.SUCCEEDED && phase != Phase.COMPLETED) {
            throw new IllegalArgumentException("a successful run must be completed");
        }
        if (status == Status.SUCCEEDED && activeSnapshotAfter.isEmpty()) {
            throw new IllegalArgumentException("a successful run requires an active snapshot");
        }
        if (status == Status.INTERRUPTED && phase == Phase.COMPLETED) {
            throw new IllegalArgumentException("an interrupted run keeps the phase it was interrupted in");
        }
    }

    /**
     * Constructeur de compatibilité : le run est considéré au format antérieur, donc jamais
     * reprenable. Le code qui produit ou recopie un run au format courant passe la version
     * explicitement.
     */
    public IndexingRun(
            UUID id,
            UUID projectId,
            Status status,
            Phase phase,
            Instant createdAt,
            Optional<Instant> completedAt,
            List<IndexerExecution> executions,
            Optional<String> stagedSnapshotId,
            Optional<String> activeSnapshotBefore,
            Optional<String> activeSnapshotAfter,
            Optional<String> message
    ) {
        this(id, projectId, status, phase, createdAt, completedAt, executions, stagedSnapshotId,
                activeSnapshotBefore, activeSnapshotAfter, message, LEGACY_FORMAT_VERSION);
    }

    /** Run sans trace de reprise (aucune reprise considérée). */
    public IndexingRun(
            UUID id,
            UUID projectId,
            Status status,
            Phase phase,
            Instant createdAt,
            Optional<Instant> completedAt,
            List<IndexerExecution> executions,
            Optional<String> stagedSnapshotId,
            Optional<String> activeSnapshotBefore,
            Optional<String> activeSnapshotAfter,
            Optional<String> message,
            int runFormatVersion
    ) {
        this(id, projectId, status, phase, createdAt, completedAt, executions, stagedSnapshotId,
                activeSnapshotBefore, activeSnapshotAfter, message, runFormatVersion, Optional.empty());
    }

    /**
     * Trace de reprise (ADR 0039 §6) : numéro de tentative, cibles réutilisées et réexécutées, et la
     * raison publique quand une reprise a été considérée puis refusée.
     */
    public record ResumeTrace(int attempt, int reusedTargets, int reexecutedTargets, Optional<String> refusalReason) {
        public ResumeTrace {
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
            if (reusedTargets < 0 || reexecutedTargets < 0) {
                throw new IllegalArgumentException("target counts must not be negative");
            }
            refusalReason = normalizeText(refusalReason, "refusalReason");
        }
    }

    /**
     * Clé stable d'une cible d'exécution (ADR 0039 §1) :
     * {@code sha256(indexerId + NUL + providerVersion + NUL + portable(projectRelativeRoot))}.
     */
    public static String targetKey(String indexerId, String providerVersion, Path projectRelativeRoot) {
        String id = requireText(indexerId, "indexerId");
        String version = requireText(providerVersion, "providerVersion");
        String scope = portable(Objects.requireNonNull(projectRelativeRoot, "projectRelativeRoot"));
        MessageDigest digest = Sha256.newDigest();
        digest.update(id.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(version.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(scope.getBytes(StandardCharsets.UTF_8));
        return Sha256.hex(digest);
    }

    private static Optional<String> normalizeText(Optional<String> value, String label) {
        Objects.requireNonNull(value, label);
        return value.map(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException(label + " must not contain blank text");
            }
            return text;
        });
    }

    /**
     * Règle de format sur disque partagée entre ce port et ses adaptateurs : forme portable d'un chemin
     * relatif au projet (normalisé, séparateur {@code /}), telle qu'elle entre dans {@link #targetKey} et
     * telle que les adaptateurs de persistance l'écrivent dans les points de contrôle. Publique pour qu'il
     * n'en existe qu'une définition (ADR 0044) ; la modifier change le format des runs déjà persistés.
     */
    public static String portable(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    /**
     * {@code INTERRUPTED} (ADR 0039 §2) : le processus est mort alors que le run possédait au moins
     * un point de contrôle valide ou un snapshot préparé. Terminal pour la disponibilité — il ne
     * bloque jamais un nouveau run — il ne fait qu'offrir une reprise sur le même identifiant.
     */
    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        INTERRUPTED
    }

    public enum Phase {
        PROVIDER_EXECUTION,
        STAGING,
        PROMOTION,
        COMPLETED
    }

    /**
     * Exécution d'un provider sur une cible. Le point de contrôle est absent pour un run écrit par
     * une version antérieure, ou quand l'orchestrateur n'a pas pu l'établir de façon sûre : dans les
     * deux cas la cible n'est jamais réutilisable par une reprise.
     */
    public record IndexerExecution(
            Language language,
            String indexerId,
            Path finalArtifact,
            Optional<ExecutionCheckpoint> checkpoint
    ) {
        public IndexerExecution {
            Objects.requireNonNull(language, "language");
            if (indexerId == null || indexerId.isBlank()) {
                throw new IllegalArgumentException("indexerId must not be blank");
            }
            Objects.requireNonNull(finalArtifact, "finalArtifact");
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        }

        /** Constructeur de compatibilité : exécution sans point de contrôle. */
        public IndexerExecution(Language language, String indexerId, Path finalArtifact) {
            this(language, indexerId, finalArtifact, Optional.empty());
        }
    }

    /**
     * Point de contrôle durable d'une cible terminée (ADR 0039 §1). Tout ce qui est nécessaire pour
     * décider, après une coupure, si l'artefact est encore valable : le scope, la version du
     * provider, la taille et le SHA-256 de l'artefact, l'empreinte des sources du scope, le mode et,
     * en incrémental, les fichiers ciblés relativement au scope.
     */
    public record ExecutionCheckpoint(
            Path projectRelativeRoot,
            String providerVersion,
            long artifactBytes,
            String artifactSha256,
            String scopeFingerprint,
            IndexingMode mode,
            List<String> changedFiles,
            Instant completedAt
    ) {
        /** Borne du nombre de fichiers ciblés persistés par point de contrôle incrémental. */
        public static final int MAX_CHANGED_FILES = 4_096;

        public ExecutionCheckpoint {
            projectRelativeRoot = Objects.requireNonNull(projectRelativeRoot, "projectRelativeRoot").normalize();
            if (projectRelativeRoot.isAbsolute() || projectRelativeRoot.startsWith("..")) {
                throw new IllegalArgumentException("projectRelativeRoot must stay inside project");
            }
            providerVersion = requireText(providerVersion, "providerVersion");
            if (artifactBytes < 1L || artifactBytes > IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("artifactBytes must be within the SCIP artifact budget");
            }
            artifactSha256 = requireSha256(artifactSha256, "artifactSha256");
            scopeFingerprint = requireSha256(scopeFingerprint, "scopeFingerprint");
            Objects.requireNonNull(mode, "mode");
            if (mode == IndexingMode.NONE) {
                throw new IllegalArgumentException("NONE is not an executable indexing mode");
            }
            changedFiles = immutableSortedPortablePaths(changedFiles);
            if (mode == IndexingMode.FULL && !changedFiles.isEmpty()) {
                throw new IllegalArgumentException("a FULL checkpoint must not carry changed files");
            }
            if (mode == IndexingMode.INCREMENTAL && changedFiles.isEmpty()) {
                throw new IllegalArgumentException("an INCREMENTAL checkpoint requires changed files");
            }
            Objects.requireNonNull(completedAt, "completedAt");
        }

        private static String requireSha256(String value, String label) {
            Objects.requireNonNull(value, label);
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(label + " must contain exactly 64 hexadecimal characters");
            }
            return normalized;
        }

        private static List<String> immutableSortedPortablePaths(List<String> paths) {
            List<String> copy = List.copyOf(Objects.requireNonNull(paths, "changedFiles"));
            if (copy.size() > MAX_CHANGED_FILES) {
                throw new IllegalArgumentException("changedFiles exceeds the checkpoint budget: "
                        + copy.size() + "/" + MAX_CHANGED_FILES);
            }
            String previous = null;
            for (String path : copy) {
                if (path.isBlank() || path.startsWith("/") || path.contains("\\")) {
                    throw new IllegalArgumentException("changedFiles must contain portable relative paths");
                }
                if (previous != null && previous.compareTo(path) >= 0) {
                    throw new IllegalArgumentException("changedFiles must be strictly sorted and unique");
                }
                previous = path;
            }
            return copy;
        }
    }
}

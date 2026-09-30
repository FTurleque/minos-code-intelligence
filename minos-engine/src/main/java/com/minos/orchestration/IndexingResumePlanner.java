package com.minos.orchestration;

import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Plan de reprise d'un run {@code INTERRUPTED} (ADR 0039 §3 et §7).
 *
 * <p>Le plan part toujours des cibles négociées <em>maintenant</em>, jamais de la liste des
 * exécutions du run interrompu. Pour chaque cible, le point de contrôle de même clé
 * ({@link IndexingRun#targetKey}) n'est retenu que si toutes les conditions de l'ADR sont vraies.
 * Une cible sans point de contrôle valable pour une raison attendue (indexeur non qualifié, mode ou
 * fichiers ciblés différents, sources du scope modifiées) est simplement réexécutée ; tout écart
 * inattendu (artefact absent, hors du répertoire de run, lien symbolique, taille ou SHA-256
 * différents, empreinte incalculable, TTL dépassé) annule la reprise entière. Fail-closed : au
 * moindre doute, run complet.</p>
 */
final class IndexingResumePlanner {

    static final Duration DEFAULT_RESUME_TTL = Duration.ofHours(24);

    private static final System.Logger LOGGER = System.getLogger(IndexingResumePlanner.class.getName());

    private IndexingResumePlanner() {
    }

    record Request(
            ProjectIndexState previous,
            Path projectRoot,
            List<IndexingExecutionTarget> targets,
            IndexingMode mode,
            List<String> changedFiles,
            Instant now,
            Duration resumeTtl
    ) {
        Request {
            Objects.requireNonNull(previous, "previous");
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            Objects.requireNonNull(mode, "mode");
            changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(resumeTtl, "resumeTtl");
            if (resumeTtl.isZero() || resumeTtl.isNegative()) {
                throw new IllegalArgumentException("resumeTtl must be positive");
            }
        }
    }

    /** Cible du plan courant dont l'artefact du run interrompu est réutilisé tel quel. */
    record ReusedTarget(IndexingExecutionTarget target, IndexerExecution execution) {
        ReusedTarget {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(execution, "execution");
            if (execution.checkpoint().isEmpty()) {
                throw new IllegalArgumentException("a reused target requires a checkpoint");
            }
        }
    }

    sealed interface Outcome permits Outcome.Resume, Outcome.Refused, Outcome.NotOffered {
        /** Le run est rouvert : {@code reused} tel quel, {@code remaining} réexécuté. */
        record Resume(IndexingRun run, int attempt, List<ReusedTarget> reused, List<IndexingExecutionTarget> remaining)
                implements Outcome {
            public Resume {
                Objects.requireNonNull(run, "run");
                if (attempt < 2) throw new IllegalArgumentException("a resumed attempt is at least the second one");
                reused = List.copyOf(Objects.requireNonNull(reused, "reused"));
                remaining = List.copyOf(Objects.requireNonNull(remaining, "remaining"));
                if (reused.isEmpty()) throw new IllegalArgumentException("a resume reuses at least one target");
            }

            /**
             * Vrai seulement quand la reprise couvre <em>exactement</em> ce que le run interrompu avait mis
             * en snapshot : aucune cible à réexécuter, et l'ensemble des clés de cible des exécutions du
             * run égal à celui des cibles courantes (R4). Le snapshot préparé par la tentative interrompue
             * contient l'index de chacune de ses exécutions ; si un module a disparu depuis, le promouvoir
             * publierait un index qu'un run complet ne produirait jamais. Fail-closed : une exécution sans
             * point de contrôle n'a pas de clé de cible, donc n'est pas prouvée présente dans le plan.
             */
            boolean stagedSnapshotCoversThePlan() {
                if (!remaining.isEmpty()) return false;
                Set<String> staged = new HashSet<>();
                for (IndexerExecution execution : run.executions()) {
                    Optional<String> key = executionKey(execution);
                    if (key.isEmpty()) return false;
                    staged.add(key.orElseThrow());
                }
                Set<String> current = new HashSet<>();
                for (ReusedTarget target : reused) current.add(targetKey(target.target()));
                return staged.equals(current);
            }
        }

        /** Un run était offert mais la reprise est refusée ; {@code reason} est publique (aucun chemin). */
        record Refused(String reason) implements Outcome {
            public Refused {
                if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            }
        }

        /** Le projet n'offre aucun run à la reprise. */
        record NotOffered() implements Outcome {
        }
    }

    static Outcome plan(
            IndexStateStore stateStore,
            ResumableRunMarkers port,
            ResumableArtifactPolicy policy,
            Request request
    ) {
        Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        Optional<UUID> reference = request.previous().resumableRunId();
        if (reference.isEmpty()) return new Outcome.NotOffered();
        UUID runId = reference.orElseThrow();
        try {
            return decide(stateStore, port, policy, request, runId);
        } catch (Refusal refusal) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS refuses to resume indexing run " + runId + " and falls back to a full run: "
                            + refusal.getMessage());
            return new Outcome.Refused(refusal.getMessage());
        }
    }

    private static Outcome decide(
            IndexStateStore stateStore,
            ResumableRunMarkers port,
            ResumableArtifactPolicy policy,
            Request request,
            UUID runId
    ) throws Refusal {
        IndexingRun run = stateStore.findRun(runId)
                .filter(candidate -> candidate.projectId().equals(request.previous().projectId()))
                .filter(candidate -> candidate.status() == IndexingRun.Status.INTERRUPTED)
                .orElseThrow(() -> new Refusal("resumable run is missing or not interrupted"));
        if (run.runFormatVersion() != IndexingRun.CURRENT_FORMAT_VERSION) {
            throw new Refusal("resumable run was written in run format " + run.runFormatVersion()
                    + ", expected " + IndexingRun.CURRENT_FORMAT_VERSION);
        }
        Path runDirectory = canonicalRunDirectory(port, runId);

        List<ReusedTarget> reused = new ArrayList<>();
        List<IndexingExecutionTarget> remaining = new ArrayList<>();
        for (IndexingExecutionTarget target : request.targets()) {
            Optional<IndexerExecution> checkpointed = checkpointFor(run, target);
            if (checkpointed.isEmpty() || !reusable(target, checkpointed.orElseThrow(), request, policy, runDirectory)) {
                remaining.add(target);
            } else {
                reused.add(new ReusedTarget(target, checkpointed.orElseThrow()));
            }
        }
        if (reused.isEmpty()) throw new Refusal("no reusable target in the interrupted run");
        int attempt = run.resume().map(IndexingRun.ResumeTrace::attempt).orElse(1) + 1;
        return new Outcome.Resume(run, attempt, reused, remaining);
    }

    private static Path canonicalRunDirectory(ResumableRunMarkers port, UUID runId) throws Refusal {
        Path runDirectory = port.runDirectory(runId)
                .orElseThrow(() -> new Refusal("run directory is unknown to this runtime"));
        if (!Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new Refusal("run directory is missing");
        }
        try {
            return runDirectory.toRealPath();
        } catch (IOException failure) {
            throw new Refusal("run directory cannot be resolved: " + failure.getClass().getSimpleName());
        }
    }

    private static Optional<IndexerExecution> checkpointFor(IndexingRun run, IndexingExecutionTarget target) {
        String key = targetKey(target);
        return run.executions().stream()
                .filter(execution -> executionKey(execution).filter(key::equals).isPresent())
                .findFirst();
    }

    /** Clé de la cible courante : celle que le point de contrôle d'une exécution a portée si elle a réussi. */
    private static String targetKey(IndexingExecutionTarget target) {
        IndexerSelection selection = target.selection();
        return IndexingRun.targetKey(
                selection.indexer().id(), selection.indexer().version(), target.projectRelativeRoot());
    }

    /** Clé de cible d'une exécution du run, vide quand elle ne porte aucun point de contrôle. */
    private static Optional<String> executionKey(IndexerExecution execution) {
        return execution.checkpoint().map(checkpoint -> IndexingRun.targetKey(
                execution.indexerId(), checkpoint.providerVersion(), checkpoint.projectRelativeRoot()));
    }

    /**
     * Whether a checkpointed execution may serve the target. Returns {@code false} for the expected
     * reasons (the target is simply re-executed) and throws {@link Refusal} for every unexpected
     * discrepancy (the whole resume is abandoned).
     */
    private static boolean reusable(
            IndexingExecutionTarget target,
            IndexerExecution execution,
            Request request,
            ResumableArtifactPolicy policy,
            Path runDirectory
    ) throws Refusal {
        IndexerSelection selection = target.selection();
        ExecutionCheckpoint checkpoint = execution.checkpoint().orElseThrow();
        if (!policy.qualifies(selection.indexer())) return false;
        if (execution.language() != selection.language()) return false;
        if (checkpoint.mode() != request.mode()) return false;
        List<String> scoped = IndexingRunExecutor.scopedChangedFiles(
                request.mode(), request.changedFiles(), target.projectRelativeRoot());
        if (!checkpoint.changedFiles().equals(scoped)) return false;
        if (Duration.between(checkpoint.completedAt(), request.now()).compareTo(request.resumeTtl()) > 0) {
            throw new Refusal("checkpoint older than resume TTL");
        }

        Path artifact = execution.finalArtifact();
        // Containment is decided on canonical paths before a single byte is read (R1-6).
        if (Files.isSymbolicLink(artifact)) throw new Refusal("artifact is a symbolic link");
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) throw new Refusal("artifact is missing");
        Path real;
        try {
            real = artifact.toRealPath();
        } catch (IOException failure) {
            throw new Refusal("artifact cannot be resolved: " + failure.getClass().getSimpleName());
        }
        if (!real.startsWith(runDirectory)) throw new Refusal("artifact lies outside the run directory");
        long size;
        try {
            size = Files.size(real);
        } catch (IOException failure) {
            throw new Refusal("artifact size cannot be read: " + failure.getClass().getSimpleName());
        }
        if (size != checkpoint.artifactBytes() || size > IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES) {
            throw new Refusal("artifact size differs from checkpoint");
        }
        ExecutionCheckpoints.ArtifactDigest digest;
        try {
            digest = ExecutionCheckpoints.artifactDigest(artifact);
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            throw new Refusal(unavailable.getMessage());
        }
        if (digest.bytes() != checkpoint.artifactBytes() || !digest.sha256().equals(checkpoint.artifactSha256())) {
            throw new Refusal("artifact digest differs from checkpoint");
        }

        String fingerprint;
        try {
            fingerprint = ExecutionCheckpoints.scopeFingerprint(request.projectRoot(), target.projectRelativeRoot());
        } catch (ExecutionCheckpoints.Unavailable unavailable) {
            throw new Refusal(unavailable.getMessage());
        }
        return fingerprint.equals(checkpoint.scopeFingerprint());
    }

    private static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;

        private Refusal(String reason) {
            super(reason);
        }
    }
}

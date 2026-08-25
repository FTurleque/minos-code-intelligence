package com.minos.orchestration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Applies bounded retention to persisted indexing-run history without invalidating project state. */
public final class IndexRunRetentionService {

    private final FileIndexStateStore stateStore;

    public IndexRunRetentionService(Path storageRoot, FileIndexStateStore stateStore) {
        Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public RetentionResult compact(UUID projectId, IndexRunRetentionPolicy policy) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");

        List<IndexingRun> runs = stateStore.listRuns(projectId);
        Optional<UUID> latestRunId = stateStore.findProjectState(projectId)
                .flatMap(ProjectIndexState::latestRunId);

        List<IndexingRun> succeeded = runs.stream()
                .filter(run -> run.status() == IndexingRun.Status.SUCCEEDED)
                .sorted(newestFirst())
                .toList();
        List<IndexingRun> nonSucceeded = runs.stream()
                .filter(run -> run.status() != IndexingRun.Status.SUCCEEDED)
                .sorted(newestFirst())
                .toList();

        Set<UUID> retained = new HashSet<>();
        keep(retained, succeeded, policy.maxSucceededRuns());
        keep(retained, nonSucceeded, policy.maxNonSucceededRuns());
        latestRunId.ifPresent(retained::add);

        List<UUID> deleted = new ArrayList<>();
        for (IndexingRun run : runs) {
            if (retained.contains(run.id())) continue;
            if (stateStore.deleteRun(projectId, run.id())) deleted.add(run.id());
        }

        List<UUID> retainedOrdered = runs.stream()
                .map(IndexingRun::id)
                .filter(retained::contains)
                .toList();
        return new RetentionResult(retainedOrdered, List.copyOf(deleted), latestRunId.orElse(null));
    }

    private static Comparator<IndexingRun> newestFirst() {
        return Comparator
                .comparing((IndexingRun run) -> run.completedAt().orElse(run.createdAt()))
                .thenComparing(IndexingRun::id)
                .reversed();
    }

    private static void keep(Set<UUID> retained, List<IndexingRun> runs, int count) {
        for (int index = 0; index < Math.min(count, runs.size()); index++) retained.add(runs.get(index).id());
    }

    public record RetentionResult(
            List<UUID> retainedRunIds,
            List<UUID> deletedRunIds,
            UUID protectedLatestRunId
    ) {
        public RetentionResult {
            retainedRunIds = List.copyOf(Objects.requireNonNull(retainedRunIds, "retainedRunIds"));
            deletedRunIds = List.copyOf(Objects.requireNonNull(deletedRunIds, "deletedRunIds"));
        }
    }
}

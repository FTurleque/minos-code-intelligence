import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexRunRetentionPolicy;
import com.minos.orchestration.IndexRunRetentionService;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.output.DeterministicJson;
import com.minos.store.FileSymbolSnapshotStore;
import com.minos.store.SnapshotCompactionService;
import com.minos.store.SnapshotRetentionPolicy;
import com.minos.store.SnapshotRetentionService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class M16RetentionProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: M16RetentionProbe <root> <output-json>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.createDirectories(output.getParent());

        Path snapshotRoot = root.resolve("snapshots");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(snapshotRoot);
        UUID projectId = UUID.randomUUID();
        Symbol symbol = new Symbol(
                "symbol", "m16#symbol", SymbolIdentityQuality.CANONICAL,
                projectId.toString(), null, "src/Symbol.java", null,
                SymbolKind.CLASS, "Symbol", "bench.Symbol", null, "JAVA", null,
                ResolutionStatus.RESOLVED,
                new Origin("m16-retention", "synthetic", "1", "retention", OriginType.OTHER),
                false, false, Set.of());
        for (int index = 1; index <= 6; index++) {
            snapshots.publish(projectId, "snapshot-" + index, List.of(symbol), List.of(), List.of());
            Thread.sleep(5L);
        }
        int snapshotCountBefore = snapshots.retentionService().listSnapshotFiles(projectId).size();
        SnapshotRetentionService.RetentionResult snapshotResult = new SnapshotCompactionService(snapshotRoot)
                .compact(projectId, SnapshotRetentionPolicy.defaults());
        int snapshotCountAfter = snapshots.retentionService().listSnapshotFiles(projectId).size();
        String activeSnapshot = snapshots.loadActiveKnowledge(projectId).orElseThrow().snapshotId();
        if (!"snapshot-6".equals(activeSnapshot)) {
            throw new IllegalStateException("retention changed active snapshot: " + activeSnapshot);
        }
        if (snapshotCountAfter > SnapshotRetentionPolicy.DEFAULT_MAX_HISTORICAL_SNAPSHOTS + 1) {
            throw new IllegalStateException("snapshot retention did not bound persisted files: " + snapshotCountAfter);
        }

        Path stateRoot = root.resolve("index-state");
        FileIndexStateStore stateStore = new FileIndexStateStore(stateRoot);
        UUID latestRunId = null;
        for (int index = 1; index <= 25; index++) {
            IndexingRun run = terminalRun(projectId, true, index);
            stateStore.saveRun(run);
        }
        for (int index = 26; index <= 40; index++) {
            IndexingRun run = terminalRun(projectId, false, index);
            stateStore.saveRun(run);
            latestRunId = run.id();
        }
        stateStore.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.STALE,
                Optional.of(activeSnapshot),
                Optional.of(latestRunId),
                Instant.parse("2026-07-26T00:00:00Z"),
                Optional.of("M16 retention probe")
        ));
        int runCountBefore = stateStore.listRuns(projectId).size();
        IndexRunRetentionService.RetentionResult runResult = new IndexRunRetentionService(stateRoot, stateStore)
                .compact(projectId, IndexRunRetentionPolicy.defaults());
        int runCountAfter = stateStore.listRuns(projectId).size();
        if (runCountAfter > IndexRunRetentionPolicy.DEFAULT_MAX_SUCCEEDED_RUNS
                + IndexRunRetentionPolicy.DEFAULT_MAX_NON_SUCCEEDED_RUNS) {
            throw new IllegalStateException("run retention did not bound history: " + runCountAfter);
        }
        if (stateStore.findRun(latestRunId).isEmpty()) {
            throw new IllegalStateException("run retention deleted protected latestRunId: " + latestRunId);
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("snapshot_count_before", snapshotCountBefore);
        json.put("snapshot_count_after", snapshotCountAfter);
        json.put("snapshot_deleted", snapshotResult.deletedHistoricalFiles().size());
        json.put("snapshot_retained_historical", snapshotResult.retainedHistoricalFiles().size());
        json.put("active_snapshot_id", activeSnapshot);
        json.put("run_count_before", runCountBefore);
        json.put("run_count_after", runCountAfter);
        json.put("run_deleted", runResult.deletedRunIds().size());
        json.put("protected_latest_run_id", latestRunId.toString());
        json.put("protected_latest_run_present", true);
        Files.writeString(output, DeterministicJson.render(json) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.printf(
                "M16 retention: snapshots=%d->%d runs=%d->%d active=%s latestRunProtected=true%n",
                snapshotCountBefore, snapshotCountAfter, runCountBefore, runCountAfter, activeSnapshot);
    }

    private static IndexingRun terminalRun(UUID projectId, boolean succeeded, int ordinal) {
        Instant time = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(ordinal * 60L);
        return new IndexingRun(
                UUID.randomUUID(),
                projectId,
                succeeded ? IndexingRun.Status.SUCCEEDED : IndexingRun.Status.FAILED,
                succeeded ? IndexingRun.Phase.COMPLETED : IndexingRun.Phase.PROMOTION,
                time,
                Optional.of(time.plusSeconds(1)),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                succeeded ? Optional.of("snapshot-" + ordinal) : Optional.empty(),
                succeeded ? Optional.empty() : Optional.of("synthetic failure")
        );
    }
}

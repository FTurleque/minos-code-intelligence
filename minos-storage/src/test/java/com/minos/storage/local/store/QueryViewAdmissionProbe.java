package com.minos.storage.local.store;

import com.minos.diagnostics.SnapshotMemoryExhaustedException;
import com.minos.storage.codec.QueryViewMemoryBudget;
import com.minos.storage.codec.SnapshotLimits;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Child JVM of {@link QueryViewAdmissionChildJvmTest}: loads the active views of two projects at the same instant
 * and prints one line per project, {@code PROBE <project> loaded <symbols>} or {@code PROBE <project> refused}.
 *
 * <p>{@code bounded} uses the process budget, as production does; {@code unbounded} injects a budget far above the
 * heap, which is the store without admission. Run with a reduced {@code -Xmx} and {@code -XX:+ExitOnOutOfMemoryError},
 * an {@link OutOfMemoryError} ends the JVM with code 3; any other failure exits with 1.</p>
 */
public final class QueryViewAdmissionProbe {

    static final String LINE = "PROBE ";

    private QueryViewAdmissionProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("usage: bounded|unbounded <root> <projectA> <projectB>");
        QueryViewMemoryBudget budget = switch (args[0]) {
            case "bounded" -> QueryViewMemoryBudget.shared();
            case "unbounded" -> QueryViewMemoryBudget.of(1L << 50, QueryViewMemoryBudget.MAXIMUM_PERCENT,
                    SnapshotLimits.VIEW_ADMISSION_WAIT);
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        };
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(Path.of(args[1]),
                FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_ENTRIES, SnapshotLimits.production(),
                FileSymbolSnapshotStore.DEFAULT_MAX_ACTIVE_QUERY_RETRIES, ActiveQueryBuildHook.NOOP, budget);
        List<UUID> projects = List.of(UUID.fromString(args[2]), UUID.fromString(args[3]));
        CyclicBarrier start = new CyclicBarrier(projects.size());
        List<String> lines = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (UUID projectId : projects) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(1, TimeUnit.MINUTES);
                    // The view is not kept: its symbol count is all this thread retains.
                    int symbols = store.loadActiveReadView(projectId).orElseThrow().snapshot().symbols().size();
                    record(lines, LINE + projectId + " loaded " + symbols);
                } catch (SnapshotMemoryExhaustedException refused) {
                    record(lines, LINE + projectId + " refused");
                } catch (Exception failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                }
            }, "probe-" + projectId);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(Duration.ofMinutes(2));
        }
        lines.forEach(System.out::println);
        if (!failures.isEmpty() || lines.size() != projects.size()) {
            failures.forEach(failure -> System.out.println("PROBE failure " + failure));
            System.exit(1);
        }
    }

    private static void record(List<String> lines, String line) {
        synchronized (lines) {
            lines.add(line);
        }
    }
}

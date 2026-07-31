package com.minos.application;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.analysis.FingerprintConstrainedJavaProgramGraphProvider;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramGraphPerformanceQualificationTest {

    private static final String FILE_COUNT_PROPERTY = "minos.m28.programGraph.files";
    private static final String RESULT_PROPERTY = "minos.m28.programGraph.result";

    @Test
    void recordsColdWarmCacheHitAndModifiedSourceDisposition(@TempDir Path root) throws Exception {
        int fileCount = Integer.getInteger(FILE_COUNT_PROPERTY, 64);
        assertTrue(fileCount >= 4 && fileCount <= 2_000, "qualification file count must stay within provider bounds");

        Path projectRoot = Files.createDirectories(root.resolve("project"));
        List<String> fileIds = generateCorpus(projectRoot, fileCount);
        Path home = root.resolve("minos-home");
        MinosApplication application = MinosApplication.open(home);
        RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "m28-program-graph-scale");
        String snapshotId = "snapshot-m28-program-graph-scale";
        application.snapshotStore().publish(
                project.id(),
                snapshotId,
                symbols(project, snapshotId, fileIds),
                List.of(),
                List.of());
        application.fingerprintStore().publish(
                project.id(),
                snapshotId,
                application.fingerprintService().capture(projectRoot));
        application.fingerprintStore().promote(project.id(), snapshotId);

        long coldStarted = System.nanoTime();
        ProgramGraph cold = application.programGraphService().getGraph(project.id().toString());
        long coldNanos = System.nanoTime() - coldStarted;

        long warmStarted = System.nanoTime();
        ProgramGraph warm = application.programGraphService().getGraph(project.id().toString());
        long warmNanos = System.nanoTime() - warmStarted;

        var stats = application.programGraphService().cacheStats();
        assertSame(cold, warm, "warm path must return the bounded in-memory cache entry");
        assertEquals(1L, stats.misses());
        assertEquals(1L, stats.hits());
        assertEquals(1, stats.entries());
        assertTrue(stats.providerKeyNanos() > 0L);
        assertTrue(stats.analysisNanos() > 0L);
        assertTrue(cold.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertTrue(cold.supports(ProgramGraphCapability.LOCAL_DATA_FLOW));
        assertTrue(cold.supports(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW));

        Path changed = projectRoot.resolve(fileIds.getFirst());
        Files.writeString(changed, Files.readString(changed, StandardCharsets.UTF_8)
                + System.lineSeparator() + "// deterministic M28 source change" + System.lineSeparator(),
                StandardCharsets.UTF_8);
        MinosApplication reopened = MinosApplication.open(home);
        long modifiedStarted = System.nanoTime();
        ProgramGraph modified = reopened.programGraphService().getGraph(project.id().toString());
        long modifiedNanos = System.nanoTime() - modifiedStarted;
        assertFalse(modified.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertTrue(modified.limitations().contains(
                FingerprintConstrainedJavaProgramGraphProvider.SOURCE_MISMATCH_LIMITATION));

        long sourceBytes;
        try (var paths = Files.walk(projectRoot)) {
            sourceBytes = paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).sum();
        }
        Path result = Path.of(System.getProperty(
                RESULT_PROPERTY,
                "target/m28-program-graph-performance.json")).toAbsolutePath().normalize();
        Files.createDirectories(result.getParent());
        String json = """
                {
                  "profile": "M28_PROGRAM_GRAPH_JAVA",
                  "file_count": %d,
                  "source_bytes": %d,
                  "cold_nanos": %d,
                  "warm_nanos": %d,
                  "modified_source_nanos": %d,
                  "provider_key_nanos": %d,
                  "analysis_nanos": %d,
                  "cache_hits": %d,
                  "cache_misses": %d,
                  "cache_entries": %d,
                  "warm_identity_hit": true,
                  "modified_source_disposition": "%s",
                  "decision": "KEEP_FINGERPRINT_CONSTRAINED_IN_MEMORY_CACHE"
                }
                """.formatted(
                fileCount,
                sourceBytes,
                coldNanos,
                warmNanos,
                modifiedNanos,
                stats.providerKeyNanos(),
                stats.analysisNanos(),
                stats.hits(),
                stats.misses(),
                stats.entries(),
                FingerprintConstrainedJavaProgramGraphProvider.SOURCE_MISMATCH_LIMITATION);
        Files.writeString(result, json, StandardCharsets.UTF_8);
        assertTrue(Files.size(result) > 0L);
    }

    private static List<String> generateCorpus(Path projectRoot, int fileCount) throws Exception {
        List<String> fileIds = new ArrayList<>(fileCount);
        for (int index = 0; index < fileCount; index++) {
            String suffix = String.format("%04d", index);
            String simpleName = "Generated" + suffix;
            String calleeName = "callee" + suffix;
            String fileId = "src/main/java/demo/" + simpleName + ".java";
            Path source = projectRoot.resolve(fileId);
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package demo;
                    final class %s {
                        int %s(int value) {
                            return value + 1;
                        }
                        int run(int value) {
                            int copy = %s(value);
                            if (copy > 0) {
                                copy++;
                            } else {
                                copy--;
                            }
                            return copy;
                        }
                    }
                    """.formatted(simpleName, calleeName, calleeName), StandardCharsets.UTF_8);
            fileIds.add(fileId);
        }
        return List.copyOf(fileIds);
    }

    private static List<Symbol> symbols(
            RegisteredProject project,
            String snapshotId,
            List<String> fileIds
    ) {
        Origin origin = new Origin("m28-performance", "GENERATED_FIXTURE", "1", snapshotId, OriginType.OTHER);
        return fileIds.stream().map(fileId -> {
            String simpleName = Path.of(fileId).getFileName().toString().replace(".java", "");
            SymbolLocation location = new SymbolLocation(
                    fileId, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
            return new Symbol(
                    "m28-performance-" + simpleName,
                    "demo/" + simpleName + "#",
                    SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                    project.id().toString(),
                    null,
                    fileId,
                    null,
                    SymbolKind.CLASS,
                    simpleName,
                    "demo." + simpleName,
                    null,
                    "java",
                    location,
                    ResolutionStatus.RESOLVED,
                    origin,
                    false,
                    false,
                    Set.of());
        }).toList();
    }
}

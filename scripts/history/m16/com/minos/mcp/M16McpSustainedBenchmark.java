package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.output.DeterministicJson;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class M16McpSustainedBenchmark {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: M16McpSustainedBenchmark <home> <repetitions> <output-json>");
        }
        Path home = Path.of(args[0]).toAbsolutePath().normalize();
        int repetitions = Integer.parseInt(args[1]);
        if (repetitions < 5 || repetitions > 500) {
            throw new IllegalArgumentException("repetitions must be between 5 and 500");
        }
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());

        MinosApplication application = MinosApplication.open(home);
        RegisteredProject project = application.projectRegistry().listProjects().stream()
                .filter(candidate -> "m16-scale".equals(candidate.displayName()))
                .findFirst().orElseThrow(() -> new IllegalStateException("m16-scale project is missing"));
        CodeKnowledgeSnapshot snapshot = application.snapshotStore().loadActiveKnowledge(project.id()).orElseThrow();
        int symbolCount = snapshot.symbols().size();
        String symbolGroup = "SymbolGroup%04d".formatted((symbolCount / 2) % 1_000);
        String usageAnchor = symbolId(symbolCount / 2 + 3);
        String dependencyAnchor = symbolId(symbolCount / 2 + 2);
        String dependentAnchor = symbolId(symbolCount / 2 + 3);
        String impactAnchor = symbolId(symbolCount / 2 + 1);
        String projectName = project.displayName();

        FileSymbolSnapshotStore.CacheStats beforeBackend = application.snapshotStore().cacheStats();
        MinosApplicationMcpBackend backend = new MinosApplicationMcpBackend(application);
        for (int i = 0; i < 3; i++) {
            backendSequence(backend, projectName, symbolGroup, usageAnchor,
                    dependencyAnchor, dependentAnchor, impactAnchor);
        }
        List<Long> backendNanos = new ArrayList<>();
        for (int i = 0; i < repetitions; i++) {
            long started = System.nanoTime();
            backendSequence(backend, projectName, symbolGroup, usageAnchor,
                    dependencyAnchor, dependentAnchor, impactAnchor);
            backendNanos.add(System.nanoTime() - started);
        }
        FileSymbolSnapshotStore.CacheStats afterBackend = application.snapshotStore().cacheStats();

        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();
        String classpath = System.getProperty("java.class.path");
        ServerParameters parameters = ServerParameters.builder(javaExecutable)
                .args("-cp", classpath, MinosMcpServer.class.getName())
                .env(Map.of("MINOS_HOME", home.toString()))
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .build();

        List<Long> stdioNanos = new ArrayList<>();
        Map<String, List<Long>> perTool = new LinkedHashMap<>();
        try {
            client.initialize();
            for (int i = 0; i < 2; i++) {
                stdioSequence(client, projectName, symbolGroup, usageAnchor,
                        dependencyAnchor, dependentAnchor, impactAnchor, null);
            }
            for (int i = 0; i < repetitions; i++) {
                long sequenceStarted = System.nanoTime();
                stdioSequence(client, projectName, symbolGroup, usageAnchor,
                        dependencyAnchor, dependentAnchor, impactAnchor, perTool);
                stdioNanos.add(System.nanoTime() - sequenceStarted);
            }
        } finally {
            client.closeGracefully();
        }

        Stats backendStats = stats(backendNanos);
        Stats stdioStats = stats(stdioNanos);
        Map<String, Object> toolStats = new LinkedHashMap<>();
        perTool.forEach((name, values) -> toolStats.put(name, stats(values).toMap()));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("repetitions", repetitions);
        json.put("calls_per_sequence", 8);
        json.put("backend_sequence", backendStats.toMap());
        json.put("stdio_sequence", stdioStats.toMap());
        json.put("stdio_tools", toolStats);
        json.put("backend_full_snapshot_load_delta",
                afterBackend.fullSnapshotLoads() - beforeBackend.fullSnapshotLoads());
        json.put("backend_query_view_build_delta",
                afterBackend.queryViewBuilds() - beforeBackend.queryViewBuilds());
        json.put("backend_cache_hit_delta", afterBackend.hits() - beforeBackend.hits());
        Files.writeString(output, DeterministicJson.render(json) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.printf(
                "M16 MCP sustained: repetitions=%d backend-p95=%.3fms stdio-p95=%.3fms stdio-p99=%.3fms full-load-delta=%d build-delta=%d hits=%d%n",
                repetitions, backendStats.p95Ms(), stdioStats.p95Ms(), stdioStats.p99Ms(),
                afterBackend.fullSnapshotLoads() - beforeBackend.fullSnapshotLoads(),
                afterBackend.queryViewBuilds() - beforeBackend.queryViewBuilds(),
                afterBackend.hits() - beforeBackend.hits());
    }

    private static void backendSequence(
            MinosApplicationMcpBackend backend,
            String project,
            String group,
            String usageAnchor,
            String dependencyAnchor,
            String dependentAnchor,
            String impactAnchor
    ) throws Exception {
        backend.findSymbols(new MinosMcpBackend.SymbolSearchRequest(
                project, group, null, null, null, 20));
        backend.findUsages(new MinosMcpBackend.RelationRequest(project, usageAnchor, 20));
        backend.findRelationships(MinosMcpBackend.RelationshipOperation.DEPENDENCIES,
                new MinosMcpBackend.RelationRequest(project, dependencyAnchor, 20));
        backend.findRelationships(MinosMcpBackend.RelationshipOperation.DEPENDENTS,
                new MinosMcpBackend.RelationRequest(project, dependentAnchor, 20));
        backend.findRelationships(MinosMcpBackend.RelationshipOperation.RELATED_TESTS,
                new MinosMcpBackend.RelationRequest(project, impactAnchor, 20));
        backend.searchCode(new MinosMcpBackend.SearchRequest(
                project, group, null, null, null, 5, 1, 3, 10, 0, 4_000, false));
        backend.architecture(project);
        backend.impact(new MinosMcpBackend.ImpactRequest(project, impactAnchor, 4, 200));
    }

    private static void stdioSequence(
            McpSyncClient client,
            String project,
            String group,
            String usageAnchor,
            String dependencyAnchor,
            String dependentAnchor,
            String impactAnchor,
            Map<String, List<Long>> perTool
    ) {
        call(client, "minos_find_symbols", Map.of("project", project, "query", group, "limit", 20), perTool);
        call(client, "minos_find_usages", Map.of("project", project, "symbolId", usageAnchor, "limit", 20), perTool);
        call(client, "minos_dependencies", Map.of("project", project, "symbolId", dependencyAnchor, "limit", 20), perTool);
        call(client, "minos_dependents", Map.of("project", project, "symbolId", dependentAnchor, "limit", 20), perTool);
        call(client, "minos_related_tests", Map.of("project", project, "symbolId", impactAnchor, "limit", 20), perTool);
        call(client, "minos_search_code", Map.of(
                "project", project, "query", group, "limit", 5, "depth", 1,
                "usages", 3, "relationships", 10, "contextLines", 0,
                "maxTokens", 4_000, "includeSource", false), perTool);
        call(client, "minos_architecture", Map.of("project", project), perTool);
        call(client, "minos_impact", Map.of(
                "project", project, "symbolId", impactAnchor, "depth", 4, "limit", 200), perTool);
    }

    private static void call(
            McpSyncClient client,
            String tool,
            Map<String, Object> arguments,
            Map<String, List<Long>> perTool
    ) {
        long started = System.nanoTime();
        var result = client.callTool(CallToolRequest.builder(tool).arguments(arguments).build());
        long elapsed = System.nanoTime() - started;
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP tool failed during sustained benchmark: " + tool + " -> " + result.content());
        }
        if (perTool != null) {
            perTool.computeIfAbsent(tool, ignored -> new ArrayList<>()).add(elapsed);
        }
    }

    private static Stats stats(List<Long> values) {
        long[] nanos = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(nanos);
        return new Stats(
                nanosToMs(nanos[(int) Math.floor((nanos.length - 1) * 0.50)]),
                nanosToMs(nanos[(int) Math.floor((nanos.length - 1) * 0.95)]),
                nanosToMs(nanos[(int) Math.floor((nanos.length - 1) * 0.99)]),
                nanosToMs(Arrays.stream(nanos).sum() / nanos.length)
        );
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static String symbolId(int index) {
        return "sym-%09d".formatted(index);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record Stats(double p50Ms, double p95Ms, double p99Ms, double averageMs) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("p50_ms", round(p50Ms));
            map.put("p95_ms", round(p95Ms));
            map.put("p99_ms", round(p99Ms));
            map.put("average_ms", round(averageMs));
            return map;
        }
    }
}

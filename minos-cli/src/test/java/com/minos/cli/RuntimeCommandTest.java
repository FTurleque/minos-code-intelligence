package com.minos.cli;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.dynamic.RuntimeIntelligenceService;
import com.minos.dynamic.RuntimeObservationSession;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileRuntimeObservationStore;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCommandTest {

    @Test
    void importsAndQueriesTheSamePartialSessionAcrossAllCliActions(@TempDir Path root) throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "cli-runtime");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(project.id(), "snapshot-cli", List.of(symbol(project)));
        RuntimeCommand command = new RuntimeCommand(new RuntimeIntelligenceService(
                registry, snapshots, new FileRuntimeObservationStore(root.resolve("runtime"))));
        Path envelope = root.resolve("runtime.tsv");
        Files.writeString(envelope, String.join("\n",
                RuntimeObservationSession.FORMAT,
                "session\trun-cli",
                "project\t" + project.id(),
                "snapshot\tsnapshot-cli",
                "started\t2026-07-29T06:00:00Z",
                "ended\t2026-07-29T06:01:00Z",
                "collector\tfixture\t1",
                "environment\ttest",
                "completeness\tPARTIAL",
                "symbol\tkey:service\tcom.acme.Service\tsrc/Service.java\t\t7\t42",
                ""), StandardCharsets.UTF_8);

        Result imported = run(command, "import", "cli-runtime", "--file", envelope.toString(), "--format", "json");
        Result sessions = run(command, "sessions", "cli-runtime", "--format", "json");
        Result report = run(command, "report", "cli-runtime", "--session", "run-cli", "--format", "json");
        Result symbol = run(command, "symbol", "cli-runtime", "--symbol", "service", "--format", "json");

        assertEquals(0, imported.exitCode());
        assertTrue(imported.output().contains("\"nature\":\"OBSERVED_PARTIAL\""));
        assertTrue(imported.output().contains("\"exhaustive\":false"));
        assertTrue(sessions.output().contains("\"sessionId\":\"run-cli\""));
        assertTrue(report.output().contains("\"observedSymbolRatio\":1.0"));
        assertTrue(symbol.output().contains("\"executionHits\":7"));
    }

    @Test
    void validatesActionSpecificRequiredOptionsAndBoundsBeforeCallingServices(@TempDir Path root)
            throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "cli-runtime");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(project.id(), "snapshot-cli", List.of(symbol(project)));
        RuntimeCommand command = new RuntimeCommand(new RuntimeIntelligenceService(
                registry, snapshots, new FileRuntimeObservationStore(root.resolve("runtime"))));

        Result missingFile = run(command, "import", "cli-runtime");
        Result missingSymbol = run(command, "symbol", "cli-runtime");
        Result oversizedSessions = run(command, "sessions", "cli-runtime", "--limit", "129");
        Result oversizedReport = run(command, "report", "cli-runtime", "--limit", "1001");

        assertEquals(2, missingFile.exitCode());
        assertTrue(missingFile.error().contains("--file is required"));
        assertEquals(2, missingSymbol.exitCode());
        assertTrue(missingSymbol.error().contains("--symbol is required"));
        assertEquals(2, oversizedSessions.exitCode());
        assertEquals(2, oversizedReport.exitCode());
    }

    private static Symbol symbol(RegisteredProject project) {
        return new Symbol(
                "service", "key:service", SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(), "main", "src/Service.java", null,
                SymbolKind.CLASS, "Service", "com.acme.Service", null, "java", null,
                ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run", OriginType.OTHER),
                false, false, Set.of());
    }

    private static Result run(RuntimeCommand command, String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exit = command.run(arguments, output, error);
        return new Result(exit, output.toString(), error.toString());
    }

    private record Result(int exitCode, String output, String error) { }
}

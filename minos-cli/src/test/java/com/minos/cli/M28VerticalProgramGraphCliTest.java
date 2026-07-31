package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M28VerticalProgramGraphCliTest {

    @Test
    void productionCliAndIdeProtocolExposeAdvancedProviderCapabilities(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = MinosCliRunner.run(
                fixture.application(),
                new String[]{"ide", "program-graph", fixture.project().id().toString(), "--format", "json"},
                output,
                error);

        assertEquals(0, exit, error::toString);
        assertEquals("", error.toString());
        String json = output.toString();
        for (String capability : List.of(
                "CONTROL_FLOW",
                "LOCAL_DATA_FLOW",
                "INTERPROCEDURAL_DATA_FLOW",
                "SECURITY_TAINT")) {
            assertTrue(json.contains(capability), () -> "missing capability " + capability + " in " + json);
        }
        assertTrue(json.contains("minos-java-source-v1"), json);
        assertTrue(json.contains("DERIVED"), json);
        assertTrue(json.contains("TAINT_FLOW"), json);
    }

    private static Fixture fixture(Path root) throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        String fileId = "src/main/java/demo/VerticalSurfaceFixture.java";
        Path source = projectRoot.resolve(fileId);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                final class VerticalSurfaceFixture {
                    String source() { return "raw"; }
                    String sanitize(String value) { return value; }
                    void sink(String value) { }
                    String callee(String value) { return value; }
                    String run() {
                        String raw = source();
                        String clean = sanitize(raw);
                        sink(clean);
                        String copy = callee(clean);
                        if (copy.isEmpty()) {
                            return raw;
                        }
                        return copy;
                    }
                }
                """, StandardCharsets.UTF_8);
        Path config = projectRoot.resolve(".minos/java-advanced-provider.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "sources=source\nsanitizers=sanitize\nsinks=sink\n", StandardCharsets.UTF_8);

        MinosApplication application = MinosApplication.open(root.resolve("minos-home"));
        RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "m28-cli-vertical");
        String snapshotId = "snapshot-m28-cli-vertical";
        application.snapshotStore().publish(
                project.id(), snapshotId, List.of(symbol(project, snapshotId, fileId)), List.of(), List.of());
        application.fingerprintStore().publish(
                project.id(), snapshotId, application.fingerprintService().capture(projectRoot));
        application.fingerprintStore().promote(project.id(), snapshotId);
        return new Fixture(application, project);
    }

    private static Symbol symbol(RegisteredProject project, String snapshotId, String fileId) {
        SymbolLocation location = new SymbolLocation(fileId, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
        Origin origin = new Origin("m28-cli", "FIXTURE", "1", snapshotId, OriginType.OTHER);
        return new Symbol(
                "m28-cli-vertical-symbol",
                "demo/VerticalSurfaceFixture#",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                null,
                fileId,
                null,
                SymbolKind.CLASS,
                "VerticalSurfaceFixture",
                "demo.VerticalSurfaceFixture",
                null,
                "java",
                location,
                ResolutionStatus.RESOLVED,
                origin,
                false,
                false,
                Set.of());
    }

    private record Fixture(MinosApplication application, RegisteredProject project) {
    }
}

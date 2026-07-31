package com.minos.program.analysis;

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
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceProgramGraphDecompositionTest {

    private static final Path REPOSITORY_ROOT = resolveRepositoryRoot();
    private static final Path FIXTURE_ROOT = REPOSITORY_ROOT
            .resolve("fixtures/m22/java-advanced-provider/project")
            .normalize();
    private static final Path ANALYSIS_SOURCE_ROOT = REPOSITORY_ROOT
            .resolve("minos-application/src/main/java/com/minos/program/analysis")
            .normalize();
    private static final List<String> CORPUS = List.of(
            "src/main/java/demo/CfgFixture.java",
            "src/main/java/demo/DefUseFixture.java",
            "src/main/java/demo/InterproceduralFixture.java",
            "src/main/java/demo/SecurityFixture.java");

    @Test
    void decomposedPipelineIsDeterministicAcrossTheControlledM22Corpus() throws Exception {
        RegisteredProject project = project(FIXTURE_ROOT);
        CodeKnowledgeSnapshot snapshot = snapshot(project, "snapshot-m28-decomposition", CORPUS);
        JavaSourceProgramGraphProvider provider = new JavaSourceProgramGraphProvider();

        String firstKey = provider.cacheKey(project, snapshot);
        ProgramGraph first = provider.analyze(project, snapshot);
        String secondKey = provider.cacheKey(project, snapshot);
        ProgramGraph second = provider.analyze(project, snapshot);

        assertEquals(firstKey, secondKey);
        assertEquals(first, second);
        assertTrue(first.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertTrue(first.supports(ProgramGraphCapability.LOCAL_DATA_FLOW));
        assertTrue(first.supports(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW));
        assertTrue(first.supports(ProgramGraphCapability.SECURITY_TAINT));
        assertFalse(first.nodes().isEmpty());
        assertFalse(first.edges().isEmpty());
        assertEquals(first.nodes().size(), first.nodes().stream().map(node -> node.id()).distinct().count());
        assertEquals(first.edges().size(), first.edges().stream().map(edge -> edge.id()).distinct().count());
        assertTrue(first.edges().stream().allMatch(edge ->
                JavaSourceProgramGraphProvider.PROVIDER_ID.equals(edge.origin().providerId())));
        assertTrue(first.limitations().contains("JAVA_ADVANCED_PROVIDER_V1"));
        assertTrue(first.limitations().contains("JAVA_AST_PARSE_ONLY_TYPE_ATTRIBUTION_NOT_PROVEN"));
    }

    @Test
    void publicProviderRemainsAThinFacadeAndResponsibilitiesStaySeparated() throws Exception {
        String facade = Files.readString(ANALYSIS_SOURCE_ROOT.resolve("JavaSourceProgramGraphProvider.java"));

        assertTrue(facade.lines().count() <= 80L, "public provider must remain a thin stable facade");
        assertFalse(facade.contains("TreeScanner"));
        assertFalse(facade.contains("JavacTask"));
        assertFalse(facade.contains("MessageDigest"));
        assertFalse(facade.contains("Files.readAllBytes"));

        for (String component : List.of(
                "JavaSourceWorkspace.java",
                "JavaAstParser.java",
                "JavaDefUseAnalyzer.java",
                "JavaControlFlowAnalyzer.java",
                "JavaInterproceduralFlowResolver.java",
                "JavaTaintAnalyzer.java",
                "JavaProgramGraphAssembler.java")) {
            assertTrue(Files.isRegularFile(ANALYSIS_SOURCE_ROOT.resolve(component)),
                    "missing component " + component);
        }
    }

    private static Path resolveRepositoryRoot() {
        String multiModuleRoot = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModuleRoot != null && !multiModuleRoot.isBlank()) {
            Path candidate = Path.of(multiModuleRoot).toAbsolutePath().normalize();
            if (isRepositoryRoot(candidate)) {
                return candidate;
            }
        }

        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (isRepositoryRoot(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot resolve MINOS repository root for decomposition fitness test");
    }

    private static boolean isRepositoryRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isDirectory(candidate.resolve("minos-application/src/main/java"))
                && Files.isDirectory(candidate.resolve("fixtures/m22/java-advanced-provider/project"));
    }

    private static RegisteredProject project(Path root) {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new RegisteredProject(UUID.randomUUID(), root, "m28-java-corpus", Optional.empty(), now, now);
    }

    private static CodeKnowledgeSnapshot snapshot(
            RegisteredProject project,
            String snapshotId,
            List<String> fileIds
    ) {
        List<Symbol> symbols = fileIds.stream()
                .map(fileId -> symbol(project, snapshotId, fileId))
                .toList();
        return new CodeKnowledgeSnapshot(project.id(), snapshotId, symbols, List.of(), List.of());
    }

    private static Symbol symbol(RegisteredProject project, String snapshotId, String fileId) {
        SymbolLocation location = new SymbolLocation(fileId, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
        Origin origin = new Origin("m28-fixture", "FIXTURE", "1", snapshotId, OriginType.OTHER);
        return new Symbol(
                "symbol-" + Integer.toUnsignedString(fileId.hashCode()),
                "fixture:" + fileId,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                null,
                fileId,
                null,
                SymbolKind.CLASS,
                Path.of(fileId).getFileName().toString().replace(".java", ""),
                null,
                null,
                "java",
                location,
                ResolutionStatus.RESOLVED,
                origin,
                false,
                false,
                Set.of());
    }
}

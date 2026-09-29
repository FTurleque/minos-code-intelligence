package com.minos.incremental;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInvalidationRealFixtureTest {

    @Test
    void distinguishesSourceCandidateFromBuildInvalidationOnTypeScriptModules(@TempDir Path temp) throws Exception {
        Path fixture = Path.of("fixtures/typescript/typescript-modules");
        Path workspace = temp.resolve("typescript-modules");
        copyTree(fixture, workspace);

        ProjectFingerprintService fingerprintService = new ProjectFingerprintService();
        ProjectDiscoveryService discoveryService = new ProjectDiscoveryService();
        ProjectInvalidationService invalidationService = new ProjectInvalidationService(fingerprintService);
        UUID projectId = UUID.randomUUID();
        String indexId = "typescript-modules-index";

        ProjectFingerprint baseline = fingerprintService.capture(workspace);
        ProjectFingerprintSnapshot baselineSnapshot = new ProjectFingerprintSnapshot(projectId, indexId, baseline);
        ProjectIndexState indexState = new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(indexId),
                Optional.empty(),
                Instant.EPOCH,
                Optional.empty()
        );

        Path source = firstTypeScriptFile(workspace.resolve("packages/app/src"));
        Files.writeString(source, Files.readString(source) + System.lineSeparator() + "// M7.3 source change");
        ProjectFingerprint sourceFingerprint = fingerprintService.capture(workspace);
        ProjectDiscovery sourceDiscovery = discoveryService.discover(workspace);
        ProjectInvalidationAssessment sourceAssessment = invalidationService.assess(
                indexState,
                Optional.of(baselineSnapshot),
                sourceFingerprint,
                sourceDiscovery
        );

        assertEquals(ProjectInvalidationScope.PARTIAL_CANDIDATE, sourceAssessment.scope());
        assertEquals(1, sourceAssessment.changedSourceFiles().size());
        assertTrue(sourceAssessment.changedSourceFiles().getFirst().startsWith("packages/app/src/"));
        assertEquals(0, sourceAssessment.changedTestFiles().size());
        assertEquals(0, sourceAssessment.unqualifiedChangedFiles().size());

        Path packageLock = workspace.resolve("package-lock.json");
        Files.writeString(packageLock, Files.readString(packageLock) + System.lineSeparator());
        ProjectFingerprint buildFingerprint = fingerprintService.capture(workspace);
        ProjectInvalidationAssessment buildAssessment = invalidationService.assess(
                indexState,
                Optional.of(baselineSnapshot),
                buildFingerprint,
                discoveryService.discover(workspace)
        );

        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, buildAssessment.scope());
        assertTrue(buildAssessment.reasons().contains(ProjectInvalidationReason.BUILD_DEFINITION_CHANGED));

        System.out.printf(
                "M7.3 typescript-modules invalidation: source-scope=%s, source-files=%d, build-scope=%s, build-changed=%s%n",
                sourceAssessment.scope(),
                sourceAssessment.changedSourceFiles().size(),
                buildAssessment.scope(),
                buildAssessment.changeSet().orElseThrow().buildDefinitionChanged()
        );
    }

    private static Path firstTypeScriptFile(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ts") || path.toString().endsWith(".tsx"))
                    .sorted()
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

package com.minos.incremental;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInvalidationServiceTest {

    private final ProjectFingerprintService fingerprintService = new ProjectFingerprintService();
    private final ProjectDiscoveryService discoveryService = new ProjectDiscoveryService();
    private final ProjectInvalidationService service = new ProjectInvalidationService(fingerprintService);

    @Test
    void returnsNoneWhenWorkspaceMatchesAlignedBaseline(@TempDir Path root) throws Exception {
        createJavaProject(root);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint baseline = fingerprintService.capture(root);
        ProjectDiscovery discovery = discoveryService.discover(root);

        ProjectInvalidationAssessment assessment = service.assess(
                ready(projectId, "index-1"),
                Optional.of(snapshot(projectId, "index-1", baseline)),
                fingerprintService.capture(root),
                discovery
        );

        assertEquals(ProjectInvalidationScope.NONE, assessment.scope());
        assertEquals(List.of(), assessment.reasons());
        assertTrue(assessment.changeSet().isPresent());
        assertEquals(0, assessment.changeSet().orElseThrow().changedFileCount());
    }

    @Test
    void qualifiesOnlyRecognizedSourceAndTestChangesAsPartialCandidate(@TempDir Path root) throws Exception {
        createJavaProject(root);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint baseline = fingerprintService.capture(root);

        Files.writeString(root.resolve("src/main/java/App.java"), "class App { int value = 2; }");
        Files.writeString(root.resolve("src/test/java/AppTest.java"), "class AppTest { int value = 2; }");
        ProjectFingerprint current = fingerprintService.capture(root);
        ProjectDiscovery discovery = discoveryService.discover(root);

        ProjectInvalidationAssessment assessment = service.assess(
                ready(projectId, "index-1"),
                Optional.of(snapshot(projectId, "index-1", baseline)),
                current,
                discovery
        );

        assertEquals(ProjectInvalidationScope.PARTIAL_CANDIDATE, assessment.scope());
        assertEquals(List.of(ProjectInvalidationReason.SOURCE_OR_TEST_CHANGED), assessment.reasons());
        assertEquals(List.of("src/main/java/App.java"), assessment.changedSourceFiles());
        assertEquals(List.of("src/test/java/AppTest.java"), assessment.changedTestFiles());
        assertEquals(List.of(), assessment.unqualifiedChangedFiles());
    }

    @Test
    void requiresFullInvalidationWhenBuildDefinitionChanges(@TempDir Path root) throws Exception {
        createJavaProject(root);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint baseline = fingerprintService.capture(root);

        Files.writeString(root.resolve("pom.xml"), "<project><version>2</version></project>");
        ProjectInvalidationAssessment assessment = service.assess(
                ready(projectId, "index-1"),
                Optional.of(snapshot(projectId, "index-1", baseline)),
                fingerprintService.capture(root),
                discoveryService.discover(root)
        );

        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, assessment.scope());
        assertEquals(List.of(ProjectInvalidationReason.BUILD_DEFINITION_CHANGED), assessment.reasons());
    }

    @Test
    void requiresFullInvalidationWhenIgnorePolicyChanges(@TempDir Path root) throws Exception {
        createJavaProject(root);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint baseline = fingerprintService.capture(root);

        Files.writeString(root.resolve(".minosignore"), "*.generated\n");
        ProjectInvalidationAssessment assessment = service.assess(
                ready(projectId, "index-1"),
                Optional.of(snapshot(projectId, "index-1", baseline)),
                fingerprintService.capture(root),
                discoveryService.discover(root)
        );

        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, assessment.scope());
        assertEquals(List.of(ProjectInvalidationReason.IGNORE_POLICY_CHANGED), assessment.reasons());
    }

    @Test
    void requiresFullInvalidationWhenAnyChangedFileCannotBeQualified(@TempDir Path root) throws Exception {
        createJavaProject(root);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint baseline = fingerprintService.capture(root);

        Files.writeString(root.resolve("src/main/java/App.java"), "class App { int value = 3; }");
        Files.writeString(root.resolve("README.md"), "changed");
        ProjectInvalidationAssessment assessment = service.assess(
                ready(projectId, "index-1"),
                Optional.of(snapshot(projectId, "index-1", baseline)),
                fingerprintService.capture(root),
                discoveryService.discover(root)
        );

        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, assessment.scope());
        assertEquals(
                List.of(
                        ProjectInvalidationReason.UNQUALIFIED_FILE_CHANGE,
                        ProjectInvalidationReason.SOURCE_OR_TEST_CHANGED
                ),
                assessment.reasons()
        );
        assertEquals(List.of("README.md"), assessment.unqualifiedChangedFiles());
        assertEquals(List.of("src/main/java/App.java"), assessment.changedSourceFiles());
    }

    @Test
    void deletionOfLastSourceFileFallsBackToFullWhenCurrentDiscoveryCannotProveItsRoot(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("src/main/java/Only.java"), "class Only {}");
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint baseline = fingerprintService.capture(root);

        Files.delete(root.resolve("src/main/java/Only.java"));
        ProjectInvalidationAssessment assessment = service.assess(
                ready(projectId, "index-1"),
                Optional.of(snapshot(projectId, "index-1", baseline)),
                fingerprintService.capture(root),
                discoveryService.discover(root)
        );

        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, assessment.scope());
        assertEquals(List.of(ProjectInvalidationReason.UNQUALIFIED_FILE_CHANGE), assessment.reasons());
        assertEquals(List.of("src/main/java/Only.java"), assessment.unqualifiedChangedFiles());
    }

    @Test
    void requiresFullInvalidationWhenBaselineIsMissingOrMisaligned(@TempDir Path root) throws Exception {
        createJavaProject(root);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint current = fingerprintService.capture(root);
        ProjectDiscovery discovery = discoveryService.discover(root);

        ProjectInvalidationAssessment missing = service.assess(
                ready(projectId, "index-1"),
                Optional.empty(),
                current,
                discovery
        );
        ProjectInvalidationAssessment mismatch = service.assess(
                ready(projectId, "index-1"),
                Optional.of(snapshot(projectId, "index-old", current)),
                current,
                discovery
        );
        ProjectInvalidationAssessment firstIndex = service.assess(
                ProjectIndexState.neverIndexed(projectId, Instant.EPOCH),
                Optional.empty(),
                current,
                discovery
        );

        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, missing.scope());
        assertEquals(List.of(ProjectInvalidationReason.MISSING_FINGERPRINT_BASELINE), missing.reasons());
        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, mismatch.scope());
        assertEquals(List.of(ProjectInvalidationReason.BASELINE_INDEX_MISMATCH), mismatch.reasons());
        assertEquals(ProjectInvalidationScope.FULL_REQUIRED, firstIndex.scope());
        assertEquals(List.of(ProjectInvalidationReason.NO_ACTIVE_INDEX), firstIndex.reasons());
    }

    private static ProjectFingerprintSnapshot snapshot(
            UUID projectId,
            String indexSnapshotId,
            ProjectFingerprint fingerprint
    ) {
        return new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
    }

    private static ProjectIndexState ready(UUID projectId, String snapshotId) {
        return new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(snapshotId),
                Optional.empty(),
                Instant.EPOCH,
                Optional.empty()
        );
    }

    private static void createJavaProject(Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
        Files.writeString(root.resolve("pom.xml"), "<project><version>1</version></project>");
        Files.writeString(root.resolve(".minosignore"), "*.tmp\n");
        Files.writeString(root.resolve("src/main/java/App.java"), "class App { int value = 1; }");
        Files.writeString(root.resolve("src/test/java/AppTest.java"), "class AppTest { int value = 1; }");
    }
}

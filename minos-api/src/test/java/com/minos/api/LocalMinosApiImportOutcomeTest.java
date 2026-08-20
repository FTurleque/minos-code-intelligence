package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectOperations;
import com.minos.application.ProjectOperations.IndexImportCommitStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A Java consumer must be able to tell "committed and settled" from "committed but still
 * acknowledging", exactly as the CLI already can.
 *
 * <p>Before {@code importScipOutcome} existed, {@link LocalMinosApi} mapped {@code
 * IndexImportResult} onto {@code IndexImportDto} and dropped both the commit status and the
 * diagnostic on the floor, so all four application states were indistinguishable to an API caller.
 * Every assertion below fails against that mapping.</p>
 */
class LocalMinosApiImportOutcomeTest {

    private static final Path INDEX_FILE = Path.of("index.scip");
    private static final MinosApi.IndexImportRequest REQUEST =
            new MinosApi.IndexImportRequest("scip-typescript", "0.4.0", null, null);

    @TempDir
    Path home;

    @ParameterizedTest
    @EnumSource(IndexImportCommitStatus.class)
    void everyApplicationCommitStatusReachesTheJavaConsumerDistinctly(IndexImportCommitStatus status)
            throws Exception {
        try (LocalMinosApi api = api(new StubProjectOperations(status, "durability acknowledgement pending"))) {
            MinosApi.IndexImportOutcomeDto outcome = api.importScipOutcome("demo", INDEX_FILE, REQUEST);

            assertEquals(status.name(), outcome.commitStatus().name(),
                    "the published status must name the same state the application reported");
            assertEquals("durability acknowledgement pending", outcome.diagnostic());
            assertEquals("snapshot-1", outcome.index().snapshotId());
            assertEquals(7, outcome.index().normalizedSymbolCount());
        }
    }

    @Test
    void pendingAcknowledgementsAreDerivedFromTheStatusNotFromTheDiagnosticText() throws Exception {
        assertPending(IndexImportCommitStatus.COMMITTED, false, false);
        assertPending(IndexImportCommitStatus.COMMITTED_DURABILITY_PENDING, true, false);
        assertPending(IndexImportCommitStatus.COMMITTED_METADATA_PENDING, false, true);
        assertPending(IndexImportCommitStatus.COMMITTED_DURABILITY_AND_METADATA_PENDING, true, true);
    }

    @Test
    void anInternalDiagnosticIsRedactedBeforeItCrossesThePublicBoundary() throws Exception {
        String leaky = "fsync failed for C:\\Users\\minos\\.minos\\snapshots\\snapshot-1.bin";
        try (LocalMinosApi api = api(new StubProjectOperations(
                IndexImportCommitStatus.COMMITTED_DURABILITY_PENDING, leaky))) {
            MinosApi.IndexImportOutcomeDto outcome = api.importScipOutcome("demo", INDEX_FILE, REQUEST);

            assertNotNull(outcome.diagnostic());
            assertFalse(outcome.diagnostic().contains("C:\\Users"), outcome.diagnostic());
            assertFalse(outcome.diagnostic().contains("snapshot-1.bin"), outcome.diagnostic());
            assertEquals("internal diagnostic redacted", outcome.diagnostic());
            assertEquals(MinosApi.ImportCommitStatus.COMMITTED_DURABILITY_PENDING, outcome.commitStatus(),
                    "redacting the diagnostic must not degrade the status itself");
        }
    }

    @Test
    void absenceOfADiagnosticStaysNullInsteadOfBecomingARedactionPlaceholder() throws Exception {
        try (LocalMinosApi api = api(new StubProjectOperations(IndexImportCommitStatus.COMMITTED, null))) {
            assertNull(api.importScipOutcome("demo", INDEX_FILE, REQUEST).diagnostic());
        }
    }

    @Test
    void theHistoricalImportOperationKeepsItsExactContract() throws Exception {
        StubProjectOperations operations = new StubProjectOperations(
                IndexImportCommitStatus.COMMITTED_METADATA_PENDING, "metadata recovery pending");
        try (LocalMinosApi api = api(operations)) {
            MinosApi.IndexImportDto imported = api.importScip("demo", INDEX_FILE, REQUEST);

            assertEquals("snapshot-1", imported.snapshotId());
            assertEquals("scip-typescript", imported.providerId());
            assertEquals(7, imported.normalizedSymbolCount());
            assertEquals(1, operations.calls, "the historical operation must run exactly one import");

            MinosApi.IndexImportOutcomeDto outcome = api.importScipOutcome("demo", INDEX_FILE, REQUEST);
            assertEquals(imported, outcome.index(),
                    "the outcome must carry the historical DTO unchanged, not a re-shaped copy");
            assertEquals(2, operations.calls);
        }
    }

    @Test
    void bothOperationsRejectNullArgumentsAsInvalidRequests() throws Exception {
        try (LocalMinosApi api = api(new StubProjectOperations(IndexImportCommitStatus.COMMITTED, null))) {
            assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, assertThrows(MinosApi.MinosApiException.class,
                    () -> api.importScipOutcome("demo", INDEX_FILE, null)).code());
            assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, assertThrows(MinosApi.MinosApiException.class,
                    () -> api.importScipOutcome("demo", null, REQUEST)).code());
            assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, assertThrows(MinosApi.MinosApiException.class,
                    () -> api.importScip("demo", null, REQUEST)).code());
        }
    }

    /**
     * A contract-v1 implementation that never heard of the new operation still compiles (source
     * compatibility) and answers UNAVAILABLE rather than inventing a settled COMMITTED outcome.
     */
    @Test
    void aContractV1ImplementationStaysCompatibleAndCapabilityHonest() throws Exception {
        MinosApi legacy = new ContractV1MinosApi();

        MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                () -> legacy.importScipOutcome("demo", INDEX_FILE, REQUEST));

        assertEquals(MinosApi.ErrorCode.UNAVAILABLE, failure.code());
        assertEquals("legacy-snapshot", legacy.importScip("demo", INDEX_FILE, REQUEST).snapshotId());
    }

    private void assertPending(IndexImportCommitStatus status, boolean durability, boolean metadata)
            throws Exception {
        try (LocalMinosApi api = api(new StubProjectOperations(status, null))) {
            MinosApi.IndexImportOutcomeDto outcome = api.importScipOutcome("demo", INDEX_FILE, REQUEST);
            assertEquals(durability, outcome.durabilityAcknowledgementPending(), status.name());
            assertEquals(metadata, outcome.metadataRecoveryPending(), status.name());
        }
    }

    private LocalMinosApi api(ProjectOperations operations) throws IOException {
        return new LocalMinosApi(MinosApplication.builder(home).build(), operations);
    }

    private static final class StubProjectOperations implements ProjectOperations {
        private final IndexImportCommitStatus status;
        private final String diagnostic;
        private int calls;

        private StubProjectOperations(IndexImportCommitStatus status, String diagnostic) {
            this.status = status;
            this.diagnostic = diagnostic;
        }

        @Override
        public ProjectView addProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProjectView> listProjects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectView inspectProject(String projectIdentifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IndexImportResult importScip(
                String projectIdentifier,
                Path indexFile,
                String providerId,
                String providerVersion,
                String moduleId,
                String snapshotId
        ) {
            calls++;
            return new IndexImportResult(
                    "11111111-1111-1111-1111-111111111111", "snapshot-1", providerId, providerVersion,
                    7, 5, 3, 1, 0, 0, "2026-08-20T10:15:30Z", status, diagnostic);
        }
    }

    /** Deliberately implements only the contract-v1 abstract methods. */
    private static final class ContractV1MinosApi implements MinosApi {
        @Override
        public ProjectDto addProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProjectDto> listProjects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectDto getProject(String projectIdentifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IndexImportDto importScip(String projectIdentifier, Path indexFile, IndexImportRequest request) {
            return new IndexImportDto("legacy", "legacy-snapshot", request.providerId(), request.providerVersion(),
                    0, 0, 0, 0, 0, 0, "2026-08-20T10:15:30Z");
        }

        @Override
        public List<SymbolDto> findSymbols(String projectIdentifier, SymbolQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UsageDto> findUsages(String projectIdentifier, String symbolId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RelationshipDto> findRelationships(String projectIdentifier, RelationshipQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ArchitectureDto getArchitecture(String projectIdentifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModuleContextDto getModuleContext(String projectIdentifier, String moduleIdentifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ImpactReportDto analyzeImpact(String projectIdentifier, ImpactQuery query) {
            throw new UnsupportedOperationException();
        }
    }
}

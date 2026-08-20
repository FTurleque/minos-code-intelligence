package com.minos.api;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@code null} handed in by a caller is a malformed request, not a MINOS execution defect.
 *
 * <p>{@link MinosApiSupport#execute} maps {@link IllegalArgumentException} to {@link
 * MinosApi.ErrorCode#INVALID_REQUEST} and everything unclassified to {@link
 * MinosApi.ErrorCode#EXECUTION_FAILURE}. Facades that guarded their public parameters with {@code
 * Objects.requireNonNull} therefore published a client mistake as an internal failure. The fix is
 * validation at the public boundary, never a {@code catch (NullPointerException)} inside {@code
 * execute}: that would relabel genuine MINOS defects as caller errors, which is the opposite
 * mistake and a far more expensive one.</p>
 */
class MinosApiNullRequestTaxonomyTest {

    @TempDir
    Path home;

    private MinosApplication application;

    @BeforeEach
    void openApplication() throws Exception {
        application = MinosApplication.builder(home).build();
    }

    @AfterEach
    void closeApplication() throws Exception {
        application.close();
    }

    @Test
    void theCoreFacadeClassifiesEveryNullRequestArgumentAsAnInvalidRequest() {
        MinosApi api = new LocalMinosApi(application);

        assertInvalidRequest(() -> api.addProject(null, "demo"));
        assertInvalidRequest(() -> api.getProject(null));
        assertInvalidRequest(() -> api.importScip("demo", null,
                new MinosApi.IndexImportRequest("scip-typescript", null, null, null)));
        assertInvalidRequest(() -> api.importScip("demo", Path.of("index.scip"), null));
        assertInvalidRequest(() -> api.importScipOutcome("demo", Path.of("index.scip"), null));
        assertInvalidRequest(() -> api.findSymbols("demo", null));
        assertInvalidRequest(() -> api.findUsages("demo", null, 10));
        assertInvalidRequest(() -> api.findRelationships("demo", null));
        assertInvalidRequest(() -> api.getArchitecture(null));
        assertInvalidRequest(() -> api.getArchitectureGraph(null));
        assertInvalidRequest(() -> api.getModuleContext(null, "module"));
        assertInvalidRequest(() -> api.analyzeImpact("demo", null));
    }

    @Test
    void theMultiRepositoryFacadeClassifiesEveryNullRequestArgumentAsAnInvalidRequest() {
        MinosMultiRepositoryApi api = new LocalMinosMultiRepositoryApi(application);

        assertInvalidRequest(() -> api.createWorkspace(null));
        assertInvalidRequest(() -> api.getWorkspace(null));
        assertInvalidRequest(() -> api.assignProjectToWorkspace(null, null));
        assertInvalidRequest(() -> api.inspectGit(null));
        assertInvalidRequest(() -> api.analyzeGitActivity(null, null));
        assertInvalidRequest(() -> api.analyzeWorkspace("workspace", null));
        assertInvalidRequest(() -> api.addProject(null, "demo"));
        assertInvalidRequest(() -> api.importScipOutcome("demo", Path.of("index.scip"), null));
    }

    @Test
    void theSemanticFacadeClassifiesEveryNullRequestArgumentAsAnInvalidRequest() {
        SemanticCodeIntelligenceApi api = new LocalSemanticCodeIntelligenceApi(application);

        assertInvalidRequest(() -> api.getSemanticIndexStatus(null));
        assertInvalidRequest(() -> api.synchronizeSemanticIndex(null));
        assertInvalidRequest(() -> api.semanticSearch("demo", null));
        assertInvalidRequest(() -> api.hybridSearch("demo", null));
        assertInvalidRequest(() -> api.buildHybridContext("demo", null));
    }

    @Test
    void theAdvancedFacadeClassifiesEveryNullRequestArgumentAsAnInvalidRequest() {
        AdvancedCodeIntelligenceApi api = new LocalAdvancedCodeIntelligenceApi(application);

        assertInvalidRequest(() -> api.getProgramGraph("demo", null));
        assertInvalidRequest(() -> api.analyzeImpactV2("demo", null));
        assertInvalidRequest(() -> api.analyzeSecurityPaths("demo", null));
    }

    /**
     * Team mode is disabled here, so an operation that reaches the control plane must answer
     * UNAVAILABLE. A {@code null} request object is rejected before that point: it is malformed
     * whether or not the capability exists.
     */
    @Test
    void theTeamFacadeSeparatesAMalformedRequestFromADisabledCapability() {
        MinosTeamApi api = new LocalMinosTeamApi(application);

        assertInvalidRequest(() -> api.bootstrap(null));
        assertInvalidRequest(() -> api.grantMember("token", "request", null));
        assertInvalidRequest(() -> api.bindProject("token", "request", null));
        assertInvalidRequest(() -> api.setRetention("token", "request", null));

        MinosApi.MinosApiException disabled = assertThrows(MinosApi.MinosApiException.class,
                () -> api.issueToken("token", "request", "principal", Duration.ofHours(1)));
        assertEquals(MinosApi.ErrorCode.UNAVAILABLE, disabled.code());
    }

    @Test
    void publicQueryRecordsRejectNullsWithTheSameIllegalArgumentContractAsTheirSiblings() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinosMultiRepositoryApi.GitActivityQuery(null, 10, 10, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new MinosApi.IndexImportRequest(null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MinosApi.ImpactQuery(null, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MinosApi.IndexImportOutcomeDto(null, MinosApi.ImportCommitStatus.COMMITTED, null));
    }

    /**
     * The taxonomy fix must not have been implemented by swallowing NullPointerException inside
     * {@link MinosApiSupport#execute}: an internal defect has to stay an execution failure.
     */
    @Test
    void anInternalNullPointerExceptionRemainsAnExecutionFailure() {
        MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                () -> MinosApiSupport.execute(() -> {
                    throw new NullPointerException("internal MINOS defect");
                }));

        assertEquals(MinosApi.ErrorCode.EXECUTION_FAILURE, failure.code());
        assertNull(failure.getCause());
    }

    private static void assertInvalidRequest(Executable call) {
        MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class, call);
        assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, failure.code(),
                () -> "a null caller argument must be INVALID_REQUEST, not " + failure.code()
                        + " (" + failure.getMessage() + ")");
    }
}

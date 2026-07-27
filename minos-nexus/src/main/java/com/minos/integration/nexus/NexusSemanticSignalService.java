package com.minos.integration.nexus;

import com.minos.application.MinosApplication;
import com.minos.semantic.HybridSearchService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * M20 NEXUS v2 projection.
 *
 * <p>MINOS supplies bounded code-local candidates and their explicit signals. NEXUS remains
 * responsible for global multi-source ranking, final selection and context budget allocation.</p>
 */
public final class NexusSemanticSignalService {

    public static final int MAX_CANDIDATES = 500;
    public static final String RESPONSIBILITY_BOUNDARY =
            "MINOS_PROVIDES_CODE_LOCAL_SIGNALS_NEXUS_OWNS_GLOBAL_RANKING_SELECTION_AND_MULTI_SOURCE_BUDGET";

    private final MinosApplication application;

    public NexusSemanticSignalService(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    public NexusSemanticSignalContract.Export export(String projectReference, String query, int limit) throws IOException {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (limit < 1 || limit > MAX_CANDIDATES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_CANDIDATES);
        }
        HybridSearchService.HybridResponse response = application.hybridSearchService().search(
                projectReference, new HybridSearchService.HybridRequest(query, limit, 0.0));
        List<NexusSemanticSignalContract.Candidate> candidates = response.hits().stream().map(hit ->
                new NexusSemanticSignalContract.Candidate(
                        hit.document().stableKey(), hit.document().kind().name(), hit.document().sourceId(),
                        hit.document().fileId(), hit.document().startLine(), hit.document().endLine(),
                        hit.score(), hit.rankingMode(), hit.signals().stream().map(signal ->
                                new NexusSemanticSignalContract.Signal(
                                        signal.type(), signal.score(), signal.nature().name())).toList())).toList();
        List<String> limitations = new ArrayList<>(response.limitations());
        limitations.add("NEXUS_GLOBAL_RANKING_NOT_PERFORMED_BY_MINOS");
        limitations.add("NEXUS_MULTI_SOURCE_CONTEXT_BUDGET_NOT_OWNED_BY_MINOS");
        return new NexusSemanticSignalContract.Export(
                NexusSemanticSignalContract.CONTRACT_VERSION,
                NexusSemanticSignalContract.PRODUCER,
                response.projectId(), response.snapshotId(), response.query(), response.semanticAvailable(),
                candidates, List.copyOf(limitations), RESPONSIBILITY_BOUNDARY);
    }
}

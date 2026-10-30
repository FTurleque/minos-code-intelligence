package com.minos.program.analysis;

import com.minos.program.ProgramGraph;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;

/** Extension point for provider-specific advanced program facts. */
public interface ProgramGraphProvider {
    String id();

    /**
     * Cache identity for the provider contribution on one project/snapshot.
     *
     * <p>Static providers can keep the provider id. Providers backed by mutable sidecars or external
     * artifacts must include an artifact fingerprint so ProgramGraphService never serves a stale
     * contribution after those facts change without a structured snapshot promotion.</p>
     */
    default String cacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        return id();
    }

    /**
     * Cache identity together with what it stands for. The service calls this method; the default wraps
     * {@link #cacheKey} as an identity that needs no declaration.
     *
     * <p>A provider that reuses, for a bounded delay, a key obtained by reading mutable project sources must say
     * so: the service then declares the contribution as a bounded-delay view of the disk rather than as the
     * indexed state.</p>
     */
    default CacheKeyResolution resolveCacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot)
            throws IOException {
        return CacheKeyResolution.exact(cacheKey(project, snapshot));
    }

    ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException;

    /**
     * Budget-aware construction entry point. Providers with potentially large fact sets must override
     * this method and stop materialization when the supplied budget is exhausted.
     */
    default ProgramGraph analyze(
            RegisteredProject project,
            CodeKnowledgeSnapshot snapshot,
            ProgramGraphBudget budget
    ) throws IOException {
        return analyze(project, snapshot);
    }

    /**
     * @param key the cache identity of the contribution
     * @param sourceStateReused {@code true} when the key stands for project sources as they were read at most the
     *                          reuse delay ago, not for a state proven equal to the indexed snapshot
     */
    record CacheKeyResolution(String key, boolean sourceStateReused) {
        public static CacheKeyResolution exact(String key) {
            return new CacheKeyResolution(key, false);
        }
    }
}

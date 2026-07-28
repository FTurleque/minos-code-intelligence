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

    ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException;
}

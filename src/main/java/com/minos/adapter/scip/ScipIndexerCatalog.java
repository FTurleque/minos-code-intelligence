package com.minos.adapter.scip;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexerRegistry;

import java.util.List;
import java.util.Set;

/**
 * Descripteurs M1 fondés uniquement sur les capacités réellement qualifiées en M0.
 *
 * <p>Cette classe adapte la connaissance fournisseur vers les contrats
 * d'orchestration MINOS ; aucun type SCIP n'entre dans {@code orchestration}.</p>
 */
public final class ScipIndexerCatalog {

    private ScipIndexerCatalog() {
    }

    public static List<IndexerDescriptor> qualifiedM1Descriptors() {
        return List.of(scipJava(), scipTypeScript());
    }

    public static void registerQualifiedM1(IndexerRegistry registry) {
        registry.registerAll(qualifiedM1Descriptors());
    }

    public static IndexerDescriptor scipJava() {
        return new IndexerDescriptor(
                "scip-java",
                "0.13.1",
                "scip-java",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.IMPLEMENTATION_RELATIONS,
                        IndexerCapability.MULTI_MODULE,
                        IndexerCapability.TEST_SOURCES
                ),
                IndexerQualification.QUALIFIED_WITH_CONSTRAINTS,
                100,
                List.of(
                        "qualified in M0 on Maven projects only",
                        "no final index is published when project compilation fails",
                        "incremental indexing has not been qualified for scip-java 0.13.1",
                        "CALLS relations are not emitted explicitly",
                        "some symbol kinds remain unspecified"
                )
        );
    }

    public static IndexerDescriptor scipTypeScript() {
        return new IndexerDescriptor(
                "scip-typescript",
                "0.4.0",
                "scip-typescript",
                Set.of(Language.TYPESCRIPT),
                Set.of(),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.STRUCTURAL_RELATIONS,
                        IndexerCapability.MULTI_MODULE,
                        IndexerCapability.TEST_SOURCES,
                        IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE
                ),
                IndexerQualification.QUALIFIED_WITH_CONSTRAINTS,
                100,
                List.of(
                        "incremental indexing has not been qualified for scip-typescript 0.4.0",
                        "overloaded declarations can share one provider symbol identity",
                        "symbol kinds are frequently unspecified",
                        "structural relations are incomplete and do not distinguish extends from implements reliably",
                        "CALLS relations are not emitted explicitly"
                )
        );
    }
}

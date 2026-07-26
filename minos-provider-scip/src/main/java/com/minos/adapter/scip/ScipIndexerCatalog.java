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
 * Descripteurs des providers SCIP qualifiés par MINOS.
 *
 * <p>M14 réutilise pour Java la version réellement qualifiée pendant M0 sous
 * Windows. Les limites sémantiques restent conservatrices et l'incrémental
 * n'est toujours pas revendiqué.</p>
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
                        "qualified semantics remain restricted to Maven projects",
                        "Windows execution uses the M0-qualified Maven/javac shims and ScipWriter compatibility patch",
                        "no final index may be promoted when provider execution or project compilation fails",
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

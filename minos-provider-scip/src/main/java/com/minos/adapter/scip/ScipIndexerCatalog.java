package com.minos.adapter.scip;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.CapabilitySupportLevel;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProvider;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.ProviderCapabilityProfile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider catalog for the SCIP adapter family. */
public final class ScipIndexerCatalog {

    public static final String SCIP_PYTHON_VERSION = "0.6.6";

    private ScipIndexerCatalog() {
    }

    /** Historical M1 catalog preserved for compatibility/replay. */
    public static List<IndexerDescriptor> qualifiedM1Descriptors() {
        return List.of(scipJava(), scipTypeScript());
    }

    public static void registerQualifiedM1(IndexerRegistry registry) {
        registry.registerAll(qualifiedM1Descriptors());
    }

    /** M17 provider extensions, including Kotlin through scip-java and managed scip-python. */
    public static List<IndexerProvider> qualifiedM17Providers() {
        return List.of(
                provider(scipJava(), scipJavaProfile()),
                provider(scipTypeScript(), scipTypeScriptProfile()),
                provider(scipPython(), scipPythonProfile())
        );
    }

    public static List<IndexerDescriptor> qualifiedM17Descriptors() {
        return qualifiedM17Providers().stream().map(IndexerProvider::descriptor).toList();
    }

    public static IndexerDescriptor scipJava() {
        return new IndexerDescriptor(
                "scip-java",
                "0.13.1",
                "scip-java",
                Set.of(Language.JAVA, Language.KOTLIN),
                Set.of(BuildSystem.MAVEN),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.STABLE_SYMBOL_IDENTITY,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.IMPLEMENTATION_RELATIONS,
                        IndexerCapability.MULTI_MODULE,
                        IndexerCapability.TEST_SOURCES,
                        IndexerCapability.RUNTIME_INSTALLATION
                ),
                IndexerQualification.QUALIFIED_WITH_CONSTRAINTS,
                100,
                List.of(
                        "qualified runtime execution remains restricted to Maven projects on Windows",
                        "Kotlin support is provided by scip-java and must pass the M17 Kotlin fixture before promotion",
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
                        IndexerCapability.STABLE_SYMBOL_IDENTITY,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.STRUCTURAL_RELATIONS,
                        IndexerCapability.MULTI_MODULE,
                        IndexerCapability.TEST_SOURCES,
                        IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE,
                        IndexerCapability.RUNTIME_INSTALLATION
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

    public static IndexerDescriptor scipPython() {
        return new IndexerDescriptor(
                "scip-python",
                SCIP_PYTHON_VERSION,
                "scip-python",
                Set.of(Language.PYTHON),
                Set.of(),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.TEST_SOURCES,
                        IndexerCapability.RUNTIME_INSTALLATION
                ),
                IndexerQualification.QUALIFIED_WITH_CONSTRAINTS,
                90,
                List.of(
                        "managed runtime requires Node.js and npm plus Python 3.10+ in PATH",
                        "environment/package resolution follows scip-python and may use pip metadata",
                        "implementation, call and incremental relations are not claimed by MINOS M17",
                        "multi-root Python workspace semantics are not claimed"
                )
        );
    }

    public static ProviderCapabilityProfile scipJavaProfile() {
        return profile(scipJava(), Map.ofEntries(
                entry(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STABLE_SYMBOL_IDENTITY, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.UNRESOLVED_REFERENCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.IMPLEMENTATION_RELATIONS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STRUCTURAL_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.MULTI_MODULE, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.TEST_SOURCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.POSITION_UTF16, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.RUNTIME_INSTALLATION, CapabilitySupportLevel.FULL)
        ));
    }

    public static ProviderCapabilityProfile scipTypeScriptProfile() {
        return profile(scipTypeScript(), Map.ofEntries(
                entry(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STABLE_SYMBOL_IDENTITY, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.UNRESOLVED_REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.IMPLEMENTATION_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.STRUCTURAL_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.MULTI_MODULE, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.TEST_SOURCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.POSITION_UTF16, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.RUNTIME_INSTALLATION, CapabilitySupportLevel.FULL)
        ));
    }

    public static ProviderCapabilityProfile scipPythonProfile() {
        return profile(scipPython(), Map.ofEntries(
                entry(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STABLE_SYMBOL_IDENTITY, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.UNRESOLVED_REFERENCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.IMPLEMENTATION_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.STRUCTURAL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.MULTI_MODULE, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.TEST_SOURCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.POSITION_UTF16, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.RUNTIME_INSTALLATION, CapabilitySupportLevel.FULL)
        ));
    }

    private static IndexerProvider provider(IndexerDescriptor descriptor, ProviderCapabilityProfile profile) {
        return new IndexerProvider() {
            @Override public IndexerDescriptor descriptor() { return descriptor; }
            @Override public ProviderCapabilityProfile capabilityProfile() { return profile; }
        };
    }

    private static ProviderCapabilityProfile profile(
            IndexerDescriptor descriptor,
            Map<IndexerCapability, CapabilitySupportLevel> support
    ) {
        EnumMap<IndexerCapability, CapabilitySupportLevel> exhaustive = new EnumMap<>(IndexerCapability.class);
        exhaustive.putAll(support);
        return new ProviderCapabilityProfile(descriptor.id(), exhaustive, descriptor.limitations());
    }

    private static Map.Entry<IndexerCapability, CapabilitySupportLevel> entry(
            IndexerCapability capability,
            CapabilitySupportLevel level
    ) {
        return Map.entry(capability, level);
    }
}

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
import com.minos.orchestration.ProviderOperationalProfile;
import com.minos.orchestration.ProviderPlatform;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider catalog for the SCIP adapter family. */
public final class ScipIndexerCatalog {

    public static final String SCIP_PYTHON_VERSION = "0.6.6";
    public static final String SCIP_CLANG_VERSION = "0.4.0";
    public static final String SCIP_DOTNET_VERSION = "0.2.14";
    public static final String SCIP_GO_VERSION = "0.2.7";
    public static final String RUST_ANALYZER_SCIP_VERSION = "0.3.2913";

    public static final String SCIP_CLANG_ID = "scip-clang";
    public static final String SCIP_DOTNET_ID = "scip-dotnet";
    public static final String SCIP_GO_ID = "scip-go";
    public static final String RUST_ANALYZER_SCIP_ID = "rust-analyzer-scip";

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
                provider(scipJava(), scipJavaProfile(), scipJavaOperationalProfile()),
                provider(scipTypeScript(), scipTypeScriptProfile(), scipTypeScriptOperationalProfile()),
                provider(scipPython(), scipPythonProfile(), scipPythonOperationalProfile())
        );
    }

    public static List<IndexerDescriptor> qualifiedM17Descriptors() {
        return qualifiedM17Providers().stream().map(IndexerProvider::descriptor).toList();
    }

    /**
     * M24 catalog. New polyglot providers deliberately remain EXPERIMENTAL until
     * the exact-head Windows/Linux fixtures promote only the evidence actually observed.
     */
    public static List<IndexerProvider> qualifiedM24Providers() {
        List<IndexerProvider> providers = new ArrayList<>(qualifiedM17Providers());
        providers.add(provider(scipClang(), scipClangProfile(), scipClangOperationalProfile()));
        providers.add(provider(scipDotnet(), scipDotnetProfile(), scipDotnetOperationalProfile()));
        providers.add(provider(scipGo(), scipGoProfile(), scipGoOperationalProfile()));
        providers.add(provider(rustAnalyzerScip(), rustAnalyzerScipProfile(), rustAnalyzerScipOperationalProfile()));
        return List.copyOf(providers);
    }

    public static List<IndexerDescriptor> qualifiedM24Descriptors() {
        return qualifiedM24Providers().stream().map(IndexerProvider::descriptor).toList();
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
                        "Windows execution applies the upstream #211 regexp compatibility workaround required by scip-python 0.6.6",
                        "implementation, call and incremental relations are not claimed by MINOS M17",
                        "multi-root Python workspace semantics are not claimed"
                )
        );
    }

    public static IndexerDescriptor scipClang() {
        return new IndexerDescriptor(
                SCIP_CLANG_ID,
                SCIP_CLANG_VERSION,
                "scip-clang",
                Set.of(Language.C, Language.CPP),
                Set.of(BuildSystem.CMAKE),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.STABLE_SYMBOL_IDENTITY,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.UNRESOLVED_REFERENCES,
                        IndexerCapability.IMPLEMENTATION_RELATIONS,
                        IndexerCapability.STRUCTURAL_RELATIONS,
                        IndexerCapability.TEST_SOURCES,
                        IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE
                ),
                IndexerQualification.EXPERIMENTAL,
                85,
                List.of(
                        "M24 runtime qualification target is Linux x86_64; upstream 0.4.0 publishes no Windows binary",
                        "a JSON compilation database is required and must represent the fixture build accurately",
                        "stable MINOS identities use measured structural fallback, not canonical cross-provider identity",
                        "structural and implementation relationship completeness is provider-dependent",
                        "CALLS and incremental indexing are not claimed by M24",
                        "CFG, def-use, data-flow and security are not implied by this SCIP provider"
                )
        );
    }

    public static IndexerDescriptor scipDotnet() {
        return new IndexerDescriptor(
                SCIP_DOTNET_ID,
                SCIP_DOTNET_VERSION,
                "scip-dotnet",
                Set.of(Language.CSHARP),
                Set.of(BuildSystem.DOTNET),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.STABLE_SYMBOL_IDENTITY,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.UNRESOLVED_REFERENCES,
                        IndexerCapability.IMPLEMENTATION_RELATIONS,
                        IndexerCapability.STRUCTURAL_RELATIONS,
                        IndexerCapability.MULTI_MODULE,
                        IndexerCapability.TEST_SOURCES,
                        IndexerCapability.RUNTIME_INSTALLATION
                ),
                IndexerQualification.EXPERIMENTAL,
                90,
                List.of(
                        "M24 pins scip-dotnet 0.2.14 and requires a compatible local .NET SDK",
                        "managed installation must use a MINOS_HOME/tools tool path and never a global dotnet tool install",
                        "stable MINOS identities use measured structural fallback, not canonical cross-provider identity",
                        "relationship completeness follows Roslyn/scip-dotnet output and is not extrapolated",
                        "CALLS and incremental indexing are not claimed by M24",
                        "CFG, def-use, data-flow and security are not implied by this SCIP provider"
                )
        );
    }

    public static IndexerDescriptor scipGo() {
        return new IndexerDescriptor(
                SCIP_GO_ID,
                SCIP_GO_VERSION,
                "scip-go",
                Set.of(Language.GO),
                Set.of(BuildSystem.GO_MODULE),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.STABLE_SYMBOL_IDENTITY,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.UNRESOLVED_REFERENCES,
                        IndexerCapability.IMPLEMENTATION_RELATIONS,
                        IndexerCapability.STRUCTURAL_RELATIONS,
                        IndexerCapability.TEST_SOURCES,
                        IndexerCapability.RUNTIME_INSTALLATION
                ),
                IndexerQualification.EXPERIMENTAL,
                90,
                List.of(
                        "M24 pins scip-go 0.2.7 and requires a Go toolchain in PATH",
                        "canonical qualification uses go.mod projects; go.work is discovery-only until measured",
                        "managed installation is confined with GOBIN under MINOS_HOME/tools",
                        "stable MINOS identities use measured structural fallback, not canonical cross-provider identity",
                        "CALLS and incremental indexing are not claimed by M24",
                        "CFG, def-use, data-flow and security are not implied by this SCIP provider"
                )
        );
    }

    public static IndexerDescriptor rustAnalyzerScip() {
        return new IndexerDescriptor(
                RUST_ANALYZER_SCIP_ID,
                RUST_ANALYZER_SCIP_VERSION,
                "rust-analyzer scip",
                Set.of(Language.RUST),
                Set.of(BuildSystem.CARGO),
                Set.of(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.STABLE_SYMBOL_IDENTITY,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.UNRESOLVED_REFERENCES,
                        IndexerCapability.IMPLEMENTATION_RELATIONS,
                        IndexerCapability.STRUCTURAL_RELATIONS,
                        IndexerCapability.TEST_SOURCES
                ),
                IndexerQualification.EXPERIMENTAL,
                80,
                List.of(
                        "M24 targets rust-analyzer release 2026-05-25 / v0.3.2913 and requires cargo/rustc",
                        "MINOS does not mutate rustup or install a compiler toolchain implicitly",
                        "the scip-rust wrapper is not treated as a separate semantic engine",
                        "stable MINOS identities use measured structural fallback, not canonical cross-provider identity",
                        "CALLS and incremental indexing are not claimed by M24",
                        "CFG, def-use, data-flow and security are not implied by this SCIP provider"
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

    public static ProviderCapabilityProfile scipClangProfile() {
        return profile(scipClang(), Map.ofEntries(
                entry(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STABLE_SYMBOL_IDENTITY, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.UNRESOLVED_REFERENCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.IMPLEMENTATION_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.STRUCTURAL_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.MULTI_MODULE, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.TEST_SOURCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.POSITION_UTF16, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.RUNTIME_INSTALLATION, CapabilitySupportLevel.UNSUPPORTED)
        ));
    }

    public static ProviderCapabilityProfile scipDotnetProfile() {
        return profile(scipDotnet(), Map.ofEntries(
                entry(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STABLE_SYMBOL_IDENTITY, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.UNRESOLVED_REFERENCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.IMPLEMENTATION_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.STRUCTURAL_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.MULTI_MODULE, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.TEST_SOURCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.POSITION_UTF16, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.RUNTIME_INSTALLATION, CapabilitySupportLevel.FULL)
        ));
    }

    public static ProviderCapabilityProfile scipGoProfile() {
        return profile(scipGo(), Map.ofEntries(
                entry(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STABLE_SYMBOL_IDENTITY, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.UNRESOLVED_REFERENCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.IMPLEMENTATION_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.STRUCTURAL_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.MULTI_MODULE, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.TEST_SOURCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.POSITION_UTF16, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.RUNTIME_INSTALLATION, CapabilitySupportLevel.FULL)
        ));
    }

    public static ProviderCapabilityProfile rustAnalyzerScipProfile() {
        return profile(rustAnalyzerScip(), Map.ofEntries(
                entry(IndexerCapability.SYMBOLS, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.STABLE_SYMBOL_IDENTITY, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.REFERENCES, CapabilitySupportLevel.FULL),
                entry(IndexerCapability.UNRESOLVED_REFERENCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.IMPLEMENTATION_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.STRUCTURAL_RELATIONS, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.MULTI_MODULE, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.TEST_SOURCES, CapabilitySupportLevel.PARTIAL),
                entry(IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED),
                entry(IndexerCapability.POSITION_UTF16, CapabilitySupportLevel.EXPERIMENTAL),
                entry(IndexerCapability.RUNTIME_INSTALLATION, CapabilitySupportLevel.UNSUPPORTED)
        ));
    }

    private static ProviderOperationalProfile scipJavaOperationalProfile() {
        return operational(
                scipJava(),
                Set.of(ProviderPlatform.WINDOWS_X64),
                List.of("Java 24 JDK", "Maven", "PowerShell", "Git Bash", "C# compiler shim on qualified Windows runtime"),
                "READY only after the M17/M22 packaged scip-java runtime and required local tools are present",
                "managed under MINOS_HOME/tools with the historical M17 Windows compatibility runtime",
                "M2/M17 qualified deterministic MINOS identity; provider raw symbols remain preserved",
                "Origin records scip-java/version/index-run and raw SCIP symbols are retained as ProviderReference"
        );
    }

    private static ProviderOperationalProfile scipTypeScriptOperationalProfile() {
        return operational(
                scipTypeScript(),
                Set.of(ProviderPlatform.WINDOWS_X64),
                List.of("Node.js", "npm"),
                "READY only when the pinned managed executable and Node/npm runtime are available",
                "managed npm installation is confined under MINOS_HOME/tools",
                "stable identity is PARTIAL because overloaded provider declarations can collide",
                "Origin records scip-typescript/version/index-run and raw SCIP symbols are retained as ProviderReference"
        );
    }

    private static ProviderOperationalProfile scipPythonOperationalProfile() {
        return operational(
                scipPython(),
                Set.of(ProviderPlatform.WINDOWS_X64),
                List.of("Node.js 16+", "npm", "Python 3.10+", "pip"),
                "READY only when the pinned package, Python runtime and pip are available",
                "managed npm installation is confined under MINOS_HOME/tools; Windows compatibility preload is local",
                "stable identity is PARTIAL and remains structural/provider-scoped fallback",
                "Origin records scip-python/version/index-run and raw SCIP symbols are retained as ProviderReference"
        );
    }

    private static ProviderOperationalProfile scipClangOperationalProfile() {
        return operational(
                scipClang(),
                Set.of(),
                List.of("scip-clang 0.4.0", "Linux x86_64 for M24 runtime qualification", "JSON compilation database"),
                "READY requires the exact scip-clang version and a supported host; Windows is reported unsupported",
                "M24 does not auto-install scip-clang; operator-managed pinned binary is inspected explicitly",
                "repeated fixture indexing must prove deterministic structural MINOS ids without namespace/type collisions",
                "Origin must record scip-clang 0.4.0/index-run and preserve each raw SCIP symbol"
        );
    }

    private static ProviderOperationalProfile scipDotnetOperationalProfile() {
        return operational(
                scipDotnet(),
                Set.of(),
                List.of("compatible .NET SDK", "scip-dotnet 0.2.14"),
                "READY requires the pinned local tool and a compatible dotnet host",
                "dotnet tool installation uses --tool-path under MINOS_HOME/tools and never global installation",
                "repeated fixture indexing must prove deterministic structural MINOS ids across namespace/type/method symbols",
                "Origin must record scip-dotnet 0.2.14/index-run and preserve each raw SCIP symbol"
        );
    }

    private static ProviderOperationalProfile scipGoOperationalProfile() {
        return operational(
                scipGo(),
                Set.of(),
                List.of("Go toolchain", "scip-go 0.2.7", "canonical go.mod project for promotion fixture"),
                "READY requires the pinned local scip-go executable and a Go toolchain",
                "go install is executed with GOBIN confined under MINOS_HOME/tools",
                "repeated fixture indexing must prove deterministic structural MINOS ids across modules/packages/symbols",
                "Origin must record scip-go 0.2.7/index-run and preserve each raw SCIP symbol"
        );
    }

    private static ProviderOperationalProfile rustAnalyzerScipOperationalProfile() {
        return operational(
                rustAnalyzerScip(),
                Set.of(),
                List.of("cargo", "rustc", "rust-analyzer release 2026-05-25 / v0.3.2913"),
                "READY requires cargo/rustc plus the pinned rust-analyzer build exposing the scip subcommand",
                "M24 does not mutate rustup or install a Rust compiler toolchain implicitly",
                "repeated fixture indexing must prove deterministic structural MINOS ids across crate/module/trait symbols",
                "Origin must record rust-analyzer-scip 0.3.2913/index-run and preserve each raw SCIP symbol"
        );
    }

    private static IndexerProvider provider(
            IndexerDescriptor descriptor,
            ProviderCapabilityProfile profile,
            ProviderOperationalProfile operationalProfile
    ) {
        return new IndexerProvider() {
            @Override public IndexerDescriptor descriptor() { return descriptor; }
            @Override public ProviderCapabilityProfile capabilityProfile() { return profile; }
            @Override public ProviderOperationalProfile operationalProfile() { return operationalProfile; }
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

    private static ProviderOperationalProfile operational(
            IndexerDescriptor descriptor,
            Set<ProviderPlatform> qualificationPlatforms,
            List<String> requirements,
            String readiness,
            String installation,
            String stableIdentity,
            String provenance
    ) {
        return new ProviderOperationalProfile(
                descriptor.id(),
                true,
                qualificationPlatforms,
                requirements,
                readiness,
                installation,
                stableIdentity,
                provenance
        );
    }

    private static Map.Entry<IndexerCapability, CapabilitySupportLevel> entry(
            IndexerCapability capability,
            CapabilitySupportLevel level
    ) {
        return Map.entry(capability, level);
    }
}

package com.minos.adapter.scip;

import com.minos.domain.PositionEncoding;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.SymbolInformation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M24PolyglotIdentityProvenanceTest {
    private final ScipSymbolNormalizer normalizer = new ScipSymbolNormalizer();

    @Test
    void keepsIdsStableAcrossRunsAndProvenanceRunSpecificForEveryM24Provider() {
        for (Case value : cases()) {
            ScipSymbolFact fact = fact(value, value.rawSymbol(), value.relativePath());
            Symbol first = normalize(value, fact, "run-a", value.relativePath(), 4);
            Symbol second = normalize(value, fact, "run-b", value.relativePath(), 4);

            assertEquals(SymbolIdentityQuality.STRUCTURAL_FALLBACK, first.identityQuality(), value.providerId());
            assertEquals(first.id(), second.id(), value.providerId());
            assertEquals(first.symbolKey(), second.symbolKey(), value.providerId());
            assertEquals(value.providerId(), first.origin().providerId());
            assertEquals(value.version(), first.origin().providerVersion());
            assertEquals("run-a", first.origin().indexRunId());
            assertEquals("run-b", second.origin().indexRunId());
            assertTrue(first.providerReferences().stream()
                    .anyMatch(reference -> reference.providerId().equals(value.providerId())
                            && reference.externalId().equals(value.rawSymbol())), value.providerId());
        }
    }

    @Test
    void doesNotCollideHomonymousSymbolsAcrossNamespacesPackagesOrModules() {
        for (Case value : cases()) {
            ScipSymbolFact alpha = fact(value,
                    value.rawSymbol().replace("alpha/Greeter#", "alpha/Greeter#"),
                    value.relativePath().replace("main", "alpha"));
            ScipSymbolFact beta = fact(value,
                    value.rawSymbol().replace("alpha/Greeter#", "beta/Greeter#"),
                    value.relativePath().replace("main", "beta"));

            Symbol first = normalize(value, alpha, "run-a", alpha.relativePath(), 4);
            Symbol second = normalize(value, beta, "run-a", beta.relativePath(), 4);
            assertNotEquals(first.id(), second.id(), value.providerId());
            assertNotEquals(first.symbolKey(), second.symbolKey(), value.providerId());
        }
    }

    @Test
    void externalIdentityRemainsProviderScopedInsteadOfInventingLocalCanonicality() {
        Case value = cases().get(2);
        ScipSymbolFact external = new ScipSymbolFact(
                "scip-go gomod stdlib 1.0 fmt/Println().",
                "Println",
                SymbolInformation.Kind.Method,
                "func Println(a ...any) (n int, err error)",
                "",
                "",
                "go",
                true);

        Symbol symbol = normalizer.normalize(
                external,
                "project-m24",
                null,
                null,
                null,
                value.providerId(),
                value.version(),
                "run-external",
                false).orElseThrow();

        assertEquals(SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK, symbol.identityQuality());
        assertTrue(symbol.external());
        assertEquals(value.providerId(), symbol.origin().providerId());
        assertTrue(symbol.providerReferences().stream()
                .anyMatch(reference -> reference.externalId().equals(external.rawSymbol())));
    }

    private Symbol normalize(Case value, ScipSymbolFact fact, String runId, String path, int line) {
        String fileId = "file:" + path;
        return normalizer.normalize(
                fact,
                "project-m24",
                "module-main",
                fileId,
                new SymbolLocation(fileId, line, 0, line, 12, PositionEncoding.UTF16_CODE_UNITS),
                value.providerId(),
                value.version(),
                runId,
                false).orElseThrow();
    }

    private static ScipSymbolFact fact(Case value, String rawSymbol, String relativePath) {
        return new ScipSymbolFact(
                rawSymbol,
                "Greeter",
                SymbolInformation.Kind.Class,
                "Greeter",
                "",
                relativePath,
                value.language(),
                false);
    }

    private static List<Case> cases() {
        return List.of(
                new Case(
                        ScipIndexerCatalog.SCIP_CLANG_ID,
                        ScipIndexerCatalog.SCIP_CLANG_VERSION,
                        "cpp",
                        "scip-clang cmake fixture 1.0 minos/m24/alpha/Greeter#",
                        "src/main.cpp"),
                new Case(
                        ScipIndexerCatalog.SCIP_DOTNET_ID,
                        ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                        "csharp",
                        "scip-dotnet nuget fixture 1.0 Minos/M24/alpha/Greeter#",
                        "src/main.cs"),
                new Case(
                        ScipIndexerCatalog.SCIP_GO_ID,
                        ScipIndexerCatalog.SCIP_GO_VERSION,
                        "go",
                        "scip-go gomod fixture 1.0 example.com/minos/m24go/alpha/Greeter#",
                        "main.go"),
                new Case(
                        ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID,
                        ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                        "rust",
                        "rust-analyzer cargo fixture 1.0 minos_m24/alpha/Greeter#",
                        "src/main.rs"));
    }

    private record Case(String providerId, String version, String language, String rawSymbol, String relativePath) {}
}

package com.minos.api;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A richer facade must never lose a capability of the facade it extends.
 *
 * <p>{@link MinosMultiRepositoryApi} extends {@link MinosApi}, whose optional operations carry a
 * {@code default} that answers {@link MinosApi.ErrorCode#UNAVAILABLE}. Those defaults exist for
 * <em>third-party</em> contract-v1 implementations. When {@link LocalMinosMultiRepositoryApi}
 * inherited one, it silently claimed a capability was unavailable while the very application it
 * wraps -- and the {@link LocalMinosApi} it composes -- implemented it.</p>
 *
 * <p>The structural check below is deliberately not a hand-maintained list of method names: it
 * enumerates {@link MinosApi} reflectively, so a future additive default is covered the day it is
 * added rather than the day somebody remembers to extend a list. That is also why {@link
 * LocalMinosMultiRepositoryApi} forwards even {@code contractVersion()}: with no exemption the rule
 * stays "forward everything", which needs no judgement call and no allowlist.</p>
 */
class LocalMinosMultiRepositoryApiParityTest {

    @Test
    void everyMinosApiOperationIsImplementedInsteadOfInheritingAnUnavailableDefault() {
        List<String> inherited = Arrays.stream(MinosApi.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(LocalMinosMultiRepositoryApiParityTest::resolvesToTheContractDefault)
                .map(Method::toGenericString)
                .sorted()
                .toList();

        assertTrue(inherited.isEmpty(),
                () -> "LocalMinosMultiRepositoryApi still inherits MinosApi contract defaults instead of "
                        + "delegating to the LocalMinosApi it composes: " + inherited);
    }

    @Test
    void theArchitectureGraphCapabilitySurvivesTheMultiRepositoryFacade(@TempDir Path home) throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));

        try (MinosApplication application = MinosApplication.builder(home).build()) {
            MinosMultiRepositoryApi api = new LocalMinosMultiRepositoryApi(application);
            MinosApi single = new LocalMinosApi(application);

            api.addProject(fixture, "parity-typescript");
            api.importScip("parity-typescript", scip,
                    new MinosApi.IndexImportRequest("scip-typescript", "0.4.0", null, null));

            MinosApi.ArchitectureGraphDto graph = api.getArchitectureGraph("parity-typescript");

            assertEquals(3, graph.moduleCount());
            assertTrue(graph.edgeCount() > 0, "the multi-repository facade must expose real graph edges");
            assertEquals(single.getArchitectureGraph("parity-typescript"), graph,
                    "both facades must answer with the same graph, not one of them with UNAVAILABLE");
        }
    }

    @Test
    void theTeamSurfaceIsDelegatedAndKeepsItsOwnFailClosedDecision(@TempDir Path home) throws Exception {
        try (MinosApplication application = MinosApplication.builder(home).build()) {
            MinosMultiRepositoryApi api = new LocalMinosMultiRepositoryApi(application);

            MinosTeamApi team = api.team();

            assertNotNull(team, "team() must return the composed team surface, not fail on the facade default");
            MinosApi.MinosApiException failure = assertThrows(MinosApi.MinosApiException.class,
                    () -> team.tenant("not-a-token"));
            assertEquals(MinosApi.ErrorCode.UNAVAILABLE, failure.code(),
                    "team mode is genuinely disabled here, so the team surface itself must fail closed");
        }
    }

    @Test
    void theDetailedImportOutcomeIsAlsoReachableThroughTheMultiRepositoryFacade(@TempDir Path home)
            throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));

        try (MinosApplication application = MinosApplication.builder(home).build()) {
            MinosMultiRepositoryApi api = new LocalMinosMultiRepositoryApi(application);
            api.addProject(fixture, "parity-outcome");

            MinosApi.IndexImportOutcomeDto outcome = api.importScipOutcome("parity-outcome", scip,
                    new MinosApi.IndexImportRequest("scip-typescript", "0.4.0", null, null));

            assertEquals(MinosApi.ImportCommitStatus.COMMITTED, outcome.commitStatus());
            assertTrue(outcome.index().normalizedSymbolCount() > 0);
        }
    }

    private static boolean resolvesToTheContractDefault(Method declared) {
        try {
            Method resolved = LocalMinosMultiRepositoryApi.class
                    .getMethod(declared.getName(), declared.getParameterTypes());
            return resolved.getDeclaringClass() == MinosApi.class;
        } catch (NoSuchMethodException exception) {
            return fail("MinosApi method is not visible on LocalMinosMultiRepositoryApi: " + declared);
        }
    }
}

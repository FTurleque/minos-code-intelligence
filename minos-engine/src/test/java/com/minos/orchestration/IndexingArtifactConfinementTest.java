package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingResumeTest.Fixture;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Q5 (confinement) : le chemin d'artefact rendu par un exécuteur doit rester dans le répertoire de run
 * {@code runs/<runId>/}, par le chemin physique et non par le texte du chemin. Un artefact qui en sort
 * (par {@code ..}, par un lien symbolique final ou par un répertoire ancêtre lié) est refusé avant tout
 * point de contrôle et toute mise en snapshot ; le run échoue sans rien avoir lu hors de son répertoire.
 */
class IndexingArtifactConfinementTest {

    private static final String PROVIDER = "scip-typescript";
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");
    private static final String OUTSIDE_CONTENT = "scip:a file that is not in the run directory";

    @Test
    void anArtifactReachedThroughParentSegmentsOutsideTheRunDirectoryIsRefused(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        Path outside = outsideArtifact(fixture);
        BiFunction<Path, IndexingExecutionRequest, Path> viaParentSegments = (runDirectory, request) ->
                runDirectory.resolve("..").resolve("..").resolve("outside").resolve(outside.getFileName());

        IndexingRun run = execute(fixture, viaParentSegments);

        assertRefusedWithoutStaging(fixture, run, "lies outside the run directory");
    }

    @Test
    void anArtifactThatIsASymbolicLinkToAFileOutsideTheRunDirectoryIsRefused(@TempDir Path temp) throws Exception {
        assumeTrue(symbolicLinksAvailable(temp), "symbolic links are not available here");
        Fixture fixture = new Fixture(temp);
        Path outside = outsideArtifact(fixture);
        BiFunction<Path, IndexingExecutionRequest, Path> throughALeafLink = (runDirectory, request) ->
                link(runDirectory.resolve("index.scip"), outside);

        IndexingRun run = execute(fixture, throughALeafLink);

        assertRefusedWithoutStaging(fixture, run, "symbolic link");
    }

    @Test
    void anArtifactUnderADirectoryLinkedOutsideTheRunDirectoryIsRefused(@TempDir Path temp) throws Exception {
        assumeTrue(symbolicLinksAvailable(temp), "symbolic links are not available here");
        Fixture fixture = new Fixture(temp);
        Path outside = outsideArtifact(fixture);
        BiFunction<Path, IndexingExecutionRequest, Path> throughAnAncestorLink = (runDirectory, request) ->
                link(runDirectory.resolve("scope"), outside.getParent()).resolve(outside.getFileName());

        IndexingRun run = execute(fixture, throughAnAncestorLink);

        assertRefusedWithoutStaging(fixture, run, "lies outside the run directory");
    }

    @Test
    void anArtifactInsideTheRunDirectoryIsStillAccepted(@TempDir Path temp) throws Exception {
        Fixture fixture = new Fixture(temp);
        BiFunction<Path, IndexingExecutionRequest, Path> inside = (runDirectory, request) -> {
            try {
                Path directory = Files.createDirectories(runDirectory.resolve(PROVIDER).resolve("scope"));
                return Files.writeString(directory.resolve("index.scip"), "scip:inside");
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };

        IndexingRun run = execute(fixture, inside);

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status(), String.valueOf(run.message()));
    }

    @Test
    void anExecutorWithAStoreOfItsOwnIsNotConfinedToTheRunDirectory(@TempDir Path temp) throws Exception {
        // The distributed executor returns an artifact of its verified bundle cache, which is not under runs/<runId>/.
        Fixture fixture = new Fixture(temp);
        Path cached = outsideArtifact(fixture);
        IndexerExecutor storeOwner = new Fixture.Executor(fixture, 99) {
            @Override
            public boolean artifactsLiveInRunDirectory() {
                return false;
            }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) {
                return new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, cached, request.projectRelativeRoot());
            }
        };

        IndexingRun run = fixture.lifecycle(storeOwner, T0).execute(fixture.projectId, fixture.root,
                IndexingResumeTest.negotiation());

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status(), String.valueOf(run.message()));
    }

    private static Path outsideArtifact(Fixture fixture) throws IOException {
        Path directory = Files.createDirectories(fixture.home.resolve("outside"));
        return Files.writeString(directory.resolve("index.scip"), OUTSIDE_CONTENT);
    }

    /** Un seul cible, dont l'exécuteur rend le chemin calculé par {@code artifactFor(répertoire de run, requête)}. */
    private static IndexingRun execute(Fixture fixture,
                                       BiFunction<Path, IndexingExecutionRequest, Path> artifactFor) throws Exception {
        IndexerExecutor executor = new Fixture.Executor(fixture, 99) {
            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) throws IOException {
                // The real marker port creates the run directory when it holds it, before the first provider.
                Path runDirectory = Files.createDirectories(fixture.markers.runDirectory(request.runId()).orElseThrow());
                return new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, artifactFor.apply(runDirectory, request),
                        request.projectRelativeRoot());
            }
        };
        return fixture.lifecycle(executor, T0).execute(fixture.projectId, fixture.root,
                IndexingResumeTest.negotiation());
    }

    private static void assertRefusedWithoutStaging(Fixture fixture, IndexingRun run, String expectedReason) {
        assertEquals(IndexingRun.Status.FAILED, run.status(), "an artifact outside the run directory is refused");
        assertTrue(run.message().orElseThrow().contains(expectedReason), run.message().orElse(""));
        assertTrue(run.executions().isEmpty(), "no checkpoint is taken on a refused artifact");
        assertTrue(fixture.stager.requests.isEmpty(), "nothing outside the run directory is staged");
    }

    /** Crée {@code link} vers {@code target} et le rend : l'exécuteur de test l'annonce comme son artefact. */
    private static Path link(Path link, Path target) {
        try {
            Files.createDirectories(link.getParent());
            return Files.createSymbolicLink(link, target);
        } catch (IOException failure) {
            throw new IllegalStateException("the test could not create its symbolic link", failure);
        }
    }

    /** Sous Windows sans privilège, la création d'un lien échoue : les tests de lien sautent, ils n'échouent pas. */
    private static boolean symbolicLinksAvailable(Path directory) {
        try {
            Path target = Files.createDirectories(directory.resolve("probe-target"));
            Files.createSymbolicLink(directory.resolve("probe-link"), target);
            return true;
        } catch (IOException | UnsupportedOperationException unavailable) {
            return false;
        }
    }
}

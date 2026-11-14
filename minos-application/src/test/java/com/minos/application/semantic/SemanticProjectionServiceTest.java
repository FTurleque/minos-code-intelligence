package com.minos.application.semantic;

import com.minos.application.semantic.SemanticProjectionService.Refusal;
import com.minos.application.semantic.SemanticProjectionService.State;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The projection of a semantic index: prepared by an explicit, bounded and reproducible computation that reads
 * the vectors by pages, bound to the identity of its index, and read back without any computation.
 */
class SemanticProjectionServiceTest {

    private ProjectionFixture fixture;
    private SemanticProjectionService service;
    private String projectId;

    @BeforeEach
    void wire(@TempDir Path temp) {
        fixture = new ProjectionFixture(temp);
        service = fixture.service;
        projectId = fixture.projectId;
    }

    @Test
    void twoPreparationsOfTheSameIndexWithTheSameSeedGiveTheSameCoordinates() throws Exception {
        fixture.index(300);

        service.prepare(projectId, ProjectionFixture.request(120, 7L), SemanticProjectionService.Progress.NONE);
        SemanticProjection first = fixture.projections.read(projectId).orElseThrow();
        service.prepare(projectId, ProjectionFixture.request(120, 7L), SemanticProjectionService.Progress.NONE);
        SemanticProjection second = fixture.projections.read(projectId).orElseThrow();
        service.prepare(projectId, ProjectionFixture.request(120, 8L), SemanticProjectionService.Progress.NONE);
        SemanticProjection otherSeed = fixture.projections.read(projectId).orElseThrow();

        assertEquals(first, second);
        assertEquals(120, first.points().size());
        assertEquals(3, fixture.projections.publications);
        assertNotEquals(keys(first), keys(otherSeed), "another seed draws another sample");
        assertTrue(first.points().stream().anyMatch(point -> Math.abs(point.x()) == 1.0f || Math.abs(point.y()) == 1.0f),
                "coordinates are scaled so that the farthest point touches the unit square");
    }

    @Test
    void aPreparationThatExceedsItsMemoryBudgetFailsAndLeavesThePublishedProjectionIntact() throws Exception {
        fixture.index(400);
        service.prepare(projectId, ProjectionFixture.request(50, 1L), SemanticProjectionService.Progress.NONE);
        SemanticProjection published = fixture.projections.read(projectId).orElseThrow();

        SemanticProjectionService.RefusedException refused = assertThrows(
                SemanticProjectionService.RefusedException.class,
                () -> service.prepare(projectId, new SemanticProjectionService.PreparationRequest(
                        400, 1L, Set.of(SemanticDocumentKind.CHUNK), 16L * 1024L),
                        SemanticProjectionService.Progress.NONE));

        assertEquals(Refusal.MEMORY_BUDGET_EXCEEDED, refused.refusal());
        assertTrue(refused.getMessage().contains("--max-memory-mb"), refused.getMessage());
        assertTrue(refused.getMessage().contains("--max-points"), refused.getMessage());
        assertEquals(published, fixture.projections.read(projectId).orElseThrow());
        assertEquals(1, fixture.projections.publications);
    }

    @Test
    void aPreparationCallsNeitherTheProviderNorTheSearch() throws Exception {
        fixture.index(150);

        service.prepare(projectId, ProjectionFixture.request(150, 42L), SemanticProjectionService.Progress.NONE);

        assertEquals(0, fixture.provider.calls);
        assertEquals(0, fixture.vectors.searches);
        assertEquals(0, fixture.vectors.storedDocuments);
    }

    @Test
    void aPreparationReadsTheVectorsByBoundedPagesAndNeverLoadsTheWholeIndex() throws Exception {
        fixture.index(2_500);
        fixture.vectors.refuseFullLoad = true;
        List<String> phases = new ArrayList<>();

        SemanticProjectionService.PreparationReport report = service.prepare(projectId,
                ProjectionFixture.request(2_500, 42L), (phase, pass, vectorsRead) -> phases.add(phase));

        assertEquals(0, fixture.vectors.fullLoads);
        assertTrue(fixture.vectors.vectorPages >= 3 * report.passes(), "every pass walks the index by pages");
        assertTrue(fixture.vectors.largestPage <= SemanticVectorStore.MAX_VECTOR_PAGE_SIZE);
        assertEquals(2_500, report.pointCount());
        assertEquals(2_500, report.documentCount());
        assertTrue(report.passes() >= 3 && report.passes() <= SemanticProjectionService.MAX_ITERATIONS + 3);
        assertTrue(report.peakWorkingBytes() > 0 && report.peakWorkingBytes() <= 64L * 1024L * 1024L);
        assertTrue(phases.contains("select") && phases.contains("fit") && phases.contains("project"), phases.toString());
    }

    @Test
    void thePrincipalAxisCarriesTheVarianceOfVectorsSpreadAlongOneDirection() throws Exception {
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        for (int number = 0; number < 60; number++) {
            double[] values = new double[ProjectionFixture.DIMENSIONS];
            values[2] = number - 30.0;
            values[5] = (number % 2 == 0 ? 1.0 : -1.0) * 0.5;
            values[0] = 3.0;
            String key = ProjectionFixture.key("chunk", number);
            documents.add(fixture.document(ProjectionFixture.ACTIVE, SemanticDocumentKind.CHUNK, key, "c" + number,
                    SemanticVector.fromArray(key, values)));
        }
        fixture.replace(ProjectionFixture.ACTIVE, documents);

        service.prepare(projectId, ProjectionFixture.request(100, 42L), SemanticProjectionService.Progress.NONE);
        SemanticProjection projection = fixture.projections.read(projectId).orElseThrow();

        assertTrue(projection.explainedVariance().get(0) > 0.99, projection.explainedVariance().toString());
        assertEquals(1.0, projection.explainedVariance().get(0) + projection.explainedVariance().get(1), 1e-9);
        assertFalse(projection.sampled());
        List<SemanticProjection.Point> points = projection.points();
        for (int number = 1; number < points.size(); number++) {
            assertTrue(points.get(number).x() > points.get(number - 1).x(),
                    "the first axis orders the documents as their only spread coordinate does");
        }
        assertEquals(-1.0f, points.getFirst().x(), 1e-6f);
        assertTrue(points.stream().allMatch(point -> Math.abs(point.y()) < 0.05f));
    }

    @Test
    void aSingleDocumentOrIdenticalVectorsAreProjectedOnTheOriginWithoutVariance() throws Exception {
        String key = ProjectionFixture.key("chunk", 0);
        double[] values = new double[ProjectionFixture.DIMENSIONS];
        values[1] = 0.5;
        fixture.replace(ProjectionFixture.ACTIVE, List.of(
                fixture.document(ProjectionFixture.ACTIVE, SemanticDocumentKind.CHUNK, key, "one",
                        SemanticVector.fromArray(key, values)),
                fixture.document(ProjectionFixture.ACTIVE, SemanticDocumentKind.CHUNK, key + "b", "two",
                        SemanticVector.fromArray(key + "b", values))));

        service.prepare(projectId, ProjectionFixture.request(10, 42L), SemanticProjectionService.Progress.NONE);
        SemanticProjection projection = fixture.projections.read(projectId).orElseThrow();

        assertEquals(List.of(0.0, 0.0), projection.explainedVariance());
        assertTrue(projection.points().stream().allMatch(point -> point.x() == 0.0f && point.y() == 0.0f));
    }

    @Test
    void onlyTheDocumentsOfTheSelectedKindsAreProjected() throws Exception {
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        for (int number = 0; number < 20; number++) {
            documents.add(fixture.document(ProjectionFixture.ACTIVE, SemanticDocumentKind.CHUNK,
                    ProjectionFixture.key("chunk", number), "chunk " + number));
            documents.add(fixture.document(ProjectionFixture.ACTIVE, SemanticDocumentKind.SYMBOL,
                    ProjectionFixture.key("symbol", number), "symbol " + number));
        }
        fixture.replace(ProjectionFixture.ACTIVE, documents);

        SemanticProjectionService.PreparationReport report = service.prepare(projectId,
                new SemanticProjectionService.PreparationRequest(100, 42L, Set.of(SemanticDocumentKind.SYMBOL),
                        64L * 1024L * 1024L), SemanticProjectionService.Progress.NONE);
        SemanticProjection projection = fixture.projections.read(projectId).orElseThrow();

        assertEquals(20, report.pointCount());
        assertEquals(40, projection.documentCount());
        assertEquals(20, projection.candidateCount());
        assertTrue(projection.points().stream().allMatch(point -> point.kind() == SemanticDocumentKind.SYMBOL));
    }

    @Test
    void anIndexThatIsNotReadyOrHoldsNoDocumentOfTheKindsIsRefusedAndNothingIsPublished() throws Exception {
        fixture.index(10);
        SemanticProjectionService.RefusedException nothing = assertThrows(
                SemanticProjectionService.RefusedException.class,
                () -> service.prepare(projectId, new SemanticProjectionService.PreparationRequest(10, 42L,
                        Set.of(SemanticDocumentKind.FILE), 64L * 1024L * 1024L),
                        SemanticProjectionService.Progress.NONE));
        fixture.activate("snapshot-2");
        SemanticProjectionService.RefusedException stale = assertThrows(
                SemanticProjectionService.RefusedException.class,
                () -> service.prepare(projectId, ProjectionFixture.request(10, 42L),
                        SemanticProjectionService.Progress.NONE));

        assertEquals(Refusal.NO_DOCUMENT_OF_THE_SELECTED_KINDS, nothing.refusal());
        assertEquals(Refusal.INDEX_NOT_READY, stale.refusal());
        assertTrue(stale.getMessage().contains("minos ide semantic-index-sync"), stale.getMessage());
        assertEquals(0, fixture.projections.publications);
    }

    @Test
    void anIndexReplacedWhileItIsProjectedFailsThePreparationAndPublishesNothing() throws Exception {
        fixture.index(1_200);
        fixture.vectors.afterVectorPage = () -> {
            if (fixture.vectors.vectorPages == 4) fixture.index(ProjectionFixture.ACTIVE, 1_200, "changed ");
        };

        SemanticProjectionService.RefusedException refused = assertThrows(
                SemanticProjectionService.RefusedException.class,
                () -> service.prepare(projectId, ProjectionFixture.request(1_200, 42L),
                        SemanticProjectionService.Progress.NONE));

        assertEquals(Refusal.INDEX_CHANGED_DURING_PREPARATION, refused.refusal());
        assertEquals(0, fixture.projections.publications);
    }

    @Test
    void aStoreThatSortsItsPagesByALinguisticCollationIsProjectedLikeAnyOther() throws Exception {
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        for (int number = 0; number < 1_500; number++) {
            String key = (number % 2 == 0 ? "Chunk:" : "chunk:") + (number % 3 == 0 ? "B_" : "a-") + number;
            documents.add(fixture.document(ProjectionFixture.ACTIVE, SemanticDocumentKind.CHUNK, key, "c" + number));
        }
        fixture.replace(ProjectionFixture.ACTIVE, documents);
        fixture.vectors.collation = String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder());

        SemanticProjectionService.PreparationReport sampled = service.prepare(projectId,
                ProjectionFixture.request(400, 3L), SemanticProjectionService.Progress.NONE);
        SemanticProjection sample = fixture.projections.read(projectId).orElseThrow();
        service.prepare(projectId, ProjectionFixture.request(1_500, 3L), SemanticProjectionService.Progress.NONE);
        SemanticProjection whole = fixture.projections.read(projectId).orElseThrow();

        assertEquals(400, sampled.pointCount());
        assertEquals(keys(sample).stream().sorted().toList(), keys(sample), "published points are in stable-key order");
        assertEquals(1_500, whole.points().size());
        assertEquals(documents.stream().map(value -> value.document().stableKey()).sorted().toList(), keys(whole));
        assertEquals(State.PRETE, service.status(projectId).state());
    }

    @Test
    void theLastPassIsReportedBeforeTheProjectionIsPublished() throws Exception {
        fixture.index(50);
        List<String> events = new ArrayList<>();

        service.prepare(projectId, ProjectionFixture.request(50, 42L), (phase, pass, vectorsRead) -> {
            if (SemanticProjectionService.PUBLISH_PHASE.equals(phase)) {
                events.add(phase + ":" + fixture.projections.publications);
            }
        });

        assertEquals(List.of("publish:0"), events, "the publication is announced once, before it happens");
        assertEquals(1, fixture.projections.publications);
    }

    @Test
    void aSecondPreparationOfTheSameProjectIsRefusedWhileOneIsRunning() {
        fixture.index(10);
        fixture.projections.busy = true;

        SemanticProjectionService.RefusedException refused = assertThrows(
                SemanticProjectionService.RefusedException.class,
                () -> service.prepare(projectId, ProjectionFixture.request(10, 42L),
                        SemanticProjectionService.Progress.NONE));

        assertEquals(Refusal.PREPARATION_IN_PROGRESS, refused.refusal());
    }

    @Test
    void aRequestOutOfBoundsNamesTheBound() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProjectionFixture.request(0, 1L))
                .getMessage().contains("between 1 and " + SemanticProjectionService.MAX_POINTS));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticProjectionService.PreparationRequest(10, 1L, Set.of(), 1L << 20));
        assertThrows(IllegalArgumentException.class, () -> new SemanticProjectionService.PreparationRequest(
                10, 1L, Set.of(SemanticDocumentKind.CHUNK), 0L));
    }

    @Test
    void aProjectionWhoseIndexChangedIsStale() throws Exception {
        fixture.index(40);
        service.prepare(projectId, ProjectionFixture.request(40, 42L), SemanticProjectionService.Progress.NONE);
        SemanticProjectionService.Status ready = service.status(projectId);

        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        for (int number = 0; number < 40; number++) {
            documents.add(fixture.document(ProjectionFixture.ACTIVE, SemanticDocumentKind.CHUNK,
                    ProjectionFixture.key("chunk", number), number == 17 ? "edited content" : "content " + number));
        }
        fixture.replace(ProjectionFixture.ACTIVE, documents);
        SemanticProjectionService.Status stale = service.status(projectId);
        SemanticProjectionService.Points points = service.points(projectId, 0, 10);

        assertEquals(State.PRETE, ready.state());
        assertEquals(State.PERIMEE, stale.state());
        assertEquals(40, stale.projection().orElseThrow().pointCount());
        assertEquals(State.PERIMEE, points.status().state());
        assertEquals(10, points.points().size(), "a stale projection is still shown, with its state");
    }

    @Test
    void aProjectWithoutProjectionIsAbsentNamesTheCommandAndReadsNoVector() throws Exception {
        fixture.index(30);
        fixture.vectors.refuseFullLoad = true;

        SemanticProjectionService.Status status = service.status(projectId);
        SemanticProjectionService.Points points = service.points(projectId, 0, 100);

        assertEquals(State.ABSENTE, status.state());
        assertEquals("minos semantic projection prepare " + projectId, status.prepareCommand());
        assertTrue(status.projection().isEmpty());
        assertEquals(30, status.indexDocumentCount());
        assertEquals(State.ABSENTE, points.status().state());
        assertEquals(List.of(), points.points());
        assertEquals(0, fixture.vectors.vectorReads());
        assertEquals(0, fixture.provider.calls);
    }

    @Test
    void aPreparedProjectionIsReadBackWithoutReadingAnyVector() throws Exception {
        fixture.index(30);
        service.prepare(projectId, ProjectionFixture.request(30, 42L), SemanticProjectionService.Progress.NONE);
        int readsOfThePreparation = fixture.vectors.vectorReads();
        fixture.vectors.refuseFullLoad = true;

        service.status(projectId);
        service.points(projectId, 0, 30);

        assertEquals(readsOfThePreparation, fixture.vectors.vectorReads());
    }

    @Test
    void aSampleOfTwoHundredPointsOutOfAThousandDocumentsIsAnnouncedWithItsSeed() throws Exception {
        fixture.index(1_000);
        service.prepare(projectId, ProjectionFixture.request(200, 4_242L), SemanticProjectionService.Progress.NONE);

        SemanticProjectionService.Points first = service.points(projectId, 0, 150);
        SemanticProjectionService.Points second = service.points(projectId, 150, 150);
        SemanticProjectionService.Description description = first.status().projection().orElseThrow();

        assertEquals(State.PRETE, first.status().state());
        assertEquals(200, description.pointCount());
        assertEquals(1_000, description.documentCount());
        assertEquals(1_000, description.candidateCount());
        assertTrue(description.sampled());
        assertEquals(4_242L, description.seed());
        assertEquals("pca", description.method());
        assertEquals(200, description.maxPoints());
        assertEquals(List.of("CHUNK"), description.kinds());
        assertEquals(projectId, description.index().projectId());
        assertEquals(150, first.points().size());
        assertEquals(50, second.points().size());
        assertEquals(200, first.total());
        List<String> walked = new ArrayList<>();
        first.points().forEach(point -> walked.add(point.stableKey()));
        second.points().forEach(point -> walked.add(point.stableKey()));
        assertEquals(walked.stream().sorted().distinct().toList(), walked, "one index, each point once, in key order");
    }

    @Test
    void aPageOfPointsOutOfBoundsNamesTheBound() throws Exception {
        fixture.index(5);

        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.points(projectId, 0, 0)).getMessage()
                .contains("between 1 and " + SemanticProjectionService.MAX_POINTS_PAGE));
        assertThrows(IllegalArgumentException.class,
                () -> service.points(projectId, 0, SemanticProjectionService.MAX_POINTS_PAGE + 1));
        assertThrows(IllegalArgumentException.class, () -> service.points(projectId, -1, 10));
    }

    @Test
    void anUnreadableProjectionIsAFailedStateThatNamesTheCommandAndShowsNoPoint() throws Exception {
        fixture.index(20);
        service.prepare(projectId, ProjectionFixture.request(20, 42L), SemanticProjectionService.Progress.NONE);
        fixture.projections.unreadable = true;

        SemanticProjectionService.Status status = service.status(projectId);
        SemanticProjectionService.Points points = service.points(projectId, 0, 10);

        assertEquals(State.ECHOUEE, status.state());
        assertEquals("PROJECTION_UNREADABLE", status.cause());
        assertTrue(status.prepareCommand().startsWith("minos semantic projection prepare "));
        assertEquals(List.of(), points.points());
    }

    private static List<String> keys(SemanticProjection projection) {
        return projection.points().stream().map(SemanticProjection.Point::stableKey).toList();
    }
}

package com.minos.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What every {@link IndexStateStore} guarantees about the resume offer of a project (ADR 0039 §2): the run offered for
 * resume is read back as written, by the same store and by a store reopened on the same storage, and a withdrawn offer
 * is read back as absent. Each storage backend runs this contract by implementing this interface in a test class, so
 * that the test class keeps its own superclass.
 */
public interface IndexStateStoreContract {

    Instant UPDATED = Instant.parse("2026-10-26T10:00:00Z");

    /** A store on fresh, empty storage. */
    IndexStateStore newStore() throws Exception;

    /** A new store instance on the storage of the last store returned by {@link #newStore()}. */
    IndexStateStore reopen() throws Exception;

    @Test
    default void aFailedStateThatOffersARunIsReadBackAsWritten() throws Exception {
        IndexStateStore store = newStore();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ProjectIndexState offered = new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(runId), UPDATED, Optional.of("interrupted"), Optional.of(runId));

        store.saveProjectState(offered);

        assertEquals(offered, store.findProjectState(projectId).orElseThrow());
        assertEquals(offered, reopen().findProjectState(projectId).orElseThrow());
    }

    @Test
    default void aStateWithoutOfferIsReadBackWithoutOffer() throws Exception {
        IndexStateStore store = newStore();
        UUID projectId = UUID.randomUUID();
        ProjectIndexState plain = new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(UUID.randomUUID()), UPDATED, Optional.of("failed"));

        store.saveProjectState(plain);

        assertEquals(Optional.empty(), reopen().findProjectState(projectId).orElseThrow().resumableRunId());
    }

    @Test
    default void aWithdrawnOfferIsReadBackAsAbsent() throws Exception {
        IndexStateStore store = newStore();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(runId), UPDATED, Optional.of("interrupted"), Optional.of(runId)));

        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(runId), UPDATED.plusSeconds(1), Optional.of("restarted")));

        assertEquals(Optional.empty(), store.findProjectState(projectId).orElseThrow().resumableRunId());
        assertEquals(Optional.empty(), reopen().findProjectState(projectId).orElseThrow().resumableRunId());
    }

    @Test
    default void aStaleStateThatOffersARunIsReadBackAsWritten() throws Exception {
        IndexStateStore store = newStore();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ProjectIndexState offered = new ProjectIndexState(projectId, ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-previous"), Optional.of(runId), UPDATED, Optional.of("interrupted"),
                Optional.of(runId));

        store.saveProjectState(offered);

        assertEquals(offered, reopen().findProjectState(projectId).orElseThrow());
    }
}

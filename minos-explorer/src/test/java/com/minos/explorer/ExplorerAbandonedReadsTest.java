package com.minos.explorer;

import com.minos.application.diagnostics.InternalFailureReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request abandoned at its deadline gives its slot back even when the read ignores its interruption, and the reads
 * that stay stuck are counted, bounded and said. The reads here block on a latch that they keep waiting on after an
 * interruption, as a read stuck on a lock or on computation would.
 */
class ExplorerAbandonedReadsTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger serviceCalls = new AtomicInteger();
    private ExplorerServer server;
    private Map<String, String> bearer;

    @BeforeEach
    void start() throws IOException {
        server = ExplorerServer.start((method, segments, parameters, body, failures) -> {
            serviceCalls.incrementAndGet();
            if (segments.contains("stuck")) awaitIgnoringInterruption();
            return ExplorerApi.Answer.ok(Map.of("ok", true));
        }, 0, ListeningPolicy.Scope.LOOPBACK, Duration.ofMillis(250), log::add,
                InternalFailureReporter.withoutJournal("explorer"), System::nanoTime);
        String url = server.accessUrl();
        bearer = Map.of("Authorization", "Bearer " + url.substring(url.indexOf("#token=") + 7));
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.close();
    }

    @Test
    void readsThatIgnoreTheirInterruptionGiveTheirSlotsBackAtTheDeadline() throws Exception {
        List<Integer> statuses = stuckRequests(ExplorerServer.MAX_CONCURRENT_API_CALLS);

        assertEquals(java.util.Collections.nCopies(ExplorerServer.MAX_CONCURRENT_API_CALLS, 503), statuses);
        assertEquals(ExplorerServer.MAX_CONCURRENT_API_CALLS, server.abandonedReads());
        RawHttp.Response normal = RawHttp.get(port(), "/api/v1/projects", bearer);
        assertEquals(200, normal.status(), normal.body());
    }

    @Test
    void atTheBoundANewRequestIsRefusedAsBusyWithoutACallAndAReturnedReadFreesAPlace() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int batch = 0; batch < ExplorerServer.MAX_ABANDONED_READS / ExplorerServer.MAX_CONCURRENT_API_CALLS;
             batch++) {
            statuses.addAll(stuckRequests(ExplorerServer.MAX_CONCURRENT_API_CALLS));
        }
        assertTrue(statuses.stream().allMatch(status -> status == 503), statuses.toString());
        assertEquals(ExplorerServer.MAX_ABANDONED_READS, server.abandonedReads());
        int calledBefore = serviceCalls.get();

        RawHttp.Response busy = RawHttp.get(port(), "/api/v1/projects", bearer);

        assertEquals(503, busy.status());
        assertTrue(busy.body().contains("\"code\":\"BUSY\""), busy.body());
        assertEquals(calledBefore, serviceCalls.get(), "no service is called at the bound");
        assertTrue(log.contains("abandoned reads still running: " + ExplorerServer.MAX_ABANDONED_READS + " (limit "
                + ExplorerServer.MAX_ABANDONED_READS + ")"), log.toString());

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (server.abandonedReads() > 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(0, server.abandonedReads());
        assertEquals(200, RawHttp.get(port(), "/api/v1/projects", bearer).status());
    }

    @Test
    void stoppingTheSurfaceDoesNotWaitForTheAbandonedReads() throws Exception {
        stuckRequests(ExplorerServer.MAX_CONCURRENT_API_CALLS);
        assertEquals(ExplorerServer.MAX_CONCURRENT_API_CALLS, server.abandonedReads());

        assertTimeout(Duration.ofSeconds(5), server::close);
    }

    /** Sends {@code count} requests that block on the latch, together; returns their statuses once all answered. */
    private List<Integer> stuckRequests(int count) throws Exception {
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        List<Thread> senders = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Thread sender = new Thread(() -> {
                try {
                    statuses.add(RawHttp.get(port(), "/api/v1/projects/stuck/documents", bearer).status());
                } catch (IOException failure) {
                    statuses.add(-1);
                }
            });
            sender.start();
            senders.add(sender);
        }
        for (Thread sender : senders) sender.join(20_000);
        return statuses;
    }

    private int port() {
        return server.port();
    }

    private void awaitIgnoringInterruption() {
        while (true) {
            try {
                release.await();
                return;
            } catch (InterruptedException ignored) {
                // A read stuck on a lock does not honour its interruption: keep waiting.
            }
        }
    }
}

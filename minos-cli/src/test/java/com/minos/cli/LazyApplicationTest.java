package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.bootstrap.CloseCountingStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Le point de construction différée du câblage : ouvre une fois, ne ferme que ce qu'il a ouvert, propage les échecs tels quels. */
class LazyApplicationTest {

    /** Un contrat de service dont une méthode déclare {@link IOException} et l'autre non. */
    interface Probe {
        String read() throws IOException;

        String name();
    }

    @Test
    void anApplicationThatIsNeverUsedIsNeverOpenedNorClosed(@TempDir Path home) throws Exception {
        AtomicInteger opens = new AtomicInteger();
        try (LazyApplication application = LazyApplication.opening(home, () -> {
            opens.incrementAndGet();
            return MinosApplication.open(home);
        })) {
            application.deferred(MinosApplication::gitIntelligence);
        }
        assertEquals(0, opens.get());
    }

    @Test
    void concurrentFirstUsesOpenTheApplicationExactlyOnce(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select();
             LazyApplication application = LazyApplication.opening(home, () -> MinosApplication.open(home))) {
            int callers = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(callers);
            try {
                List<Future<MinosApplication>> results = new ArrayList<>();
                for (int index = 0; index < callers; index++) {
                    results.add(executor.submit(() -> {
                        start.await();
                        return application.get();
                    }));
                }
                start.countDown();
                MinosApplication first = results.getFirst().get();
                for (Future<MinosApplication> result : results) assertSame(first, result.get());
            } finally {
                executor.shutdownNow();
            }
            assertEquals(1, CloseCountingStorageProvider.opens(), "one application, not one per caller");
        }
        assertEquals(1, CloseCountingStorageProvider.closes(), "closed exactly once, by the owner");
    }

    @Test
    void anApplicationHandedInByItsOpenerIsNeverClosedHere(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            MinosApplication opened = MinosApplication.open(home);
            LazyApplication.of(opened).close();
            assertEquals(0, CloseCountingStorageProvider.closes());
            opened.close();
        }
    }

    @Test
    void closingTwiceClosesTheStorageOnce(@TempDir Path home) throws Exception {
        try (CloseCountingStorageProvider.Selection selection = CloseCountingStorageProvider.select()) {
            LazyApplication application = LazyApplication.opening(home, () -> MinosApplication.open(home));
            application.get();
            application.close();
            application.close();
            assertEquals(1, CloseCountingStorageProvider.closes());
        }
    }

    @Test
    void aClosedApplicationIsNotReopened(@TempDir Path home) throws Exception {
        AtomicInteger opens = new AtomicInteger();
        LazyApplication application = LazyApplication.opening(home, () -> {
            opens.incrementAndGet();
            return MinosApplication.open(home);
        });
        application.close();
        assertThrows(IllegalStateException.class, application::get);
        assertEquals(0, opens.get());
    }

    @Test
    void aServiceIsBuiltOnItsFirstCallAndOnlyOnce(@TempDir Path home) throws Exception {
        AtomicInteger builds = new AtomicInteger();
        Probe real = new Probe() {
            @Override public String read() { return "data"; }
            @Override public String name() { return "probe"; }
        };
        try (LazyApplication application = LazyApplication.opening(home, () -> MinosApplication.open(home))) {
            Probe handle = LazyApplication.lazy(Probe.class, application.deferred(opened -> {
                builds.incrementAndGet();
                return real;
            }));
            assertEquals(0, builds.get(), "handing the handle out builds nothing");
            assertEquals("Probe (deferred)", handle.toString(), "Object methods do not build the service either");
            assertEquals(0, builds.get());

            assertEquals("data", handle.read());
            assertEquals("probe", handle.name());
            assertEquals(1, builds.get());
        }
    }

    @Test
    void theExceptionsOfAServiceCrossTheHandleAsTheyAre(@TempDir Path home) throws Exception {
        try (LazyApplication application = LazyApplication.opening(home, () -> MinosApplication.open(home))) {
            Probe failing = LazyApplication.lazy(Probe.class, () -> new Probe() {
                @Override public String read() throws IOException { throw new IOException("disk"); }
                @Override public String name() { throw new IllegalStateException("broken"); }
            });
            assertEquals("disk", assertThrows(IOException.class, failing::read).getMessage());
            assertEquals("broken", assertThrows(IllegalStateException.class, failing::name).getMessage());

            // A factory that fails with an IOException surfaces as IOException where the contract declares it.
            Probe unbuildable = LazyApplication.lazy(Probe.class, application.deferred(opened -> {
                throw new IOException("cannot build");
            }));
            assertEquals("cannot build", assertThrows(IOException.class, unbuildable::read).getMessage());
            assertEquals("cannot build", assertThrows(UncheckedIOException.class, unbuildable::name).getCause().getMessage());
        }
    }

    @Test
    void anOpenFailureCarriesTheOriginalIOException(@TempDir Path home) {
        IOException refused = new IOException("home refused");
        LazyApplication application = LazyApplication.opening(home, () -> {
            throw refused;
        });
        LazyApplication.OpenFailure failure = assertThrows(LazyApplication.OpenFailure.class, application::get);
        assertSame(refused, failure.failure());
        assertEquals("home refused", failure.getMessage());
        Probe handle = LazyApplication.lazy(Probe.class, application.deferred(opened -> null));
        assertThrows(LazyApplication.OpenFailure.class, handle::read);
        assertSame(refused, assertThrows(IOException.class, failure::rethrow));
    }

    @Test
    void anUncheckedOpenFailureIsRelayedThenRethrownWithItsOwnType(@TempDir Path home) {
        IllegalArgumentException invalid = new IllegalArgumentException("dimensions must be between 32 and 16384");
        LazyApplication application = LazyApplication.opening(home, () -> {
            throw invalid;
        });
        LazyApplication.OpenFailure failure = assertThrows(LazyApplication.OpenFailure.class, application::get);
        assertSame(invalid, failure.failure());
        assertSame(invalid, assertThrows(IllegalArgumentException.class, failure::rethrow));
    }
}

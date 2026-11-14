package com.minos.explorer;

import com.minos.application.MinosApplication;
import com.minos.testsupport.DerivedTenantKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One run of the command: where the surface refuses to start (team mode, container listening outside a
 * container, a port it cannot bind), what it prints, and that it stops when asked.
 */
class ExplorerSessionTest {

    private static final Pattern URL = Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)/#token=([0-9a-f]{64})");

    @Test
    void theSurfaceServesUntilItIsAskedToStopAndPrintsItsLoopbackAddress(@TempDir Path temp) throws Exception {
        try (IndexedHome fixture = IndexedHome.create(temp)) {
            Output error = new Output();
            CountDownLatch stop = new CountDownLatch(1);
            AtomicInteger exit = new AtomicInteger(-1);
            Thread session = new Thread(() -> exit.set(
                    ExplorerSession.serve(fixture.application, 0, Map.of(), error, stop::await)));
            session.start();
            Matcher url = error.awaitUrl();

            int port = Integer.parseInt(url.group(1));
            RawHttp.Response projects = RawHttp.get(port, "/api/v1/projects",
                    Map.of("Authorization", "Bearer " + url.group(2)));
            stop.countDown();
            session.join(10_000);

            assertEquals(200, projects.status());
            assertEquals(ExplorerSession.SUCCESS, exit.get());
            assertTrue(error.toString().contains("loopback only"), error.toString());
            assertTrue(error.toString().contains("MINOS explorer stopped."), error.toString());
            assertFalse(error.toString().contains(fixture.home.toString()), "MINOS_HOME on the error output");
            assertThrows(java.io.IOException.class, () -> RawHttp.get(port, "/", Map.of()));
        }
    }

    @Test
    void theSurfaceRefusesToStartWhenTheTeamModeIsEnabledAndOpensNoPort(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        try (MinosApplication team = MinosApplication.builder(home)
                .hostedTenantKeyProvider(DerivedTenantKeys.provider()).build()) {
            StringBuilder error = new StringBuilder();
            AtomicInteger waits = new AtomicInteger();

            int exit = ExplorerSession.serve(team, 0, Map.of(), error, waits::incrementAndGet);

            assertEquals(ExplorerSession.USAGE_ERROR, exit);
            assertTrue(error.toString().startsWith("error: minos explorer serve is not available when the team mode"),
                    error.toString());
            assertTrue(error.toString().contains("MINOS_HOSTED_MODE"), error.toString());
            assertFalse(error.toString().contains("http://"), "no address is printed, nothing listens");
            assertEquals(0, waits.get(), "the session never started");
        }
    }

    @Test
    void containerListeningIsRefusedOutsideAContainerAndOpensNoPort(@TempDir Path temp) throws Exception {
        try (IndexedHome fixture = IndexedHome.create(temp)) {
            for (Map<String, String> environment : List.of(
                    Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "enabled"),
                    Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "enabled", ListeningPolicy.RUNTIME_LOCATION_ENV, "native"),
                    Map.of(ListeningPolicy.CONTAINER_LISTEN_ENV, "0.0.0.0", ListeningPolicy.RUNTIME_LOCATION_ENV, "docker"))) {
                StringBuilder error = new StringBuilder();
                AtomicInteger waits = new AtomicInteger();

                int exit = ExplorerSession.serve(fixture.application, 0, environment, error, waits::incrementAndGet);

                assertEquals(ExplorerSession.USAGE_ERROR, exit, environment.toString());
                assertTrue(error.toString().contains(ListeningPolicy.CONTAINER_LISTEN_ENV), error.toString());
                assertFalse(error.toString().contains("http://"), "no address is printed, nothing listens");
                assertEquals(0, waits.get());
            }
        }
    }

    @Test
    void aPortOutOfRangeOrAlreadyTakenIsAnErrorThatNamesTheOption(@TempDir Path temp) throws Exception {
        try (IndexedHome fixture = IndexedHome.create(temp);
             ServerSocket taken = new ServerSocket(0, 1, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            StringBuilder outOfRange = new StringBuilder();
            StringBuilder busy = new StringBuilder();

            int usage = ExplorerSession.serve(fixture.application, 70_000, Map.of(), outOfRange, () -> { });
            int execution = ExplorerSession.serve(fixture.application, taken.getLocalPort(), Map.of(), busy, () -> { });

            assertEquals(ExplorerSession.USAGE_ERROR, usage);
            assertTrue(outOfRange.toString().contains("--port must be between 0 and 65535"));
            assertEquals(ExplorerSession.EXECUTION_ERROR, execution);
            assertTrue(busy.toString().contains("--port"), busy.toString());
            assertFalse(busy.toString().contains("http://"));
        }
    }

    /** Collects what the session prints and releases {@link #addressPrinted} once the access URL is there. */
    private static final class Output implements Appendable {
        private final StringBuffer text = new StringBuffer();
        private final CountDownLatch addressPrinted = new CountDownLatch(1);

        @Override
        public Appendable append(CharSequence characters) {
            text.append(characters);
            if (URL.matcher(text).find()) addressPrinted.countDown();
            return this;
        }

        @Override
        public Appendable append(CharSequence characters, int start, int end) {
            return append(characters.subSequence(start, end));
        }

        @Override
        public Appendable append(char character) {
            return append(String.valueOf(character));
        }

        Matcher awaitUrl() throws InterruptedException {
            if (!addressPrinted.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("no access URL printed: " + text);
            }
            Matcher matcher = URL.matcher(text);
            matcher.find();
            return matcher;
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}

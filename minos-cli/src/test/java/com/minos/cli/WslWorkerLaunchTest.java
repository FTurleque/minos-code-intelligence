package com.minos.cli;

import com.minos.application.WslIndexingWorker;
import com.minos.cli.command.FindSymbolCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal worker command of the WSL2 route: reserved, absent from help, and it opens no application. */
class WslWorkerLaunchTest {

    @TempDir Path home;

    @Test
    void theCommandIsRecognisedOnlyWithItsGroupAndIsAbsentFromHelp() {
        assertTrue(WslWorkerLaunch.isWorkerCommand(new String[]{"internal", "wsl-worker", "request.properties"}));
        assertFalse(WslWorkerLaunch.isWorkerCommand(new String[]{"internal"}));
        assertFalse(WslWorkerLaunch.isWorkerCommand(new String[]{"wsl-worker", "internal"}));
        assertFalse(MinosCli.usage().contains("wsl-worker"));
        assertFalse(MinosCli.usage().contains("internal"));
    }

    @Test
    void theWorkerRunsTheRequestAndPrintsItsStatusLine() throws Exception {
        List<Path> requests = new ArrayList<>();
        WslIndexingWorker worker = new RecordingWorker(requests);
        Streams streams = new Streams();

        int code = WslWorkerLaunch.run(new String[]{"internal", "wsl-worker", "request.properties"},
                composer(Optional.of(worker)), home, streams.out, streams.err);

        assertEquals(0, code);
        assertEquals(List.of(Path.of("request.properties")), requests);
        assertEquals("minos-wsl-worker-v1 OK\n", streams.out());
    }

    @Test
    void theProbeOptionRunsTheProbeModeAndReadsNoRequest() throws Exception {
        List<Path> requests = new ArrayList<>();
        Streams streams = new Streams();

        int code = WslWorkerLaunch.run(new String[]{"internal", "wsl-worker", "--probe"},
                composer(Optional.of(new RecordingWorker(requests))), home, streams.out, streams.err);

        assertEquals(0, code);
        assertEquals(List.of(), requests, "the probe mode reads no request file");
        assertEquals("minos-wsl-probe-v1 maven OK\n", streams.out());
        assertEquals("", streams.err());

        Streams unavailable = new Streams();
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, WslWorkerLaunch.run(
                new String[]{"internal", "wsl-worker", "--probe"}, composer(Optional.empty()), home,
                unavailable.out, unavailable.err));
        assertTrue(unavailable.err().contains("only inside a Linux distribution"), unavailable.err());
    }

    @Test
    void aMissingRequestIsAUsageErrorAndAHostWithoutWorkerRefuses() throws Exception {
        Streams usage = new Streams();
        assertEquals(FindSymbolCommand.USAGE_ERROR, WslWorkerLaunch.run(new String[]{"internal", "wsl-worker"},
                composer(Optional.empty()), home, usage.out, usage.err));
        assertTrue(usage.err().contains("exactly one request file or --probe"), usage.err());

        Streams unavailable = new Streams();
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, WslWorkerLaunch.run(
                new String[]{"internal", "wsl-worker", "request.properties"},
                composer(Optional.empty()), home, unavailable.out, unavailable.err));
        assertTrue(unavailable.err().contains("only inside a Linux distribution"), unavailable.err());
        assertFalse(unavailable.err().contains(home.toString()), unavailable.err());
    }

    /** A worker that records its requests and answers OK, and whose probe reports Maven ready. */
    private record RecordingWorker(List<Path> requests) implements WslIndexingWorker {
        @Override
        public int run(Path request, Appendable output) throws IOException {
            requests.add(request);
            output.append("minos-wsl-worker-v1 OK\n");
            return 0;
        }

        @Override
        public int probe(Appendable output) throws IOException {
            output.append("minos-wsl-probe-v1 maven OK\n");
            return 0;
        }
    }

    private static Function<Path, Optional<WslIndexingWorker>> composer(Optional<WslIndexingWorker> worker) {
        return home -> worker;
    }

    private static final class Streams {
        private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        String out() {
            return outBytes.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return errBytes.toString(StandardCharsets.UTF_8);
        }
    }
}

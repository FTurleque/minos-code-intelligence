package com.minos.explorer;

import com.minos.application.diagnostics.InternalFailureReporter;
import com.minos.diagnostics.InternalFailureTrace;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * The one writer of the diagnostic lines of the exploration surface: an internal failure (with its origin and a
 * reference, and the stack in the private journal when it is on) and the events that a hostile page or a stuck read
 * can repeat (refusals, abandoned reads), written at most once per reason and per window with the number that were
 * counted since the previous line. No line carries an address, a parameter, a header or the session token.
 */
final class ExplorerDiagnostics {

    /** The window of a repeated event: the first one is written at once, the next ones are counted. */
    static final Duration WINDOW = Duration.ofSeconds(60);

    private final Consumer<String> log;
    private final InternalFailureReporter reporter;
    private final LongSupplier nanos;
    private static final String EXCHANGE_KEY = "exchange";

    private final Map<String, Limit> limits = new HashMap<>();

    /** What is known of one repeated event: when its line was last written and how many were counted since. */
    private static final class Limit {
        private long lastLineNanos;
        private long counted;
    }

    ExplorerDiagnostics(Consumer<String> log, InternalFailureReporter reporter, LongSupplier nanos) {
        this.log = Objects.requireNonNull(log, "log");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    /**
     * Writes the line of an internal failure of the route named {@code route} (a fixed label, never the address) and
     * offers its stack to the private journal.
     *
     * @return the reference that the answer carries
     */
    String internalFailure(String route, Throwable failure) {
        InternalFailureTrace trace = reporter.report(route, failure);
        log.accept(trace.line("request failed", "route", route));
        return trace.reference();
    }

    /**
     * A failure of the exchange itself (a connection cut while the answer is written, an unexpected exception outside
     * the API): the first of a window is written as an internal failure, with its reference and private entry; the
     * next ones are counted and summarised by the first line of the next window, so that a client that opens and cuts
     * connections cannot make the journal turn.
     */
    void exchangeFailure(Throwable failure) {
        long skipped;
        synchronized (this) {
            long now = nanos.getAsLong();
            Limit limit = limits.get(EXCHANGE_KEY);
            if (limit == null) {
                limit = new Limit();
                limits.put(EXCHANGE_KEY, limit);
                limit.lastLineNanos = now;
                skipped = 0;
            } else if (now - limit.lastLineNanos >= WINDOW.toNanos()) {
                skipped = limit.counted;
                limit.counted = 0;
                limit.lastLineNanos = now;
            } else {
                limit.counted++;
                return;
            }
        }
        if (skipped > 0) log.accept("request failures outside a route: " + skipped + " since the previous line");
        internalFailure("serve", failure);
    }

    /** A request refused for {@code reason} (host, origin or token): the first of a window is written at once. */
    void refusal(String reason) {
        repeated("refused:" + reason, "refused request: " + reason,
                counted -> "refused requests: " + reason + " (" + counted + " since the previous line)");
    }

    /** A request refused as busy because {@code running} abandoned reads, of {@code limit}, have not returned. */
    void abandonedReads(int running, int limit) {
        String line = "abandoned reads still running: " + running + " (limit " + limit + ")";
        repeated("abandoned-reads", line, counted -> line + "; " + counted + " requests refused since the previous line");
    }

    private synchronized void repeated(String key, String firstLine, LongFunction<String> summary) {
        long now = nanos.getAsLong();
        Limit limit = limits.get(key);
        if (limit == null) {
            limit = new Limit();
            limits.put(key, limit);
            limit.lastLineNanos = now;
            log.accept(firstLine);
            return;
        }
        limit.counted++;
        if (now - limit.lastLineNanos < WINDOW.toNanos()) return;
        long counted = limit.counted;
        limit.counted = 0;
        limit.lastLineNanos = now;
        log.accept(counted == 1 ? firstLine : summary.apply(counted));
    }
}

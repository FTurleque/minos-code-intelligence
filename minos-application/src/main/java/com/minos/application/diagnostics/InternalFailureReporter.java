package com.minos.application.diagnostics;

import com.minos.diagnostics.InternalFailureTrace;
import com.minos.io.InternalFailureJournal;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * What a surface calls when it turns an internal failure into an opaque answer: it describes the failure (reference,
 * origin, stderr line) and hands the entry to the private diagnostic journal, which writes only when it is switched on
 * and available. The journal type of the engine never appears in this API, so a surface names this facade and the
 * pure {@link InternalFailureTrace} and nothing of {@code com.minos.io}.
 *
 * <p>Reporting never throws and never changes the answer of the surface: whatever happens to the journal, the caller
 * receives the trace it logs and the reference it appends to its error.</p>
 */
public final class InternalFailureReporter {

    private final String surface;
    private final InternalFailureJournal journal;

    /** A reporter of {@code surface} whose journal lives under {@code home} and follows the given settings. */
    public InternalFailureReporter(String surface, Path home, Map<String, String> environment,
                                   Properties properties) {
        this(surface, new InternalFailureJournal(home, environment, properties));
    }

    InternalFailureReporter(String surface, InternalFailureJournal journal) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /** A reporter without a journal: stderr line and reference only. */
    public static InternalFailureReporter withoutJournal(String surface) {
        return new InternalFailureReporter(surface, InternalFailureJournal.inactive());
    }

    /**
     * Describes {@code failure} for {@code operation} and offers it to the journal.
     *
     * @return the trace: its line goes to the surface's log and its reference to the answer
     */
    public InternalFailureTrace report(String operation, Throwable failure) {
        InternalFailureTrace trace = InternalFailureTrace.describe(surface, operation, failure);
        journal.append(trace);
        return trace;
    }

    /** Whether the setting asks for the private journal. */
    public boolean journalEnabled() {
        return journal.isEnabled();
    }
}

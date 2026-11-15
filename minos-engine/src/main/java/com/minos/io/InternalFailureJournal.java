package com.minos.io;

import com.minos.diagnostics.DiagnosticEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Private, opt-in journal of internal failures: one owner-only file per failure under
 * {@code <home>/logs/internal-failures/}, at most {@link #MAX_ENTRIES} of them.
 *
 * <p>The journal is off unless {@value #ENVIRONMENT_VARIABLE} or the system property {@value #SYSTEM_PROPERTY} is
 * {@value #ENABLED}. It writes nothing but on an internal failure, only through {@link PrivateLocalStorage} and
 * {@link DurableAtomicFile} (no append, no lock, no rename), and never throws to its caller: when the directory cannot
 * be created or written, the entry is dropped, the unavailability is reported once per journal and the journal then
 * stays silent. The surface that asked for the entry answers exactly as it would without a journal.</p>
 */
public final class InternalFailureJournal {

    public static final String ENVIRONMENT_VARIABLE = "MINOS_DIAGNOSTIC_LOG";
    public static final String SYSTEM_PROPERTY = "minos.diagnostic.log";
    public static final String ENABLED = "enabled";
    /** Entries kept; the oldest are removed beyond it. */
    public static final int MAX_ENTRIES = 200;
    static final String ENTRY_GLOB = "*.txt";

    private static final System.Logger LOGGER = System.getLogger(InternalFailureJournal.class.getName());

    private enum State { INACTIVE, ACTIVE, UNAVAILABLE }

    private final Path directory;
    private final int maxEntries;
    private final Supplier<Instant> clock;
    private final boolean unrecognizedSetting;
    private State state;
    private boolean unrecognizedReported;

    /** A journal under {@code home}, switched on or off by {@code environment} and {@code properties}. */
    public InternalFailureJournal(Path home, Map<String, String> environment, Properties properties) {
        this(home, environment, properties, MAX_ENTRIES, Instant::now);
    }

    /** As above with a smaller bound and a clock, for tests. */
    public InternalFailureJournal(Path home, Map<String, String> environment, Properties properties, int maxEntries,
                                  Supplier<Instant> clock) {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.directory = home.resolve("logs").resolve("internal-failures");
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
        String setting = setting(environment, properties);
        this.unrecognizedSetting = setting != null && !ENABLED.equals(setting);
        this.state = ENABLED.equals(setting) ? State.ACTIVE : State.INACTIVE;
    }

    /** A journal that never writes, for a surface built without a storage home. */
    public static InternalFailureJournal inactive() {
        return new InternalFailureJournal(Path.of("."), Map.of(), new Properties());
    }

    /** Whether the setting asks for the journal (an unavailable journal still reports {@code true}). */
    public synchronized boolean isEnabled() {
        return state != State.INACTIVE;
    }

    /** Adds {@code entry}; does nothing when the journal is inactive or unavailable. */
    public synchronized void append(DiagnosticEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (unrecognizedSetting && !unrecognizedReported) {
            unrecognizedReported = true;
            LOGGER.log(System.Logger.Level.WARNING,
                    "diagnostic journal setting is not recognized; the journal stays off");
        }
        if (state != State.ACTIVE) return;
        try {
            Instant now = clock.get();
            PrivateLocalStorage.ensurePrivateDirectory(directory);
            PrivateLocalStorage.writePrivateFile(directory.resolve(entry.fileName(now)),
                    entry.entry(now).getBytes(StandardCharsets.UTF_8));
            purge();
        } catch (IOException | RuntimeException failure) {
            state = State.UNAVAILABLE;
            LOGGER.log(System.Logger.Level.WARNING,
                    "diagnostic journal unavailable (" + failure.getClass().getName() + ")");
        }
    }

    private void purge() throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, ENTRY_GLOB)) {
            for (Path entry : entries) names.add(entry.getFileName().toString());
        }
        if (names.size() <= maxEntries) return;
        Collections.sort(names);
        for (String name : names.subList(0, names.size() - maxEntries)) {
            DurableAtomicFile.deleteIfExists(directory.resolve(name), "diagnostic journal entry");
        }
    }

    private static String setting(Map<String, String> environment, Properties properties) {
        String property = properties.getProperty(SYSTEM_PROPERTY);
        if (property != null && !property.isBlank()) return property.trim();
        String value = environment.get(ENVIRONMENT_VARIABLE);
        return value == null || value.isBlank() ? null : value.trim();
    }
}

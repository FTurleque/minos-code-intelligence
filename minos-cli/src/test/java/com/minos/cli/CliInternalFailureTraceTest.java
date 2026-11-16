package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.application.resolution.ProjectResolver;
import com.minos.cli.support.CliCommandSupport;
import com.minos.io.InternalFailureJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An execution failure of the common command frame or of the launcher is traced only when the journal is on. */
class CliInternalFailureTraceTest {

    private static final String USAGE = "Usage: minos probe [--flag]";
    private static final Pattern REFERENCE_LINE = Pattern.compile("diagnostic reference: ([0-9a-f]{8})\n");

    @TempDir
    Path home;

    @Test
    void anUnexpectedFailureKeepsItsErrorLineAndAddsTheReferenceOfItsJournalEntry() throws Exception {
        Launch launch = launch(true, failingCommand(new IllegalStateException("boom")));

        assertEquals(1, launch.exit());
        assertTrue(launch.error().startsWith("error: probe failed: boom\n"), launch.error());
        Matcher matcher = REFERENCE_LINE.matcher(launch.error());
        assertTrue(matcher.find(), launch.error());
        assertEquals("error: probe failed: boom\n" + matcher.group(0), launch.error());
        List<Path> entries = entries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).getFileName().toString().endsWith("-" + matcher.group(1) + ".txt"));
        String entry = Files.readString(entries.get(0));
        assertTrue(entry.contains("surface=cli operation=probe ref=" + matcher.group(1)), entry);
        assertFalse(entry.contains(home.toString()), "no absolute path in the entry");
    }

    @Test
    void withoutTheSettingTheOutputIsUnchangedAndNothingIsWritten() {
        Launch launch = launch(false, failingCommand(new IllegalStateException("boom")));

        assertEquals(1, launch.exit());
        assertEquals("error: probe failed: boom\n", launch.error());
        assertFalse(Files.exists(home.resolve("logs")));
    }

    @Test
    void anUnknownProjectIsAPublicErrorAndLeavesNoEntryNorReferenceLine() throws Exception {
        Launch launch = launch(true, (application, arguments, out, err) ->
                CliCommandSupport.<String[]>run(arguments, out, err, USAGE, options -> options, "probe", options -> {
                    try (MinosApplication opened = MinosApplication.open(home)) {
                        new ProjectResolver(opened.projectRegistry()).resolve(" ");
                    }
                    return 0;
                }));

        assertEquals(1, launch.exit());
        assertTrue(launch.error().startsWith("error: probe failed: "), launch.error());
        assertFalse(launch.error().contains("diagnostic reference:"), launch.error());
        assertEquals(List.of(), entries());
    }

    @Test
    void aUsageErrorAnswersWithCode2AndLeavesNoEntry() throws Exception {
        Launch launch = launch(true, (application, arguments, out, err) ->
                CliCommandSupport.<String[]>run(arguments, out, err, USAGE, options -> {
                    throw new IllegalArgumentException("unknown option: --nope");
                }, "probe", options -> 0));

        assertEquals(2, launch.exit());
        assertFalse(launch.error().contains("diagnostic reference:"), launch.error());
        assertEquals(List.of(), entries());
    }

    @Test
    void aFailureOfTheLauncherItselfIsTracedWhenTheJournalIsOn() throws Exception {
        Launch launch = launch(true, (application, arguments, out, err) -> {
            throw new IOException("disk failure");
        });

        assertEquals(1, launch.exit());
        assertTrue(launch.error().startsWith("error: MINOS bootstrap failed: disk failure\n"), launch.error());
        Matcher matcher = REFERENCE_LINE.matcher(launch.error());
        assertTrue(matcher.find(), launch.error());
        assertEquals(1, entries().size());
        assertTrue(entries().get(0).getFileName().toString().endsWith("-" + matcher.group(1) + ".txt"));
    }

    @Test
    void theJournalBindingEndsWithTheLaunchSoALaterBareCallIsNotTraced() throws Exception {
        launch(true, failingCommand(new IllegalStateException("boom")));
        int before = entries().size();

        String reference = CliCommandSupport.traceExecutionFailure("probe", new IllegalStateException("late"));

        assertNull(reference);
        assertEquals(before, entries().size());
    }

    private MinosLauncher.CommandRunner failingCommand(Exception failure) {
        return (application, arguments, out, err) ->
                CliCommandSupport.<String[]>run(arguments, out, err, USAGE, options -> options, "probe", options -> {
                    throw failure;
                });
    }

    private Launch launch(boolean journal, MinosLauncher.CommandRunner runner) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Map<String, String> environment = journal
                ? Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString(),
                        InternalFailureJournal.ENVIRONMENT_VARIABLE, InternalFailureJournal.ENABLED)
                : Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString());
        int exit = MinosLauncher.launch(new String[]{"probe"}, environment, new Properties(),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                opened -> {
                    throw new IOException("the application is not needed by these commands");
                },
                runner);
        return new Launch(exit, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    private List<Path> entries() throws IOException {
        Path directory = home.resolve("logs").resolve("internal-failures");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.sorted().toList();
        }
    }

    private record Launch(int exit, String output, String error) {
    }
}

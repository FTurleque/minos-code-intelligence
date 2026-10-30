package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Règles de résolution et de rendu de {@link CommandLocator} qui ne dépendent ni de la plateforme ni du
 * {@code PATH} de l'hôte : la valeur de {@code PATH} et le mode Windows sont injectés.
 */
class CommandLocatorResolutionRulesTest {

    private static final String SEPARATOR = File.pathSeparator;

    private static Path executableFile(Path path) throws Exception {
        Path written = Files.writeString(path, "fixture");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(written, PosixFilePermissions.fromString("rwx------"));
        }
        return written;
    }

    @Test
    void aCommandThatNamesAPathIsRefusedBeforeAnyLookup(@TempDir Path temp) {
        String pathValue = temp.toAbsolutePath().toString();

        IllegalArgumentException slash = assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.findInPath("bin/tool", pathValue, false));
        IllegalArgumentException backslash = assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.findInPath("bin\\tool", pathValue, true));

        assertEquals("command must be a simple executable name", slash.getMessage());
        assertEquals("command must be a simple executable name", backslash.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CommandLocator.findInPath(" ", pathValue, false),
                "a blank command names nothing");
        assertThrows(IllegalArgumentException.class, () -> CommandLocator.findSystemExecutable("../sh"),
                "the system resolver must never be steered out of its fixed directories");
    }

    @Test
    void anAbsentOrBlankPathValueResolvesNothing() {
        assertTrue(CommandLocator.findInPath("tool", null, false).isEmpty());
        assertTrue(CommandLocator.findInPath("tool", "", true).isEmpty());
        assertTrue(CommandLocator.findInPath("tool", "   ", false).isEmpty());
    }

    @Test
    void unusablePathEntriesAreSkippedWithoutHidingALaterUsableOne(@TempDir Path temp) throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin")).toAbsolutePath().normalize();
        Path tool = executableFile(bin.resolve("minos-rules-probe"));
        Path missing = temp.resolve("missing").toAbsolutePath().normalize();
        Path notADirectory = Files.writeString(temp.resolve("plain-file"), "x").toAbsolutePath().normalize();
        String invalid = temp.toAbsolutePath() + File.separator + "nul\0entry";

        String unusableOnly = String.join(SEPARATOR, invalid, missing.toString(), notADirectory.toString());
        assertTrue(CommandLocator.findInPath("minos-rules-probe", unusableOnly, false).isEmpty(),
                "an invalid, missing or non-directory PATH entry must never resolve a command");

        assertEquals(tool.toRealPath(),
                CommandLocator.findInPath("minos-rules-probe", unusableOnly + SEPARATOR + bin, false).orElseThrow(),
                "unusable entries must not stop the search before a usable absolute entry");
    }

    @Test
    void aDirectoryNamedLikeTheCommandIsNeverReturnedAsAnExecutable(@TempDir Path temp) throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin")).toAbsolutePath().normalize();
        Files.createDirectories(bin.resolve("minos-rules-probe"));

        assertTrue(CommandLocator.findInPath("minos-rules-probe", bin.toString(), false).isEmpty());
        assertTrue(CommandLocator.findInPath("minos-rules-probe", bin.toString(), true).isEmpty());
    }

    @Test
    void windowsModeTriesExecutableExtensionsBeforeTheBareName(@TempDir Path temp) throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin")).toAbsolutePath().normalize();
        Path bare = executableFile(bin.resolve("tool"));
        Path batch = Files.writeString(bin.resolve("tool.cmd"), "batch");
        Path legacy = Files.writeString(bin.resolve("legacy.bat"), "legacy");
        Path binary = Files.writeString(bin.resolve("probe.exe"), "binary");
        Path extensionless = Files.writeString(bin.resolve("script"), "script");
        String pathValue = bin.toString();

        assertEquals(batch.toRealPath(), CommandLocator.findInPath("tool", pathValue, true).orElseThrow(),
                "a Windows lookup prefers an executable extension to the bare name");
        assertEquals(binary.toRealPath(), CommandLocator.findInPath("probe", pathValue, true).orElseThrow());
        assertEquals(legacy.toRealPath(), CommandLocator.findInPath("legacy", pathValue, true).orElseThrow());
        assertEquals(extensionless.toRealPath(), CommandLocator.findInPath("script", pathValue, true).orElseThrow(),
                "the bare name remains the last candidate");
        assertEquals(bare.toRealPath(), CommandLocator.findInPath("tool", pathValue, false).orElseThrow(),
                "outside Windows mode only the exact name is a candidate");
    }

    @Test
    void windowsModeLooksUpAnExplicitExtensionAsIs(@TempDir Path temp) throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin")).toAbsolutePath().normalize();
        Files.writeString(bin.resolve("probe.exe.exe"), "decoy");
        Files.writeString(bin.resolve("tool.cmd.exe"), "decoy");
        Files.writeString(bin.resolve("legacy.bat.exe"), "decoy");
        Path binary = Files.writeString(bin.resolve("probe.exe"), "binary");
        String pathValue = bin.toString();

        assertEquals(binary.toRealPath(), CommandLocator.findInPath("probe.exe", pathValue, true).orElseThrow(),
                "an explicit extension must not be extended a second time");
        assertTrue(CommandLocator.findInPath("tool.cmd", pathValue, true).isEmpty(),
                "tool.cmd.exe is another command, not a candidate for tool.cmd");
        assertTrue(CommandLocator.findInPath("legacy.bat", pathValue, true).isEmpty(),
                "legacy.bat.exe is another command, not a candidate for legacy.bat");
    }

    @Test
    void aPlainExecutableIsInvokedDirectlyWithItsArgumentsInOrder() {
        Path executable = Path.of("provider-tool");

        assertEquals(List.of("provider-tool", "index", "--output", "out.scip"),
                CommandLocator.invocation(executable, "index", "--output", "out.scip"));
        assertEquals(List.of("provider-tool"), CommandLocator.invocation(executable, (String[]) null),
                "an absent argument array means no argument");
    }

    @Test
    void anInvocationRefusesAMissingExecutableOrANullArgument() {
        Path executable = Path.of("provider-tool");

        assertThrows(NullPointerException.class, () -> CommandLocator.invocation(null, "index"));
        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.invocation(executable, "index", null));
        IllegalArgumentException batch = assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.windowsBatchInvocation(executable, "index", null));
        assertThrows(NullPointerException.class, () -> CommandLocator.windowsBatchInvocation(null, "index"));

        assertEquals("arguments must not contain null values", direct.getMessage());
        assertEquals("arguments must not contain null values", batch.getMessage());
    }

    @Test
    void aBatchInvocationWithoutArgumentsQuotesTheExecutableAlone() {
        List<String> command = CommandLocator.windowsBatchInvocation(Path.of("provider.cmd"), (String[]) null);

        assertEquals(6, command.size());
        assertEquals(List.of("/d", "/v:off", "/s", "/c"), command.subList(1, 5));
        assertEquals("\"\"provider.cmd\"\"", command.get(5));
        assertEquals(Path.of("provider.cmd"), CommandLocator.windowsBatchExecutable(command).orElseThrow());
    }

    @Test
    void aBatchInvocationRefusesControlCharactersAndNamesTheOffendingToken() {
        Path executable = Path.of("provider.cmd");
        Path expanding = Path.of("100%.cmd");

        IllegalArgumentException nul = assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.windowsBatchInvocation(executable, "a\0b"));
        IllegalArgumentException carriageReturn = assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.windowsBatchInvocation(executable, "a\rb"));
        IllegalArgumentException executableToken = assertThrows(IllegalArgumentException.class,
                () -> CommandLocator.windowsBatchInvocation(expanding, "index"));

        assertEquals("argument contains a forbidden control character", nul.getMessage());
        assertEquals("argument contains a forbidden control character", carriageReturn.getMessage());
        assertTrue(executableToken.getMessage().startsWith("executable contains '%'"),
                "the refusal must say that the executable, not an argument, is unsafe");
    }

    @Test
    void onlyTheExactHardenedCmdShapeHidesABatchExecutable() {
        String line = "\"\"C:\\tools\\provider.cmd\" \"index\"\"";

        assertEquals(Path.of("C:\\tools\\provider.cmd"), CommandLocator.windowsBatchExecutable(
                List.of("cmd.exe", "/D", "/V:OFF", "/S", "/C", line)).orElseThrow(),
                "the hardening flags are recognised whatever their case");
        assertTrue(CommandLocator.windowsBatchExecutable(
                List.of("cmd.exe", "/x", "/v:off", "/s", "/c", line)).isEmpty());
        assertTrue(CommandLocator.windowsBatchExecutable(
                List.of("cmd.exe", "/d", "/v:on", "/s", "/c", line)).isEmpty(),
                "delayed expansion left on is not the hardened shape");
        assertTrue(CommandLocator.windowsBatchExecutable(
                List.of("cmd.exe", "/d", "/v:off", "/q", "/c", line)).isEmpty());
        assertTrue(CommandLocator.windowsBatchExecutable(
                List.of("cmd.exe", "/d", "/v:off", "/s", "/k", line)).isEmpty());
    }

    @Test
    void aCommandLineThatIsNotDoublyQuotedHidesNoExecutable() {
        assertTrue(batchExecutableOf("\"\"").isEmpty(), "too short to hold a quoted token");
        assertTrue(batchExecutableOf("provider.cmd index").isEmpty(), "no outer quote");
        assertTrue(batchExecutableOf("\"provider.cmd\" \"index\"").isEmpty(), "no inner quote");
        assertTrue(batchExecutableOf("\"\"provider.cmd index").isEmpty(), "the first token is never closed");
        assertTrue(batchExecutableOf("\"\"provider\0.cmd\" \"index\"\"").isEmpty(),
                "a first token that is not a path is refused instead of propagating the failure");
    }

    private static java.util.Optional<Path> batchExecutableOf(String commandLine) {
        return CommandLocator.windowsBatchExecutable(List.of("cmd.exe", "/d", "/v:off", "/s", "/c", commandLine));
    }
}

package com.minos.intellij.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MinosCommandLine {
    /** The cmd.exe launch shape produced by {@link #build}; shared with the process launcher (one definition). */
    private static final String CMD_EXECUTABLE = "cmd.exe";
    private static final List<String> CMD_SWITCHES = List.of("/d", "/v:off", "/s", "/c");
    private static final int MAX_DESCRIBED_CHARACTERS = 64;

    private MinosCommandLine() {
    }

    /**
     * True for exactly {@code [cmd.exe, /d, /v:off, /s, /c, <command string>]}: the command string is then a raw
     * cmd.exe line that must reach cmd.exe untouched, never re-serialized with the C runtime rules (MINOS-AUD-C04).
     */
    static boolean isCmdBatchInvocation(List<String> command) {
        if (command.size() != CMD_SWITCHES.size() + 2 || !CMD_EXECUTABLE.equalsIgnoreCase(command.getFirst())) {
            return false;
        }
        return command.subList(1, 1 + CMD_SWITCHES.size()).equals(CMD_SWITCHES);
    }

    static List<String> build(String executable, List<String> arguments, String osName) {
        String effectiveExecutable = requireToken(executable, "executable");
        List<String> safeArguments = arguments.stream().map(value -> requireToken(value, "argument")).toList();
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        if (windows && isBatch(effectiveExecutable)) {
            List<String> command = new ArrayList<>();
            // The strong process launcher resolves this token to canonical SystemRoot\\System32\\cmd.exe
            // before the ownership plan is published. Never resolve it from ComSpec or PATH there.
            command.add(CMD_EXECUTABLE);
            command.addAll(CMD_SWITCHES);
            command.add(renderWindowsBatchCommand(effectiveExecutable, safeArguments));
            return List.copyOf(command);
        }
        List<String> command = new ArrayList<>(safeArguments.size() + 1);
        command.add(effectiveExecutable);
        command.addAll(safeArguments);
        return List.copyOf(command);
    }

    private static boolean isBatch(String executable) {
        String normalized = executable.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".cmd") || normalized.endsWith(".bat");
    }

    private static String renderWindowsBatchCommand(String executable, List<String> arguments) {
        StringBuilder command = new StringBuilder(quoteWindows(executable, "executable"));
        for (String argument : arguments) {
            command.append(' ').append(quoteWindows(argument, "argument"));
        }
        return command.toString();
    }

    /**
     * Inside double quotes, {@code & | < > ^} are literal for {@code cmd /d /v:off /s /c}: the value is wrapped and
     * never altered. Two characters cannot be received intact by a batch file and are refused rather than corrupted:
     * a double quote breaks the quote balance that {@code /s} relies on, and a percent sign is expanded by cmd.exe
     * even between quotes.
     */
    private static String quoteWindows(String value, String role) {
        if (value.indexOf('"') >= 0 || value.indexOf('%') >= 0) {
            throw new IllegalArgumentException(role + " '" + describe(value) + "' contains a double quote or a percent"
                    + " sign, which a Windows batch launcher (.cmd, .bat) cannot receive intact. Configure the native"
                    + " launcher (app\\minos.exe in the MINOS installation) as the MINOS executable instead.");
        }
        return "\"" + value + "\"";
    }

    private static String describe(String value) {
        return value.length() <= MAX_DESCRIBED_CHARACTERS ? value : value.substring(0, MAX_DESCRIBED_CHARACTERS) + "...";
    }

    private static String requireToken(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " contains a forbidden control character");
        }
        return value;
    }
}

package com.minos.intellij.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MinosCommandLine {

    private MinosCommandLine() {
    }

    static List<String> build(String executable, List<String> arguments, String osName) {
        String effectiveExecutable = requireToken(executable, "executable");
        List<String> safeArguments = arguments.stream().map(value -> requireToken(value, "argument")).toList();
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        if (windows && isBatch(effectiveExecutable)) {
            List<String> command = new ArrayList<>();
            // The strong process launcher resolves this token to canonical SystemRoot\\System32\\cmd.exe
            // before the ownership plan is published. Never resolve it from ComSpec or PATH there.
            command.add("cmd.exe");
            command.add("/d");
            command.add("/v:off");
            command.add("/s");
            command.add("/c");
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
        StringBuilder command = new StringBuilder(quoteWindows(executable));
        for (String argument : arguments) {
            command.append(' ').append(quoteWindows(argument));
        }
        return command.toString();
    }

    private static String quoteWindows(String value) {
        String escaped = value
                .replace("^", "^^")
                .replace("%", "%%")
                .replace("&", "^&")
                .replace("|", "^|")
                .replace("<", "^<")
                .replace(">", "^>")
                .replace("\"", "^\"");
        return "\"" + escaped + "\"";
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

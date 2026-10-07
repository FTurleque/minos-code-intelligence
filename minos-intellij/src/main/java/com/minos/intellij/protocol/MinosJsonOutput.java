package com.minos.intellij.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

/** The JSON object a MINOS command printed, once its exit code is known to be one the caller accepts. */
final class MinosJsonOutput {

    private MinosJsonOutput() { }

    /**
     * The exit code of a MINOS command is a contract: each caller names the codes it accepts for that command (for
     * {@code project list}, see {@link MinosProjectList#ACCEPTED_EXIT_CODES}); any other code is a failure whose
     * diagnostic is shown.
     */
    static JsonObject parse(int exitCode, String stdout, String stderr, Set<Integer> acceptedExitCodes)
            throws MinosProtocolException {
        if (!acceptedExitCodes.contains(exitCode)) {
            String diagnostic = stderr.isBlank() ? stdout : stderr;
            throw new MinosProtocolException("MINOS command failed (exit " + exitCode + "): " + diagnostic.trim());
        }
        try {
            JsonElement parsed = JsonParser.parseString(stdout.trim());
            if (!parsed.isJsonObject()) throw new MinosProtocolException("MINOS command did not return a JSON object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new MinosProtocolException("Invalid JSON returned by MINOS: " + abbreviate(stdout), exception);
        }
    }

    private static String abbreviate(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "…";
    }
}

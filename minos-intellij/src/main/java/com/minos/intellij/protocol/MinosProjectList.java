package com.minos.intellij.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.function.Predicate;

/**
 * {@code minos project list --format json} as the plugin reads it.
 *
 * <p>The command exits 3, a <em>partial result</em>, when some registry entries are unreadable: its JSON is then valid
 * and complete for the projects it could read, and carries {@code degradedCount}. The plugin accepts it for this
 * command, resolves the open project from it, and tells the user about the unreadable entries.</p>
 */
final class MinosProjectList {

    static final int SUCCESS_EXIT_CODE = 0;
    static final int PARTIAL_RESULT_EXIT_CODE = 3;

    /** The exit codes of {@code project list} whose output is usable. */
    static final Set<Integer> ACCEPTED_EXIT_CODES = Set.of(SUCCESS_EXIT_CODE, PARTIAL_RESULT_EXIT_CODE);

    /** The registered project, and how many registry entries MINOS could not read while listing (0 when healthy). */
    record Resolution(JsonObject project, int unreadable) { }

    private MinosProjectList() { }

    /** How many registry entries the listing could not read ({@code degradedCount}, absent when none). */
    static int unreadableCount(JsonObject inventory) {
        JsonElement count = inventory.get("degradedCount");
        return count == null || count.isJsonNull() ? 0 : count.getAsInt();
    }

    /**
     * The project whose root is the open IntelliJ project. Not found in a healthy registry, it is unregistered; not
     * found while entries are unreadable, it may be one of them, which is a different answer and says so.
     */
    static Resolution resolve(JsonObject inventory, Predicate<String> isOpenProjectRoot, String basePath)
            throws MinosProtocolException {
        int unreadable = unreadableCount(inventory);
        JsonArray projects = inventory.has("projects") ? inventory.getAsJsonArray("projects") : new JsonArray();
        for (JsonElement element : projects) {
            if (!element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            JsonElement rootPath = candidate.get("rootPath");
            if (rootPath != null && !rootPath.isJsonNull() && isOpenProjectRoot.test(rootPath.getAsString())) {
                return new Resolution(candidate, unreadable);
            }
        }
        if (unreadable > 0) {
            throw new MinosProtocolException("MINOS did not find this IntelliJ project among its readable registry entries, and "
                    + describe(unreadable) + ": it may be one of them. Run `minos project list` to see them.");
        }
        throw new MinosProjectNotRegisteredException(
                "This IntelliJ project is not registered in MINOS. Run `minos project add \"" + basePath + "\"` first.");
    }

    /** « 1 registry entry is unreadable » or « N registry entries are unreadable », as the CLI words it. */
    static String describe(int count) {
        return count == 1 ? "1 registry entry is unreadable" : count + " registry entries are unreadable";
    }
}

package com.minos.intellij.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minos.intellij.navigation.MinosLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How the tool window presents a CLI response, without the platform or Swing (AUD-TST-08): the locations a result
 * carries, the connection line and the architecture and module summaries. A missing field shows its fallback text.
 */
final class MinosResultPresentation {

    private MinosResultPresentation() {
    }

    /** Every location found in {@code result}, in document order, each with the JSON path it was found at. */
    static List<ResultLocation> locations(JsonElement result) {
        List<ResultLocation> found = new ArrayList<>();
        collect(result, "$", found);
        return List.copyOf(found);
    }

    private static void collect(JsonElement element, String path, List<ResultLocation> target) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (isLocation(object)) {
                try {
                    target.add(new ResultLocation(path, MinosLocation.from(object)));
                } catch (RuntimeException ignored) {
                    // Keep malformed optional evidence from breaking the complete result view.
                }
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collect(entry.getValue(), path + "." + entry.getKey(), target);
            }
        } else if (element.isJsonArray()) {
            for (int index = 0; index < element.getAsJsonArray().size(); index++) {
                collect(element.getAsJsonArray().get(index), path + "[" + index + "]", target);
            }
        }
    }

    private static boolean isLocation(JsonObject object) {
        return object.has("fileId") && object.has("startLine") && object.has("startColumn");
    }

    static String connectionText(JsonObject result) {
        JsonElement project = result.get("project");
        if (project != null && project.isJsonObject()) {
            JsonObject projectObject = project.getAsJsonObject();
            String name = text(projectObject, "name", "?");
            JsonObject index = result.has("index") && result.get("index").isJsonObject()
                    ? result.getAsJsonObject("index")
                    : new JsonObject();
            return "MINOS: connected — " + name + " — " + text(index, "state", "UNKNOWN")
                    + " — provider " + text(index, "providerId", "-");
        }
        return "MINOS: connected — IntelliJ project is not registered";
    }

    static String architectureSummary(JsonObject architecture, int visibleNodes, int maxNodes) {
        return String.join("\n",
                "Project: " + text(architecture, "projectName", "?"),
                "Snapshot: " + text(architecture, "snapshotId", "?"),
                "Nature: " + text(architecture, "nature", "?"),
                "Modules: " + integer(architecture, "moduleCount"),
                "Visible nodes: " + visibleNodes + " (bound " + maxNodes + ")",
                "Relationships: " + integer(architecture, "relationshipCount"),
                "Edges shown are the moduleDependencies returned by MINOS architecture JSON.",
                "Select a node for module details; hover a node for incoming/outgoing edge evidence."
        );
    }

    static String moduleSummary(JsonObject module) {
        return String.join("\n",
                "Module: " + text(module, "name", "?"),
                "Id: " + text(module, "id", "?"),
                "Path: " + text(module, "relativePath", ""),
                "Build systems: " + json(module, "buildSystems"),
                "Languages: " + json(module, "languages"),
                "Source roots: " + integer(module, "sourceRootCount"),
                "Symbols: " + integer(module, "symbolCount"),
                "Namespaces: " + integer(module, "namespaceCount")
        );
    }

    private static String text(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static String json(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null ? "[]" : value.toString();
    }

    private static int integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    /** A location of a result, shown as {@code file:line:column — path}. */
    record ResultLocation(String sourcePath, MinosLocation location) {
        @Override
        public String toString() {
            return location.fileId() + ":" + location.startLine() + ":" + location.startColumn()
                    + " — " + sourcePath;
        }
    }
}

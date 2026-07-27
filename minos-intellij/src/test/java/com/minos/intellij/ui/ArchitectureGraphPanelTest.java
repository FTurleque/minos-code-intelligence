package com.minos.intellij.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureGraphPanelTest {

    @Test
    void boundsVisibleModulesAndFiltersAcrossEntireArchitecture() {
        JsonObject architecture = new JsonObject();
        JsonArray modules = new JsonArray();
        for (int index = 0; index < 25; index++) {
            JsonObject module = new JsonObject();
            module.addProperty("id", "module-" + index);
            module.addProperty("name", index == 24 ? "billing-domain" : "module " + index);
            module.addProperty("relativePath", "modules/m" + index);
            modules.add(module);
        }
        architecture.add("modules", modules);
        architecture.add("moduleDependencies", new JsonArray());

        ArchitectureGraphPanel panel = new ArchitectureGraphPanel();
        panel.setGraph(architecture, 12);

        assertEquals(12, panel.visibleNodeCount());
        panel.setFilter("billing");
        assertEquals(1, panel.visibleNodeCount());
    }

    @Test
    void exposesIncomingAndOutgoingDependencyEvidenceWithoutInventingEdges() {
        JsonObject architecture = new JsonObject();
        JsonArray modules = new JsonArray();
        modules.add(module("api", "API", "api"));
        modules.add(module("domain", "Domain", "domain"));
        architecture.add("modules", modules);

        JsonObject dependency = new JsonObject();
        dependency.addProperty("sourceModuleId", "api");
        dependency.addProperty("sourceModuleName", "API");
        dependency.addProperty("targetModuleId", "domain");
        dependency.addProperty("targetModuleName", "Domain");
        dependency.addProperty("dependencyCount", 4);
        dependency.addProperty("nature", "OBSERVED");
        dependency.addProperty("confidence", 0.92);
        JsonArray evidence = new JsonArray();
        evidence.add("src/Main.java");
        dependency.add("evidence", evidence);
        JsonArray samples = new JsonArray();
        samples.add("dep-1");
        dependency.add("sampleDependencyIds", samples);
        JsonArray dependencies = new JsonArray();
        dependencies.add(dependency);
        architecture.add("moduleDependencies", dependencies);

        ArchitectureGraphPanel panel = new ArchitectureGraphPanel();
        panel.setGraph(architecture, 20);

        String api = panel.edgeSummaryFor("api");
        String domain = panel.edgeSummaryFor("domain");
        assertTrue(api.contains("OUT Domain — 4 deps"));
        assertTrue(api.contains("nature=OBSERVED"));
        assertTrue(api.contains("confidence=0.92"));
        assertTrue(api.contains("evidence=1"));
        assertTrue(api.contains("dep-1"));
        assertTrue(domain.contains("IN API — 4 deps"));
    }

    private static JsonObject module(String id, String name, String path) {
        JsonObject module = new JsonObject();
        module.addProperty("id", id);
        module.addProperty("name", name);
        module.addProperty("relativePath", path);
        return module;
    }
}

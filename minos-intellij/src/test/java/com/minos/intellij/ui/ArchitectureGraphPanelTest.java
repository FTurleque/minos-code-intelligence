package com.minos.intellij.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectureGraphPanelTest {

    @Test
    void boundsVisibleModulesAndFiltersByModuleText() {
        JsonObject architecture = new JsonObject();
        JsonArray modules = new JsonArray();
        for (int index = 0; index < 25; index++) {
            JsonObject module = new JsonObject();
            module.addProperty("id", "module-" + index);
            module.addProperty("name", index == 7 ? "billing-domain" : "module " + index);
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
}

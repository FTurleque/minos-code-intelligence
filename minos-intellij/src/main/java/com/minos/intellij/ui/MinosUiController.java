package com.minos.intellij.ui;

import com.google.gson.JsonObject;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

@Service(Service.Level.PROJECT)
public final class MinosUiController {

    private final Project project;
    private volatile MinosToolWindowPanel panel;

    public MinosUiController(Project project) {
        this.project = project;
    }

    public static MinosUiController getInstance(Project project) {
        return project.getService(MinosUiController.class);
    }

    public void attach(MinosToolWindowPanel value) {
        panel = value;
    }

    public void showResult(String title, JsonObject result) {
        showToolWindow();
        MinosToolWindowPanel current = panel;
        if (current != null) {
            current.showResult(title, result);
        }
    }

    public void showArchitecture(JsonObject architecture, String moduleId) {
        showToolWindow();
        MinosToolWindowPanel current = panel;
        if (current != null) {
            current.showArchitecture(architecture, moduleId);
        }
    }

    public void showError(String title, Throwable failure) {
        showToolWindow();
        MinosToolWindowPanel current = panel;
        if (current != null) {
            current.showError(title, failure);
        }
    }

    public void refreshStatus() {
        MinosToolWindowPanel current = panel;
        if (current != null) {
            current.refreshStatus();
        }
    }

    private void showToolWindow() {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("MINOS");
        if (toolWindow != null) {
            toolWindow.show();
        }
    }
}

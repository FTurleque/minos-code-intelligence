package com.minos.intellij.ui;

import com.google.gson.JsonObject;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class MinosUiController {

    private final Project project;
    // MINOS-AUD-H21: a private lock, so code holding the public service instance cannot contend for it, and no
    // delivery ever runs while it is held.
    private final Object lock = new Object();
    private MinosToolWindowPanel panel;
    private Consumer<MinosToolWindowPanel> pendingDelivery;

    public MinosUiController(Project project) {
        this.project = project;
    }

    public static MinosUiController getInstance(Project project) {
        return project.getService(MinosUiController.class);
    }

    public void attach(MinosToolWindowPanel value) {
        Consumer<MinosToolWindowPanel> pending;
        synchronized (lock) {
            panel = value;
            pending = pendingDelivery;
            pendingDelivery = null;
        }
        if (pending != null) {
            pending.accept(value);
        }
    }

    public void showResult(String title, JsonObject result) {
        deliver(panel -> panel.showResult(title, result));
    }

    public void showArchitecture(JsonObject architecture, String moduleId) {
        deliver(panel -> panel.showArchitecture(architecture, moduleId));
    }

    public void showError(String title, Throwable failure) {
        deliver(panel -> panel.showError(title, failure));
    }

    public void refreshStatus() {
        deliver(MinosToolWindowPanel::refreshStatus);
    }

    private void deliver(Consumer<MinosToolWindowPanel> delivery) {
        MinosToolWindowPanel current;
        synchronized (lock) {
            current = panel;
            if (current == null) {
                pendingDelivery = delivery;
            }
        }
        showToolWindow();
        if (current != null) {
            delivery.accept(current);
        }
    }

    private void showToolWindow() {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("MINOS");
        if (toolWindow != null) {
            toolWindow.show();
        }
    }
}

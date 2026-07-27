package com.minos.intellij.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class MinosToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MinosToolWindowPanel panel = new MinosToolWindowPanel(project);
        MinosUiController.getInstance(project).attach(panel);
        Content content = ContentFactory.getInstance().createContent(panel.component(), "", false);
        toolWindow.getContentManager().addContent(content);
        panel.refreshStatus();
    }
}

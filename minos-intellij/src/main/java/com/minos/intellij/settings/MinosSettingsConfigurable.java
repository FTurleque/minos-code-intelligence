package com.minos.intellij.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class MinosSettingsConfigurable implements Configurable {

    private final Project project;
    private JPanel panel;
    private JBTextField executable;
    private JBTextField home;
    private JBTextField timeout;
    private JBTextField maxGraphNodes;

    public MinosSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "MINOS";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new JPanel(new GridBagLayout());
        executable = new JBTextField();
        home = new JBTextField();
        timeout = new JBTextField();
        maxGraphNodes = new JBTextField();

        addRow(0, "MINOS executable (IDE-global)", executable);
        addRow(1, "MINOS_HOME (IDE-global, optional)", home);
        addRow(2, "Command timeout (seconds)", timeout);
        addRow(3, "Maximum architecture nodes", maxGraphNodes);

        GridBagConstraints filler = constraints(4, 0);
        filler.weighty = 1;
        panel.add(new JPanel(), filler);
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        MinosSettingsState.Settings state = MinosSettingsState.getInstance(project).value();
        return !state.executable.equals(executable.getText().trim())
                || !state.minosHome.equals(home.getText().trim())
                || state.timeoutSeconds != parseInt(timeout.getText(), -1)
                || state.maxGraphNodes != parseInt(maxGraphNodes.getText(), -1);
    }

    @Override
    public void apply() {
        int parsedTimeout = bounded(timeout.getText(), 1, 300, MinosSettingsState.DEFAULT_TIMEOUT_SECONDS);
        int parsedGraph = bounded(maxGraphNodes.getText(), 10, 500, MinosSettingsState.DEFAULT_MAX_GRAPH_NODES);
        MinosSettingsState.getInstance(project).update(executable.getText(), home.getText(), parsedTimeout, parsedGraph);
        reset();
    }

    @Override
    public void reset() {
        if (executable == null) {
            return;
        }
        MinosSettingsState.Settings state = MinosSettingsState.getInstance(project).value();
        executable.setText(state.executable);
        home.setText(state.minosHome);
        timeout.setText(Integer.toString(state.timeoutSeconds));
        maxGraphNodes.setText(Integer.toString(state.maxGraphNodes));
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        executable = null;
        home = null;
        timeout = null;
        maxGraphNodes = null;
    }

    private void addRow(int row, String label, JComponent component) {
        GridBagConstraints left = constraints(row, 0);
        left.weightx = 0;
        panel.add(new JBLabel(label + ":"), left);
        GridBagConstraints right = constraints(row, 1);
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, right);
    }

    private static GridBagConstraints constraints(int row, int column) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(4, 4, 4, 4);
        return constraints;
    }

    private static int bounded(String value, int minimum, int maximum, int fallback) {
        int parsed = parseInt(value, fallback);
        return parsed >= minimum && parsed <= maximum ? parsed : fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}

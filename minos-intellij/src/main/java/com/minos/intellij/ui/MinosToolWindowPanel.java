package com.minos.intellij.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.minos.intellij.protocol.MinosCliClient;
import com.minos.intellij.protocol.MinosProtocolException;
import com.minos.intellij.service.MinosProjectService;
import com.minos.intellij.settings.MinosSettingsState;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.util.concurrent.Callable;

public final class MinosToolWindowPanel {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private final Project project;
    private final MinosCliClient client;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JBLabel connection = new JBLabel("MINOS: checking…");
    private final JBTextArea projectStatus = textArea();
    private final JBTextArea resultArea = textArea();
    private final JBTextArea graphDetails = textArea();
    private final JBTextArea gitArea = textArea();
    private final ArchitectureGraphPanel graph = new ArchitectureGraphPanel();
    private final JBTextField graphFilter = new JBTextField();
    private JsonObject currentArchitecture;

    public MinosToolWindowPanel(Project project) {
        this.project = project;
        this.client = MinosCliClient.getInstance(project);
        root.add(toolbar(), BorderLayout.NORTH);
        tabs.addTab("Project", projectPanel());
        tabs.addTab("Architecture", architecturePanel());
        tabs.addTab("Results", new JBScrollPane(resultArea));
        tabs.addTab("Git Activity", new JBScrollPane(gitArea));
        root.add(tabs, BorderLayout.CENTER);

        graph.setSelectionListener(module -> graphDetails.setText(moduleSummary(module)));
        graphFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { updateFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { updateFilter(); }
        });
    }

    public JComponent component() {
        return root;
    }

    public void refreshStatus() {
        runBackground("Refresh MINOS status", () -> {
            JsonObject handshake = client.handshake();
            try {
                JsonObject registered = client.resolveProject();
                JsonObject status = client.indexStatus(registered.get("id").getAsString());
                JsonObject combined = new JsonObject();
                combined.add("protocol", handshake);
                combined.add("project", registered);
                combined.add("index", status);
                return combined;
            } catch (MinosProtocolException notRegistered) {
                JsonObject combined = new JsonObject();
                combined.add("protocol", handshake);
                combined.addProperty("projectRegistration", "MISSING");
                combined.addProperty("message", notRegistered.getMessage());
                return combined;
            }
        }, result -> {
            connection.setText(connectionText(result));
            projectStatus.setText(PRETTY.toJson(result));
            projectStatus.setCaretPosition(0);
            tabs.setSelectedIndex(0);
        });
    }

    public void showResult(String title, JsonObject result) {
        resultArea.setText(title + "\n\n" + PRETTY.toJson(result));
        resultArea.setCaretPosition(0);
        tabs.setSelectedIndex(2);
    }

    public void showArchitecture(JsonObject architecture, String moduleId) {
        currentArchitecture = architecture;
        int maxNodes = MinosSettingsState.getInstance(project).value().maxGraphNodes;
        graph.setGraph(architecture, maxNodes);
        if (moduleId != null && !moduleId.isBlank()) {
            graphFilter.setText(moduleId);
        } else {
            graphFilter.setText("");
        }
        graphDetails.setText(architectureSummary(architecture, graph.visibleNodeCount(), maxNodes));
        graphDetails.setCaretPosition(0);
        tabs.setSelectedIndex(1);
    }

    public void showError(String title, Throwable failure) {
        String message = failure == null || failure.getMessage() == null ? "Unknown failure" : failure.getMessage();
        resultArea.setText(title + "\n\nERROR: " + message);
        resultArea.setCaretPosition(0);
        tabs.setSelectedIndex(2);
    }

    private JComponent toolbar() {
        JPanel panel = new JPanel(new BorderLayout());
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("Refresh", this::refreshStatus));
        bar.add(button("Register", this::registerProject));
        bar.addSeparator();
        bar.add(button("Index", () -> runIndex(false, false)));
        bar.add(button("Reindex Full", () -> runIndex(true, false)));
        bar.add(button("Plan", () -> runIndex(false, true)));
        bar.addSeparator();
        bar.add(button("Doctor", this::doctor));
        bar.add(button("Architecture", this::loadArchitecture));
        bar.add(button("Git", this::loadGit));
        panel.add(bar, BorderLayout.CENTER);
        panel.add(connection, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent projectPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JBLabel("Runtime, registered project, provider and active snapshot"), BorderLayout.NORTH);
        panel.add(new JBScrollPane(projectStatus), BorderLayout.CENTER);
        return panel;
    }

    private JComponent architecturePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        filterPanel.add(new JBLabel("Module filter:"));
        graphFilter.setColumns(24);
        filterPanel.add(graphFilter);
        filterPanel.add(new JBLabel("Graph is bounded by Settings > MINOS."));
        panel.add(filterPanel, BorderLayout.NORTH);

        JBSplitter splitter = new JBSplitter(false, 0.72f);
        splitter.setFirstComponent(new JBScrollPane(graph));
        splitter.setSecondComponent(new JBScrollPane(graphDetails));
        panel.add(splitter, BorderLayout.CENTER);
        return panel;
    }

    private void registerProject() {
        runBackground("Register project in MINOS", client::registerProject, result -> {
            showResult("Project registered", result);
            refreshStatus();
        });
    }

    private void runIndex(boolean forceFull, boolean dryRun) {
        runWithContext(dryRun ? "Plan MINOS index" : "Run MINOS index", context ->
                client.index(context.projectId(), forceFull, dryRun), result -> {
            showResult(dryRun ? "Index plan" : "Index result", result);
            if (!dryRun) {
                refreshStatus();
            }
        });
    }

    private void doctor() {
        runBackground("Run MINOS doctor", client::doctor, result -> showResult("MINOS doctor", result));
    }

    private void loadArchitecture() {
        runWithContext("Load MINOS architecture", context -> client.architecture(context.projectId()),
                result -> showArchitecture(result, null));
    }

    private void loadGit() {
        runWithContext("Load factual Git activity", context -> client.gitActivity(context.projectId()), result -> {
            gitArea.setText("Activity is factual; commit frequency is not architectural or business importance.\n\n"
                    + PRETTY.toJson(result));
            gitArea.setCaretPosition(0);
            tabs.setSelectedIndex(3);
        });
    }

    private void updateFilter() {
        graph.setFilter(graphFilter.getText());
        if (currentArchitecture != null) {
            int maxNodes = MinosSettingsState.getInstance(project).value().maxGraphNodes;
            graphDetails.setText(architectureSummary(currentArchitecture, graph.visibleNodeCount(), maxNodes));
        }
    }

    private void runWithContext(
            String title,
            ContextOperation operation,
            java.util.function.Consumer<JsonObject> success
    ) {
        runBackground(title, () -> operation.execute(MinosProjectService.getInstance(project).context()), success);
    }

    private void runBackground(String title, Callable<JsonObject> operation, java.util.function.Consumer<JsonObject> success) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            private JsonObject result;
            private Throwable failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    result = operation.call();
                } catch (Throwable throwable) {
                    failure = throwable;
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null) {
                    showError(title, failure);
                } else if (result != null) {
                    success.accept(result);
                }
            }
        });
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JBTextArea textArea() {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        return area;
    }

    private static String connectionText(JsonObject result) {
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

    private static String architectureSummary(JsonObject architecture, int visibleNodes, int maxNodes) {
        return String.join("\n",
                "Project: " + text(architecture, "projectName", "?"),
                "Snapshot: " + text(architecture, "snapshotId", "?"),
                "Nature: " + text(architecture, "nature", "?"),
                "Modules: " + integer(architecture, "moduleCount"),
                "Visible nodes: " + visibleNodes + " (bound " + maxNodes + ")",
                "Relationships: " + integer(architecture, "relationshipCount"),
                "Edges shown are the moduleDependencies returned by MINOS architecture JSON.",
                "Select a node for module details."
        );
    }

    private static String moduleSummary(JsonObject module) {
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

    @FunctionalInterface
    private interface ContextOperation {
        JsonObject execute(MinosProjectService.Context context) throws Exception;
    }
}

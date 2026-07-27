package com.minos.intellij.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
@State(name = "MinosSettings", storages = @Storage("minos.xml"))
public final class MinosSettingsState implements PersistentStateComponent<MinosSettingsState.Settings> {

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_GRAPH_NODES = 120;

    public static final class Settings {
        public String executable = defaultExecutable();
        public String minosHome = "";
        public int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        public int maxGraphNodes = DEFAULT_MAX_GRAPH_NODES;
    }

    private Settings settings = new Settings();

    public static MinosSettingsState getInstance(Project project) {
        return project.getService(MinosSettingsState.class);
    }

    @Override
    public @Nullable Settings getState() {
        return settings;
    }

    @Override
    public void loadState(@NotNull Settings state) {
        settings = state;
        normalize();
    }

    public Settings value() {
        normalize();
        return settings;
    }

    public void update(String executable, String minosHome, int timeoutSeconds, int maxGraphNodes) {
        settings.executable = executable == null ? "" : executable.trim();
        settings.minosHome = minosHome == null ? "" : minosHome.trim();
        settings.timeoutSeconds = timeoutSeconds;
        settings.maxGraphNodes = maxGraphNodes;
        normalize();
    }

    private void normalize() {
        if (settings.executable == null || settings.executable.isBlank()) {
            settings.executable = defaultExecutable();
        }
        if (settings.minosHome == null) {
            settings.minosHome = "";
        }
        if (settings.timeoutSeconds < 1 || settings.timeoutSeconds > 300) {
            settings.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        if (settings.maxGraphNodes < 10 || settings.maxGraphNodes > 500) {
            settings.maxGraphNodes = DEFAULT_MAX_GRAPH_NODES;
        }
    }

    private static String defaultExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "minos.cmd" : "minos";
    }
}

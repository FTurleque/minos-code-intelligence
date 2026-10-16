package com.minos.intellij.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * IDE-global MINOS client settings.
 *
 * <p>The state deliberately lives at application scope even though the settings UI is opened from
 * a project. Executable paths and MINOS_HOME are process-launch trust inputs and must therefore
 * never be loaded from versionable project metadata such as {@code .idea/minos.xml}.</p>
 */
@Service(Service.Level.APP)
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
        Objects.requireNonNull(project, "project");
        return ApplicationManager.getApplication().getService(MinosSettingsState.class);
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
        settings.executable = MinosSettingsRules.executable(settings.executable, System.getProperty("os.name"));
        if (settings.minosHome == null) {
            settings.minosHome = "";
        }
        settings.timeoutSeconds = MinosSettingsRules.timeoutSeconds(settings.timeoutSeconds);
        settings.maxGraphNodes = MinosSettingsRules.maxGraphNodes(settings.maxGraphNodes);
    }

    private static String defaultExecutable() {
        return MinosSettingsRules.defaultExecutable(System.getProperty("os.name"));
    }
}

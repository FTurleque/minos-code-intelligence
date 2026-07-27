package com.minos.intellij.actions;

import com.google.gson.JsonObject;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.minos.intellij.protocol.MinosM21Client;
import com.minos.intellij.service.MinosProjectService;
import com.minos.intellij.settings.MinosSettingsState;
import com.minos.intellij.ui.MinosUiController;
import org.jetbrains.annotations.NotNull;

/** Native IntelliJ entry points for M21-S6 surface parity. */
public final class MinosM21Actions {

    private MinosM21Actions() {
    }

    private abstract static class ProjectAction extends AnAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabledAndVisible(event.getProject() != null);
        }

        @Override
        public final void actionPerformed(@NotNull AnActionEvent event) {
            Project project = event.getProject();
            if (project == null) return;
            String query = prompt(project);
            if (requiresQuery() && query == null) return;
            runBackground(project, title(), () -> {
                MinosProjectService.Context context = MinosProjectService.getInstance(project).context();
                return execute(project, context, query);
            });
        }

        protected String prompt(Project project) {
            return null;
        }

        protected boolean requiresQuery() {
            return false;
        }

        protected abstract String title();

        protected abstract JsonObject execute(Project project, MinosProjectService.Context context, String query)
                throws Exception;
    }

    private abstract static class QueryAction extends ProjectAction {
        @Override
        protected final String prompt(Project project) {
            return Messages.showInputDialog(
                    project,
                    "Enter the bounded MINOS retrieval query:",
                    title(),
                    Messages.getQuestionIcon());
        }

        @Override
        protected final boolean requiresQuery() {
            return true;
        }
    }

    private abstract static class SymbolAction extends AnAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabledAndVisible(
                    event.getProject() != null
                            && event.getData(CommonDataKeys.EDITOR) != null
                            && event.getData(CommonDataKeys.PSI_FILE) != null);
        }

        @Override
        public final void actionPerformed(@NotNull AnActionEvent event) {
            Project project = event.getProject();
            Editor editor = event.getData(CommonDataKeys.EDITOR);
            PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
            if (project == null || editor == null || file == null) return;
            runBackground(project, title(), () -> {
                MinosProjectService service = MinosProjectService.getInstance(project);
                MinosProjectService.Context context = service.context();
                JsonObject symbol = service.resolveSymbol(editor, file);
                return execute(project, context, symbol);
            });
        }

        protected abstract String title();

        protected abstract JsonObject execute(
                Project project,
                MinosProjectService.Context context,
                JsonObject symbol
        ) throws Exception;
    }

    public static final class ProgramGraph extends ProjectAction {
        @Override protected String title() { return "MINOS Program Graph"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, String query) throws Exception {
            int maxNodes = Math.max(1, MinosSettingsState.getInstance(project).value().maxGraphNodes);
            int maxEdges = Math.min(50_000, Math.max(100, maxNodes * 8));
            return MinosM21Client.getInstance(project).programGraph(context.projectId(), maxNodes, maxEdges);
        }
    }

    public static final class ImpactV2 extends SymbolAction {
        @Override protected String title() { return "MINOS Impact v2"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) throws Exception {
            return MinosM21Client.getInstance(project).impactV2(context.projectId(), required(symbol, "id"));
        }
    }

    public static final class SecurityPaths extends ProjectAction {
        @Override protected String title() { return "MINOS Security Paths"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, String query) throws Exception {
            return MinosM21Client.getInstance(project).securityPaths(context.projectId());
        }
    }

    public static final class SemanticIndexStatus extends ProjectAction {
        @Override protected String title() { return "MINOS Semantic Index Status"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, String query) throws Exception {
            return MinosM21Client.getInstance(project).semanticIndexStatus(context.projectId());
        }
    }

    public static final class SemanticIndexSync extends ProjectAction {
        @Override protected String title() { return "Synchronize MINOS Semantic Index"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, String query) throws Exception {
            return MinosM21Client.getInstance(project).synchronizeSemanticIndex(context.projectId());
        }
    }

    public static final class SemanticSearch extends QueryAction {
        @Override protected String title() { return "MINOS Semantic Search"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, String query) throws Exception {
            return MinosM21Client.getInstance(project).semanticSearch(context.projectId(), query, 50);
        }
    }

    public static final class HybridSearch extends QueryAction {
        @Override protected String title() { return "MINOS Hybrid Search"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, String query) throws Exception {
            return MinosM21Client.getInstance(project).hybridSearch(context.projectId(), query, 50);
        }
    }

    public static final class HybridContext extends QueryAction {
        @Override protected String title() { return "MINOS Hybrid Context"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, String query) throws Exception {
            return MinosM21Client.getInstance(project).hybridContext(context.projectId(), query, 10, 4_000);
        }
    }

    private static void runBackground(Project project, String title, ThrowingOperation operation) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            private JsonObject result;
            private Throwable failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    result = operation.execute();
                } catch (ProcessCanceledException canceled) {
                    throw canceled;
                } catch (Throwable throwable) {
                    failure = throwable;
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null) {
                    MinosUiController.getInstance(project).showError(title, failure);
                } else if (result != null) {
                    MinosUiController.getInstance(project).showResult(title, result);
                }
            }
        });
    }

    private static String required(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull() || object.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("MINOS symbol is missing `" + name + "`");
        }
        return object.get(name).getAsString();
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        JsonObject execute() throws Exception;
    }
}

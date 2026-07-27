package com.minos.intellij.actions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.minos.intellij.navigation.MinosLocation;
import com.minos.intellij.navigation.MinosNavigation;
import com.minos.intellij.protocol.MinosCliClient;
import com.minos.intellij.service.MinosProjectService;
import com.minos.intellij.ui.MinosUiController;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

public final class MinosEditorActions {

    private MinosEditorActions() {
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
            if (project == null || editor == null || file == null) {
                return;
            }
            ProgressManager.getInstance().run(new Task.Backgroundable(project, title(), true) {
                private JsonObject result;
                private JsonObject symbol;
                private MinosProjectService.Context context;
                private Throwable failure;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        MinosProjectService service = MinosProjectService.getInstance(project);
                        context = service.context();
                        symbol = service.resolveSymbol(editor, file);
                        result = execute(project, context, symbol);
                    } catch (ProcessCanceledException canceled) {
                        throw canceled;
                    } catch (Throwable throwable) {
                        failure = throwable;
                    }
                }

                @Override
                public void onSuccess() {
                    if (failure != null) {
                        MinosUiController.getInstance(project).showError(title(), failure);
                    } else {
                        try {
                            present(project, context, symbol, result);
                        } catch (ProcessCanceledException canceled) {
                            throw canceled;
                        } catch (Throwable throwable) {
                            MinosUiController.getInstance(project).showError(title(), throwable);
                        }
                    }
                }
            });
        }

        protected abstract String title();

        protected abstract JsonObject execute(
                Project project,
                MinosProjectService.Context context,
                JsonObject symbol
        ) throws Exception;

        protected void present(
                Project project,
                MinosProjectService.Context context,
                JsonObject symbol,
                JsonObject result
        ) {
            MinosUiController.getInstance(project).showResult(title(), result);
        }

        protected static String symbolId(JsonObject symbol) {
            return required(symbol, "id");
        }
    }

    public static final class OpenDefinition extends SymbolAction {
        @Override protected String title() { return "Open MINOS definition"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) {
            return symbol;
        }

        @Override
        protected void present(Project project, MinosProjectService.Context context, JsonObject symbol, JsonObject result) {
            JsonElement element = symbol.get("location");
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("MINOS symbol has no navigable definition location");
            }
            MinosNavigation.open(project, context.root(), MinosLocation.from(element.getAsJsonObject()));
        }
    }

    public static final class FindUsages extends SymbolAction {
        @Override protected String title() { return "MINOS usages"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) throws Exception {
            return MinosCliClient.getInstance(project).findUsages(context.projectId(), symbolId(symbol), 500);
        }
    }

    public static final class Dependents extends SymbolAction {
        @Override protected String title() { return "MINOS dependents"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) throws Exception {
            return MinosCliClient.getInstance(project).relationships("dependents", context.projectId(), symbolId(symbol), 500);
        }
    }

    public static final class Implementations extends SymbolAction {
        @Override protected String title() { return "MINOS implementations"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) throws Exception {
            return MinosCliClient.getInstance(project).relationships("find-implementations", context.projectId(), symbolId(symbol), 500);
        }
    }

    public static final class RelatedTests extends SymbolAction {
        @Override protected String title() { return "MINOS related tests"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) throws Exception {
            return MinosCliClient.getInstance(project).relationships("related-tests", context.projectId(), symbolId(symbol), 500);
        }
    }

    public static final class Impact extends SymbolAction {
        @Override protected String title() { return "MINOS impact"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) throws Exception {
            return MinosCliClient.getInstance(project).impact(context.projectId(), symbolId(symbol));
        }
    }

    public static final class ShowArchitecture extends SymbolAction {
        @Override protected String title() { return "MINOS architecture"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) throws Exception {
            return MinosCliClient.getInstance(project).architecture(context.projectId());
        }

        @Override
        protected void present(Project project, MinosProjectService.Context context, JsonObject symbol, JsonObject result) {
            MinosUiController.getInstance(project).showArchitecture(result, nullable(symbol, "moduleId"));
        }
    }

    public static final class CopyIdentity extends SymbolAction {
        @Override protected String title() { return "Copy MINOS symbol identity"; }

        @Override
        protected JsonObject execute(Project project, MinosProjectService.Context context, JsonObject symbol) {
            return symbol;
        }

        @Override
        protected void present(Project project, MinosProjectService.Context context, JsonObject symbol, JsonObject result) {
            String id = required(symbol, "id");
            String qualifiedName = nullable(symbol, "qualifiedName");
            String value = qualifiedName == null || qualifiedName.isBlank() ? id : id + "\n" + qualifiedName;
            CopyPasteManager.getInstance().setContents(new StringSelection(value));
            JsonObject confirmation = new JsonObject();
            confirmation.addProperty("symbolId", id);
            if (qualifiedName != null) {
                confirmation.addProperty("qualifiedName", qualifiedName);
            }
            confirmation.addProperty("copied", true);
            MinosUiController.getInstance(project).showResult(title(), confirmation);
        }
    }

    private static String required(JsonObject object, String name) {
        String value = nullable(object, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MINOS symbol is missing `" + name + "`");
        }
        return value;
    }

    private static String nullable(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}

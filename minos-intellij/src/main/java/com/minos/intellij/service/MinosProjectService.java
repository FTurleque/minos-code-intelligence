package com.minos.intellij.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.minos.intellij.protocol.MinosCliClient;
import com.minos.intellij.protocol.MinosProtocolException;

import java.nio.file.Path;

@Service(Service.Level.PROJECT)
public final class MinosProjectService {

    private final Project project;

    public MinosProjectService(Project project) {
        this.project = project;
    }

    public static MinosProjectService getInstance(Project project) {
        return project.getService(MinosProjectService.class);
    }

    public Context context() throws MinosProtocolException {
        JsonObject registered = MinosCliClient.getInstance(project).resolveProject();
        return new Context(
                registered.get("id").getAsString(),
                registered.get("name").getAsString(),
                Path.of(registered.get("rootPath").getAsString()).toAbsolutePath().normalize(),
                registered
        );
    }

    public JsonObject resolveSymbol(Editor editor, PsiFile psiFile) throws MinosProtocolException {
        Context context = context();
        Candidate candidate = ReadAction
                .nonBlocking(() -> candidate(editor, psiFile, context.root()))
                .executeSynchronously();
        if (candidate.name() == null || candidate.name().isBlank()) {
            throw new MinosProtocolException("Place the caret on a named code element before running a MINOS action");
        }

        JsonObject result = MinosCliClient.getInstance(project).findSymbols(context.projectId(), candidate.name(), 100);
        JsonArray symbols = result.has("symbols") ? result.getAsJsonArray("symbols") : new JsonArray();
        if (symbols.size() == 0) {
            throw new MinosProtocolException(
                    "MINOS found no symbol named `" + candidate.name() + "` in the active snapshot");
        }

        return MinosSymbolRanking.best(symbols, candidate.relativeFile(), candidate.caretLine())
                .orElseThrow(() -> new MinosProtocolException("MINOS returned no usable symbol result"));
    }

    private static Candidate candidate(Editor editor, PsiFile psiFile, Path projectRoot) {
        int offset = editor.getCaretModel().getOffset();
        int safeOffset = Math.max(0, Math.min(offset, Math.max(0, psiFile.getTextLength() - 1)));
        PsiElement element = psiFile.getTextLength() == 0 ? null : psiFile.findElementAt(safeOffset);
        String name = symbolName(element);
        if (name == null || name.isBlank()) {
            String selected = editor.getSelectionModel().getSelectedText();
            name = selected == null ? null : selected.trim();
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        String relative = virtualFile == null ? null : MinosSymbolRanking.relative(projectRoot, Path.of(virtualFile.getPath()));
        int caretLine = editor.getDocument().getLineNumber(Math.min(offset, editor.getDocument().getTextLength())) + 1;
        return new Candidate(name, relative, caretLine);
    }

    private static String symbolName(PsiElement element) {
        PsiElement cursor = element;
        if (cursor != null) {
            PsiReference reference = cursor.getReference();
            if (reference != null) {
                PsiElement resolved = reference.resolve();
                String name = nameOf(resolved);
                if (name != null) {
                    return name;
                }
            }
        }
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getParent()) {
            String name = nameOf(cursor);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return element == null ? null : element.getText();
    }

    /** MINOS-AUD-H21: one read of the name, so the value tested is the value returned. */
    private static String nameOf(PsiElement element) {
        return element instanceof PsiNamedElement named ? named.getName() : null;
    }

    private record Candidate(String name, String relativeFile, int caretLine) {
    }

    public record Context(String projectId, String projectName, Path root, JsonObject project) {
    }
}

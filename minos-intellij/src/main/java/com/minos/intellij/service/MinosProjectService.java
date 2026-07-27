package com.minos.intellij.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Comparator;

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
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(Math.max(0, Math.min(offset, Math.max(0, psiFile.getTextLength() - 1))));
        String name = symbolName(element);
        if (name == null || name.isBlank()) {
            String selected = editor.getSelectionModel().getSelectedText();
            name = selected == null ? null : selected.trim();
        }
        if (name == null || name.isBlank()) {
            throw new MinosProtocolException("Place the caret on a named code element before running a MINOS action");
        }

        JsonObject result = MinosCliClient.getInstance(project).findSymbols(context.projectId(), name, 100);
        JsonArray symbols = result.has("symbols") ? result.getAsJsonArray("symbols") : new JsonArray();
        if (symbols.isEmpty()) {
            throw new MinosProtocolException("MINOS found no symbol named `" + name + "` in the active snapshot");
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        String relative = virtualFile == null ? null : relative(context.root(), Path.of(virtualFile.getPath()));
        int caretLine = editor.getDocument().getLineNumber(offset) + 1;

        return symbols.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .min(Comparator.comparingInt(symbol -> score(symbol, relative, caretLine)))
                .orElseThrow(() -> new MinosProtocolException("MINOS returned no usable symbol result"));
    }

    private static int score(JsonObject symbol, String relativeFile, int caretLine) {
        int score = 1000;
        String fileId = nullableString(symbol, "fileId");
        if (relativeFile != null && fileId != null) {
            String normalizedFile = fileId.replace('\\', '/');
            String normalizedRelative = relativeFile.replace('\\', '/');
            if (normalizedFile.equals(normalizedRelative) || normalizedFile.endsWith("/" + normalizedRelative)) {
                score -= 700;
            }
        }
        JsonElement locationElement = symbol.get("location");
        if (locationElement != null && locationElement.isJsonObject()) {
            JsonObject location = locationElement.getAsJsonObject();
            int start = location.get("startLine").getAsInt();
            score += Math.min(200, Math.abs(start - caretLine));
            if (start == caretLine) {
                score -= 200;
            }
        }
        String resolution = nullableString(symbol, "resolutionStatus");
        if ("RESOLVED".equals(resolution)) {
            score -= 50;
        }
        return score;
    }

    private static String symbolName(PsiElement element) {
        PsiElement cursor = element;
        if (cursor != null) {
            PsiReference reference = cursor.getReference();
            if (reference != null) {
                PsiElement resolved = reference.resolve();
                if (resolved instanceof PsiNamedElement named && named.getName() != null) {
                    return named.getName();
                }
            }
        }
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getParent()) {
            if (cursor instanceof PsiNamedElement named && named.getName() != null && !named.getName().isBlank()) {
                return named.getName();
            }
        }
        return element == null ? null : element.getText();
    }

    private static String relative(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) {
            return null;
        }
        return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
    }

    private static String nullableString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    public record Context(String projectId, String projectName, Path root, JsonObject project) {
    }
}

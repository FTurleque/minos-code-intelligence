package com.minos.intellij.navigation;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MinosNavigation {

    private MinosNavigation() {
    }

    public static void open(Project project, Path projectRoot, MinosLocation location) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path raw = Path.of(location.fileId());
        Path target = (raw.isAbsolute() ? raw : root.resolve(raw)).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("MINOS location escapes the registered project root: " + location.fileId());
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("MINOS source file does not exist: " + target);
        }
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
        if (file == null) {
            throw new IllegalArgumentException("IntelliJ VFS cannot resolve MINOS source file: " + target);
        }
        Document document = FileDocumentManager.getInstance().getDocument(file);
        int offset = 0;
        if (document != null && document.getLineCount() > 0) {
            int lineIndex = Math.max(0, Math.min(location.startLine() - 1, document.getLineCount() - 1));
            int lineStart = document.getLineStartOffset(lineIndex);
            int lineEnd = document.getLineEndOffset(lineIndex);
            CharSequence lineText = document.getCharsSequence().subSequence(lineStart, lineEnd);
            offset = Math.min(lineStart + location.utf16Column(lineText), document.getTextLength());
        }
        new OpenFileDescriptor(project, file, offset).navigate(true);
    }
}

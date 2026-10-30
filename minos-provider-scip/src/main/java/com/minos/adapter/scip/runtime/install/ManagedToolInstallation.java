package com.minos.adapter.scip.runtime.install;

import com.minos.io.DurableAtomicFile;
import com.minos.io.FileTreeOperations;
import com.minos.io.PrivateLocalStorage;
import com.minos.runtime.local.process.BoundedProcessOutput;
import com.minos.runtime.local.process.InstallationProcessEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * The elementary steps of a managed tool installation: run an installation command with a bounded log, write a new
 * file into a private tree, publish a file or a directory, delete a tree.
 */
final class ManagedToolInstallation {

    /** The byte bound of an installation log, the one its capture applies. */
    static final long MAX_INSTALL_LOG_BYTES = BoundedProcessOutput.DEFAULT_MAX_BYTES_PER_STREAM;

    private ManagedToolInstallation() {
    }

    static void run(List<String> command, Path workingDirectory, Path log, Duration timeout)
            throws IOException, InterruptedException {
        PrivateLocalStorage.ensurePrivateDirectory(log.toAbsolutePath().normalize().getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        InstallationProcessEnvironment.apply(builder, Map.of());
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(process, log, null);
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.descendants().toList().reversed().forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            capture.await();
            throw new IllegalStateException("tool command timed out; see the tool log " + leafName(log));
        }
        capture.await();
        if (process.exitValue() != 0) throw new IllegalStateException("tool command failed with code " + process.exitValue() + "; see the tool log " + leafName(log));
    }

    /** A new file in a private directory, never through a link, whose access is inherited from the directory. */
    static void writeNewFile(Path target, InputStream content) throws IOException {
        try (OutputStream output = PrivateLocalStorage.openNewTreeFile(target)) {
            content.transferTo(output);
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        FileTreeOperations.deleteRecursively(path);
    }

    /** The parent is made private before every publication, so that a new tool folder exists and is owner-only. */
    static void moveFile(Path source, Path target) throws IOException {
        PrivateLocalStorage.ensurePrivateDirectory(target.toAbsolutePath().normalize().getParent());
        DurableAtomicFile.replace(source, target, "managed tool file installation");
    }

    static void moveDirectory(Path source, Path target) throws IOException {
        PrivateLocalStorage.ensurePrivateDirectory(target.toAbsolutePath().normalize().getParent());
        DurableAtomicFile.moveDirectory(source, target, "managed tool directory installation");
    }
}

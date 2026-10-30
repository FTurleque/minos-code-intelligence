package com.minos.adapter.scip.runtime.install;

import com.minos.io.ConfinedFileOpener;
import com.minos.io.PrivateLocalStorage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.minos.diagnostics.PublicErrorMessages.leafName;

/** Bounded extraction of a tool archive under a private root: no entry outside it, a bounded count and size. */
final class BoundedZipExtraction {

    private BoundedZipExtraction() {
    }

    /**
     * Extracts a zip archive under {@code destinationRoot}, rejecting any entry that would resolve
     * outside it (zip-slip) and bounding both entry count and total extracted bytes so a corrupted
     * or oversized archive cannot turn installation into unbounded disk consumption.
     */
    static void extractZipBounded(
            Path archive, Path destinationRoot, long maxTotalBytes, long maxEntries
    ) throws IOException {
        Path root = destinationRoot.toAbsolutePath().normalize();
        PrivateLocalStorage.ensurePrivateDirectory(root);
        long totalBytes = 0L;
        long entries = 0L;
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IOException("archive has too many entries: " + leafName(archive));
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("archive entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = openExtractedFile(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        totalBytes += read;
                        if (totalBytes > maxTotalBytes) {
                            throw new IOException("archive exceeds extraction size budget: " + leafName(archive));
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    /**
     * Extracts a distribution archive whose entries all live under {@code topLevelDirectory}. An earlier extraction
     * may have been interrupted: its tree is removed first, so that every entry is created new and a relaunch succeeds.
     */
    static void extractDistribution(
            Path archive, Path root, String topLevelDirectory, long maxTotalBytes, long maxEntries
    ) throws IOException {
        ManagedToolInstallation.deleteRecursively(root.resolve(topLevelDirectory));
        extractZipBounded(archive, root, maxTotalBytes, maxEntries);
    }

    /** A file of an archive is new: a name met twice (or twice under case folding) is a defect of the archive. */
    private static OutputStream openExtractedFile(Path target) throws IOException {
        try {
            return PrivateLocalStorage.openNewTreeFile(target);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            throw new IOException("archive has a duplicate entry", duplicate);
        }
    }
}

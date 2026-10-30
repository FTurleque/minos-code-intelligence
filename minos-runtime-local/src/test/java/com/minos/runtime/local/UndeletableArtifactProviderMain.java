package com.minos.runtime.local;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Fake provider that writes a valid artifact and then makes it impossible for its (non-root) owner to delete: a
 * parent directory without the write bit on a POSIX file system, otherwise the DOS read-only attribute.
 * Arguments: the artifact path, then {@code empty} to write a zero-byte artifact instead (no protection then).
 */
public final class UndeletableArtifactProviderMain {

    private UndeletableArtifactProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Path artifact = Path.of(arguments[0]);
        Files.createDirectories(artifact.getParent());
        if (arguments.length > 1 && "empty".equals(arguments[1])) {
            Files.write(artifact, new byte[0]);
            return;
        }
        Files.writeString(artifact, "fresh-scip", StandardCharsets.UTF_8);
        // Linux also exposes a "dos" view, stored as an extended attribute that does not prevent deletion.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(artifact.getParent(), PosixFilePermissions.fromString("r-xr-xr-x"));
        } else {
            Files.setAttribute(artifact, "dos:readonly", true);
        }
    }
}

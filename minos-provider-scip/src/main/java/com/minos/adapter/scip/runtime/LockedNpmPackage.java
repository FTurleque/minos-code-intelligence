package com.minos.adapter.scip.runtime;

import com.minos.io.BoundedInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Repository-owned npm lockfile preparation for managed SCIP runtimes. */
final class LockedNpmPackage {
    private static final long MAX_LOCKFILE_BYTES = 4L * 1024L * 1024L;

    private LockedNpmPackage() {
    }

    static void prepare(
            Class<?> resourceOwner,
            Path installRoot,
            String lockResource,
            String packageName,
            String version,
            String expectedIntegrity
    ) throws IOException {
        Objects.requireNonNull(resourceOwner, "resourceOwner");
        Objects.requireNonNull(installRoot, "installRoot");
        requireText(lockResource, "lockResource");
        requireText(packageName, "packageName");
        requireText(version, "version");
        requireText(expectedIntegrity, "expectedIntegrity");
        Files.createDirectories(installRoot);
        Files.writeString(
                installRoot.resolve("package.json"),
                "{\n  \"private\": true,\n  \"dependencies\": {\n    \"" + packageName
                        + "\": \"" + version + "\"\n  }\n}\n",
                StandardCharsets.UTF_8);
        Path lock = installRoot.resolve("package-lock.json");
        try (InputStream input = resourceOwner.getResourceAsStream(lockResource)) {
            if (input == null) throw new IOException("packaged npm lockfile is missing: " + lockResource);
            Files.copy(input, lock, StandardCopyOption.REPLACE_EXISTING);
        }
        verify(lock, packageName, version, expectedIntegrity);
    }

    static void verify(Path lock, String packageName, String version, String expectedIntegrity) throws IOException {
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lock)) {
            throw new IOException("managed npm lockfile must be a regular non-symbolic file");
        }
        long size = Files.size(lock);
        if (size < 1L || size > MAX_LOCKFILE_BYTES) {
            throw new IOException("managed npm lockfile size is invalid");
        }
        String json;
        try (BoundedInputStream input = new BoundedInputStream(
                Files.newInputStream(lock), MAX_LOCKFILE_BYTES, "managed npm lockfile")) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String marker = "\"node_modules/" + packageName + "\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new IOException("managed npm lockfile does not contain root package: " + packageName);
        int nextPackage = json.indexOf("\"node_modules/", start + marker.length());
        String rootEntry = nextPackage < 0 ? json.substring(start) : json.substring(start, nextPackage);
        if (!rootEntry.contains("\"version\": \"" + version + "\"")) {
            throw new IOException("managed npm lockfile root version mismatch for " + packageName);
        }
        if (!rootEntry.contains("\"integrity\": \"" + expectedIntegrity + "\"")) {
            throw new IOException("managed npm lockfile root integrity mismatch for " + packageName);
        }
        if (!json.contains("\"lockfileVersion\": 3")) {
            throw new IOException("managed npm lockfile must use lockfileVersion 3");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }
}
